package dev.homekiosk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Hand-built settings form (no AppCompat/Preference library). Reached via 5 taps in the top-left
 * corner + PIN, or automatically on first run when no start URL is set.
 */
@Suppress("UseSwitchCompatOrMaterialCode")
class SettingsActivity : Activity() {

    private lateinit var cfg: Config
    private lateinit var form: LinearLayout

    private lateinit var url: EditText
    private lateinit var awake: EditText
    private lateinit var sleepLevel: EditText
    private lateinit var nightOn: Switch
    private lateinit var nightStart: EditText
    private lateinit var nightEnd: EditText
    private lateinit var nightLevel: EditText
    private lateinit var timeout: EditText
    private lateinit var motionOn: Switch
    private lateinit var motionPct: EditText
    private lateinit var motionDelta: EditText
    private lateinit var motionDebug: Switch
    private lateinit var port: EditText
    private lateinit var token: EditText
    private lateinit var webhook: EditText
    private lateinit var pin: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        title = "Kiosk settings"
        val pad = dp(16)
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(form) })

        val owner = KioskPolicy.isDeviceOwner(this)
        note("v${BuildConfig.VERSION_NAME} · device owner: ${if (owner) "yes" else "no"} · " +
            "lock task: ${if (KioskPolicy.isInLockTask(this)) "on" else "off"}")

        header("Page")
        url = text("Start URL", cfg.url, InputType.TYPE_TEXT_VARIATION_URI, "https://dash.example.com/")

        header("Brightness (0–255)")
        awake = number("Awake brightness", cfg.awakeBrightness)
        sleepLevel = number("Asleep brightness (0 = dimmest; raise to 1–3 if 0 misbehaves)", cfg.sleepBrightness)
        nightOn = switch("Night window", cfg.nightEnabled)
        nightStart = text("Night starts (HH:MM)", cfg.nightStart, InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        nightEnd = text("Night ends (HH:MM)", cfg.nightEnd, InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        nightLevel = number("Awake brightness during night window", cfg.nightBrightness)

        header("Sleep / motion")
        timeout = number("Dim after N minutes without motion/touch (0 = never)", cfg.sleepTimeoutMin)
        motionOn = switch("Motion detection (front camera)", cfg.motionEnabled)
        motionPct = text("Trigger when ≥ this % of blocks change (lower = more sensitive)",
            cfg.motionPercent.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        motionDelta = number("Block luma change that counts (0–255, lower = more sensitive)", cfg.motionDelta)
        motionDebug = switch("Show motion debug overlay", cfg.motionDebug)

        header("HTTP API")
        port = number("Port", cfg.httpPort)
        token = text("Token (API disabled while empty)", cfg.httpToken, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        button("Generate random token") { token.setText(Config.randomToken()) }

        header("Home Assistant")
        webhook = text("Webhook URL (optional)", cfg.haWebhook, InputType.TYPE_TEXT_VARIATION_URI,
            "https://ha.example.com/api/webhook/…")

        header("Security")
        pin = text("Settings PIN", cfg.pin, InputType.TYPE_CLASS_NUMBER)

        button("Save") { if (save()) finish() }

        header("Kiosk")
        button("Exit kiosk (stop lock task)") {
            if (save()) { setResult(RESULT_EXIT_KIOSK); finish() }
        }
        button("Open Android settings (only works outside lock task)") {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
        }
        button("Remove device owner…") {
            AlertDialog.Builder(this)
                .setTitle("Remove device owner?")
                .setMessage("Stops kiosk lock, re-enables the lock screen and clears the HOME override. " +
                    "Afterwards the app can be uninstalled normally. Re-provisioning needs a factory reset.")
                .setPositiveButton("Remove") { _, _ -> setResult(RESULT_REMOVE_OWNER); finish() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun save(): Boolean {
        val u = url.text.toString().trim()
        if (u.isNotEmpty() && !(u.startsWith("http://") || u.startsWith("https://"))) return err("URL must start with http:// or https://")
        fun int(e: EditText, range: IntRange, name: String): Int? =
            e.text.toString().trim().toIntOrNull()?.takeIf { it in range } ?: run { err("$name must be ${range.first}–${range.last}"); null }
        val aw = int(awake, 0..255, "Awake brightness") ?: return false
        val sl = int(sleepLevel, 0..255, "Asleep brightness") ?: return false
        val nl = int(nightLevel, 0..255, "Night brightness") ?: return false
        val to = int(timeout, 0..1440, "Timeout") ?: return false
        val md = int(motionDelta, 0..255, "Block change") ?: return false
        val p = int(port, 1024..65535, "Port") ?: return false
        val pct = motionPct.text.toString().trim().toFloatOrNull()?.takeIf { it > 0f && it <= 100f }
            ?: return err("Motion % must be 0–100")
        val ns = nightStart.text.toString().trim()
        val ne = nightEnd.text.toString().trim()
        if (Config.parseHm(ns) == null || Config.parseHm(ne) == null) return err("Night times must be HH:MM")
        val wh = webhook.text.toString().trim()
        if (wh.isNotEmpty() && !(wh.startsWith("http://") || wh.startsWith("https://"))) return err("Webhook must be an http(s) URL")
        val pn = pin.text.toString().trim()
        if (pn.length < 4) return err("PIN needs at least 4 digits")

        cfg.prefs.edit()
            .putString(Config.K_URL, u)
            .putInt(Config.K_AWAKE_BRIGHTNESS, aw)
            .putInt(Config.K_SLEEP_BRIGHTNESS, sl)
            .putBoolean(Config.K_NIGHT_ENABLED, nightOn.isChecked)
            .putString(Config.K_NIGHT_START, ns)
            .putString(Config.K_NIGHT_END, ne)
            .putInt(Config.K_NIGHT_BRIGHTNESS, nl)
            .putInt(Config.K_SLEEP_TIMEOUT, to)
            .putBoolean(Config.K_MOTION_ENABLED, motionOn.isChecked)
            .putFloat(Config.K_MOTION_PERCENT, pct)
            .putInt(Config.K_MOTION_DELTA, md)
            .putBoolean(Config.K_MOTION_DEBUG, motionDebug.isChecked)
            .putInt(Config.K_HTTP_PORT, p)
            .putString(Config.K_HTTP_TOKEN, token.text.toString().trim())
            .putString(Config.K_HA_WEBHOOK, wh)
            .putString(Config.K_PIN, pn)
            .apply()
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun err(msg: String): Boolean {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        return false
    }

    // ---- tiny form builders ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(t: String) {
        form.addView(TextView(this).apply {
            text = t
            textSize = 18f
            setPadding(0, dp(20), 0, dp(4))
        })
    }

    private fun note(t: String) {
        form.addView(TextView(this).apply { text = t; textSize = 13f; alpha = 0.7f })
    }

    private fun label(t: String) {
        form.addView(TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(8), 0, 0) })
    }

    private fun text(lbl: String, value: String, type: Int, hintText: String? = null): EditText {
        label(lbl)
        return EditText(this).apply {
            inputType = if (type and InputType.TYPE_MASK_CLASS == 0) InputType.TYPE_CLASS_TEXT or type else type
            setText(value)
            isSingleLine = true
            hint = hintText
            form.addView(this)
        }
    }

    private fun number(lbl: String, value: Int): EditText =
        text(lbl, value.toString(), InputType.TYPE_CLASS_NUMBER)

    private fun switch(lbl: String, value: Boolean): Switch = Switch(this).apply {
        text = lbl
        isChecked = value
        setPadding(0, dp(12), 0, dp(4))
        form.addView(this)
    }

    private fun button(lbl: String, onClick: (View) -> Unit) {
        form.addView(Button(this).apply {
            text = lbl
            gravity = Gravity.CENTER
            setOnClickListener(onClick)
        })
    }

    companion object {
        const val RESULT_EXIT_KIOSK = Activity.RESULT_FIRST_USER + 1
        const val RESULT_REMOVE_OWNER = Activity.RESULT_FIRST_USER + 2
    }
}
