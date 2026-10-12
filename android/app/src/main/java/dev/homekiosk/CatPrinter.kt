package dev.homekiosk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Owns the BLE link to a GB03-style "cat printer" for the whole process (one instance, like the
 * HTTP server: MainActivity can be recreated, the connection should not be).
 *
 * Threads: connection state machine on its own HandlerThread ([h]); print jobs and keepalive
 * pings are serialized on a single job thread, which blocks while waiting for each write to
 * complete (Android allows one outstanding GATT operation at a time). GATT callbacks arrive on
 * binder threads and either post to [h] or signal the job thread directly.
 *
 * Every BLE call is wrapped: a missing permission or a flaky stack must never crash the kiosk.
 */
@SuppressLint("MissingPermission")
class CatPrinter private constructor(context: Context) {

    private val app = context.applicationContext
    private val cfg = Config(app)
    private val h = Handler(HandlerThread("CatPrinter").apply { start() }.looper)
    private val jobs = Executors.newSingleThreadExecutor { r -> Thread(r, "CatPrinter-job") }

    /** Called on the job thread when a print finishes: (ok, error message). */
    @Volatile var listener: ((Boolean, String?) -> Unit)? = null

    @Volatile var state = "off"; private set
    @Volatile var lastError: String? = null; private set
    @Volatile private var lastPrintAt = 0L

    // --- connection (mutated on h) ---
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var tx: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    private var direct = false
    private var started = false
    private var scanCb: ScanCallback? = null
    private var disconnectedSince = SystemClock.elapsedRealtime()
    private var lastSeen = 0L
    private var lastScanAt = 0L
    private var retryMs = RETRY_MIN_MS
    private var scanRetryMs = SCAN_RETRY_MIN_MS
    private var nextScanAt = 0L
    private var receiverRegistered = false

    // --- writes / flow control (job thread waits, binder threads signal) ---
    private val writeMon = Object()
    private var writeOutstanding = false
    private val flowMon = Object()
    @Volatile private var paused = false
    @Volatile private var keepaliveQueued = false

    private val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    // ------------------------------------------------------------------ public API

    /** Starts scanning / connecting if enabled and permitted. Idempotent; safe from any thread. */
    fun start() = h.post { startInternal() }

    fun setEnabled(on: Boolean) {
        cfg.printerEnabled = on
        h.post { if (on) startInternal() else stopInternal("off") }
    }

    /** Clears the saved printer and scans for a new one. */
    fun forget() = h.post {
        Log.i(TAG, "forgetting printer ${cfg.printerAddress}")
        cfg.printerAddress = ""
        cfg.printerName = ""
        closeGatt()
        stopScan()
        lastError = null
        nextScanAt = 0L
        scanRetryMs = SCAN_RETRY_MIN_MS
        if (started) startScan()
    }

    /** Queues [bitmap] for printing. Returns null when queued, otherwise an error message. */
    fun print(bitmap: Bitmap): String? {
        if (!cfg.printerEnabled) return "Printer is disabled in kiosk settings"
        if (!hasPermissions(app)) return "No Bluetooth permission"
        if (adapter?.isEnabled != true) return "Bluetooth is off"
        val data = try {
            CatPrinterProtocol.job(rowsFor(bitmap))
        } catch (e: Exception) {
            Log.w(TAG, "image conversion failed", e)
            return "Bad image: ${e.message}"
        }
        start()
        jobs.execute { runJob(data) }
        return null
    }

    fun printTestPage(): String? = print(testPage())

    fun statusJson(): JSONObject = JSONObject().apply {
        put("enabled", cfg.printerEnabled)
        put("state", state)
        put("name", cfg.printerName.ifEmpty { JSONObject.NULL })
        put("address", cfg.printerAddress.ifEmpty { JSONObject.NULL })
        put("lastError", lastError ?: JSONObject.NULL)
        put("lastPrintAt", if (lastPrintAt > 0) lastPrintAt else JSONObject.NULL)
    }

