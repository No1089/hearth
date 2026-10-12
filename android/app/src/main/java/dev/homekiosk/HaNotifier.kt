package dev.homekiosk

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Fire-and-forget POSTs to an optional Home Assistant webhook. Failures are ignored. */
class HaNotifier(private val webhookUrl: () -> String) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ha-notify").apply { isDaemon = true }
    }
    private var lastMotionMs = 0L

    /** Motion is rate-limited to once per 30 s. */
    fun motion() {
        val now = SystemClock.elapsedRealtime()
        if (lastMotionMs != 0L && now - lastMotionMs < MOTION_INTERVAL_MS) return
        lastMotionMs = now
        post("motion")
    }

    fun state(asleep: Boolean) = post(if (asleep) "asleep" else "awake")

    private fun post(event: String) {
        val target = webhookUrl().trim()
        if (target.isEmpty() || !(target.startsWith("http://") || target.startsWith("https://"))) return
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write("""{"event":"$event"}""".toByteArray()) }
                conn.responseCode // forces the request; body ignored
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun shutdown() = executor.shutdown()

    companion object {
        const val MOTION_INTERVAL_MS = 30_000L
    }
}
