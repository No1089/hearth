package dev.homekiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log

/** Device-owner-only setup. Every call is a no-op (returns false) when we are not device owner. */
object KioskPolicy {
    private const val TAG = "KioskPolicy"

    /** Session flag set by "Exit kiosk" in Settings so we don't immediately re-lock. */
    @Volatile var lockSuspended = false

    fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(ctx: Context): Boolean =
        dpm(ctx)?.isDeviceOwnerApp(ctx.packageName) == true

    /** Applies all device-owner policies. Safe to call repeatedly. */
    fun applyOwnerPolicies(activity: Activity): Boolean {
        if (!isDeviceOwner(activity)) return false
        val dpm = dpm(activity) ?: return false
        val admin = AdminReceiver.component(activity)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
            // Keep the power-button menu usable; everything else (home, recents, status bar,
            // notifications) stays blocked while in lock task.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        } catch (e: Exception) { Log.w(TAG, "lock task packages", e) }
        try { dpm.setKeyguardDisabled(admin, true) } catch (e: Exception) { Log.w(TAG, "keyguard", e) }
        try {
            val all = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, all.toString())
        } catch (e: Exception) { Log.w(TAG, "stay on", e) }
        try {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter,
                ComponentName(activity, MainActivity::class.java))
        } catch (e: Exception) { Log.w(TAG, "preferred home", e) }
        try {
            // Headless: no runtime-permission dialog needed for motion detection.
            dpm.setPermissionGrantState(admin, activity.packageName, Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        } catch (e: Exception) { Log.w(TAG, "camera grant", e) }
        return true
    }

    fun isInLockTask(ctx: Context): Boolean =
        ctx.getSystemService(ActivityManager::class.java)?.lockTaskModeState !=
            ActivityManager.LOCK_TASK_MODE_NONE

    /** Enters lock task only when device owner (otherwise Android would show screen pinning). */
    fun maybeStartLockTask(activity: Activity) {
        if (lockSuspended || !isDeviceOwner(activity)) return
        if (!dpm(activity)!!.isLockTaskPermitted(activity.packageName)) return
        if (isInLockTask(activity)) return
        try { activity.startLockTask() } catch (e: Exception) { Log.w(TAG, "startLockTask", e) }
    }

    fun stopLockTask(activity: Activity) {
        lockSuspended = true
        try { if (isInLockTask(activity)) activity.stopLockTask() } catch (e: Exception) { Log.w(TAG, "stopLockTask", e) }
    }

    /** Undoes our policies and gives up device owner so the app can be uninstalled. */
    fun removeDeviceOwner(activity: Activity): Boolean {
        if (!isDeviceOwner(activity)) return false
        val dpm = dpm(activity) ?: return false
        val admin = AdminReceiver.component(activity)
        stopLockTask(activity)
        runCatching { dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName) }
        runCatching { dpm.setKeyguardDisabled(admin, false) }
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        return try {
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(activity.packageName)
            true
        } catch (e: Exception) {
            Log.w(TAG, "clearDeviceOwnerApp", e)
            false
        }
    }
}
