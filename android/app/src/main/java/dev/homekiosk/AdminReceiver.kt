package dev.homekiosk

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Device admin component. Becomes useful once provisioned as device owner:
 *   adb shell dpm set-device-owner dev.homekiosk/.AdminReceiver
 */
class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) = ComponentName(context, AdminReceiver::class.java)
    }
}
