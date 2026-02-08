package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity

/**
 * Local record of what was counted at a location.
 * PDA is source of truth — this persists between sessions.
 */
@Entity(tableName = "inventory_records", primaryKeys = ["locationBarcode", "productBarcode"])
data class InventoryRecordEntity(
    val locationBarcode: String,
    val productBarcode: String,
    val productName: String,
    val quantity: Double,
    val type: String = "item",  // "item" or "box"
    val lastCounted: Long = System.currentTimeMillis()
)
