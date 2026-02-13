package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "picking_orders")
data class PickingOrderEntity(
    @PrimaryKey val id: Long,
    val name: String,               // e.g., "WH/OUT/00001"
    val state: String,              // assigned, done, cancel
    val partnerName: String? = null,
    val origin: String? = null,     // SO number
    val priority: String = "0",     // 0=Normal, 1=Urgent
    val scheduledDate: Long = 0L,   // epoch millis
    val locationId: Long = 0,
    val locationDestId: Long = 0,
    val lineCount: Int = 0,
    val pickedCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
