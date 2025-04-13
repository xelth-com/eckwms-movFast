// app/src/main/java/com/xelth/eckwms_movfast/diagnostics/ScannerApiTester.kt
package com.xelth.eckwms_movfast.diagnostics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.tools.XCImage
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.FlashMode
import com.xcheng.scanner.AimerMode
import com.xcheng.scanner.OutputMethod
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.XcBarcodeScanner
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.scanners.XCScannerWrapper
import java.lang.reflect.Method
import java.nio.ByteBuffer

/**
 * Stores results of a scanner API test for UI display
 */
data class ScannerApiTestResult(
    val name: String,
    val success: Boolean,
    val functionResults: List<FunctionTestResult>
)

/**
 * Represents the result of testing a single scanner function
 */
data class FunctionTestResult(
    val functionName: String,
    val passed: Boolean,
    val message: String = ""
)

/**
 * Класс для тестирования и вывода информации о доступных API функциях сканера
 * в журнал Logcat для диагностики и отладки.
 */
class ScannerApiTester(private val scannerManager: ScannerManager) {
    private val TAG = "ScannerApiTester"

    /**
     * Метод для преобразования 8-битных необработанных данных в Bitmap
     * как в Java-примере
     */
    private fun raw8ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        try {
            val bits = ByteArray(data.size * 4) // RGBA массив

            for (i in data.indices) {
                // Оригинальный принцип: 4 байта представляют один пиксель, RGB = значение яркости, Alpha = 0xFF
                bits[i * 4 + 0] = data[i] // R
                bits[i * 4 + 1] = data[i] // G
                bits[i * 4 + 2] = data[i] // B
                bits[i * 4 + 3] = -1      // 0xFF, A
            }

            // Bitmap.Config.ARGB_8888 означает: режим изображения 8 бит
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bits))

            return bmp
        } catch (e: Exception) {
            Log.e(TAG, "Error converting raw8 to bitmap: ${e.message}")
            return null
        }
    }

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
     * Тестирует функции, связанные с изображениями, используя непосредственно XcBarcodeScanner
     * как в Java-примере
     */
    private fun testImageFunctions() {
        Log.d(TAG, "ФУНКЦИИ ИЗОБРАЖЕНИЯ:")

        try {
            Log.d(TAG, "Пытаемся получить последнее декодированное изображение...")
            val lastImg = XcBarcodeScanner.getLastDecodeImage()

            if (lastImg != null) {
                Log.d(TAG, "✓ Изображение получено успешно!")

                // Получаем данные как в Java-примере
                val width = lastImg.width
                val height = lastImg.height
                val stride = lastImg.stride
                val data = lastImg.data

                Log.d(TAG, "  - Размер: ${width}x${height}, stride: $stride")

                if (data != null) {
                    Log.d(TAG, "  - Данные: размер=${data.size} байт")

                    // Пробуем преобразовать в bitmap как в Java-примере
                    val bitmap = raw8ToBitmap(data, width, height)

                    if (bitmap != null) {
                        Log.d(TAG, "  - Успешно преобразовано в Bitmap размером ${bitmap.width}x${bitmap.height}")
                    } else {
                        Log.d(TAG, "  - Не удалось преобразовать в Bitmap")
                    }
                } else {
                    Log.d(TAG, "  - Данные: null")
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

        // Проверяем методы XCImage если возможно
        try {
            val xcImageMethods = XCImage::class.java.methods
            Log.d(TAG, "Методы в XCImage (${xcImageMethods.size}):")
            xcImageMethods.distinctBy { it.name }.sortedBy { it.name }.forEach { method ->
                Log.d(TAG, "  - ${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения методов XCImage: ${e.message}")
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

    /**
     * Runs all API tests and returns results in a UI-friendly format
     * @return Test results for UI display
     */
    fun runUIFriendlyTests(): ScannerApiTestResult {
        val results = mutableListOf<FunctionTestResult>()

        // Test scanner status
        results.addAll(testScannerStatusUI())

        // Test barcode types
        results.addAll(testBarcodeTypesUI())

        // Test scanner settings
        results.addAll(testScannerSettingsUI())

        // Test image functions
        results.addAll(testImageFunctionsUI())

        val allPassed = results.all { it.passed }

        return ScannerApiTestResult(
            name = "All Tests",
            success = allPassed,
            functionResults = results
        )
    }

    /**
     * Tests basic scanner status and returns results for UI display
     * @return List of test results
     */
    fun testScannerStatusUI(): List<FunctionTestResult> {
        val results = mutableListOf<FunctionTestResult>()

        try {
            // Test initialization
            val isInitialized = scannerManager.isInitialized()
            results.add(
                FunctionTestResult(
                    functionName = "Scanner Initialization",
                    passed = isInitialized,
                    message = if (isInitialized) "Scanner is initialized" else "Scanner is not initialized"
                )
            )
        } catch (e: Exception) {
            results.add(
                FunctionTestResult(
                    functionName = "Scanner Initialization",
                    passed = false,
                    message = "Error: ${e.message}"
                )
            )
        }

        try {
            // Test version retrieval
            val version = XCScannerWrapper.getServiceVersion()
            results.add(
                FunctionTestResult(
                    functionName = "Service Version",
                    passed = version.isNotEmpty(),
                    message = if (version.isNotEmpty()) "Version: $version" else "Failed to get version"
                )
            )
        } catch (e: Exception) {
            results.add(
                FunctionTestResult(
                    functionName = "Service Version",
                    passed = false,
                    message = "Error: ${e.message}"
                )
            )
        }

        try {
            // Test loop scan status
            val isLoopScanActive = scannerManager.isLoopScanRunning()
            results.add(
                FunctionTestResult(
                    functionName = "Loop Scan Status",
                    passed = true, // This is just a status check, not a pass/fail test
                    message = if (isLoopScanActive) "Loop scanning is active" else "Loop scanning is not active"
                )
            )
        } catch (e: Exception) {
            results.add(
                FunctionTestResult(
                    functionName = "Loop Scan Status",
                    passed = false,
                    message = "Error: ${e.message}"
                )
            )
        }

        return results
    }

    /**
     * Tests barcode type support and returns results for UI display
     * @return List of test results
     */
    fun testBarcodeTypesUI(): List<FunctionTestResult> {
        val results = mutableListOf<FunctionTestResult>()

        val barcodeTypes = listOf(
            "QR Code" to BarcodeType.QRCODE,
            "Code 128" to BarcodeType.CODE128,
            "Code 39" to BarcodeType.CODE39,
            "EAN-13" to BarcodeType.EAN13,
            "EAN-8" to BarcodeType.EAN8,
            "UPC-A" to BarcodeType.UPCA,
            "UPC-E" to BarcodeType.UPCE,
            "DataMatrix" to BarcodeType.DATAMATRIX,
            "PDF417" to BarcodeType.PDF417
        )

        var overallSuccess = true

        for ((name, type) in barcodeTypes) {
            try {
                // Test if the barcode type is enabled
                val wasEnabled = XCScannerWrapper.isBarcodeTypeEnabled(type)

                // Try to toggle the state
                XCScannerWrapper.enableBarcodeType(type, !wasEnabled)
                val toggledState = XCScannerWrapper.isBarcodeTypeEnabled(type)
                val toggleSuccess = toggledState != wasEnabled

                // Restore original state
                XCScannerWrapper.enableBarcodeType(type, wasEnabled)
                val restoredState = XCScannerWrapper.isBarcodeTypeEnabled(type)
                val restoreSuccess = restoredState == wasEnabled

                val success = toggleSuccess && restoreSuccess
                overallSuccess = overallSuccess && success

                results.add(
                    FunctionTestResult(
                        functionName = "$name Barcode Support",
                        passed = success,
                        message = if (success)
                            "Successfully toggled state"
                        else
                            "Failed to toggle barcode state"
                    )
                )
            } catch (e: Exception) {
                overallSuccess = false
                results.add(
                    FunctionTestResult(
                        functionName = "$name Barcode Support",
                        passed = false,
                        message = "Error: ${e.message}"
                    )
                )
            }
        }

        results.add(0,
            FunctionTestResult(
                functionName = "Barcode Types Support",
                passed = overallSuccess,
                message = if (overallSuccess)
                    "All barcode types supported"
                else
                    "Some barcode types not supported"
            )
        )

        return results
    }

    /**
     * Tests scanner settings and returns results for UI display
     * @return List of test results
     */
    fun testScannerSettingsUI(): List<FunctionTestResult> {
        val results = mutableListOf<FunctionTestResult>()

        // Test flash modes
        try {
            val flashModeResults = testSettingOptionUI(
                "Flash Mode",
                listOf(
                    "Off" to FlashMode.OFF,
                    "Illuminate Only" to FlashMode.ILLUME_ONLY,
                    "Illuminate with Strobe" to FlashMode.ILLUME_STROBE
                ),
                XCScannerWrapper::setFlashLightsMode
            )
            results.addAll(flashModeResults)
        } catch (e: Exception) {
            results.add(
                FunctionTestResult(
                    functionName = "Flash Modes",
                    passed = false,
                    message = "Error: ${e.message}"
                )
            )
        }

        // Test aimer modes
        try {
            val aimerModeResults = testSettingOptionUI(
                "Aimer Mode",
                listOf(
                    "Always Off" to AimerMode.ALWAYS_OFF,
                    "On During Scan" to AimerMode.TRIGGER_ON,
                    "Always On" to AimerMode.ALWAYS_ON
                ),
                XCScannerWrapper::setAimerLightsMode
            )
            results.addAll(aimerModeResults)
        } catch (e: Exception) {
            results.add(
                FunctionTestResult(
                    functionName = "Aimer Modes",
                    passed = false,
                    message = "Error: ${e.message}"
                )
            )
        }

        // Test output methods
        try {
            val outputMethodResults = testSettingOptionUI(
                "Output Method",
                listOf(
                    "Broadcast" to OutputMethod.BROADCAST,
                    "Clipboard" to OutputMethod.CLIPBOARD
                ),
                XCScannerWrapper::setOutputMethod
            )
            results.addAll(outputMethodResults)
        } catch (e: Exception) {
            results.add(
                FunctionTestResult(
                    functionName = "Output Methods",
                    passed = false,
                    message = "Error: ${e.message}"
                )
            )
        }

        // Test notification types
        try {
            val notificationResults = testSettingOptionUI(
                "Notification Type",
                listOf(
                    "Sound" to NotificationType.SOUND,
                    "Sound and Vibration" to NotificationType.SOUND_VIBRATOR,
                    "Vibration" to NotificationType.VIBRATOR
                ),
                XCScannerWrapper::setSuccessNotification
            )
            results.addAll(notificationResults)
        } catch (e: Exception) {
            results.add(
                FunctionTestResult(
                    functionName = "Notification Types",
                    passed = false,
                    message = "Error: ${e.message}"
                )
            )
        }

        // Test timeout setting
        try {
            val minTimeout = 1
            val maxTimeout = 9

            // Try setting min value
            XCScannerWrapper.setTimeout(minTimeout)

            // Try setting max value
            XCScannerWrapper.setTimeout(maxTimeout)

            results.add(
                FunctionTestResult(
                    functionName = "Timeout Setting",
                    passed = true,
                    message = "Successfully set timeout values"
                )
            )
        } catch (e: Exception) {
            results.add(
                FunctionTestResult(
                    functionName = "Timeout Setting",
                    passed = false,
                    message = "Error: ${e.message}"
                )
            )
        }

        return results
    }

    /**
     * Tests image functions and returns results for UI display
     * Использует правильный подход из Java-примера для доступа к изображениям
     * @return List of test results
     */
    fun testImageFunctionsUI(): List<FunctionTestResult> {
        val results = mutableListOf<FunctionTestResult>()

        // Test getLastDecodeImage
        try {
            // Используем метод через XCScannerWrapper
            val lastImg = XCScannerWrapper.getLastDecodeImage()
            val hasImage = lastImg != null

            if (hasImage) {
                val width = lastImg?.width ?: 0
                val height = lastImg?.height ?: 0
                val stride = lastImg?.stride ?: 0
                val hasData = lastImg?.data != null
                val dataSize = lastImg?.data?.size ?: 0

                results.add(
                    FunctionTestResult(
                        functionName = "Image Retrieval",
                        passed = true,
                        message = "Got image: ${width}x${height}, stride: $stride, data size: $dataSize bytes"
                    )
                )

                // Test image conversion to bitmap
                if (hasData) {
                    try {
                        val data = lastImg?.data
                        if (data != null) {
                            val bitmap = raw8ToBitmap(data, width, height)
                            val bitmapCreated = bitmap != null

                            results.add(
                                FunctionTestResult(
                                    functionName = "Bitmap Conversion",
                                    passed = bitmapCreated,
                                    message = if (bitmapCreated)
                                        "Successfully converted to bitmap ${bitmap?.width}x${bitmap?.height}"
                                    else
                                        "Failed to convert to bitmap"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        results.add(
                            FunctionTestResult(
                                functionName = "Bitmap Conversion",
                                passed = false,
                                message = "Error: ${e.message}"
                            )
                        )
                    }
                } else {
                    results.add(
                        FunctionTestResult(
                            functionName = "Image Data",
                            passed = false,
                            message = "No image data available"
                        )
                    )
                }
            } else {
                results.add(
                    FunctionTestResult(
                        functionName = "Image Retrieval",
                        passed = false,
                        message = "No image available - try scanning first"
                    )
                )
            }
        } catch (e: Exception) {
            results.add(
                FunctionTestResult(
                    functionName = "Image Retrieval",
                    passed = false,
                    message = "Error: ${e.message}"
                )
            )
        }

        return results
    }

    /**
     * Generic helper to test setting options
     */
    private fun <T> testSettingOptionUI(
        settingName: String,
        options: List<Pair<String, T>>,
        setterMethod: (T) -> Unit
    ): List<FunctionTestResult> {
        val results = mutableListOf<FunctionTestResult>()

        var allOptionsPassed = true

        for ((name, value) in options) {
            try {
                setterMethod(value)
                results.add(
                    FunctionTestResult(
                        functionName = "$settingName: $name",
                        passed = true,
                        message = "Successfully set option"
                    )
                )
            } catch (e: Exception) {
                allOptionsPassed = false
                results.add(
                    FunctionTestResult(
                        functionName = "$settingName: $name",
                        passed = false,
                        message = "Error: ${e.message}"
                    )
                )
            }
        }

        results.add(0,
            FunctionTestResult(
                functionName = "$settingName Support",
                passed = allOptionsPassed,
                message = if (allOptionsPassed)
                    "All $settingName options supported"
                else
                    "Some $settingName options not supported"
            )
        )

        return results
    }
}