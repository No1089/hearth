package dev.homekiosk

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var cfg: Config
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private lateinit var messageView: TextView
    private lateinit var sleepOverlay: View
    private lateinit var debugBox: LinearLayout
    private lateinit var debugText: TextView
    private lateinit var debugDot: View
    private val main = Handler(Looper.getMainLooper())
    private lateinit var ha: HaNotifier

    // --- state; @Volatile fields are read by the HTTP / JS-bridge threads for /status ---
    @Volatile private var asleep = false
    @Volatile private var lastMotionWallMs = 0L
    @Volatile private var lastTouchWallMs = 0L
    @Volatile private var appliedBrightness = -1
    @Volatile private var currentUrl = ""
    @Volatile private var lastFraction = 0f
    private var lastActivityElapsed = SystemClock.elapsedRealtime()
    private var lastMotionEventElapsed = 0L
    private var ignoreMotionUntil = 0L
    private var pageLoadedElapsed = 0L
    private var pageFailed = false
    private var retryDelayMs = RETRY_MIN_MS
    private var resumed = false

    // --- HTTP ---

    // --- camera ---
    private val detector = MotionDetector()
    private var analysisExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBound = false
    private var motionThresholdPct = 3f

    // --- hidden settings gesture ---
    private val cornerTaps = ArrayDeque<Long>()
    private var swallowGesture = false
    private var pinDialog: AlertDialog? = null

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            when (r.resultCode) {
                SettingsActivity.RESULT_EXIT_KIOSK -> {
                    KioskPolicy.stopLockTask(this, KioskPolicy.LOCK_PAUSE_MS)
                    toast("Kiosk paused for 10 minutes")
                }
                SettingsActivity.RESULT_REMOVE_OWNER -> {
                    val ok = KioskPolicy.removeDeviceOwner(this)
                    toast(if (ok) "Device owner removed — app can now be uninstalled" else "Not device owner")
                }
            }
            // URL/port/etc. may have changed; onResume re-reads config.
            if (cfg.url != currentUrl && cfg.url.isNotEmpty()) loadStartUrl()
        }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) bindCamera()
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        ha = HaNotifier { cfg.haWebhook }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        buildViews()
        setContentView(root)

        cfg.applyExtras(intent?.extras).let { if (it.isNotEmpty()) toast("Config updated: ${it.joinToString()}") }
        KioskPolicy.applyOwnerPolicies(this)
        restartServerIfNeeded()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView?.let { if (it.canGoBack()) it.goBack() }
                // Otherwise swallow: a kiosk never "exits" via back.
            }
        })

        if (cfg.url.isEmpty()) {
            showMessage(getString(R.string.app_name) + "\n\nNo start URL set.\nTap the top-left corner 5× for Settings.")
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        } else {
            loadStartUrl()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        active = java.lang.ref.WeakReference(this)
        hideSystemBars()
        KioskPolicy.applyOwnerPolicies(this)
        KioskPolicy.maybeStartLockTask(this)
        webView?.onResume()
        reloadConfig()
        markActivity()
        main.removeCallbacks(tick)
        main.postDelayed(tick, TICK_MS)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(tick)
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (active?.get() === this) active = null
        ha.shutdown()
        cameraProvider?.unbindAll()
        analysisExecutor?.shutdown()
        pinDialog?.dismiss()
        webView?.let { root.removeView(it); it.destroy() }
        webView = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Re-reads settings that may have changed (Settings screen, adb intent, HTTP). */
    private fun reloadConfig() {
        restartServerIfNeeded()
        detector.blockDelta = cfg.motionDelta
        motionThresholdPct = cfg.motionPercent
        debugBox.visibility = if (cfg.motionDebug) View.VISIBLE else View.GONE
        applyBrightness()
        if (cfg.motionEnabled) ensureCamera() else unbindCamera()
    }

    private fun applyIntentExtras(intent: Intent?) {
        val changed = cfg.applyExtras(intent?.extras)
        if (changed.isEmpty()) return
        Log.i(TAG, "config from intent: $changed")
        toast("Config updated: ${changed.joinToString()}")
        reloadConfigIfReady()
        if ("url" in changed && cfg.url.isNotEmpty()) loadStartUrl()
    }

    private fun reloadConfigIfReady() {
        if (::debugBox.isInitialized && resumed) reloadConfig()
    }

    // ------------------------------------------------------------------ views

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        messageView = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.GRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        sleepOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true // swallow touches (dispatchTouchEvent handles waking)
        }
        debugDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.DKGRAY) }
        }
        debugText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(8), 0, 0, 0)
        }
        debugBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(8), dp(6), dp(10), dp(6))
            addView(debugDot, LinearLayout.LayoutParams(dp(14), dp(14)))
            addView(debugText)
            visibility = View.GONE
        }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        // z-order: webview (index 0, added later) < message < sleep overlay < debug
        root.addView(messageView, FrameLayout.LayoutParams(match, match))
        root.addView(sleepOverlay, FrameLayout.LayoutParams(match, match))
        root.addView(debugBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(12)) })
        createWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val wv = WebView(this)
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        wv.addJavascriptInterface(KioskBridge(), "Kiosk")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== webView) return
                if (!pageFailed && url != null && url != "about:blank") {
                    pageLoadedElapsed = SystemClock.elapsedRealtime()
                    retryDelayMs = RETRY_MIN_MS
                    main.removeCallbacks(retry)
                    hideMessage()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view === webView && request.isForMainFrame) onLoadFailed("${error.errorCode} ${error.description}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (view === webView && request.isForMainFrame && response.statusCode >= 500) onLoadFailed("HTTP ${response.statusCode}")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer gone (crash=${detail.didCrash()}), recreating WebView")
                if (view === webView) {
                    root.removeView(view)
                    webView = null
                    view.destroy()
                    showMessage("Reconnecting… (renderer restarted)")
                    createWebView()
                    if (resumed) webView?.onResume()
                    main.postDelayed({ loadStartUrl() }, 1000)
                }
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (BuildConfig.DEBUG) Log.d(TAG, "console: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                return true
            }
            // Avoid the grey "play" placeholder before autoplay videos start.
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        root.addView(wv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
    }

    private fun showMessage(text: String) {
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        messageView.visibility = View.GONE
    }

    // ------------------------------------------------------------------ loading / retry

    private fun loadStartUrl() {
        val url = cfg.url
        if (url.isEmpty()) {
            showMessage("No start URL set.\nTap the top-left corner 5× for Settings.")
            return
        }
        currentUrl = url
        main.removeCallbacks(retry)
        webView?.loadUrl(url)
    }

    private fun onLoadFailed(why: String) {
        pageFailed = true
        Log.w(TAG, "load failed: $why; retry in ${retryDelayMs / 1000}s")
        showMessage("Reconnecting… ($why)\nretrying in ${retryDelayMs / 1000}s")
        main.removeCallbacks(retry)
        main.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private val retry = Runnable { loadStartUrl() }

    private fun reloadPage() {
        if (currentUrl.isEmpty()) loadStartUrl() else webView?.reload()
        pageLoadedElapsed = SystemClock.elapsedRealtime() // avoid double reloads while it loads
    }

    // ------------------------------------------------------------------ periodic tick

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            applyBrightness() // picks up night-window transitions
            if (resumed) KioskPolicy.maybeStartLockTask(this@MainActivity) // ends an "Exit kiosk" pause
            val timeoutMs = cfg.sleepTimeoutMin * 60_000L
            if (!asleep && timeoutMs > 0 && now - lastActivityElapsed >= timeoutMs) goToSleep("timer")
            if (pageLoadedElapsed > 0 && now - pageLoadedElapsed > MAX_PAGE_AGE_MS && !pageFailed) {
                Log.i(TAG, "page older than 24h, reloading")
                reloadPage()
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------------ sleep / wake

    private fun markActivity() {
        lastActivityElapsed = SystemClock.elapsedRealtime()
    }

    private fun goToSleep(reason: String) {
        if (asleep) return
        asleep = true
        sleepOverlay.visibility = View.VISIBLE
        applyBrightness()
        // The screen going dark changes the room light the camera sees (auto-exposure swings), so
        // ignore motion briefly; a deliberate "sleep" (page button / API) gets a longer grace so
        // the hand that pressed it doesn't immediately wake it again.
        ignoreMotionUntil = SystemClock.elapsedRealtime() +
            if (reason == "timer") SLEEP_SETTLE_MS else SLEEP_GRACE_MS
        dispatchState(reason)
        ha.state(true)
    }

    private fun wakeUp(reason: String) {
        markActivity()
        if (!asleep) return
        asleep = false
        sleepOverlay.visibility = View.GONE
        applyBrightness()
        hideSystemBars()
        dispatchState(reason)
        ha.state(false)
    }

    private fun applyBrightness() {
        val level = if (asleep) cfg.sleepBrightness else cfg.effectiveAwakeBrightness()
        if (level == appliedBrightness) return
        appliedBrightness = level
        window.attributes = window.attributes.apply {
            // 0f = dimmest the panel allows; the black overlay does the rest.
            screenBrightness = (level / 255f).coerceIn(0f, 1f)
        }
    }

    private fun dispatchState(reason: String) {
        val state = if (asleep) "asleep" else "awake"
        webView?.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('kiosk',{detail:{state:'$state',reason:'$reason'}}));",
            null)
    }

    // ------------------------------------------------------------------ touch

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchWallMs = System.currentTimeMillis()
            checkCornerTap(ev)
            if (asleep) {
                // First touch only wakes; the whole gesture is swallowed so it can't click the page.
                swallowGesture = true
                wakeUp("touch")
                return true
            }
            swallowGesture = false
            markActivity()
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowGesture = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun checkCornerTap(ev: MotionEvent) {
        val size = dp(80)
        if (ev.x > size || ev.y > size) {
            cornerTaps.clear()
            return
        }
        val now = SystemClock.elapsedRealtime()
        cornerTaps.addLast(now)
        while (cornerTaps.isNotEmpty() && now - cornerTaps.first() > 3000) cornerTaps.removeFirst()
        if (cornerTaps.size >= 5) {
            cornerTaps.clear()
            showPinDialog()
        }
    }

    private fun showPinDialog() {
        if (pinDialog?.isShowing == true) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        pinDialog = AlertDialog.Builder(this)
            .setTitle("Kiosk settings")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == cfg.pin) {
                    settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                } else toast("Wrong PIN")
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { hideSystemBars() }
            .show()
    }

    // ------------------------------------------------------------------ camera / motion

    private fun ensureCamera() {
        if (cameraBound) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else if (cfg.url.isNotEmpty() && !cfg.prefs.getBoolean(Config.K_CAMERA_ASKED, false)) {
            // Ask once, after first-run setup; device owner auto-grants it (KioskPolicy).
            cfg.prefs.edit().putBoolean(Config.K_CAMERA_ASKED, true).apply()
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindCamera() {
        if (cameraBound || !cfg.motionEnabled) return
        cameraBound = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                if (isDestroyed || !cameraBound) return@addListener
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    Log.w(TAG, "no front camera"); return@addListener
                }
                val exec = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                detector.reset()
                analysis.setAnalyzer(exec, MotionAnalyzer(detector, 2.0) { f ->
                    main.post { onMotionFrame(f) }
                })
                provider.unbindAll()
                // Bound to this activity's lifecycle: runs while STARTED (incl. dimmed), stops when
                // Settings covers us or the activity is destroyed.
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.w(TAG, "camera bind failed", e)
                cameraBound = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        if (!cameraBound) return
        cameraBound = false
        cameraProvider?.unbindAll()
    }

    private fun onMotionFrame(fraction: Float) {
        lastFraction = fraction
        val pct = fraction * 100f
        val hit = pct >= motionThresholdPct
        if (debugBox.visibility == View.VISIBLE) {
            debugText.text = "Δ %.1f%%  (trigger ≥ %.1f%%, block Δ>%d)".format(pct, motionThresholdPct, detector.blockDelta)
            if (hit) flashDot()
        }
        if (hit) onMotion()
    }

    private fun flashDot() {
        (debugDot.background as GradientDrawable).setColor(Color.GREEN)
        main.removeCallbacks(dotOff)
        main.postDelayed(dotOff, 400)
    }

    private val dotOff = Runnable { (debugDot.background as GradientDrawable).setColor(Color.DKGRAY) }

    private fun onMotion() {
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreMotionUntil) return
        if (now - lastMotionEventElapsed < MOTION_DEBOUNCE_MS) return
        lastMotionEventElapsed = now
        lastMotionWallMs = System.currentTimeMillis()
        ha.motion()
        if (asleep) wakeUp("motion") else markActivity()
    }

    // ------------------------------------------------------------------ HTTP API

    /**
     * One server per process, not per activity: Android can hold two MainActivity instances
     * (the HOME-type one started at boot plus a regular one), and the second would fail to
     * bind. Requests go to whichever instance was resumed last.
     */
    private fun restartServerIfNeeded() {
        val port = cfg.httpPort
        if (server != null && port == serverPort) return
        server?.stop()
        serverPort = port
        server = KioskHttpServer(port) { req ->
            active?.get()?.handleHttp(req) ?: HttpResponse(503, """{"error":"no active screen"}""")
        }.also {
            it.start()
            it.lastError?.let { e -> Log.w(TAG, e); toast("HTTP: $e") }
        }
    }

    /** Runs on an HTTP worker thread. */
    internal fun handleHttp(req: HttpRequest): HttpResponse {
        val token = cfg.httpToken
        if (token.isEmpty()) return HttpResponse(403, """{"error":"no token configured"}""")
        if (!req.isAuthorized(token)) return HttpResponse(401, """{"error":"unauthorized"}""")

        fun post(block: () -> Unit): HttpResponse {
            if (req.method != "POST") return HttpResponse(405, """{"error":"use POST"}""")
            onMain(block)
            return HttpResponse(200, statusJson().toString())
        }

        return when (req.path) {
            "/status" ->
                if (req.method == "GET") HttpResponse(200, statusJson().toString())
                else HttpResponse(405, """{"error":"use GET"}""")
            "/wake" -> post { wakeUp("remote") }
            "/sleep" -> post { goToSleep("remote") }
            "/reload" -> post { reloadPage() }
            "/brightness" -> {
                val v = req.query["value"]?.toIntOrNull()
                if (v == null || v !in 0..255) HttpResponse(400, """{"error":"value must be 0-255"}""")
                else post { cfg.awakeBrightness = v; appliedBrightness = -1; applyBrightness() }
            }
            "/url" -> {
                val u = req.query["u"]?.trim().orEmpty()
                if (!(u.startsWith("http://") || u.startsWith("https://"))) HttpResponse(400, """{"error":"u must be an http(s) URL"}""")
                else post { cfg.url = u; loadStartUrl() }
            }
            else -> HttpResponse(404, """{"error":"not found"}""")
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun statusJson(): JSONObject {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return JSONObject().apply {
            put("asleep", asleep)
            put("brightness", appliedBrightness)
            put("awakeBrightness", cfg.awakeBrightness)
            put("lastMotionMs", lastMotionWallMs)
            put("lastTouchMs", lastTouchWallMs)
            put("battery", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            put("plugged", plugged != 0)
            put("url", currentUrl)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("deviceOwner", KioskPolicy.isDeviceOwner(this@MainActivity))
            put("lockTask", KioskPolicy.isInLockTask(this@MainActivity))
            put("motionEnabled", cfg.motionEnabled && cameraBound)
            put("motionFraction", lastFraction.toDouble())
        }
    }

    // ------------------------------------------------------------------ JS bridge

    /** Exposed to the page as window.Kiosk. Methods run on a WebView binder thread. */
    inner class KioskBridge {
        @JavascriptInterface fun isKiosk(): Boolean = true
        @JavascriptInterface fun wake() { main.post { wakeUp("remote") } }
        @JavascriptInterface fun sleep() { main.post { goToSleep("remote") } }
        @JavascriptInterface fun status(): String = statusJson().toString()
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    companion object {
        private var server: KioskHttpServer? = null
        private var serverPort = -1
        @Volatile private var active: java.lang.ref.WeakReference<MainActivity>? = null
        private const val TAG = "HomeKiosk"
        private const val TICK_MS = 15_000L
        private const val RETRY_MIN_MS = 5_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val MAX_PAGE_AGE_MS = 24 * 60 * 60 * 1000L
        private const val MOTION_DEBOUNCE_MS = 2_000L
        private const val SLEEP_GRACE_MS = 10_000L
        private const val SLEEP_SETTLE_MS = 4_000L
    }
}
