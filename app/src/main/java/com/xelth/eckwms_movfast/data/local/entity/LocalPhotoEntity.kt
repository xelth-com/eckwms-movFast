package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_photos",
    indices = [
        Index("receiver_id"),
        Index("sync_status")
    ]
)
data class LocalPhotoEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "receiver_id") val receiverId: String?,       // device barcode or "CHECK:box:tag"
    @ColumnInfo(name = "original_path") val originalPath: String,    // filesDir/photos/orig_{uuid}.webp
    @ColumnInfo(name = "avatar_path") val avatarPath: String?,       // filesDir/photos/avatar_{uuid}.webp
    @ColumnInfo(name = "sync_status") val syncStatus: String,        // PENDING, SYNCED, ERROR
    @ColumnInfo(name = "slot_index") val slotIndex: Int? = null,     // repair slot that captured this
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SYNCED = "SYNCED"
        const val STATUS_ERROR = "ERROR"
    }
}
