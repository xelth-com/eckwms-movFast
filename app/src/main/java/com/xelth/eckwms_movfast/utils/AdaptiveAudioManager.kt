package com.xelth.eckwms_movfast.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.max

/**
 * Adaptive Audio Manager — dynamically adjusts media volume based on ambient noise.
 *
 * Architecture: Trigger-Based Adaptive Polling (no continuous mic usage).
 * - Called at specific trigger points (scan result, AI prompt)
 * - Samples ~150ms of audio via AudioRecord
 * - Uses EMA (Exponential Moving Average) for stable dB tracking
 * - Dynamic cooldown based on environment change rate (delta)
 * - Respects Silent/Vibrate ringer modes
 */
object AdaptiveAudioManager {
    private const val TAG = "AdaptiveAudio"

    // Audio sampling config
    private const val SAMPLE_RATE = 16000
    private const val SAMPLE_DURATION_MS = 150
    private const val SAMPLE_SIZE = SAMPLE_RATE * SAMPLE_DURATION_MS / 1000 // 2400 samples

    // EMA smoothing factor (0.3 = responsive but stable)
    private const val EMA_ALPHA = 0.3

    // Volume mapping thresholds (dB)
    private const val DB_QUIET = 60.0   // <60dB -> ~40% volume
    private const val DB_MODERATE = 75.0 // 60-75dB -> ~70% volume
    // >75dB -> 100% volume

    private var audioManager: AudioManager? = null
    private var context: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // State
    private var emaDb: Double = -1.0  // -1 = no data yet
    private var lastSampleTimeMs: Long = 0
    private var cooldownMs: Long = 0

    fun init(context: Context) {
        this.context = context.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        Log.d(TAG, "Initialized")
    }

    /**
     * Trigger an ambient noise sample. Call this at scan events, AI prompts, etc.
     * Non-blocking — runs sampling on IO dispatcher.
     */
    fun triggerSample() {
        val ctx = context ?: return
        val am = audioManager ?: return

        // 1. Mute check: skip if Silent or Vibrate
        val ringerMode = am.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            Log.d(TAG, "Skipped: ringer mode is ${if (ringerMode == AudioManager.RINGER_MODE_SILENT) "SILENT" else "VIBRATE"}")
            return
        }

        // 2. Cooldown check
        val now = System.currentTimeMillis()
        if (now - lastSampleTimeMs < cooldownMs) {
            Log.d(TAG, "Skipped: cooldown (${cooldownMs}ms remaining)")
            return
        }

        // 3. Permission check
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Skipped: RECORD_AUDIO permission not granted")
            return
        }

        // 4. Sample on IO thread
        scope.launch {
            try {
                val db = sampleAmbientDb()
                if (db < 0) return@launch // sampling failed

                // EMA calculation
                val previousEma = emaDb
                emaDb = if (previousEma < 0) db else (EMA_ALPHA * db + (1 - EMA_ALPHA) * previousEma)

                // Dynamic cooldown based on delta
                val delta = if (previousEma < 0) 0.0 else kotlin.math.abs(db - previousEma)
                cooldownMs = when {
                    delta >= 15.0 -> 5_000L    // Environment changed drastically -> resample soon
                    delta >= 5.0  -> 15_000L   // Moderate change
                    else          -> 60_000L   // Stable environment -> long cooldown
                }
                lastSampleTimeMs = System.currentTimeMillis()

                // Adjust volume
                adjustVolume(emaDb)

                Log.d(TAG, "Sample: raw=${String.format("%.1f", db)}dB, ema=${String.format("%.1f", emaDb)}dB, delta=${String.format("%.1f", delta)}, cooldown=${cooldownMs}ms")
            } catch (e: Exception) {
                Log.w(TAG, "Sampling failed: ${e.message}")
            }
        }
    }

    /**
     * Record ~150ms of audio and return approximate dB level.
     * Must be called on IO/background thread.
     */
    private fun sampleAmbientDb(): Double {
        val bufferSize = max(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            SAMPLE_SIZE * 2 // 16-bit = 2 bytes per sample
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.w(TAG, "AudioRecord failed to initialize")
            return -1.0
        }

        return try {
            recorder.startRecording()
            val buffer = ShortArray(SAMPLE_SIZE)
            val read = recorder.read(buffer, 0, SAMPLE_SIZE)
            recorder.stop()

            if (read <= 0) return -1.0

            // Find max amplitude in the sample
            var maxAmplitude = 0
            for (i in 0 until read) {
                val abs = kotlin.math.abs(buffer[i].toInt())
                if (abs > maxAmplitude) maxAmplitude = abs
            }

            // Convert to rough dB (reference: max 16-bit = 32767)
            if (maxAmplitude <= 0) 0.0
            else 20.0 * log10(maxAmplitude.toDouble())
        } finally {
            recorder.release()
        }
    }

    private fun adjustVolume(db: Double) {
        val am = audioManager ?: return
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val targetFraction = when {
            db < DB_QUIET    -> 0.4   // Quiet environment
            db < DB_MODERATE -> 0.7   // Moderate noise
            else             -> 1.0   // Loud environment
        }

        val targetVol = (maxVol * targetFraction).toInt().coerceIn(1, maxVol)
        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Only adjust if significantly different (avoid constant micro-adjustments)
        if (kotlin.math.abs(targetVol - currentVol) >= 1) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            Log.d(TAG, "Volume: $currentVol -> $targetVol (${(targetFraction * 100).toInt()}% of max, ema=${String.format("%.1f", db)}dB)")
        }
    }
}
