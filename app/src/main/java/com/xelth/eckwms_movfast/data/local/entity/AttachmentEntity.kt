package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entity_attachments",
    indices = [
        Index("res_model", "res_id"),
        Index("file_resource_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = FileResourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_resource_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "file_resource_id") val fileResourceId: String,
    @ColumnInfo(name = "res_model") val resModel: String,   // "product", "location"
    @ColumnInfo(name = "res_id") val resId: String,         // barcode / SmartCode
    @ColumnInfo(name = "is_main") val isMain: Boolean,
    val tags: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
