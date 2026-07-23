package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.TripEntity
import com.xelth.eckwms_movfast.data.local.entity.TripPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrip(trip: TripEntity)

    @Insert
    suspend fun insertPoint(point: TripPointEntity)

    @Query("SELECT * FROM trips WHERE status = 'recording' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getOpenTrip(): TripEntity?

    // Live open-trip row for the main-process UI bridge (TripManager.startUiBridge):
    // re-emits via cross-process invalidation when the :trips recorder writes.
    @Query("SELECT * FROM trips WHERE status = 'recording' ORDER BY startedAt DESC LIMIT 1")
    fun observeOpenTrip(): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :id LIMIT 1")
    suspend fun getTrip(id: String): TripEntity?

    // Newest completed trip — presets the voice trip intent (last known vehicle
    // + its end odometer as the estimated start reading).
    @Query("SELECT * FROM trips WHERE endedAt IS NOT NULL ORDER BY endedAt DESC LIMIT 1")
    suspend fun lastEndedTrip(): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAt DESC LIMIT :limit")
    fun observeTrips(limit: Int = 50): Flow<List<TripEntity>>

    // Long-press on the 🚗 hex → the last trips as console rows (offline view).
    @Query("SELECT * FROM trips ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecentTrips(limit: Int = 20): List<TripEntity>

    // Long-press on the 🧾 expense hex → the last logged expenses across all
    // trips (fuel/parking/toll/receipt points — the tax-relevant events).
    @Query(
        "SELECT * FROM trip_points WHERE kind IN ('fuel','parking','toll','receipt') " +
        "ORDER BY ts DESC LIMIT :limit"
    )
    suspend fun getRecentExpenses(limit: Int = 20): List<TripPointEntity>

    // Ended trips the server never confirmed — the SyncWorker sweep retries
    // these every run (a dropped queue job must not strand GoBD data).
    @Query("SELECT * FROM trips WHERE status = 'ended' ORDER BY startedAt ASC LIMIT :limit")
    suspend fun getUnsyncedEnded(limit: Int = 20): List<TripEntity>

    @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY seq ASC")
    suspend fun getPoints(tripId: String): List<TripPointEntity>

    // Distinct recent free-text purposes → quick-pick hexes on the Purpose field menu.
    @Query("SELECT purposeLabel FROM trips WHERE purposeLabel IS NOT NULL AND purposeLabel != '' GROUP BY purposeLabel ORDER BY MAX(startedAt) DESC LIMIT :limit")
    suspend fun recentPurposeLabels(limit: Int = 8): List<String>

    @Query("SELECT COUNT(*) FROM trip_points WHERE tripId = :tripId")
    suspend fun pointCount(tripId: String): Int

    @Query("SELECT MAX(ts) FROM trip_points WHERE tripId = :tripId")
    suspend fun lastPointTs(tripId: String): Long?

    // Newest point that carries coordinates — the anchor for the battery-death
    // gap bridge (cell-only points have no local lat/lng and can't anchor).
    @Query(
        "SELECT * FROM trip_points WHERE tripId = :tripId AND lat IS NOT NULL " +
        "AND lng IS NOT NULL ORDER BY ts DESC LIMIT 1"
    )
    suspend fun lastLocatedPoint(tripId: String): TripPointEntity?

    // Only a RECORDING trip can be ended — a retro close from the stop history
    // may have already ended it at a driver-chosen moment, and a service
    // finalize racing in afterwards must not stomp that endedAt.
    @Query(
        "UPDATE trips SET endedAt = :endedAt, status = :status " +
        "WHERE id = :id AND status = 'recording'"
    )
    suspend fun endTrip(id: String, endedAt: Long, status: String = TripEntity.STATUS_ENDED)

    // Orphaned open trip (process died, stop never delivered): close it at its
    // last real activity, keeping any existing note (COALESCE = never overwrite).
    // Recording-only guard for the same retro-close race as endTrip.
    @Query(
        "UPDATE trips SET endedAt = :endedAt, status = 'ended', " +
        "note = COALESCE(note, :note) WHERE id = :id AND status = 'recording'"
    )
    suspend fun closeStale(id: String, endedAt: Long, note: String)

    // Declared purpose merged onto the OPEN trip (spec: editable until trip end).
    // COALESCE keeps the EARLIEST declaration timestamp — the anti-fabrication
    // anchor the server seals (same earliest-wins rule as upload_trip).
    @Query(
        "UPDATE trips SET purpose = :purpose, purposeRef = :ref, purposeLabel = :label, " +
        "purposeSource = :source, purposeDeclaredAt = COALESCE(purposeDeclaredAt, :declaredAt) " +
        "WHERE id = :id"
    )
    suspend fun updatePurpose(
        id: String, purpose: String, ref: String?, label: String?, source: String?, declaredAt: Long
    )

    @Query("UPDATE trips SET status = 'synced', syncedAt = :syncedAt WHERE id = :id")
    suspend fun markSynced(id: String, syncedAt: Long)

    @Query(
        "UPDATE trips SET startOdometerKm = :km, startOdometerSource = :source, " +
        "startOdometerPhotoId = :photoId WHERE id = :id"
    )
    suspend fun setStartOdometer(id: String, km: Double, source: String, photoId: String?)

    @Query(
        "UPDATE trips SET endOdometerKm = :km, endOdometerSource = :source, " +
        "endOdometerPhotoId = :photoId WHERE id = :id"
    )
    suspend fun setEndOdometer(id: String, km: Double, source: String, photoId: String?)

    @Query("UPDATE trips SET vehicleId = :vehicleId, vehiclePlate = :plate WHERE id = :id")
    suspend fun setVehicle(id: String, vehicleId: String?, plate: String?)

    // DSGVO retention prunes only the raw auto track — expenses (fuel/parking/
    // toll/receipt) and named checkpoints are tax documents and stay on-device
    // (the app is self-sufficient without the server).
    @Query(
        "DELETE FROM trip_points WHERE kind = 'auto' AND " +
        "tripId IN (SELECT id FROM trips WHERE status = 'synced' AND syncedAt < :olderThan)"
    )
    suspend fun prunePointsOfOldSyncedTrips(olderThan: Long)

    // ── Tentative end (odometer-photo stop signal) ─────────────────────────────

    @Query(
        "UPDATE trips SET tentativeEndTs = :ts, tentativeEndOdoKm = :km, " +
        "tentativeEndPhotoId = :photoId WHERE id = :id"
    )
    suspend fun armTentativeEnd(id: String, ts: Long, km: Double, photoId: String?)

    // Driving resumed — the odometer shot was a mid-trip stop, not the end.
    @Query(
        "UPDATE trips SET tentativeEndTs = NULL, tentativeEndOdoKm = NULL, " +
        "tentativeEndPhotoId = NULL WHERE id = :id AND tentativeEndTs IS NOT NULL"
    )
    suspend fun disarmTentativeEnd(id: String)

    // OCR photo uploads finish AFTER the value lands — attach the CAS id late.
    @Query(
        "UPDATE trips SET tentativeEndPhotoId = :photoId " +
        "WHERE id = :id AND tentativeEndTs IS NOT NULL AND tentativeEndPhotoId IS NULL"
    )
    suspend fun setTentativeEndPhoto(id: String, photoId: String)
}
