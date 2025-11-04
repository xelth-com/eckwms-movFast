// app/src/main/java/com/xelth/eckwms_movfast/EckwmsApp.kt
// Обновлено с добавлением автоматического тестирования API при запуске
package com.xelth.eckwms_movfast

import android.app.Application
import android.util.Log
import com.xelth.eckwms_movfast.diagnostics.ScannerApiTester
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.utils.SettingsManager

class EckwmsApp : Application() {
    private val TAG = "EckwmsApp"

    // Ссылка на ScannerManager для удобного доступа
    lateinit var scannerManager: ScannerManager
        private set

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Приложение запускается...")

        // Initialize SettingsManager
        SettingsManager.init(this)

        // Инициализация ScannerManager
        scannerManager = ScannerManager.getInstance(this)
        scannerManager.initialize()

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