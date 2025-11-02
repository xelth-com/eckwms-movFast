package com.xelth.eckwms_movfast.utils

import android.graphics.Bitmap

/**
 * Singleton cache for temporarily holding Bitmaps
 * Used to pass large Bitmaps between screens without hitting Bundle size limits
 */
object BitmapCache {
    private var capturedImage: Bitmap? = null

    fun setCapturedImage(bitmap: Bitmap) {
        capturedImage?.recycle() // Recycle old bitmap if exists
        capturedImage = bitmap
    }

    fun getCapturedImage(): Bitmap? {
        return capturedImage
    }

    fun clearCapturedImage() {
        capturedImage?.recycle()
        capturedImage = null
    }
}
