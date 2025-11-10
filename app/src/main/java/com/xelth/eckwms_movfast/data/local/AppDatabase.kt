package com.xelth.eckwms_movfast.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xelth.eckwms_movfast.data.local.dao.ScanDao
import com.xelth.eckwms_movfast.data.local.dao.SyncQueueDao
import com.xelth.eckwms_movfast.data.local.entity.ScanEntity
import com.xelth.eckwms_movfast.data.local.entity.SyncQueueEntity

@Database(
    entities = [ScanEntity::class, SyncQueueEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "eckwms_database"
            )
                .fallbackToDestructiveMigration() // For development only
                .build()
        }
    }
}
