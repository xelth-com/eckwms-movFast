package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val completeName: String,
    val barcode: String?,
    val usage: String = "",
    val parentId: String? = null,
    val active: Boolean = true,
    val lastUpdated: Long = System.currentTimeMillis()
)
