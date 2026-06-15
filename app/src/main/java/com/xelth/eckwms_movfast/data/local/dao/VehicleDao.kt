package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.VehicleEntity

@Dao
interface VehicleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(vehicles: List<VehicleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vehicle: VehicleEntity)

    @Query("SELECT * FROM vehicles WHERE active = 1 ORDER BY plate ASC")
    suspend fun activeVehicles(): List<VehicleEntity>

    @Query("SELECT COUNT(*) FROM vehicles WHERE active = 1")
    suspend fun activeCount(): Int

    @Query("SELECT * FROM vehicles WHERE plate = :plate LIMIT 1")
    suspend fun byPlate(plate: String): VehicleEntity?
}
