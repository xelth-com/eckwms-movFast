package com.xelth.eckwms_movfast.trips

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Receives IN_VEHICLE enter/exit transitions from the Activity Recognition
 * API and toggles trip recording. EXIT uses the graceful path so short stops
 * (traffic lights, fuel) don't split one trip into many.
 */
class VehicleTransitionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TRANSITION = "com.xelth.eckwms_movfast.VEHICLE_TRANSITION"
        private const val TAG = "VehicleTransition"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRANSITION) return
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        // The API may deliver a BATCH of buffered transitions. Only the
        // chronologically LAST vehicle event reflects the current state — acting
        // on every event in delivery order let a stale EXIT schedule a graceful
        // stop AFTER a fresh ENTER and kill a just-started trip.
        val last = result.transitionEvents
            .filter { it.activityType == DetectedActivity.IN_VEHICLE }
            .maxByOrNull { it.elapsedRealTimeNanos }
            ?: return
        when (last.transitionType) {
            ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                // A spoken declaration ("я поехал в Karlsruhe") armed a trip
                // intent — movement is what actually starts it, with the
                // declared destination/client, last-known vehicle + odometer
                // and the TRUE (pre-drive) declaration timestamp.
                if (TripManager.startTripFromIntent(context)) {
                    Log.i(TAG, "IN_VEHICLE enter → trip started from voice intent")
                } else {
                    Log.i(TAG, "IN_VEHICLE enter → start trip recording")
                    TripManager.startTrip(context, manual = false)
                }
            }
            ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                Log.i(TAG, "IN_VEHICLE exit → graceful stop")
                TripManager.stopTrip(context, graceful = true)
            }
        }
    }
}