    // ------------------------------------------------------------------ lifecycle (on h)

    private fun startInternal() {
        if (!cfg.printerEnabled) { stopInternal("off"); return }
        if (!started) {
            started = true
            registerBtReceiver()
            h.removeCallbacks(watchdog)
            h.postDelayed(watchdog, WATCHDOG_MS)
        }
        if (!readyToUseBt()) return
        if (gatt != null || scanCb != null) return
        if (cfg.printerAddress.isEmpty()) startScan() else openGatt(auto = true)
    }

    private fun stopInternal(newState: String) {
        started = false
        h.removeCallbacks(watchdog)
        h.removeCallbacks(keepalive)
        stopScan()
        closeGatt()
        state = newState
    }

    /** Checks permission + adapter; sets state "off" with a reason when BLE can't be used. */
    private fun readyToUseBt(): Boolean {
        val why = when {
            !hasPermissions(app) -> "No Bluetooth permission"
            adapter == null -> "No Bluetooth on this device"
            adapter?.isEnabled != true -> "Bluetooth is off"
            else -> null
        }
        if (why != null) {
            if (lastError != why) Log.w(TAG, why)
            lastError = why
            stopScan()
            closeGatt()
            state = "off"
            return false
        }
        if (lastError?.startsWith("Bluetooth is off") == true || lastError == "No Bluetooth permission") lastError = null
        return true
    }

