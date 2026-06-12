package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local mirror of the server cell_tower cache. Mast positions are NOT personal
 * data. With this cache the PDA resolves KNOWN cells on device — their
 * coordinates then never leave the phone (privacy end-state). Only cells
 * unknown to the cache are uploaded raw for server-side OpenCelliD lookup.
 */
@Entity(tableName = "cell_towers")
data class CellTowerEntity(
    @PrimaryKey val key: String,   // "mcc-mnc-tac-cid"
    val lat: Double,
    val lng: Double,
    val rangeM: Double
)
