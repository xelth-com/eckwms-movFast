package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xelth.eckwms_movfast.data.local.entity.InventoryRecordEntity
import com.xelth.eckwms_movfast.data.local.entity.LocationEntity
import com.xelth.eckwms_movfast.data.local.entity.ProductEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReferenceDao {
    // --- Products ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<ProductEntity>)

    @Query("SELECT * FROM products WHERE active = 1 ORDER BY name ASC")
    fun getAllProductsFlow(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE barcode = :barcode OR defaultCode = :barcode LIMIT 1")
    suspend fun getProductByBarcode(barcode: String): ProductEntity?

    @Query("SELECT * FROM products WHERE name LIKE '%' || :query || '%' OR defaultCode LIKE '%' || :query || '%' LIMIT 50")
    suspend fun searchProducts(query: String): List<ProductEntity>

    @Query("DELETE FROM products")
    suspend fun clearProducts()

    @Query("SELECT COUNT(*) FROM products")
    suspend fun getProductCount(): Int

    @Query("UPDATE products SET qtyAvailable = :qty, lastUpdated = :timestamp WHERE barcode = :barcode OR defaultCode = :barcode")
    suspend fun updateProductQty(barcode: String, qty: Double, timestamp: Long = System.currentTimeMillis()): Int

    // --- Locations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocations(locations: List<LocationEntity>)

    @Query("SELECT * FROM locations WHERE active = 1 ORDER BY completeName ASC")
    fun getAllLocationsFlow(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM locations WHERE barcode = :barcode LIMIT 1")
    suspend fun getLocationByBarcode(barcode: String): LocationEntity?

    @Query("DELETE FROM locations")
    suspend fun clearLocations()

    @Query("SELECT COUNT(*) FROM locations")
    suspend fun getLocationCount(): Int

    // --- Inventory Records (PDA source of truth) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInventoryRecords(records: List<InventoryRecordEntity>)

    @Query("SELECT * FROM inventory_records WHERE locationBarcode = :locationBarcode ORDER BY productName ASC")
    suspend fun getInventoryRecords(locationBarcode: String): List<InventoryRecordEntity>

    @Query("DELETE FROM inventory_records WHERE locationBarcode = :locationBarcode")
    suspend fun clearInventoryRecords(locationBarcode: String)
}
