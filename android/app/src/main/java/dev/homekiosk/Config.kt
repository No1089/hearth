package dev.homekiosk

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.net.URI
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
    val pin: String get() = prefs.getString(K_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

    /** Bluetooth "cat printer" (GB03 etc.). Address/name are filled in by the first scan. */
    var printerEnabled: Boolean
        get() = prefs.getBoolean(K_PRINTER_ENABLED, true)
        set(v) = prefs.edit().putBoolean(K_PRINTER_ENABLED, v).apply()
    var printerAddress: String
        get() = prefs.getString(K_PRINTER_ADDRESS, "") ?: ""
        set(v) = prefs.edit().putString(K_PRINTER_ADDRESS, v).apply()
    var printerName: String
        get() = prefs.getString(K_PRINTER_NAME, "") ?: ""
        set(v) = prefs.edit().putString(K_PRINTER_NAME, v).apply()

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
     * Invalid values are ignored (and logged, without the value) rather than clamped or saved.
     * Returns the list of keys that were changed.
     */
    fun applyExtras(extras: android.os.Bundle?): List<String> {
        if (extras == null) return emptyList()
        val changed = mutableListOf<String>()
        val e = prefs.edit()
        fun reject(extra: String, why: String) { Log.w(TAG, "ignoring extra '$extra': $why") }
        fun str(extra: String, key: String, why: String = "", valid: (String) -> Boolean = { true }) {
            val v = extras.getString(extra)?.trim() ?: return
            if (!valid(v)) { reject(extra, why); return }
            e.putString(key, v); changed += extra
        }
        // Values may come as --es (string) or --ei / --ez (typed) extras.
        fun int(extra: String, key: String, range: IntRange) {
            if (!extras.containsKey(extra)) return
            val v = extras.getString(extra)?.trim()?.toIntOrNull()
                ?: extras.getInt(extra, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                ?: return reject(extra, "not a number")
            if (v !in range) return reject(extra, "must be ${range.first}–${range.last}")
            e.putInt(key, v); changed += extra
        }
        fun bool(extra: String, key: String) {
            if (!extras.containsKey(extra)) return
            val s = extras.getString(extra)
            val b = if (s != null) s.equals("true", true) || s == "1" else extras.getBoolean(extra)
            e.putBoolean(key, b); changed += extra
        }
        str("url", K_URL, "not an http(s) URL", ::isHttpUrl)
        str("token", K_HTTP_TOKEN)
        str("webhook", K_HA_WEBHOOK, "not an http(s) URL", ::isHttpUrl)
        // With `--ez remove_owner true` the pin extra authorises that action (MainActivity checks it
        // against the saved PIN) instead of changing the PIN.
        // Changing the PIN by extra needs the current one (`--es old_pin`) once the default is gone;
        // otherwise `--es pin X` followed by `--ez remove_owner true --es pin X` would need no secret.
        if (!extras.containsKey(EXTRA_REMOVE_OWNER)) {
            val current = pin
            if (current == DEFAULT_PIN || extras.getString("old_pin")?.trim() == current)
                str("pin", K_PIN, "must be 4–8 digits", ::isPin)
            else if (extras.containsKey("pin"))
                reject("pin", "needs --es old_pin <current PIN> once the default PIN has been changed")
        }
        str("night_start", K_NIGHT_START, "must be HH:MM") { parseHm(it) != null }
        str("night_end", K_NIGHT_END, "must be HH:MM") { parseHm(it) != null }
        int("port", K_HTTP_PORT, 1024..65535)
        int("brightness", K_AWAKE_BRIGHTNESS, 0..255)
        int("night_brightness", K_NIGHT_BRIGHTNESS, 0..255)
        int("sleep_minutes", K_SLEEP_TIMEOUT, 0..1440)
        bool("night", K_NIGHT_ENABLED)
        bool("motion", K_MOTION_ENABLED)
        bool("motion_debug", K_MOTION_DEBUG)
        bool("printer", K_PRINTER_ENABLED)
        e.apply()
        return changed
    }

    companion object {
        private const val TAG = "HomeKiosk"
        /** One-shot intent extra handled by MainActivity; never persisted. */
        const val EXTRA_REMOVE_OWNER = "remove_owner"

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
        const val DEFAULT_PIN = "1234"
        const val K_CAMERA_ASKED = "camera_asked"
        const val K_BT_ASKED = "bt_asked"
        const val K_PRINTER_ENABLED = "printer_enabled"
        const val K_PRINTER_ADDRESS = "printer_address"
        const val K_PRINTER_NAME = "printer_name"

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

        /** Absolute URL with scheme http or https and a non-empty authority (host[:port]). */
        fun isHttpUrl(s: String): Boolean = try {
            val u = URI(s)
            (u.scheme.equals("http", true) || u.scheme.equals("https", true)) && !u.rawAuthority.isNullOrEmpty()
        } catch (_: Exception) { false }

        /** 4–8 digits (what the PIN dialog's numeric keypad can enter). */
        fun isPin(s: String): Boolean = s.length in 4..8 && s.all { it in '0'..'9' }
    }
}

object TimeWindow {
    /**
     * True if minute-of-day [m] is inside [start, end), wrapping past midnight when end < start.
     * start == end is an empty window (never inside), not "all day".
     */
    fun contains(start: Int, end: Int, m: Int): Boolean =
        if (start == end) false
        else if (start < end) m in start until end
        else m >= start || m < end
}
