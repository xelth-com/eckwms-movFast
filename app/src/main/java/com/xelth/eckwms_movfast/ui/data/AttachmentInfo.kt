package com.xelth.eckwms_movfast.ui.data

data class AttachmentInfo(
    val id: String,
    val fileId: String,
    val mimeType: String,
    val isMain: Boolean,
    val createdAt: String
)
