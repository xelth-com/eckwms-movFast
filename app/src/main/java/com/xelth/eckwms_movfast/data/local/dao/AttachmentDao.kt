package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.AttachmentEntity
import com.xelth.eckwms_movfast.data.local.entity.FileResourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM entity_attachments WHERE res_model = :resModel AND res_id = :resId")
    fun getAttachmentsForEntity(resModel: String, resId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM entity_attachments WHERE file_resource_id = :fileId")
    suspend fun getAttachmentsByFileId(fileId: String): List<AttachmentEntity>

    // Get avatar bytes for a specific product/location by SmartCode
    @Query("""
        SELECT f.avatar_data
        FROM file_resources f
        INNER JOIN entity_attachments a ON a.file_resource_id = f.id
        WHERE a.res_model = :resModel AND a.res_id = :resId
        ORDER BY a.is_main DESC, f.created_at DESC
        LIMIT 1
    """)
    suspend fun getAvatarForEntity(resModel: String, resId: String): ByteArray?

    // Get all photos for an entity (reactive)
    @Query("""
        SELECT f.* FROM file_resources f
        INNER JOIN entity_attachments a ON a.file_resource_id = f.id
        WHERE a.res_model = :resModel AND a.res_id = :resId
        ORDER BY a.is_main DESC, f.created_at DESC
    """)
    fun getPhotosForEntity(resModel: String, resId: String): Flow<List<FileResourceEntity>>

    @Query("SELECT COUNT(*) FROM entity_attachments")
    suspend fun getAttachmentCount(): Int

    @Query("DELETE FROM entity_attachments")
    suspend fun clearAll()
}
