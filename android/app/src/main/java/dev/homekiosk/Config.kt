package dev.homekiosk

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Calendar

/**
 * Runtime configuration. Nothing site-specific is compiled in: URL, token, webhook etc. are
 * set on the device via the hidden Settings screen or the adb intent (see README).
 */
class Config(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(K_URL, "") ?: ""
        set(v) = prefs.edit().putString(K_URL, v.trim()).apply()

    /** 0-255 */
    var awakeBrightness: Int
        get() = prefs.getInt(K_AWAKE_BRIGHTNESS, 180)
        set(v) = prefs.edit().putInt(K_AWAKE_BRIGHTNESS, v.coerceIn(0, 255)).apply()

    /** 0-255, brightness while "asleep" (black overlay on top anyway). */
    val sleepBrightness: Int get() = prefs.getInt(K_SLEEP_BRIGHTNESS, 0)

    val nightEnabled: Boolean get() = prefs.getBoolean(K_NIGHT_ENABLED, false)
    val nightStart: String get() = prefs.getString(K_NIGHT_START, "22:00") ?: "22:00"
    val nightEnd: String get() = prefs.getString(K_NIGHT_END, "06:30") ?: "06:30"
    val nightBrightness: Int get() = prefs.getInt(K_NIGHT_BRIGHTNESS, 30)

    /** Minutes without motion/touch before dimming. 0 = never. */
    val sleepTimeoutMin: Int get() = prefs.getInt(K_SLEEP_TIMEOUT, 10)

    val motionEnabled: Boolean get() = prefs.getBoolean(K_MOTION_ENABLED, true)
    /** Percent of grid blocks that must change to count as motion. */
    val motionPercent: Float get() = prefs.getFloat(K_MOTION_PERCENT, 3f)
    /** Per-block average luma delta (0-255) that counts as "changed". */
    val motionDelta: Int get() = prefs.getInt(K_MOTION_DELTA, 16)
    val motionDebug: Boolean get() = prefs.getBoolean(K_MOTION_DEBUG, false)

    val httpPort: Int get() = prefs.getInt(K_HTTP_PORT, 2323)
    var httpToken: String
        get() = prefs.getString(K_HTTP_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_HTTP_TOKEN, v.trim()).apply()

    val haWebhook: String get() = prefs.getString(K_HA_WEBHOOK, "") ?: ""
    val pin: String get() = prefs.getString(K_PIN, "1234") ?: "1234"

    /** Awake brightness for "now", taking the night window into account. 0-255 */
    fun effectiveAwakeBrightness(now: Calendar = Calendar.getInstance()): Int {
        if (!nightEnabled) return awakeBrightness
        val start = parseHm(nightStart) ?: return awakeBrightness
        val end = parseHm(nightEnd) ?: return awakeBrightness
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (TimeWindow.contains(start, end, m)) nightBrightness else awakeBrightness
    }

    /**
     * Applies config extras from an intent (adb `am start ... --es url ... --es token ...`).
     * Returns the list of keys that were changed.
     */
    fun applyExtras(extras: android.os.Bundle?): List<String> {
        if (extras == null) return emptyList()
        val changed = mutableListOf<String>()
        val e = prefs.edit()
        fun str(extra: String, key: String) {
            extras.getString(extra)?.let { e.putString(key, it.trim()); changed += extra }
        }
        // Values may come as --es (string) or --ei / --ez (typed) extras.
        fun int(extra: String, key: String, range: IntRange) {
            if (!extras.containsKey(extra)) return
            val v = extras.getString(extra)?.trim()?.toIntOrNull()
                ?: extras.getInt(extra, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?: return
            e.putInt(key, v.coerceIn(range.first, range.last)); changed += extra
        }
        fun bool(extra: String, key: String) {
            if (!extras.containsKey(extra)) return
            val s = extras.getString(extra)
            val b = if (s != null) s.equals("true", true) || s == "1" else extras.getBoolean(extra)
            e.putBoolean(key, b); changed += extra
        }
        str("url", K_URL)
        str("token", K_HTTP_TOKEN)
        str("webhook", K_HA_WEBHOOK)
        str("pin", K_PIN)
        str("night_start", K_NIGHT_START)
        str("night_end", K_NIGHT_END)
        int("port", K_HTTP_PORT, 1024..65535)
        int("brightness", K_AWAKE_BRIGHTNESS, 0..255)
        int("night_brightness", K_NIGHT_BRIGHTNESS, 0..255)
        int("sleep_minutes", K_SLEEP_TIMEOUT, 0..1440)
        bool("night", K_NIGHT_ENABLED)
        bool("motion", K_MOTION_ENABLED)
        bool("motion_debug", K_MOTION_DEBUG)
        e.apply()
        return changed
    }

    companion object {
        const val K_URL = "url"
        const val K_AWAKE_BRIGHTNESS = "awake_brightness"
        const val K_SLEEP_BRIGHTNESS = "sleep_brightness"
        const val K_NIGHT_ENABLED = "night_enabled"
        const val K_NIGHT_START = "night_start"
        const val K_NIGHT_END = "night_end"
        const val K_NIGHT_BRIGHTNESS = "night_brightness"
        const val K_SLEEP_TIMEOUT = "sleep_timeout_min"
        const val K_MOTION_ENABLED = "motion_enabled"
        const val K_MOTION_PERCENT = "motion_percent"
        const val K_MOTION_DELTA = "motion_delta"
        const val K_MOTION_DEBUG = "motion_debug"
        const val K_HTTP_PORT = "http_port"
        const val K_HTTP_TOKEN = "http_token"
        const val K_HA_WEBHOOK = "ha_webhook"
        const val K_PIN = "pin"
        const val K_CAMERA_ASKED = "camera_asked"

        /** "HH:MM" -> minutes since midnight, or null. */
        fun parseHm(s: String): Int? {
            val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(s) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return null
            return h * 60 + min
        }

        fun randomToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

object TimeWindow {
    /** True if minute-of-day [m] is inside [start, end), wrapping past midnight when end <= start. */
    fun contains(start: Int, end: Int, m: Int): Boolean =
        if (start == end) false
        else if (start < end) m in start until end
        else m >= start || m < end
}
