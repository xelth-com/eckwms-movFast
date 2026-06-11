package com.xelth.eckwms_movfast.xelixir

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase B — on-device input injector for the embedded xelixir agent.
 *
 * An AccessibilityService is the only non-root way to synthesise global input on
 * Android: `dispatchGesture` for taps/swipes, `performGlobalAction` for BACK/HOME,
 * and `ACTION_SET_TEXT` on the focused node for typing. The operator sends events
 * in STREAMED resolution; [XelixirInputController] maps them to real coords and
 * calls into the live service instance here.
 *
 * Enablement: the user toggles this service once in Settings → Accessibility
 * (or a device-owner MDM enables it silently). Until enabled, instance is null
 * and input is a no-op (view-only still works).
 */
class XelixirInputService : AccessibilityService() {

    companion object {
        private const val TAG = "XelixirInputSvc"

        @Volatile var instance: XelixirInputService? = null
            private set

        /** True if this accessibility service is currently enabled by the user/MDM. */
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${XelixirInputService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }

        /** Open system accessibility settings so the user can enable the service. */
        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected — input injection ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not needed for injection */ }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ── Injection primitives (posted to the main thread for safety) ─────────

    fun tap(x: Float, y: Float) = main.post {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "tap failed: ${e.message}")
        }
    }

    fun swipe(points: List<PointF>, durationMs: Long) = main.post {
        if (points.size < 2) return@post
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(10L, 8000L))
        try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "swipe failed: ${e.message}")
        }
    }

    fun global(action: Int) = main.post {
        try {
            performGlobalAction(action)
        } catch (e: Exception) {
            Log.w(TAG, "global action $action failed: ${e.message}")
        }
    }

    /** Set text on the currently focused editable node (best-effort). */
    fun setFocusedText(text: String) = main.post {
        try {
            val root = rootInActiveWindow ?: return@post
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@post
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.w(TAG, "setFocusedText failed: ${e.message}")
        }
    }
}
