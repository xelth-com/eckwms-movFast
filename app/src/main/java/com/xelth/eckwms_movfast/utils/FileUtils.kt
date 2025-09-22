package com.xelth.eckwms_movfast.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {
    private const val TAG = "FileUtils"

    fun saveBitmapToPictures(context: Context, bitmap: Bitmap, baseName: String): String? {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val dateString = sdf.format(Date())
        val displayName = "${baseName}_${dateString}.png"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScannerDiagnostics")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri == null) {
            Log.e(TAG, "Failed to create new MediaStore record.")
            return null
        }

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                    Log.e(TAG, "Failed to save bitmap.")
                    resolver.delete(uri, null, null)
                    return null
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Log.d(TAG, "Image saved to gallery: $uri")
            return uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bitmap", e)
            resolver.delete(uri, null, null)
            return null
        }
    }
}