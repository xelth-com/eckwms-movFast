// app/src/main/java/com/xelth/eckwms_movfast/diagnostics/ScannerImageDiagnostics.kt
package com.xelth.eckwms_movfast.diagnostics

import android.util.Log
import com.tools.XCImage
import com.xcheng.scanner.XcBarcodeScanner
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Класс для глубокой диагностики функций изображения в API сканера
 */
class ScannerImageDiagnostics {
    private val TAG = "ScannerImageDiagnostics"

    /**
     * Запускает полную диагностику функций изображения сканера
     */
    fun runFullDiagnostics() {
        Log.d(TAG, "===== НАЧАЛО ГЛУБОКОЙ ДИАГНОСТИКИ ФУНКЦИЙ ИЗОБРАЖЕНИЯ СКАНЕРА =====")

        // Проверка внутреннего состояния сканера через рефлексию
        checkScannerInternalState()

        // Проверка метода getLastDecodeImage
        checkLastDecodeImageMethod()

        // Проверка структуры класса XCImage
        checkXCImageStructure()

        Log.d(TAG, "===== ЗАВЕРШЕНИЕ ГЛУБОКОЙ ДИАГНОСТИКИ ФУНКЦИЙ ИЗОБРАЖЕНИЯ СКАНЕРА =====")
    }

    /**
     * Проверяет внутреннее состояние сканера через рефлексию
     */
    private fun checkScannerInternalState() {
        Log.d(TAG, "--- Проверка внутреннего состояния сканера ---")

        try {
            // Получаем поля через рефлексию
            val fields = XcBarcodeScanner::class.java.declaredFields

            Log.d(TAG, "Поля XcBarcodeScanner:")
            for (field in fields) {
                field.isAccessible = true
                try {
                    Log.d(TAG, "  - ${field.name}: ${getFieldValue(field)}")

                    // Если это поле IScanner, проверим его подробнее
                    if (field.name.contains("Scanner", ignoreCase = true) ||
                        field.type.name.contains("IScanner", ignoreCase = true)) {
                        val scannerObj = field.get(null)
                        if (scannerObj == null) {
                            Log.d(TAG, "    • IScanner объект: NULL")
                        } else {
                            Log.d(TAG, "    • IScanner объект: ${scannerObj.javaClass.name}")

                            // Проверяем методы этого объекта
                            val methods = scannerObj.javaClass.methods
                            Log.d(TAG, "    • Доступные методы:")
                            for (method in methods) {
                                Log.d(TAG, "      - ${method.name}()")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "  - ${field.name}: Ошибка доступа - ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при проверке внутреннего состояния сканера: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Получает значение поля для вывода в лог
     */
    private fun getFieldValue(field: Field): String {
        try {
            val value = field.get(null)
            return when {
                value == null -> "null"
                value is String -> "\"$value\""
                value is Number -> value.toString()
                value is Boolean -> value.toString()
                else -> "${value.javaClass.simpleName}@${System.identityHashCode(value)}"
            }
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }

    /**
     * Проверяет метод getLastDecodeImage
     */
    private fun checkLastDecodeImageMethod() {
        Log.d(TAG, "--- Проверка метода getLastDecodeImage ---")

        try {
            // Проверяем через рефлексию
            val method = XcBarcodeScanner::class.java.getDeclaredMethod("getLastDecodeImage")
            Log.d(TAG, "Метод getLastDecodeImage найден:")
            Log.d(TAG, "  - Возвращаемый тип: ${method.returnType.name}")
            Log.d(TAG, "  - Модификаторы: ${method.modifiers}")

            // Пробуем вызвать метод напрямую через рефлексию
            Log.d(TAG, "Пытаемся вызвать метод напрямую...")
            try {
                val image = XcBarcodeScanner.getLastDecodeImage()
                if (image == null) {
                    Log.d(TAG, "  - Результат: null")
                } else {
                    Log.d(TAG, "  - Результат: XCImage объект получен!")
                    Log.d(TAG, "  - Размер: ${image.width}x${image.height}")
                    val data = image.data
                    Log.d(TAG, "  - Данные: ${data?.javaClass?.name ?: "null"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "  - Ошибка при вызове метода: ${e.message}")
                e.printStackTrace()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при проверке метода getLastDecodeImage: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Проверяет структуру класса XCImage
     */
    private fun checkXCImageStructure() {
        Log.d(TAG, "--- Проверка структуры класса XCImage ---")

        try {
            val fields = XCImage::class.java.declaredFields

            Log.d(TAG, "Поля XCImage:")
            for (field in fields) {
                field.isAccessible = true
                Log.d(TAG, "  - ${field.name}: ${field.type.name}")
            }

            val methods = XCImage::class.java.declaredMethods

            Log.d(TAG, "Методы XCImage:")
            for (method in methods) {
                Log.d(TAG, "  - ${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }}): ${method.returnType.simpleName}")
            }

            // Проверяем реализацию Parcelable
            val parcelableFields = XCImage::class.java.getFields()
                .filter { it.name == "CREATOR" }

            if (parcelableFields.isNotEmpty()) {
                Log.d(TAG, "  - Класс реализует Parcelable (CREATOR найден)")
            } else {
                Log.d(TAG, "  - Класс НЕ реализует Parcelable или CREATOR не доступен")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при проверке структуры класса XCImage: ${e.message}")
            e.printStackTrace()
        }
    }
}