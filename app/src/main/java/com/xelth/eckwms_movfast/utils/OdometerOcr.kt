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

    /**
     * Reads a German licence plate (amtliches Kennzeichen) from a photo. Returns
     * the normalized plate ("B X 123") or null — the user always confirms/edits.
     */
    suspend fun recognizePlate(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                // Try whole lines first; then the full joined text (plates can wrap).
                val plate = result.textBlocks
                    .flatMap { it.lines }
                    .firstNotNullOfOrNull { extractPlate(it.text) }
                    ?: extractPlate(result.text)
                Log.d(TAG, "plate OCR → $plate")
                cont.resume(plate)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "plate OCR failed: ${e.message}")
                cont.resume(null)
            }
    }

    /**
     * Extract a German plate: district (1–3 letters) + 1–2 letters + 1–4 digits
     * + optional E/H suffix. Returns the normalized "DISTRICT LETTERS NUMBER"
     * form (single spaces), matching the server's `normalize_plate`.
     */
    internal fun extractPlate(text: String): String? {
        val up = text.uppercase().replace(Regex("[^A-ZÄÖÜ0-9]+"), " ").trim()
        val m = Regex("\\b([A-ZÄÖÜ]{1,3}) ?([A-ZÄÖÜ]{1,2}) ?(\\d{1,4}) ?([EH]?)\\b").find(up)
            ?: return null
        val (d, l, n, suffix) = m.destructured
        return buildString {
            append(d); append(' '); append(l); append(' '); append(n); append(suffix)
        }
    }
}
