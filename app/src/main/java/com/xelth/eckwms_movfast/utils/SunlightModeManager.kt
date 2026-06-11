package com.xelth.eckwms_movfast.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages automatic "Sunlight Mode" based on ambient light sensor.
 * When bright outdoor light is detected, shifts UX feedback from visual to audio-haptic:
 * - Boosts media volume
 * - Provides distinct haptic patterns (success/error/attention)
 * - Signals UI to switch to high-contrast colors
 */
object SunlightModeManager : SensorEventListener {
    private const val TAG = "SunlightMode"

    // Lux thresholds — outdoor direct sun is 10,000-100,000 lux
    // We trigger at 10,000 (bright outdoor shade / overcast sun)
    private const val SUNLIGHT_THRESHOLD_ON = 10_000f
    // Hysteresis: turn off at 3,000 (prevents flicker at boundary)
    private const val SUNLIGHT_THRESHOLD_OFF = 3_000f
    // Require sustained readings to avoid transient triggers (e.g. flashlight)
    private const val REQUIRED_SAMPLES = 5

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var previousMediaVolume: Int = -1
    private var samplesAbove = 0
    private var samplesBelow = 0

    private val _isSunlightMode = MutableStateFlow(false)
    val isSunlightMode: StateFlow<Boolean> = _isSunlightMode

    private val _currentLux = MutableStateFlow(0f)
    val currentLux: StateFlow<Float> = _currentLux

    fun init(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (lightSensor == null) {
            Log.w(TAG, "No ambient light sensor available on this device")
        }
    }

    fun startListening() {
        lightSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Light sensor listener registered")
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
        Log.d(TAG, "Light sensor listener unregistered")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val lux = event?.values?.firstOrNull() ?: return
        _currentLux.value = lux

        if (_isSunlightMode.value) {
            // Currently ON — check if we should turn off
            if (lux < SUNLIGHT_THRESHOLD_OFF) {
                samplesBelow++
                samplesAbove = 0
                if (samplesBelow >= REQUIRED_SAMPLES) {
                    deactivateSunlightMode()
                }
            } else {
                samplesBelow = 0
            }
        } else {
            // Currently OFF — check if we should turn on
            if (lux >= SUNLIGHT_THRESHOLD_ON) {
                samplesAbove++
                samplesBelow = 0
                if (samplesAbove >= REQUIRED_SAMPLES) {
                    activateSunlightMode()
                }
            } else {
                samplesAbove = 0
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun activateSunlightMode() {
        _isSunlightMode.value = true
        samplesAbove = 0
        Log.d(TAG, "SUNLIGHT MODE ON (lux=${_currentLux.value})")
        boostAudioVolume()
        playAttention() // Alert worker that mode changed
    }

    private fun deactivateSunlightMode() {
        _isSunlightMode.value = false
        samplesBelow = 0
        Log.d(TAG, "SUNLIGHT MODE OFF (lux=${_currentLux.value})")
        restoreAudioVolume()
    }

    // --- Audio Volume Management ---

    private fun boostAudioVolume() {
        audioManager?.let { am ->
            previousMediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            Log.d(TAG, "Audio boosted: $previousMediaVolume -> $maxVolume")
        }
    }

    private fun restoreAudioVolume() {
        if (previousMediaVolume >= 0) {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, previousMediaVolume, 0)
            Log.d(TAG, "Audio restored to $previousMediaVolume")
            previousMediaVolume = -1
        }
    }

    // --- Haptic Patterns ---

    /** Success / Scanned: Two quick, crisp ticks */
    fun playSuccess() {
        vibratePattern(longArrayOf(0, 50, 80, 50), -1)
        if (_isSunlightMode.value) playTone(ToneGenerator.TONE_PROP_ACK)
    }

    /** Error / Mismatch: One long, heavy buzz */
    fun playError() {
        vibratePattern(longArrayOf(0, 400), -1)
        if (_isSunlightMode.value) playTone(ToneGenerator.TONE_PROP_NACK)
    }

    /** Action Required: Three rhythmic pulses — shield screen & read prompt */
    fun playAttention() {
        vibratePattern(longArrayOf(0, 150, 120, 150, 120, 150), -1)
        if (_isSunlightMode.value) playTone(ToneGenerator.TONE_PROP_BEEP2)
    }

    private fun vibratePattern(pattern: LongArray, repeat: Int) {
        try {
            vibrator?.let { v ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, repeat))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, repeat)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun playTone(toneType: Int) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(toneType, 200)
            // Release after tone finishes (fire-and-forget)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                toneGen.release()
            }, 300)
        } catch (e: Exception) {
            Log.w(TAG, "Tone playback failed: ${e.message}")
        }
    }

    /** Force toggle (for manual override from settings/debug) */
    fun forceToggle() {
        if (_isSunlightMode.value) {
            deactivateSunlightMode()
        } else {
            activateSunlightMode()
        }
    }
}
