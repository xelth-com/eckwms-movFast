package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // e.g., 'scan', 'image_upload'
    val payload: String, // JSON representation of the data to send
    var retries: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val scanId: Long? = null // Reference to scan_history if applicable
)
