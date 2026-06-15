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

    @Query("SELECT COUNT(*) FROM trip_points WHERE tripId = :tripId")
    suspend fun pointCount(tripId: String): Int

    @Query("UPDATE trips SET endedAt = :endedAt, status = :status WHERE id = :id")
    suspend fun endTrip(id: String, endedAt: Long, status: String = TripEntity.STATUS_ENDED)

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
