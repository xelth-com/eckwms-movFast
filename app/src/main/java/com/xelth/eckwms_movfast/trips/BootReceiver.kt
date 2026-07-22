package com.xelth.eckwms_movfast.trips

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xelth.eckwms_movfast.utils.SettingsManager

/**
 * Re-registers trip auto-detect after a reboot AND after a package update.
 *
 * Google Play Services drops Activity-Recognition transition subscriptions on
 * reboot, so without this the user would have to toggle "Fahrten automatisch
 * erkennen" again every boot. The same loss happens on every APP UPDATE
 * (`adb install -r` in the field, Play-store update in production): the update
 * kills the process and the subscription with it, and nothing re-armed it until
 * the next app launch — a drive with the app un-opened after an update was
 * silently not recorded (the 2026-07-06 Würzburg→Eschborn miss). MY_PACKAGE_REPLACED
 * fires right after the update and closes that hole. If the toggle was on, we
 * re-arm here. Normal permission (RECEIVE_BOOT_COMPLETED) → both flavors, Play-safe.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        try {
            // Application.onCreate runs before any receiver, so SettingsManager is
            // already initialized — but init() is idempotent, so be defensive.
            SettingsManager.init(context.applicationContext)
            if (SettingsManager.getTripAutoDetect()) {
                val ok = TripManager.enableAutoDetect(context.applicationContext)
                TripLog.i("BootReceiver", "Re-registered trip auto-detect (${intent.action}): $ok")
            }
        } catch (e: Exception) {
            TripLog.e("BootReceiver", "re-register failed (${intent.action}): ${e.message}", e)
        }
    }
}
