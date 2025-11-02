package com.xelth.eckwms_movfast.ui.data

enum class ScanStatus { PENDING, BUFFERED, CONFIRMED, FAILED }

data class ScanHistoryItem(
    val id: Long = System.currentTimeMillis(), // Unique ID for each item
    val barcode: String,
    val timestamp: Long,
    var status: ScanStatus,
    val type: String,
    var checksum: String? = null // Checksum returned by the server buffer
)
