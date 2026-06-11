package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "picking_orders")
data class PickingOrderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val state: String,
    val partnerName: String? = null,
    val origin: String? = null,
    val priority: String = "0",
    val scheduledDate: Long = 0L,
    val locationId: String = "",
    val locationDestId: String = "",
    val lineCount: Int = 0,
    val pickedCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
