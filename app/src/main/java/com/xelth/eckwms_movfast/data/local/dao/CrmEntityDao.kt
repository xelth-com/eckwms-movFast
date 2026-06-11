package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.CrmEntityEntity

@Dao
interface CrmEntityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CrmEntityEntity)

    @Query("SELECT * FROM crm_entities WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CrmEntityEntity?

    @Query("SELECT * FROM crm_entities WHERE entityType = :type ORDER BY fetchedAt DESC LIMIT :limit")
    suspend fun getRecentByType(type: String, limit: Int = 50): List<CrmEntityEntity>

    @Query("DELETE FROM crm_entities WHERE fetchedAt < :olderThan")
    suspend fun pruneOlderThan(olderThan: Long)
}
