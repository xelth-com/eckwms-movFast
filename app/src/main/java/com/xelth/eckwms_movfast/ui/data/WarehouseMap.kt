package com.xelth.eckwms_movfast.ui.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class WarehouseMapResponse(
    val id: Long,
    val name: String,
    val racks: List<MapRack>
)

@Serializable
data class MapRack(
    val id: Long,
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    @SerialName("location_id") val locationId: Long? = null,
    @SerialName("location_barcode") val locationBarcode: String? = null
)

// Route overlay data for picking navigation
data class RouteStop(
    val rackId: Long,
    val sequence: Int,
    val productName: String = "",
    val qty: Double = 0.0,
    val isCompleted: Boolean = false,
    val isCurrent: Boolean = false
)

data class PathPoint(val x: Int, val y: Int)
