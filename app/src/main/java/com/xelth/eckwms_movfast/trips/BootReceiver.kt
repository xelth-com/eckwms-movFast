package com.xelth.eckwms_movfast.trips

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xelth.eckwms_movfast.utils.SettingsManager

/**
 * Re-registers trip auto-detect after a reboot.
 *
 * Google Play Services drops Activity-Recognition transition subscriptions on
 * reboot, so without this the user would have to toggle "Fahrten automatisch
 * erkennen" again every boot. If the toggle was on, we re-arm it here. Normal
 * permission (RECEIVE_BOOT_COMPLETED) → present in both flavors, Play-safe.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        try {
            // Application.onCreate runs before any receiver, so SettingsManager is
            // already initialized — but init() is idempotent, so be defensive.
            SettingsManager.init(context.applicationContext)
            if (SettingsManager.getTripAutoDetect()) {
                val ok = TripManager.enableAutoDetect(context.applicationContext)
                Log.i("BootReceiver", "Re-registered trip auto-detect after boot: $ok")
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "boot re-register failed: ${e.message}", e)
        }
    }
}
