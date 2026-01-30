package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_history")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String,
    val timestamp: Long,
    var status: String, // PENDING, BUFFERED, CONFIRMED, FAILED
    val type: String,
    var checksum: String? = null,
    val orderId: String? = null,
    // Image upload fields
    val transactionType: String = "BARCODE_SCAN",  // "BARCODE_SCAN" or "IMAGE_UPLOAD"
    val imagePath: String? = null,
    val imageSize: Long? = null
)
