package dev.homekiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relaunches the kiosk after boot / app update. Android 10+ only allows this background
 * activity start for exempt apps — device owner qualifies; otherwise being the default HOME
 * app brings us up anyway.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // As device owner we are the persistent HOME app, so the system starts us at
                // boot already; launching again here would create a second instance.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED && KioskPolicy.isDeviceOwner(context)) return
                val launch = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                try { context.startActivity(launch) } catch (_: Exception) { }
            }
        }
    }
}
