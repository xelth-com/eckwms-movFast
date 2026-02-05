package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val completeName: String,   // full path (complete_name in Odoo)
    val barcode: String?,       // 'p' code, nullable
    val usage: String = "",     // internal, view, supplier, etc.
    val parentId: Long? = null,
    val active: Boolean = true,
    val lastUpdated: Long = System.currentTimeMillis()
)
