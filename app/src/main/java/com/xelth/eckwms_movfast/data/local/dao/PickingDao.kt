package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.PickLineEntity
import com.xelth.eckwms_movfast.data.local.entity.PickingOrderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PickingDao {
    // --- Picking Orders ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPickings(pickings: List<PickingOrderEntity>)

    @Query("SELECT * FROM picking_orders WHERE state = 'assigned' ORDER BY priority DESC, scheduledDate ASC")
    fun getActivePickingsFlow(): Flow<List<PickingOrderEntity>>

    @Query("SELECT * FROM picking_orders WHERE state = 'assigned' ORDER BY priority DESC, scheduledDate ASC")
    suspend fun getActivePickings(): List<PickingOrderEntity>

    @Query("SELECT * FROM picking_orders WHERE id = :id")
    suspend fun getPickingById(id: Long): PickingOrderEntity?

    // --- Pick Lines ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPickLines(lines: List<PickLineEntity>)

    @Query("SELECT * FROM pick_lines WHERE pickingId = :pickingId ORDER BY sequence ASC")
    fun getPickLinesFlow(pickingId: Long): Flow<List<PickLineEntity>>

    @Query("SELECT * FROM pick_lines WHERE pickingId = :pickingId ORDER BY sequence ASC")
    suspend fun getPickLines(pickingId: Long): List<PickLineEntity>

    @Query("UPDATE pick_lines SET qtyDone = :qtyDone, state = :state, lastUpdated = :timestamp WHERE id = :lineId")
    suspend fun updatePickLineProgress(lineId: Long, qtyDone: Double, state: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE picking_orders SET state = :state, pickedCount = :pickedCount, lastUpdated = :timestamp WHERE id = :pickingId")
    suspend fun updatePickingProgress(pickingId: Long, state: String, pickedCount: Int, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM pick_lines WHERE pickingId = :pickingId")
    suspend fun clearPickLines(pickingId: Long)

    @Query("DELETE FROM picking_orders")
    suspend fun clearAll()

    @Query("DELETE FROM pick_lines")
    suspend fun clearAllLines()
}
