package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A recorded vehicle trip (Fahrtenbuch entry).
 *
 * Position tracking is passive — cell towers + fused low-power provider,
 * no GPS — so the track is approximate by design. Odometer photos are the
 * authoritative distance source; the server computes an estimated distance
 * from the resolved track when readings are missing.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,              // UUID, same id on the server
    val startedAt: Long,                     // epoch millis
    val endedAt: Long? = null,
    val status: String = STATUS_RECORDING,   // recording | ended | synced
    val startOdometerKm: Double? = null,
    val startOdometerSource: String? = null, // photo | manual | estimated
    val startOdometerPhotoId: String? = null, // CAS uuid
    val endOdometerKm: Double? = null,
    val endOdometerSource: String? = null,
    val endOdometerPhotoId: String? = null,
    val purpose: String = "business",        // business | private | commute
    val note: String? = null,
    val manualStart: Boolean = false,        // started by button vs auto-detect
    val syncedAt: Long? = null
) {
    companion object {
        const val STATUS_RECORDING = "recording"
        const val STATUS_ENDED = "ended"
        const val STATUS_SYNCED = "synced"
    }
}

/**
 * One sampled track point. Either carries resolved coordinates (fused
 * provider) or raw cell identity that the server geocodes via OpenCelliD.
 */
@Entity(tableName = "trip_points")
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val seq: Int,
    val ts: Long,                            // epoch millis
    val source: String,                      // cell | fused | gps
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Double? = null,
    val mcc: Int? = null,
    val mnc: Int? = null,
    val tac: Int? = null,
    val cid: Long? = null,                   // Long: 5G NR NCI exceeds Int
    val signalDbm: Int? = null
)
