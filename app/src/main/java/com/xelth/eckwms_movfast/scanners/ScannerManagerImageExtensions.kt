// app/src/main/java/com/xelth/eckwms_movfast/scanners/ScannerManagerImageExtensions.kt
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
import com.xcheng.scanner.XcBarcodeScanner
import java.nio.ByteBuffer

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

        // Проверяем состояние сервиса сканера
        if (XcBarcodeScanner.a == null) {
            Log.d(TAG, "Scanner service reference is null - cannot get image")
            return handleImageError("Scanner service unavailable", barcodeValue = lastBarcode)
        }

        // Проверяем, поддерживает ли устройство захват изображений
        if (!XCScannerWrapper.isImageCaptureSupported()) {
            Log.d(TAG, "Device doesn't support image capture - generating mock image")
            return handleImageError("Image capture not supported", barcodeValue = lastBarcode)
        }

        // Пробуем получить изображение
        val xcImage = XCScannerWrapper.getLastDecodeImage()

        if (xcImage == null) {
            return handleImageError("No image available from scanner", barcodeValue = lastBarcode)
        }

        // Выводим подробную информацию о полученном изображении
        Log.d(TAG, "Image details: width=${xcImage.width}, height=${xcImage.height}, stride=${xcImage.stride}")
        Log.d(TAG, "Image format: ${xcImage.formatName}")

        // Получаем байтовый массив данных изображения
        val imageData = xcImage.data

        if (imageData == null) {
            return handleImageError("Image data is null", barcodeValue = lastBarcode)
        }

        Log.d(TAG, "Image data size: ${imageData.size} bytes")

        // Пробуем разные подходы преобразования в Bitmap
        val bitmap = try {
            // Сначала пробуем стандартный способ через BitmapFactory
            val standardBitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            if (standardBitmap != null) {
                Log.d(TAG, "Successfully created bitmap using standard decoder")
                standardBitmap
            } else {
                // Если стандартный способ не сработал, пробуем raw8 преобразование
                Log.d(TAG, "Standard bitmap conversion failed, trying raw8 conversion")
                raw8ToBitmap(imageData, xcImage.width, xcImage.height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Standard conversion failed with exception: ${e.message}")
            // При ошибке пробуем raw8 преобразование
            raw8ToBitmap(imageData, xcImage.width, xcImage.height)
        }

        return bitmap ?: handleImageError("Failed to convert image data to bitmap", barcodeValue = lastBarcode)
    } catch (e: Exception) {
        return handleImageError("Error getting image from scanner", e, lastBarcode)
    }
}

/**
 * Преобразует 8-бит необработанные данные в Bitmap
 */
private fun raw8ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
    return try {
        Log.d(TAG, "Converting raw8 data to bitmap: ${width}x${height}, data size: ${data.size}")

        // Проверка на валидность размеров
        if (width <= 0 || height <= 0 || data.isEmpty()) {
            Log.e(TAG, "Invalid image dimensions or empty data")
            return null
        }

        // Проверяем, достаточно ли данных для создания изображения
        val expectedSize = width * height
        if (data.size < expectedSize) {
            Log.e(TAG, "Data size too small: got ${data.size}, need at least $expectedSize")
            return null
        }

        // Создаем RGBA массив (4 байта на пиксель)
        val rgbaData = ByteArray(width * height * 4)

        // Преобразуем данные: для каждого байта создаем пиксель RGBA
        // где R=G=B=исходное значение, A=255 (полная непрозрачность)
        for (i in 0 until minOf(data.size, width * height)) {
            rgbaData[i * 4 + 0] = data[i] // R
            rgbaData[i * 4 + 1] = data[i] // G
            rgbaData[i * 4 + 2] = data[i] // B
            rgbaData[i * 4 + 3] = (-1).toByte() // A (255)
        }

        // Создаем Bitmap и копируем данные
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgbaData))

        Log.d(TAG, "Successfully converted raw8 data to bitmap")
        bitmap
    } catch (e: Exception) {
        Log.e(TAG, "Error converting raw8 to bitmap: ${e.message}")
        e.printStackTrace()
        null
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