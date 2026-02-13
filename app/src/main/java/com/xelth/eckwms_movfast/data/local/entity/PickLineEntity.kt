package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pick_lines",
    indices = [Index("pickingId")]
)
data class PickLineEntity(
    @PrimaryKey val id: Long,
    val pickingId: Long,
    val productId: Long,
    val productName: String,
    val productBarcode: String? = null,
    val productCode: String? = null,
    val qtyDemand: Double,
    val qtyDone: Double = 0.0,
    val locationId: Long,
    val locationName: String,
    val locationBarcode: String? = null,
    val rackId: Long? = null,
    val rackName: String? = null,
    val rackX: Int = 0,
    val rackY: Int = 0,
    val rackWidth: Int = 0,
    val rackHeight: Int = 0,
    val state: String = "assigned",
    val sequence: Int = 0,          // Route order (from TSP)
    val lastUpdated: Long = System.currentTimeMillis()
)
