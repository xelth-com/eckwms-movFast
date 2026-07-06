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

    @Query("SELECT * FROM trips WHERE id = :id LIMIT 1")
    suspend fun getTrip(id: String): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAt DESC LIMIT :limit")
    fun observeTrips(limit: Int = 50): Flow<List<TripEntity>>

    @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY seq ASC")
    suspend fun getPoints(tripId: String): List<TripPointEntity>

    // Distinct recent free-text purposes → quick-pick hexes on the Purpose field menu.
    @Query("SELECT purposeLabel FROM trips WHERE purposeLabel IS NOT NULL AND purposeLabel != '' GROUP BY purposeLabel ORDER BY MAX(startedAt) DESC LIMIT :limit")
    suspend fun recentPurposeLabels(limit: Int = 8): List<String>

    @Query("SELECT COUNT(*) FROM trip_points WHERE tripId = :tripId")
    suspend fun pointCount(tripId: String): Int

    @Query("SELECT MAX(ts) FROM trip_points WHERE tripId = :tripId")
    suspend fun lastPointTs(tripId: String): Long?

    @Query("UPDATE trips SET endedAt = :endedAt, status = :status WHERE id = :id")
    suspend fun endTrip(id: String, endedAt: Long, status: String = TripEntity.STATUS_ENDED)

    // Orphaned open trip (process died, stop never delivered): close it at its
    // last real activity, keeping any existing note (COALESCE = never overwrite).
    @Query(
        "UPDATE trips SET endedAt = :endedAt, status = 'ended', " +
        "note = COALESCE(note, :note) WHERE id = :id"
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

    @Query("DELETE FROM trip_points WHERE tripId IN (SELECT id FROM trips WHERE status = 'synced' AND syncedAt < :olderThan)")
    suspend fun prunePointsOfOldSyncedTrips(olderThan: Long)
}
