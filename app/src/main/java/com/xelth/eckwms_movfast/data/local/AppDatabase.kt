package com.xelth.eckwms_movfast.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xelth.eckwms_movfast.data.local.dao.AttachmentDao
import com.xelth.eckwms_movfast.data.local.dao.FileResourceDao
import com.xelth.eckwms_movfast.data.local.dao.PickingDao
import com.xelth.eckwms_movfast.data.local.dao.ReferenceDao
import com.xelth.eckwms_movfast.data.local.dao.ScanDao
import com.xelth.eckwms_movfast.data.local.dao.LocalPhotoDao
import com.xelth.eckwms_movfast.data.local.dao.ActiveTransactionDao
import com.xelth.eckwms_movfast.data.local.dao.CrmEntityDao
import com.xelth.eckwms_movfast.data.local.dao.SyncQueueDao
import com.xelth.eckwms_movfast.data.local.dao.CellTowerDao
import com.xelth.eckwms_movfast.data.local.dao.VehicleDao
import com.xelth.eckwms_movfast.data.local.dao.TripDao
import com.xelth.eckwms_movfast.data.local.dao.VisitDao
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
import com.xelth.eckwms_movfast.data.local.entity.CellTowerEntity
import com.xelth.eckwms_movfast.data.local.entity.VehicleEntity
import com.xelth.eckwms_movfast.data.local.entity.VisitTaskEntity

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
        TripPointEntity::class,
        VisitTaskEntity::class,
        CellTowerEntity::class,
        VehicleEntity::class
    ],
    version = 19,  // trips: tentativeEnd* (odometer-photo stop signal → 6 h auto-end)
    exportSchema = true
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
    abstract fun visitDao(): VisitDao
    abstract fun cellTowerDao(): CellTowerDao
    abstract fun vehicleDao(): VehicleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Real schema migrations. Every schema change MUST bump `version` above
        // AND add a Migration here (or an @AutoMigration). Room exports each
        // version's schema to app/schemas/ (exportSchema=true + the
        // room.schemaLocation KSP arg) so the next migration can be written and
        // tested against a known baseline.
        //
        // DO NOT reinstate fallbackToDestructiveMigration(): it silently wiped a
        // live, unsynced Fahrtenbuch trip on 2026-06-17 when an install bumped
        // the schema (.eck/TECH_DEBT.md → "Android app (movFast)"). On a missing
        // migration the app now FAILS LOUD instead of erasing the driver's data,
        // which for a GoBD logbook is the correct trade-off.
        val MIGRATIONS: Array<Migration> = arrayOf(
            // 17 → 18: trip-event fields on trip_points. Additive ALTER TABLE only,
            // so every existing (unsynced Fahrtenbuch) point is preserved.
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE trip_points ADD COLUMN kind TEXT NOT NULL DEFAULT 'auto'")
                    db.execSQL("ALTER TABLE trip_points ADD COLUMN label TEXT")
                    db.execSQL("ALTER TABLE trip_points ADD COLUMN odometerKm REAL")
                    db.execSQL("ALTER TABLE trip_points ADD COLUMN photoId TEXT")
                }
            },
            // 18 → 19: tentative-end fields on trips (odometer-photo stop signal).
            // Additive ALTER TABLE only — every existing trip is preserved.
            object : Migration(18, 19) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE trips ADD COLUMN tentativeEndTs INTEGER")
                    db.execSQL("ALTER TABLE trips ADD COLUMN tentativeEndOdoKm REAL")
                    db.execSQL("ALTER TABLE trips ADD COLUMN tentativeEndPhotoId TEXT")
                }
            },
        )

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
                .addMigrations(*MIGRATIONS)
                // Only a *downgrade* (newer DB than the installed app) wipes —
                // that can't happen through normal forward updates, so retained
                // Fahrtenbuch data is safe across every upgrade path.
                .fallbackToDestructiveMigrationOnDowngrade()
                // The trip recorder writes from the `:trips` process while the
                // UI reads from the main one: WAL makes concurrent cross-process
                // access safe, multi-instance invalidation keeps main-process
                // Flows live when :trips writes (the invalidation service is
                // pinned to :trips in the manifest so binding it never spawns
                // the heavy main process).
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .enableMultiInstanceInvalidation()
                .build()
        }
    }
}
