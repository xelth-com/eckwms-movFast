// app/src/main/java/com/xelth/eckwms_movfast/diagnostics/ScannerApiTester.kt
package com.xelth.eckwms_movfast.diagnostics

import android.util.Log
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.FlashMode
import com.xcheng.scanner.AimerMode
import com.xcheng.scanner.OutputMethod
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.XcBarcodeScanner
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.scanners.XCScannerWrapper
import java.lang.reflect.Method

/**
 * Класс для тестирования и вывода информации о доступных API функциях сканера
 * в журнал Logcat для диагностики и отладки.
 */
class ScannerApiTester(private val scannerManager: ScannerManager) {
    private val TAG = "ScannerApiTester"

    /**
     * Запускает проверку всех API функций сканера и выводит результаты в Logcat
     */
    fun testAndLogAllApiFunctions() {
        Log.d(TAG, "===== НАЧАЛО ТЕСТИРОВАНИЯ API СКАНЕРА =====")
        Log.d(TAG, "")

        // Информация о состоянии сканера
        testScannerStatus()
        Log.d(TAG, "")

        // Проверка поддержки типов штрих-кодов
        testBarcodeTypes()
        Log.d(TAG, "")

        // Тестирование функций изображения
        testImageFunctions()
        Log.d(TAG, "")

        // Тестирование доступных методов в SDK
        testAvailableMethods()
        Log.d(TAG, "")

        // Тестирование настроек сканера
        testScannerSettings()
        Log.d(TAG, "")

        Log.d(TAG, "===== ЗАВЕРШЕНИЕ ТЕСТИРОВАНИЯ API СКАНЕРА =====")
    }

    /**
     * Проверяет и выводит информацию о состоянии сканера
     */
    private fun testScannerStatus() {
        Log.d(TAG, "СОСТОЯНИЕ СКАНЕРА:")

        try {
            val isInitialized = scannerManager.isInitialized()
            Log.d(TAG, "✓ Инициализирован: $isInitialized")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Ошибка проверки инициализации: ${e.message}")
        }

        try {
            val lastBarcode = scannerManager.scanResult.value
            Log.d(TAG, "✓ Последний штрих-код: ${lastBarcode ?: "нет"}")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Ошибка получения последнего штрих-кода: ${e.message}")
        }

        try {
            val isLoopScanActive = scannerManager.isLoopScanRunning()
            Log.d(TAG, "✓ Непрерывное сканирование активно: $isLoopScanActive")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Ошибка проверки непрерывного сканирования: ${e.message}")
        }

        try {
            val version = XCScannerWrapper.getServiceVersion()
            Log.d(TAG, "✓ Версия сервиса сканирования: $version")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Ошибка получения версии сервиса: ${e.message}")
        }
    }

    /**
     * Проверяет поддержку различных типов штрих-кодов
     */
    private fun testBarcodeTypes() {
        Log.d(TAG, "ПОДДЕРЖКА ТИПОВ ШТРИХ-КОДОВ:")

        val barcodeTypes = listOf(
            "QRCODE" to BarcodeType.QRCODE,
            "CODE128" to BarcodeType.CODE128,
            "CODE39" to BarcodeType.CODE39,
            "EAN13" to BarcodeType.EAN13,
            "EAN8" to BarcodeType.EAN8,
            "UPCA" to BarcodeType.UPCA,
            "UPCE" to BarcodeType.UPCE,
            "DATAMATRIX" to BarcodeType.DATAMATRIX,
            "PDF417" to BarcodeType.PDF417
        )

        barcodeTypes.forEach { (name, type) ->
            try {
                val isEnabled = XCScannerWrapper.isBarcodeTypeEnabled(type)
                Log.d(TAG, "✓ $name: ${if (isEnabled) "включен" else "выключен"}")
            } catch (e: Exception) {
                Log.e(TAG, "✗ $name: ошибка проверки: ${e.message}")
            }
        }
    }

