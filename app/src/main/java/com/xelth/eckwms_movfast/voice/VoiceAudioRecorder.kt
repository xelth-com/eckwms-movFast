package com.xelth.eckwms_movfast.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Best-effort parallel raw-audio capture for the voice-command multimodal
 * re-listen path (Voice Commands P2).
 *
 * Runs ALONGSIDE [com.xelth.eckwms_movfast.utils.SpeechToText]: [start] on mic
 * press, [stopAndGetWavBase64] on release. SpeechRecognizer does not expose its
 * audio buffer, so we capture separately. Caveat: some devices give the mic
 * exclusively to SpeechRecognizer, so this may yield little/no audio — callers
 * MUST treat the retained WAV as optional (the text-only Gemini phase still
 * works, and audio is only ever used as an escalation when Gemini asks for it).
 */
class VoiceAudioRecorder {
    private val sampleRate = 16000
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var recording = false

    /** Begin capturing. Caller MUST hold RECORD_AUDIO (checked at the press site). */
    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        synchronized(pcm) { pcm.reset() }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord init failed: ${e.message}"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        record = rec
        recording = true
        try { rec.startRecording() } catch (e: Exception) {
            Log.w(TAG, "startRecording failed: ${e.message}"); stop(); return
        }
        job = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(minBuf)
            try {
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    when {
                        n > 0 -> synchronized(pcm) { pcm.write(buf, 0, n) }
                        n < 0 -> break
                    }
                }
            } catch (_: Exception) { /* released mid-read on stop — ignore */ }
        }
    }

    /** Stop and return captured audio as a base64 WAV, or null if too short/empty. */
    fun stopAndGetWavBase64(): String? {
        stop()
        val raw = synchronized(pcm) { pcm.toByteArray() }
        // Require ~0.3s (16000 * 2 bytes * 0.3 ≈ 9600 bytes) to be worth sending.
        if (raw.size < 9600) return null
        return Base64.encodeToString(wrapWav(raw, sampleRate), Base64.NO_WRAP)
    }

    /** Stop and discard any in-progress capture (e.g. on screen dispose). */
    fun cancel() = stop()

    private fun stop() {
        recording = false
        job = null
        record?.let { r ->
            try { r.stop() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
        }
        record = null
    }

    /** Wrap raw PCM16 mono into a minimal RIFF/WAVE container. */
    private fun wrapWav(pcm: ByteArray, rate: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = rate * channels * bits / 8
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) {
            out.write(v and 0xff); out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
        }
        fun i16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        ascii("RIFF"); i32(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); i32(16); i16(1); i16(channels); i32(rate); i32(byteRate)
        i16(channels * bits / 8); i16(bits)
        ascii("data"); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    companion object { private const val TAG = "VoiceAudioRecorder" }
}
