package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.CellTowerEntity

@Dao
interface CellTowerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(towers: List<CellTowerEntity>)

    @Query("SELECT * FROM cell_towers WHERE key = :key LIMIT 1")
    suspend fun get(key: String): CellTowerEntity?

    @Query("SELECT COUNT(*) FROM cell_towers")
    suspend fun count(): Int
}
