package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: Long,
    val defaultCode: String,    // SKU (default_code in Odoo)
    val name: String,
    val barcode: String?,       // EAN13, nullable in Odoo
    val listPrice: Double = 0.0,
    val weight: Double = 0.0,
    val active: Boolean = true,
    val lastUpdated: Long = System.currentTimeMillis()
)
