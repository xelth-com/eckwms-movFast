package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val defaultCode: String,
    val name: String,
    val barcode: String?,
    val qtyAvailable: Double = 0.0,
    val listPrice: Double = 0.0,
    val weight: Double = 0.0,
    val active: Boolean = true,
    val lastUpdated: Long = System.currentTimeMillis()
)
