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

        for (event in result.transitionEvents) {
            if (event.activityType != DetectedActivity.IN_VEHICLE) continue
            when (event.transitionType) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                    Log.i(TAG, "IN_VEHICLE enter → start trip recording")
                    TripManager.startTrip(context, manual = false)
                }
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                    Log.i(TAG, "IN_VEHICLE exit → graceful stop")
                    TripManager.stopTrip(context, graceful = true)
                }
            }
        }
    }
}
