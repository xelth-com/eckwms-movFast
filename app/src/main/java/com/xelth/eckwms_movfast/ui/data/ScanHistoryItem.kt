package com.xelth.eckwms_movfast.ui.data

enum class ScanStatus { PENDING, BUFFERED, CONFIRMED, FAILED }
enum class TransactionType { BARCODE_SCAN, IMAGE_UPLOAD }

data class ScanHistoryItem(
    val id: Long = System.currentTimeMillis(), // Unique ID for each item
    val transactionType: TransactionType = TransactionType.BARCODE_SCAN,
    val barcode: String,
    val timestamp: Long,
    var status: ScanStatus,
    val type: String,
    var checksum: String? = null, // Checksum returned by the server buffer
    val imagePath: String? = null,  // Local path for image uploads
    val imageSize: Long? = null     // Size in bytes for display
)
