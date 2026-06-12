package com.xelth.eckwms_movfast.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xelth.eckwms_movfast.data.local.dao.AttachmentDao
import com.xelth.eckwms_movfast.data.local.dao.FileResourceDao
import com.xelth.eckwms_movfast.data.local.dao.PickingDao
import com.xelth.eckwms_movfast.data.local.dao.ReferenceDao
import com.xelth.eckwms_movfast.data.local.dao.ScanDao
import com.xelth.eckwms_movfast.data.local.dao.LocalPhotoDao
import com.xelth.eckwms_movfast.data.local.dao.ActiveTransactionDao
import com.xelth.eckwms_movfast.data.local.dao.CrmEntityDao
import com.xelth.eckwms_movfast.data.local.dao.SyncQueueDao
import com.xelth.eckwms_movfast.data.local.dao.TripDao
import com.xelth.eckwms_movfast.data.local.entity.ActiveTransactionEntity
import com.xelth.eckwms_movfast.data.local.entity.ActiveTransactionItemEntity
import com.xelth.eckwms_movfast.data.local.entity.AttachmentEntity
import com.xelth.eckwms_movfast.data.local.entity.CrmEntityEntity
import com.xelth.eckwms_movfast.data.local.entity.FileResourceEntity
import com.xelth.eckwms_movfast.data.local.entity.InventoryRecordEntity
import com.xelth.eckwms_movfast.data.local.entity.LocalPhotoEntity
import com.xelth.eckwms_movfast.data.local.entity.LocationEntity
import com.xelth.eckwms_movfast.data.local.entity.PickLineEntity
import com.xelth.eckwms_movfast.data.local.entity.PickingOrderEntity
import com.xelth.eckwms_movfast.data.local.entity.ProductEntity
import com.xelth.eckwms_movfast.data.local.entity.ScanEntity
import com.xelth.eckwms_movfast.data.local.entity.SyncQueueEntity
import com.xelth.eckwms_movfast.data.local.entity.TripEntity
import com.xelth.eckwms_movfast.data.local.entity.TripPointEntity

@Database(
    entities = [
        ScanEntity::class,
        SyncQueueEntity::class,
        ProductEntity::class,
        LocationEntity::class,
        FileResourceEntity::class,
        AttachmentEntity::class,
        InventoryRecordEntity::class,
        PickingOrderEntity::class,
        PickLineEntity::class,
        LocalPhotoEntity::class,
        ActiveTransactionEntity::class,
        ActiveTransactionItemEntity::class,
        CrmEntityEntity::class,
        TripEntity::class,
        TripPointEntity::class
    ],
    version = 13,  // Trips: trips + trip_points (Fahrtenbuch)
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun referenceDao(): ReferenceDao
    abstract fun fileResourceDao(): FileResourceDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun pickingDao(): PickingDao
    abstract fun localPhotoDao(): LocalPhotoDao
    abstract fun activeTransactionDao(): ActiveTransactionDao
    abstract fun crmEntityDao(): CrmEntityDao
    abstract fun tripDao(): TripDao

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
