package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.LocalPhotoEntity

@Dao
interface LocalPhotoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: LocalPhotoEntity)

    @Query("SELECT * FROM local_photos WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): LocalPhotoEntity?

    @Query("SELECT * FROM local_photos WHERE receiver_id = :receiverId ORDER BY created_at ASC")
    suspend fun getByReceiverId(receiverId: String): List<LocalPhotoEntity>

    @Query("SELECT * FROM local_photos WHERE slot_index = :slotIndex ORDER BY created_at ASC")
    suspend fun getBySlotIndex(slotIndex: Int): List<LocalPhotoEntity>

    @Query("SELECT * FROM local_photos WHERE sync_status = 'PENDING' AND receiver_id IS NOT NULL ORDER BY created_at ASC")
    suspend fun getPendingUploads(): List<LocalPhotoEntity>

    @Query("UPDATE local_photos SET sync_status = :status WHERE uuid = :uuid")
    suspend fun updateSyncStatus(uuid: String, status: String)

    @Query("UPDATE local_photos SET receiver_id = :receiverId WHERE slot_index = :slotIndex AND receiver_id IS NULL")
    suspend fun bindSlotPhotos(slotIndex: Int, receiverId: String)

    @Query("DELETE FROM local_photos WHERE uuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("DELETE FROM local_photos WHERE slot_index = :slotIndex")
    suspend fun deleteBySlotIndex(slotIndex: Int)

    @Query("SELECT COUNT(*) FROM local_photos WHERE sync_status = 'PENDING' AND receiver_id IS NOT NULL")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM local_photos WHERE sync_status = 'SYNCED' AND created_at < :olderThan")
    suspend fun getOldSyncedPhotos(olderThan: Long): List<LocalPhotoEntity>
}
