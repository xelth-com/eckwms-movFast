package com.xelth.eckwms_movfast.trips

import android.content.Context
import android.util.Log
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.VehicleEntity

/**
 * Vehicle registry on the device. Mirrors `GET /api/vehicles` into Room for
 * offline selection at trip start, and resolves a chosen/photographed plate into
 * a trip's vehicle binding (registering the server row on first sight).
 */
object VehicleManager {
    private const val TAG = "VehicleManager"

    /** Collapse separator runs + uppercase — MUST match the server's
     *  `normalize_plate` so dedupe and the GoBD seal stay stable. */
    fun normalizePlate(raw: String): String =
        raw.uppercase().replace(Regex("[\\s-]+"), " ").trim()

    suspend fun knownVehicles(context: Context): List<VehicleEntity> =
        AppDatabase.getInstance(context).vehicleDao().activeVehicles()

    /** Pull the registry from the server into Room (best-effort, online only). */
    suspend fun refresh(context: Context) {
        try {
            val vehicles = ScanApiService(context).fetchVehicles() ?: return
            AppDatabase.getInstance(context).vehicleDao().upsertAll(
                vehicles.map { VehicleEntity(it.id, it.plate, it.label, it.photoId, it.active) }
            )
        } catch (e: Exception) {
            Log.w(TAG, "vehicle refresh failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Attach a vehicle to a trip. `pickedId` is a known vehicle chosen from the
     * list; otherwise `plateRaw` is a typed/OCR'd plate, registered (deduped) on
     * the server. Offline: the plate is still stored on the trip (vehicle_plate
     * is the GoBD-relevant field); the vehicle_id link fills in later.
     */
    suspend fun resolveAndAttach(
        context: Context,
        tripId: String,
        pickedId: String?,
        plateRaw: String?,
        photoId: String?
    ) {
        val db = AppDatabase.getInstance(context)
        val plate = plateRaw?.let { normalizePlate(it) }?.takeIf { it.isNotEmpty() }
        if (pickedId == null && plate == null) return

        var vehicleId = pickedId
        var finalPlate = plate

        if (pickedId != null) {
            if (finalPlate == null) {
                finalPlate = db.vehicleDao().activeVehicles().firstOrNull { it.id == pickedId }?.plate
            }
        } else {
            // New plate → register/dedupe on the server (returns the row id).
            val created = try {
                ScanApiService(context).createVehicle(plate!!, photoId)
            } catch (e: Exception) {
                null
            }
            if (created != null && created.id.isNotEmpty()) {
                vehicleId = created.id
                finalPlate = created.plate
                db.vehicleDao().upsert(
                    VehicleEntity(created.id, created.plate, created.label, created.photoId, created.active)
                )
            } else {
                Log.w(TAG, "createVehicle offline/failed — storing plate only on the trip")
            }
        }
        db.tripDao().setVehicle(tripId, vehicleId, finalPlate)
    }
}
