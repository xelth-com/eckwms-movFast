package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.VisitTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(visits: List<VisitTaskEntity>)

    @Query("SELECT * FROM visit_tasks WHERE status IN ('open','checked_in') ORDER BY dueDate ASC")
    fun observeOpenVisits(): Flow<List<VisitTaskEntity>>

    @Query("SELECT * FROM visit_tasks WHERE status IN ('open','checked_in') AND lat IS NOT NULL")
    suspend fun openVisitsWithGeo(): List<VisitTaskEntity>

    @Query("SELECT COUNT(*) FROM visit_tasks WHERE status IN ('open','checked_in') AND dueDate <= :today")
    suspend fun openDueCount(today: String): Int

    @Query("UPDATE visit_tasks SET status = :status, checkedInAt = :inAt, checkedOutAt = :outAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, inAt: Long?, outAt: Long?)

    @Query("UPDATE visit_tasks SET lastPromptAt = :ts WHERE id = :id")
    suspend fun markPrompted(id: String, ts: Long)

    @Query("DELETE FROM visit_tasks WHERE status = 'done' AND fetchedAt < :olderThan")
    suspend fun pruneDone(olderThan: Long)
}
