// app/src/main/java/com/xelth/eckwms_movfast/EckwmsApp.kt
package com.xelth.eckwms_movfast

import android.app.Application
import com.xelth.eckwms_movfast.scanners.ScannerManager

class EckwmsApp : Application() {

    // Ссылка на ScannerManager для удобного доступа
    lateinit var scannerManager: ScannerManager
        private set

    override fun onCreate() {
        super.onCreate()

        // Инициализация ScannerManager
        scannerManager = ScannerManager.getInstance(this)
        scannerManager.initialize()
    }

    override fun onTerminate() {
        super.onTerminate()

        // Освобождение ресурсов сканера
        scannerManager.cleanup()
    }
}