    /**
     * Тестирует функции, связанные с изображениями
     */
    private fun testImageFunctions() {
        Log.d(TAG, "ФУНКЦИИ ИЗОБРАЖЕНИЯ:")

        try {
            Log.d(TAG, "Пытаемся получить последнее декодированное изображение...")
            val image = XCScannerWrapper.getLastDecodeImage()

            if (image != null) {
                Log.d(TAG, "✓ Изображение получено успешно!")

                // Выводим доступные свойства изображения
                Log.d(TAG, "  - Размер: ${image.width}x${image.height}")

                try {
                    val data = image.data
                    if (data != null) {
                        val dataType = data.javaClass.simpleName
                        val dataSize = when (data) {
                            is ByteArray -> data.size
                            else -> "неизвестно"
                        }
                        Log.d(TAG, "  - Данные: тип=$dataType, размер=$dataSize")
                    } else {
                        Log.d(TAG, "  - Данные: null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "  - Ошибка получения данных изображения: ${e.message}")
                }
            } else {
                Log.d(TAG, "✗ getLastDecodeImage вернул null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Ошибка при вызове getLastDecodeImage: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Проверяет доступные методы в SDK сканера через рефлексию
     */
    private fun testAvailableMethods() {
        Log.d(TAG, "ДОСТУПНЫЕ МЕТОДЫ:")

        // Проверяем методы в XCScannerWrapper
        val scannerWrapperMethods = XCScannerWrapper::class.java.declaredMethods
        Log.d(TAG, "Методы в XCScannerWrapper (${scannerWrapperMethods.size}):")
        scannerWrapperMethods.distinctBy { it.name }.sortedBy { it.name }.forEach { method ->
            Log.d(TAG, "  - ${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }})")
        }

        // Проверяем публичные методы в XcBarcodeScanner
        val scannerSdkMethods = XcBarcodeScanner::class.java.methods
        Log.d(TAG, "Публичные методы в XcBarcodeScanner (${scannerSdkMethods.size}):")
        scannerSdkMethods.distinctBy { it.name }.sortedBy { it.name }.forEach { method ->
            Log.d(TAG, "  - ${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }})")
        }
    }

    /**
     * Тестирует настройки сканера
     */
    private fun testScannerSettings() {
        Log.d(TAG, "НАСТРОЙКИ СКАНЕРА:")

        // Тестирование режимов работы вспышки
        testScannerSetting(
            "Flash Modes",
            listOf(
                "OFF" to FlashMode.OFF,
                "ILLUME_ONLY" to FlashMode.ILLUME_ONLY,
                "ILLUME_STROBE" to FlashMode.ILLUME_STROBE
            ),
            XCScannerWrapper::setFlashLightsMode
        )

        // Тестирование режимов работы прицела
        testScannerSetting(
            "Aimer Modes",
            listOf(
                "ALWAYS_OFF" to AimerMode.ALWAYS_OFF,
                "TRIGGER_ON" to AimerMode.TRIGGER_ON,
                "ALWAYS_ON" to AimerMode.ALWAYS_ON
            ),
            XCScannerWrapper::setAimerLightsMode
        )

        // Тестирование методов вывода (только доступные константы)
        testScannerSetting(
            "Output Methods",
            listOf(
                "BROADCAST_EVENT" to OutputMethod.BROADCAST,
                "CLIPBOARD" to OutputMethod.CLIPBOARD
                // Убраны недоступные константы FILL_FORM и KEY_EVENT
            ),
            XCScannerWrapper::setOutputMethod
        )

        // Тестирование типов уведомлений (только доступные константы)
        testScannerSetting(
            "Notification Types",
            listOf(
                // Убрана недоступная константа NONE
                "SOUND" to NotificationType.SOUND,
                "SOUND_VIBRATOR" to NotificationType.SOUND_VIBRATOR,
                "VIBRATOR" to NotificationType.VIBRATOR
            ),
            XCScannerWrapper::setSuccessNotification
        )
    }

    /**
     * Общий метод для тестирования настроек сканера
     */
    private fun <T> testScannerSetting(
        settingName: String,
        options: List<Pair<String, T>>,
        setterMethod: (T) -> Unit
    ) {
        Log.d(TAG, "$settingName:")

        options.forEach { (name, value) ->
            try {
                setterMethod(value)
                Log.d(TAG, "  ✓ $name: установлено успешно")
            } catch (e: Exception) {
                Log.e(TAG, "  ✗ $name: ошибка установки: ${e.message}")
            }
        }
    }
}