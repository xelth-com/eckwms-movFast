package com.xelth.eckwms_movfast.xelixir

import android.accessibilityservice.AccessibilityService
import android.graphics.PointF
import android.util.Log
import kotlin.math.abs

/**
 * Translates operator ControlEvents (streamed resolution) into real on-device
 * input via [XelixirInputService].
 *
 * Pointer model: the operator sends mousedown → mousemove* → mouseup. Android has
 * no persistent cursor, so we buffer the stroke between down and up and emit ONE
 * gesture on up: a TAP if the pointer barely moved, otherwise a SWIPE along the
 * buffered path. Keys map to global actions (BACK/HOME/RECENTS); type_text goes to
 * the focused editable node.
 */
object XelixirInputController {
    private const val TAG = "XelixirInput"
    private const val TAP_SLOP_PX = 16f      // real-px movement below this = tap
    private const val MAX_BUFFER_POINTS = 256

    @Volatile private var realW = 0
    @Volatile private var realH = 0
    @Volatile private var streamW = 0
    @Volatile private var streamH = 0

    // Stroke assembly state (events arrive serialized on the WS thread).
    private var pressed = false
    private var downTimeMs = 0L
    private val points = ArrayList<PointF>(64)

    fun setMapping(realW: Int, realH: Int, streamW: Int, streamH: Int) {
        this.realW = realW
        this.realH = realH
        this.streamW = streamW
        this.streamH = streamH
    }

    fun toRealX(streamX: Int): Float =
        if (streamW > 0) streamX * (realW.toFloat() / streamW) else streamX.toFloat()

    fun toRealY(streamY: Int): Float =
        if (streamH > 0) streamY * (realH.toFloat() / streamH) else streamY.toFloat()

    fun onPointer(kind: String, x: Int, y: Int, @Suppress("UNUSED_PARAMETER") button: Int) {
        val p = PointF(toRealX(x), toRealY(y))
        when (kind) {
            "mousedown" -> {
                points.clear()
                points.add(p)
                pressed = true
                downTimeMs = System.currentTimeMillis()
            }
            "mousemove" -> {
                if (pressed && points.size < MAX_BUFFER_POINTS) points.add(p)
            }
            "mouseup" -> {
                if (!pressed) return
                points.add(p)
                pressed = false
                emitStroke()
            }
        }
    }

    private fun emitStroke() {
        val svc = XelixirInputService.instance
        if (svc == null) {
            Log.d(TAG, "input dropped — accessibility service not enabled")
            points.clear()
            return
        }
        val start = points.first()
        val end = points.last()
        val moved = abs(end.x - start.x) + abs(end.y - start.y)
        if (points.size <= 2 && moved < TAP_SLOP_PX) {
            svc.tap(start.x, start.y)
        } else {
            val duration = (System.currentTimeMillis() - downTimeMs).coerceIn(40L, 4000L)
            svc.swipe(ArrayList(points), duration)
        }
        points.clear()
    }

    fun onKey(kind: String, key: String) {
        if (kind != "keydown") return // act on press only
        val svc = XelixirInputService.instance ?: run {
            Log.d(TAG, "key dropped — accessibility service not enabled")
            return
        }
        when (key.lowercase()) {
            "escape", "esc", "back", "browserback" -> svc.global(AccessibilityService.GLOBAL_ACTION_BACK)
            "home", "meta", "super", "os" -> svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
            "f5" -> svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            else -> Log.d(TAG, "unmapped key '$key' (use type_text for characters)")
        }
    }

    fun onTypeText(text: String) {
        val svc = XelixirInputService.instance ?: run {
            Log.d(TAG, "type dropped — accessibility service not enabled")
            return
        }
        svc.setFocusedText(text)
    }
}
