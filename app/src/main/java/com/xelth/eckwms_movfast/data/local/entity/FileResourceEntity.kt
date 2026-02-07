package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_resources",
    indices = [Index(value = ["hash"], unique = true)]
)
data class FileResourceEntity(
    @PrimaryKey val id: String,
    val hash: String,
    @ColumnInfo(name = "original_name") val originalName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "storage_path") val storagePath: String,

    @ColumnInfo(name = "avatar_data", typeAffinity = ColumnInfo.BLOB)
    val avatarData: ByteArray?, // ~8KB WebP thumbnail stored directly in DB

    @ColumnInfo(name = "created_at") val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FileResourceEntity
        if (id != other.id) return false
        if (hash != other.hash) return false
        if (avatarData != null) {
            if (other.avatarData == null) return false
            if (!avatarData.contentEquals(other.avatarData)) return false
        } else if (other.avatarData != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + hash.hashCode()
        result = 31 * result + (avatarData?.contentHashCode() ?: 0)
        return result
    }
}
