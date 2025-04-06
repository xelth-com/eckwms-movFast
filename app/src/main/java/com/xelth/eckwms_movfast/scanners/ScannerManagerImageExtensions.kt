// app/src/main/java/com/xelth/eckwms_movfast/scanners/ScannerManagerImageExtensions.kt
// Расширение для ScannerManager с улучшенной обработкой ошибок изображений
package com.xelth.eckwms_movfast.scanners

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import com.tools.XCImage

/**
 * Расширение ScannerManager для работы с изображениями штрих-кодов
 */

private const val TAG = "ScannerManagerImage"

/**
 * Обработчик ошибок API изображений
 * @param errorMessage Текст ошибки для логирования
 * @param exception Исключение, которое произошло (если есть)
 * @param barcodeValue Значение отсканированного штрих-кода (если есть)
 * @return Созданное мок-изображение или null
 */
private fun handleImageError(errorMessage: String, exception: Exception? = null, barcodeValue: String? = null): Bitmap? {
    if (exception != null) {
        Log.e(TAG, errorMessage, exception)
    } else {
        Log.e(TAG, errorMessage)
    }

    // Создаем мок-изображение, если доступен штрих-код
    return if (!barcodeValue.isNullOrEmpty()) {
        Log.d(TAG, "Creating mock barcode image after error: $barcodeValue")
        createMockBarcodeImage(barcodeValue)
    } else {
        null
    }
}

/**
 * Получает последнее декодированное изображение из сканера или создает мок-изображение,
 * если изображение из сканера недоступно
 * @return Bitmap или null, если изображение недоступно
 */
fun ScannerManager.getLastDecodedImage(): Bitmap? {
    if (!this.isInitialized()) {
        return handleImageError("Scanner not initialized")
    }

    // Получаем текущий штрих-код для резервного решения
    val lastBarcode = scanResult.value

    try {
        Log.d(TAG, "Getting last decoded image from scanner")

        // Пробуем безопасно получить изображение от сканера
        var xcImage: XCImage? = null
        try {
            xcImage = XCScannerWrapper.getLastDecodeImage()
        } catch (e: NullPointerException) {
            return handleImageError("Scanner service reference is null", e, lastBarcode)
        } catch (e: Exception) {
            return handleImageError("Error getting image from scanner", e, lastBarcode)
        }

        if (xcImage == null) {
            return handleImageError("No image available from scanner", barcodeValue = lastBarcode)
        }

        try {
            Log.d(TAG, "Raw image obtained, width: ${xcImage.width}, height: ${xcImage.height}")

            // Безопасно конвертируем XCImage в Bitmap
            val bitmap = convertXCImageToBitmap(xcImage)

            if (bitmap != null) {
                return bitmap
            } else {
                return handleImageError("Failed to convert XCImage to Bitmap", barcodeValue = lastBarcode)
            }
        } catch (e: Exception) {
            return handleImageError("Error processing image data", e, lastBarcode)
        }
    } catch (e: Exception) {
        return handleImageError("Unexpected error when getting last decoded image", e, lastBarcode)
    }
}

/**
 * Преобразует XCImage в Bitmap
 */
private fun convertXCImageToBitmap(xcImage: XCImage): Bitmap? {
    try {
        // Получаем данные изображения безопасно
        val imageData = try {
            xcImage.data
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing image data: ${e.message}")
            return null
        }

        if (imageData == null) {
            Log.e(TAG, "Image data is null")
            return null
        }

        // Проверяем тип данных
        if (imageData !is ByteArray) {
            Log.e(TAG, "Image data is not a ByteArray but ${imageData.javaClass.name}")
            return null
        }

        // Проверяем, не пустой ли массив
        if (imageData.isEmpty()) {
            Log.e(TAG, "Image data is empty ByteArray")
            return null
        }

        try {
            // Создаем Bitmap из ByteArray
            return BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap from byte array: ${e.message}")
            return null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error converting XCImage to Bitmap", e)
        return null
    }
}

/**
 * Создает мок-изображение штрих-кода с визуализацией значения
 */
private fun createMockBarcodeImage(barcodeValue: String): Bitmap {
    // Размеры изображения
    val width = 800
    val height = 400

    // Создаем Bitmap
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Заполняем фон белым цветом
    canvas.drawColor(Color.WHITE)

    // Создаем Paint для рисования
    val paint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        style = Paint.Style.FILL
        textSize = 48f
        typeface = Typeface.DEFAULT_BOLD
    }

    // Получаем размеры текста
    val textBounds = Rect()
    paint.getTextBounds(barcodeValue, 0, barcodeValue.length, textBounds)

    // Рисуем значение штрих-кода
    canvas.drawText(
        barcodeValue,
        (width - textBounds.width()) / 2f,
        height / 2f + textBounds.height() / 2f,
        paint
    )

    // Рисуем простую имитацию штрих-кода (вертикальные линии)
    paint.strokeWidth = 3f

    val codeStartX = (width - textBounds.width()) / 2f - 40f
    val codeEndX = (width + textBounds.width()) / 2f + 40f
    val codeWidth = codeEndX - codeStartX
    val lineCount = barcodeValue.length * 3

    for (i in 0 until lineCount) {
        if (i % 3 != 0) continue  // Рисуем только каждую третью линию для разреженности

        val x = codeStartX + (codeWidth * i / lineCount)
        val lineHeight = (30 + (Math.random() * 70)).toFloat()
        val startY = (height / 2f) - lineHeight - 60  // Выше текста

        canvas.drawLine(x, startY, x, startY + lineHeight, paint)
    }

    // Рисуем надпись о том, что это мок-изображение
    paint.textSize = 32f
    val mockText = "Generated preview (API limitation)"
    paint.getTextBounds(mockText, 0, mockText.length, textBounds)
    canvas.drawText(
        mockText,
        (width - textBounds.width()) / 2f,
        height - 40f,
        paint
    )

    return bitmap
}

/**
 * Расширение для ScannerManager, вызываемое из основного метода cleanup()
 * для очистки ресурсов изображений
 */
internal fun ScannerManager.cleanupImageResources() {
    Log.d(TAG, "Cleaning up image resources")
    // В текущей реализации нет необходимости в дополнительной очистке
}