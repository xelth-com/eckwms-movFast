package com.xelth.eckwms_movfast.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xelth.eckwms_movfast.data.local.dao.ReferenceDao
import com.xelth.eckwms_movfast.data.local.dao.ScanDao
import com.xelth.eckwms_movfast.data.local.dao.SyncQueueDao
import com.xelth.eckwms_movfast.data.local.entity.LocationEntity
import com.xelth.eckwms_movfast.data.local.entity.ProductEntity
import com.xelth.eckwms_movfast.data.local.entity.ScanEntity
import com.xelth.eckwms_movfast.data.local.entity.SyncQueueEntity

@Database(
    entities = [
        ScanEntity::class,
        SyncQueueEntity::class,
        ProductEntity::class,
        LocationEntity::class
    ],
    version = 5,  // Added qtyAvailable to ProductEntity
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun referenceDao(): ReferenceDao

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
