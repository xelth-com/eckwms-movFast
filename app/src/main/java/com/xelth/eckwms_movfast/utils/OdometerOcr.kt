package com.xelth.eckwms_movfast.utils

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads the odometer (Kilometerstand) from a dashboard photo with ML Kit
 * text recognition. Returns the most plausible reading or null — the user
 * always confirms/edits the value before it is saved.
 */
object OdometerOcr {
    private const val TAG = "OdometerOcr"

    suspend fun recognizeKm(bitmap: Bitmap): Double? = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val candidates = result.textBlocks
                    .flatMap { it.lines }
                    .mapNotNull { line -> extractKm(line.text) }
                // Odometer is usually the largest plausible number in frame
                val best = candidates.maxOrNull()
                Log.d(TAG, "OCR candidates: $candidates → $best")
                cont.resume(best)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "OCR failed: ${e.message}")
                cont.resume(null)
            }
    }

    /** Pull a 4–7 digit reading out of a text line ("123456 km", "123.456"). */
    internal fun extractKm(text: String): Double? {
        val cleaned = text.replace(".", "").replace(",", "").replace(" ", "")
        val match = Regex("\\d{4,7}").findAll(cleaned).maxByOrNull { it.value.length }
            ?: return null
        val value = match.value.toDoubleOrNull() ?: return null
        // Sanity window: 100 km .. 2,000,000 km
        return if (value in 100.0..2_000_000.0) value else null
    }
}
