package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.FileResourceEntity

@Dao
interface FileResourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileResources(files: List<FileResourceEntity>)

    @Query("SELECT * FROM file_resources WHERE id = :id")
    suspend fun getFileResource(id: String): FileResourceEntity?

    @Query("SELECT * FROM file_resources WHERE hash = :hash")
    suspend fun getFileResourceByHash(hash: String): FileResourceEntity?

    @Query("SELECT COUNT(*) FROM file_resources")
    suspend fun getFileCount(): Int

    @Query("DELETE FROM file_resources")
    suspend fun clearAll()
}
