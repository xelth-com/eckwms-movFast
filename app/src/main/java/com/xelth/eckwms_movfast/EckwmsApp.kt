// app/src/main/java/com/xelth/eckwms_movfast/EckwmsApp.kt
// Обновлено с добавлением автоматического тестирования API при запуске
package com.xelth.eckwms_movfast

import android.app.Application
import android.util.Log
import com.xelth.eckwms_movfast.data.WarehouseRepository
import com.xelth.eckwms_movfast.diagnostics.ScannerApiTester
import com.xelth.eckwms_movfast.net.HybridMessageSender
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.sync.SyncManager
import com.xelth.eckwms_movfast.utils.CryptoManager
import com.xelth.eckwms_movfast.utils.SettingsManager

class EckwmsApp : Application() {
    private val TAG = "EckwmsApp"

    // Ссылка на ScannerManager для удобного доступа
    lateinit var scannerManager: ScannerManager
        private set

    // Ссылка на WarehouseRepository для доступа к данным
    lateinit var repository: WarehouseRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Приложение запускается...")

        // Initialize SettingsManager
        SettingsManager.init(this)

        // Initialize CryptoManager for secure key storage
        CryptoManager.initialize(this)
        Log.d(TAG, "CryptoManager initialized")

        // Initialize HybridMessageSender for WebSocket+HTTP transport
        HybridMessageSender.init()
        Log.d(TAG, "HybridMessageSender initialized")

        // Инициализация ScannerManager
        scannerManager = ScannerManager.getInstance(this)
        scannerManager.initialize()

        // Initialize WarehouseRepository for offline-first data management
        repository = WarehouseRepository.getInstance(this)
        Log.d(TAG, "WarehouseRepository initialized")

        // Schedule periodic sync to keep data in sync with server
        SyncManager.schedulePeriodicSync(this)
        Log.d(TAG, "Periodic sync scheduled")

        // Запуск автоматического тестирования API сканера при запуске
        // Log.d(TAG, "Запуск автоматического тестирования API сканера...")
        // ScannerApiTester(scannerManager).testAndLogAllApiFunctions()
        // Log.d(TAG, "Автоматическое тестирование API сканера завершено")
    }

    override fun onTerminate() {
        super.onTerminate()

        // Освобождение ресурсов сканера
        scannerManager.cleanup()
        Log.d(TAG, "Приложение завершает работу")
    }
}