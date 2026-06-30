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
import com.xelth.eckwms_movfast.utils.AdaptiveAudioManager
import com.xelth.eckwms_movfast.utils.SunlightModeManager

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
        HybridMessageSender.init(this)
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

        // Event-driven sync: trigger an immediate sync the moment connectivity
        // returns, instead of waiting up to 15 min for the next periodic run.
        SyncManager.registerConnectivityTrigger(this)
        Log.d(TAG, "Connectivity sync trigger registered")

        // Re-arm trip auto-detection on EVERY launch. The ActivityRecognition
        // transition registration is process-level and is lost when the OS kills
        // the app; it was previously only (re)armed on reboot or a manual toggle.
        // The pref defaults ON, so the trip UI shows "Auto 🟢" while nothing was
        // actually registered → no IN_VEHICLE transitions → no auto-recording.
        // requestActivityTransitionUpdates is idempotent (same PendingIntent).
        if (SettingsManager.getTripConsent() &&
            SettingsManager.getTripAutoDetect() &&
            com.xelth.eckwms_movfast.trips.TripManager.hasActivityPermission(this)
        ) {
            val armed = com.xelth.eckwms_movfast.trips.TripManager.enableAutoDetect(this)
            Log.d(TAG, "Trip auto-detect re-armed on launch: $armed")
        } else {
            Log.d(TAG, "Trip auto-detect NOT armed (consent/pref/permission missing)")
        }

        // Initialize Sunlight Mode (ambient light → audio-haptic feedback)
        SunlightModeManager.init(this)
        Log.d(TAG, "SunlightModeManager initialized")

        // Initialize Adaptive Audio (mic-based ambient noise → volume adjustment)
        AdaptiveAudioManager.init(this)
        Log.d(TAG, "AdaptiveAudioManager initialized")

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