package dev.homekiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/** Device-owner-only setup. Every call is a no-op (returns false) when we are not device owner. */
object KioskPolicy {
    private const val TAG = "KioskPolicy"

    /** "Exit kiosk" pauses the lock until this time (elapsedRealtime); it re-locks by itself. */
    @Volatile private var lockSuspendedUntil = 0L
    const val LOCK_PAUSE_MS = 10 * 60_000L
    private val RESTRICTIONS = listOf(UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_ADD_USER)

    fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(ctx: Context): Boolean =
        dpm(ctx)?.isDeviceOwnerApp(ctx.packageName) == true

    /**
     * True when we are device owner but our policies aren't in place yet, e.g. `dpm set-device-owner`
     * ran while the app was already in front (so no onCreate/onResume applied them).
     */
    fun policiesPending(ctx: Context): Boolean =
        isDeviceOwner(ctx) && dpm(ctx)?.isLockTaskPermitted(ctx.packageName) != true

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
        // Safe mode disables third-party apps (and so the lock task) — with the power menu reachable,
        // "hold Power off → Safe mode" would otherwise be a way out of the kiosk. No extra users either.
        for (r in RESTRICTIONS) {
            try { dpm.addUserRestriction(admin, r) } catch (e: Exception) { Log.w(TAG, "restriction $r", e) }
        }
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
        for (p in CatPrinter.requiredPermissions()) {
            try {
                dpm.setPermissionGrantState(admin, activity.packageName, p,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (e: Exception) { Log.w(TAG, "grant $p", e) }
        }
        return true
    }

    fun isInLockTask(ctx: Context): Boolean =
        ctx.getSystemService(ActivityManager::class.java)?.lockTaskModeState !=
            ActivityManager.LOCK_TASK_MODE_NONE

    /** Enters lock task only when device owner (otherwise Android would show screen pinning). */
    fun maybeStartLockTask(activity: Activity) {
        if (SystemClock.elapsedRealtime() < lockSuspendedUntil || !isDeviceOwner(activity)) return
        if (!dpm(activity)!!.isLockTaskPermitted(activity.packageName)) return
        if (isInLockTask(activity)) return
        try { activity.startLockTask() } catch (e: Exception) { Log.w(TAG, "startLockTask", e) }
    }

    fun stopLockTask(activity: Activity, pauseMs: Long = Long.MAX_VALUE / 2) {
        lockSuspendedUntil = SystemClock.elapsedRealtime() + pauseMs
        if (pauseMs < LOCK_PAUSE_MS * 10) scheduleReturn(activity, pauseMs)
        try { if (isInLockTask(activity)) activity.stopLockTask() } catch (e: Exception) { Log.w(TAG, "stopLockTask", e) }
    }

    /**
     * When an "Exit kiosk" pause ends, bring the kiosk back to the front even if someone left
     * Android Settings open on top (onResume then re-locks). An alarm rather than a Handler,
     * because Samsung freezes background apps and a frozen process never runs its timers.
     */
    private fun scheduleReturn(ctx: Context, delayMs: Long) {
        val launch = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val pi = PendingIntent.getActivity(ctx, 42, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        // Inexact is fine for a 10-minute pause and needs no exact-alarm permission.
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs + 1_000, pi)
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
        // clearDeviceOwnerApp drops owner-set restrictions too; clear them explicitly anyway.
        for (r in RESTRICTIONS) runCatching { dpm.clearUserRestriction(admin, r) }
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
