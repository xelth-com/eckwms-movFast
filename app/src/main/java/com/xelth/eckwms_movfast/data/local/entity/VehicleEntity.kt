package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local mirror of the server vehicle registry (Fahrtenbuch). A car is captured
 * ONCE (plate OCR or typed) and thereafter picked from this list; if exactly one
 * active vehicle exists the trip-start dialog auto-fills it. The plate (amtliches
 * Kennzeichen) is denormalized onto each trip and folded into the GoBD seal.
 * Mast-free reference data — no PII.
 */
@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val id: String,   // server uuid
    val plate: String,            // normalized "B X 123"
    val label: String? = null,
    val photoId: String? = null,  // CAS uuid of the plate photo
    val active: Boolean = true
)
