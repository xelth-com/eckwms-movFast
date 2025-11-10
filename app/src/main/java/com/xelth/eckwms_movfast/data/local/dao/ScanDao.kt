package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.*
import com.xelth.eckwms_movfast.data.local.entity.ScanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: ScanEntity): Long

    @Update
    suspend fun updateScan(scan: ScanEntity)

    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC")
    fun getAllScansFlow(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC")
    suspend fun getAllScans(): List<ScanEntity>

    @Query("SELECT * FROM scan_history WHERE id = :id")
    suspend fun getScanById(id: Long): ScanEntity?

    @Query("UPDATE scan_history SET status = :status, checksum = :checksum WHERE id = :id")
    suspend fun updateScanStatus(id: Long, status: String, checksum: String? = null)

    @Query("DELETE FROM scan_history WHERE timestamp < :cutoffTime")
    suspend fun deleteOldScans(cutoffTime: Long)
}
