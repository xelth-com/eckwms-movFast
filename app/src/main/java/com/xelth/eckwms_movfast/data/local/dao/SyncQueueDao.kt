package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.*
import com.xelth.eckwms_movfast.data.local.entity.SyncQueueEntity

@Dao
interface SyncQueueDao {
    @Insert
    suspend fun addToQueue(job: SyncQueueEntity): Long

    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextJob(): SyncQueueEntity?

    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC")
    suspend fun getAllJobs(): List<SyncQueueEntity>

    @Delete
    suspend fun deleteJob(job: SyncQueueEntity)

    @Query("UPDATE sync_queue SET retries = :retries WHERE id = :id")
    suspend fun updateRetries(id: Long, retries: Int)

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun deleteJobById(id: Long)

    @Query("SELECT COUNT(*) FROM sync_queue")
    suspend fun getQueueSize(): Int
}