    private fun registerBtReceiver() {
        if (receiverRegistered) return
        try {
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    Log.i(TAG, "adapter state $s")
                    h.post {
                        if (!started) return@post
                        if (s == BluetoothAdapter.STATE_ON) startInternal()
                        else if (s == BluetoothAdapter.STATE_OFF) readyToUseBt()
                    }
                }
            }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: Exception) { Log.w(TAG, "registerReceiver", e) }
    }

    /**
     * Every 30 s: if we've been without a connection for a while, close and retry (backing off
     * 2 → 4 → 8 → 15 min), and re-scan when the saved printer hasn't been seen for a long time.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            h.postDelayed(this, WATCHDOG_MS)
            if (!started || !readyToUseBt()) return
            if (state == "ready" || state == "printing" || scanCb != null) return
            val now = SystemClock.elapsedRealtime()
            if (cfg.printerAddress.isEmpty()) {
                if (now >= nextScanAt) startScan()
                return
            }
            if (gatt == null) { openGatt(auto = true); return }
            if (now - disconnectedSince < retryMs) return
            Log.i(TAG, "no connection for ${(now - disconnectedSince) / 1000}s, retrying (next in ${retryMs * 2 / 60_000} min)")
            retryMs = (retryMs * 2).coerceAtMost(RETRY_MAX_MS)
            disconnectedSince = now
            closeGatt()
            // Re-scan when the printer hasn't been seen in this process (also puts it back in the
            // stack's device cache after a reboot, which autoConnect needs for random addresses)
            // or not for hours; otherwise just re-arm autoConnect.
            val stale = lastSeen == 0L || now - lastSeen > RESCAN_AFTER_MS
            if (stale && (lastScanAt == 0L || now - lastScanAt > RESCAN_AFTER_MS)) startScan()
            else openGatt(auto = true)
        }
    }

    // ------------------------------------------------------------------ scanning (on h)

    private fun startScan() {
        if (scanCb != null) return
        val scanner = try { adapter?.bluetoothLeScanner } catch (e: Exception) { null }
        if (scanner == null) { lastError = "Bluetooth scanner unavailable"; return }
        val filters = NAMES.map { ScanFilter.Builder().setDeviceName(it).build() } +
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                h.post { onFound(this, result) }
            }
            override fun onScanFailed(errorCode: Int) {
                h.post {
                    Log.w(TAG, "scan failed: $errorCode")
                    if (scanCb === this) { scanCb = null; lastError = "Scan failed ($errorCode)"; scanDone(found = false) }
                }
            }
        }
        try {
            scanner.startScan(filters, settings, cb)
        } catch (e: Exception) {
            Log.w(TAG, "startScan", e); lastError = "Scan failed: ${e.message}"; return
        }
        Log.i(TAG, "scanning for ${NAMES.joinToString("/")} (${SCAN_MS / 1000}s)")
        scanCb = cb
        lastScanAt = SystemClock.elapsedRealtime()
        state = "scanning"
        h.postDelayed(scanTimeout, SCAN_MS)
    }

    private val scanTimeout = Runnable {
        if (scanCb == null) return@Runnable
        Log.i(TAG, "scan: no printer found")
        stopScan()
        scanDone(found = false)
    }

    private fun stopScan() {
        h.removeCallbacks(scanTimeout)
        val cb = scanCb ?: return
        scanCb = null
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (e: Exception) { Log.w(TAG, "stopScan", e) }
    }

    private fun onFound(cb: ScanCallback, r: ScanResult) {
        if (scanCb !== cb) return
        val name = r.scanRecord?.deviceName ?: try { r.device.name } catch (_: Exception) { null } ?: ""
        val hasService = r.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true
        if (name !in NAMES && !hasService) return
        Log.i(TAG, "scan: found '$name' ${r.device.address} rssi ${r.rssi}")
        stopScan()
        cfg.printerAddress = r.device.address
        cfg.printerName = name.ifEmpty { "printer" }
        lastSeen = SystemClock.elapsedRealtime()
        scanRetryMs = SCAN_RETRY_MIN_MS
        lastError = null
        scanDone(found = true)
    }

    private fun scanDone(found: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (found) {
            openGatt(auto = false) // we just saw it: connect directly, much faster than autoConnect
        } else if (cfg.printerAddress.isNotEmpty()) {
            openGatt(auto = true)  // re-scan of a known printer: keep waiting for it in the background
        } else {
            nextScanAt = now + scanRetryMs
            Log.i(TAG, "next scan in ${scanRetryMs / 1000}s")
            scanRetryMs = (scanRetryMs * 2).coerceAtMost(RETRY_MAX_MS)
            if (lastError == null) lastError = "No printer found — is it switched on?"
            state = "off"
        }
    }

    // ------------------------------------------------------------------ GATT (on h)

    private fun openGatt(auto: Boolean) {
        val addr = cfg.printerAddress
        if (addr.isEmpty() || !readyToUseBt()) return
        closeGatt()
        try {
            val dev = adapter!!.getRemoteDevice(addr)
            direct = !auto
            mtu = 23
            gatt = dev.connectGatt(app, auto, gattCb, BluetoothDevice.TRANSPORT_LE)
            state = "connecting"
            Log.i(TAG, "connectGatt $addr autoConnect=$auto")
            if (gatt == null) { lastError = "connectGatt returned null"; state = "off" }
        } catch (e: Exception) {
            Log.w(TAG, "connectGatt", e)
            lastError = "Connect failed: ${e.message}"
            gatt = null
            state = "off"
        }
    }

    private fun closeGatt() {
        h.removeCallbacks(keepalive)
        h.removeCallbacks(setupTimeout)
        val g = gatt ?: return
        gatt = null
        tx = null
        releaseWaiters()
        try { g.disconnect() } catch (_: Exception) { }
        try { g.close() } catch (_: Exception) { }
    }

    /** Wakes the job thread out of any write / flow-control wait (link gone). */
    private fun releaseWaiters() {
        paused = false
        synchronized(writeMon) { writeOutstanding = false; writeMon.notifyAll() }
        synchronized(flowMon) { flowMon.notifyAll() }
    }

    /** Direct connect for an on-demand print (the background autoConnect can take a while). */
    private fun connectNow() {
        if (!started || state == "ready" || state == "printing" || scanCb != null) return
        if (!readyToUseBt()) return
        if (cfg.printerAddress.isEmpty()) startScan() else openGatt(auto = false)
    }

    private val setupTimeout = Runnable {
        Log.w(TAG, "GATT setup timed out, reconnecting")
        lastError = "Printer connection setup timed out"
        reopenAfterDrop()
    }

    private fun reopenAfterDrop() {
        closeGatt()
        disconnectedSince = SystemClock.elapsedRealtime()
        if (started) openGatt(auto = true)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post { onConnState(g, status, newState) }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            h.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                Log.i(TAG, "mtu $newMtu (status $status)")
                discover(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post { onServices(g, status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            h.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notify enable status $status")
                becomeReady()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "write status $status")
            synchronized(writeMon) { writeOutstanding = false; writeMon.notifyAll() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(value)
        }

        @Deprecated("pre-33 variant")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) c.value?.let { onNotify(it) }
        }
    }

    private fun onConnState(g: BluetoothGatt, status: Int, newState: Int) {
        if (g !== gatt) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) try { g.close() } catch (_: Exception) { }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.i(TAG, "connected to ${g.device.address} (status $status)")
            lastSeen = now
            retryMs = RETRY_MIN_MS
            state = "connecting"
            h.removeCallbacks(setupTimeout)
            h.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
            val asked = try { g.requestMtu(185) } catch (e: Exception) { false }
            if (!asked) discover(g)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i(TAG, "disconnected (status $status, autoConnect=${!direct})")
            h.removeCallbacks(keepalive)
            h.removeCallbacks(setupTimeout)
            tx = null
            releaseWaiters()
            disconnectedSince = now
            state = "connecting"
            when {
                // A direct connection doesn't come back by itself: re-arm a background autoConnect.
                direct -> reopenAfterDrop()
                // Normal link loss (printer switched off / out of range): the autoConnect handle
                // stays registered and the stack reconnects when the printer comes back.
                status in LINK_LOSS -> Unit
                // Anything else (e.g. 133) tends to leave the handle dead. Drop it; the watchdog
                // opens a fresh one on its next tick (≤ 30 s), so this can't spin.
                else -> closeGatt()
            }
            // The watchdog also covers the case where autoConnect never comes back.
        }
    }

    private fun discover(g: BluetoothGatt) {
        val ok = try { g.discoverServices() } catch (e: Exception) { false }
        if (!ok) { Log.w(TAG, "discoverServices failed"); reopenAfterDrop() }
    }

    private fun onServices(g: BluetoothGatt, status: Int) {
        if (g !== gatt) return
        val chars = g.services.flatMap { it.characteristics }
        val w = chars.firstOrNull { it.uuid == TX }
        val n = chars.firstOrNull { it.uuid == RX }
        if (status != BluetoothGatt.GATT_SUCCESS || w == null) {
            Log.w(TAG, "services status $status, ae01 ${if (w == null) "missing" else "ok"}")
            lastError = "Printer characteristic ae01 not found"
            reopenAfterDrop()
            return
        }
        tx = w
        if (n == null) { Log.w(TAG, "no ae02 notify characteristic; no flow control"); becomeReady(); return }
        try {
            g.setCharacteristicNotification(n, true)
            val d = n.getDescriptor(CCCD)
            val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = when {
                d == null -> false
                Build.VERSION.SDK_INT >= 33 -> g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
                else -> @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
            }
            if (!ok) becomeReady() // onDescriptorWrite won't come
        } catch (e: Exception) {
            Log.w(TAG, "enable notify", e)
            becomeReady()
        }
    }

    private fun becomeReady() {
        if (state == "ready" || tx == null) return
        h.removeCallbacks(setupTimeout)
        state = "ready"
        if (lastError?.startsWith("Printer is off") == true || lastError?.contains("timed out") == true ||
            lastError?.startsWith("No printer found") == true) lastError = null
        Log.i(TAG, "ready: ${cfg.printerName} ${cfg.printerAddress}, mtu $mtu")
        h.removeCallbacks(keepalive)
        h.post(keepalive)
    }

    /**
     * The printer powers itself off when idle; ping GET_STATE every 20 s while connected to see
     * whether that keeps it awake. Runs on the job thread so it never interleaves with a job.
     */
    private val keepalive = object : Runnable {
        override fun run() {
            h.postDelayed(this, KEEPALIVE_MS)
            if (state != "ready" || keepaliveQueued) return
            keepaliveQueued = true
            jobs.execute {
                keepaliveQueued = false
                if (state == "ready") {
                    Log.d(TAG, "keepalive GET_STATE")
                    if (!write(CatPrinterProtocol.GET_STATE)) Log.d(TAG, "keepalive write failed")
                }
            }
        }
    }

    private fun onNotify(b: ByteArray) {
        Log.d(TAG, "notify ${CatPrinterProtocol.hex(b)}")
        if (b.size >= 7 && (b[2].toInt() and 0xFF) == CatPrinterProtocol.NOTIFY_FLOW) {
            val p = (b[6].toInt() and 0xFF) == 0x10
            if (p != paused) Log.d(TAG, if (p) "flow: pause" else "flow: resume")
            paused = p
            if (!p) synchronized(flowMon) { flowMon.notifyAll() }
        }
    }

    // ------------------------------------------------------------------ jobs (job thread)

    private class PrintFailure(msg: String) : Exception(msg)

    private fun runJob(data: ByteArray) {
        try {
            if (!awaitReady()) throw PrintFailure("Printer is off — press its power button")
            state = "printing"
            val chunk = (mtu - 3).coerceIn(20, 182)
            val t0 = SystemClock.elapsedRealtime()
            var i = 0
            while (i < data.size) {
                waitWhilePaused()
                val end = minOf(i + chunk, data.size)
                if (!write(data.copyOfRange(i, end))) throw PrintFailure("Printer disconnected while printing")
                i = end
                Thread.sleep(PACING_MS)
            }
            lastPrintAt = System.currentTimeMillis()
            Log.i(TAG, "sent ${data.size} bytes in ${(data.size + chunk - 1) / chunk} chunks of $chunk " +
                "(${SystemClock.elapsedRealtime() - t0} ms)")
            if (state == "printing") state = "ready"
            notifyListener(true, null)
        } catch (e: Exception) {
            val msg = if (e is PrintFailure) e.message!! else "Print failed: ${e.message}"
            Log.w(TAG, "print failed: $msg")
            lastError = msg
            if (state == "printing") state = if (tx != null) "ready" else "connecting"
            notifyListener(false, msg)
        }
    }

    private fun notifyListener(ok: Boolean, msg: String?) {
        try { listener?.invoke(ok, msg) } catch (e: Exception) { Log.w(TAG, "listener", e) }
    }

    /** Up to 10 s for a connection (longer while a scan that may find it is still running). */
    private fun awaitReady(): Boolean {
        if (state == "ready") return true
        h.post { startInternal(); connectNow() }
        val start = SystemClock.elapsedRealtime()
        while (true) {
            Thread.sleep(100)
            if (state == "ready") return true
            val waited = SystemClock.elapsedRealtime() - start
            if (waited > CONNECT_WAIT_MS && !(state == "scanning" && waited < CONNECT_WAIT_MS + SCAN_MS)) return false
        }
    }

    private fun waitWhilePaused() {
        val deadline = SystemClock.elapsedRealtime() + PAUSE_MAX_MS
        synchronized(flowMon) {
            while (paused && tx != null) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) throw PrintFailure("Printer stayed busy (paused) too long")
                flowMon.wait(left)
            }
        }
        if (tx == null) throw PrintFailure("Printer disconnected while printing")
    }

    /** Writes one chunk without response and waits for onCharacteristicWrite (one at a time). */
    private fun write(chunk: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = tx ?: return false
        synchronized(writeMon) { writeOutstanding = true }
        var submitted = false
        for (attempt in 0 until 50) {
            submitted = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                        BluetoothStatusCodes.SUCCESS
                } else @Suppress("DEPRECATION") run {
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    c.value = chunk
                    g.writeCharacteristic(c)
                }
            } catch (e: Exception) { Log.w(TAG, "write", e); false }
            if (submitted || tx == null) break
            Thread.sleep(10) // stack busy, try again shortly
        }
        if (!submitted) { synchronized(writeMon) { writeOutstanding = false }; return false }
        val deadline = SystemClock.elapsedRealtime() + WRITE_TIMEOUT_MS
        synchronized(writeMon) {
            while (writeOutstanding) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) { Log.w(TAG, "no write callback within ${WRITE_TIMEOUT_MS}ms"); writeOutstanding = false; break }
                writeMon.wait(left)
            }
        }
        return tx != null
    }

    // ------------------------------------------------------------------ images

    /** Scales to 384 wide (keeping aspect), flattens alpha onto white, grayscale, dithers. */
    private fun rowsFor(src: Bitmap): List<ByteArray> {
        require(src.width > 0 && src.height > 0) { "empty image" }
        val w = CatPrinterProtocol.WIDTH
        val hgt = (src.height * w.toDouble() / src.width).roundToInt().coerceIn(1, MAX_ROWS)
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val scaled = if (soft.width == w && soft.height == hgt) soft else Bitmap.createScaledBitmap(soft, w, hgt, true)
        val px = IntArray(w * hgt)
        scaled.getPixels(px, 0, w, 0, 0, w, hgt)
        val gray = IntArray(px.size) { i ->
            val p = px[i]
            val a = p ushr 24
            val lum = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            (lum * a + 255 * (255 - a)) / 255
        }
        return CatPrinterProtocol.ditherToRows(gray, w, hgt)
    }

    private fun testPage(): Bitmap {
        val w = CatPrinterProtocol.WIDTH
        val lines = listOf(
            "Home Kiosk v${BuildConfig.VERSION_NAME}",
            "${Build.MANUFACTURER} ${Build.MODEL}",
            "${cfg.printerName.ifEmpty { "?" }} ${cfg.printerAddress}",
            SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date()),
        )
        val hgt = 80 + lines.size * 40 + 60
        val bmp = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 38f
        c.drawText("Kitchen printer ✓", 8f, 46f, p)
        c.drawRect(8f, 60f, w - 8f, 63f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 26f
        var y = 100f
        for (l in lines) { c.drawText(l, 8f, y, p); y += 40f }
        // Grey ramp: shows the dither range.
        val top = y - 10f
        for (x in 0 until w) {
            val v = x * 255 / (w - 1)
            p.color = Color.rgb(v, v, v)
            c.drawRect(x.toFloat(), top, x + 1f, top + 40f, p)
        }
        return bmp
    }

    companion object {
        private const val TAG = "CatPrinter"
        val NAMES = listOf("GB01", "GB02", "GB03", "MX05", "MX06", "MX08", "MX10")
        val SERVICE: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
        val TX: UUID = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
        val RX: UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 0 success, 8 supervision timeout, 19 remote terminated, 22 local host terminated. */
        private val LINK_LOSS = setOf(0, 8, 19, 22)
        private const val SCAN_MS = 15_000L
        private const val WATCHDOG_MS = 30_000L
        private const val KEEPALIVE_MS = 20_000L
        private const val RETRY_MIN_MS = 2 * 60_000L
        private const val RETRY_MAX_MS = 15 * 60_000L
        private const val SCAN_RETRY_MIN_MS = 60_000L
        private const val RESCAN_AFTER_MS = 6 * 60 * 60_000L
        private const val SETUP_TIMEOUT_MS = 15_000L
        private const val CONNECT_WAIT_MS = 10_000L
        private const val PAUSE_MAX_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val PACING_MS = 10L
        private const val MAX_ROWS = 6_000 // ~75 cm of paper

        @Volatile private var instance: CatPrinter? = null

        fun get(ctx: Context): CatPrinter =
            instance ?: synchronized(this) { instance ?: CatPrinter(ctx).also { instance = it } }

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // pre-12 BLE scans need location

        fun hasPermissions(ctx: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
