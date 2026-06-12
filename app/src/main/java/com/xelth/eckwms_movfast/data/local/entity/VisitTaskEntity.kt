package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A planned customer visit ("надо съездить") in the check-in/check-out model.
 * The PDA geofences these LOCALLY and prompts the worker; only confirmed
 * check-in/check-out events are sent to the server — never the track.
 */
@Entity(tableName = "visit_tasks")
data class VisitTaskEntity(
    @PrimaryKey val id: String,            // server record id
    val title: String,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val dueDate: String,                   // YYYY-MM-DD
    val status: String = "open",           // open | checked_in | done
    val note: String? = null,
    val checkedInAt: Long? = null,
    val checkedOutAt: Long? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
    /** Last time the local geofence prompted for this visit (anti-spam) */
    val lastPromptAt: Long? = null
)
