package com.xelth.eckwms_movfast.xelixir

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Public entry point for the embedded xelixir support agent.
 *
 * The agent is DORMANT by default — nothing runs until [requestStart] is called.
 * Phase C will call [requestStart] from the existing mesh/WS channel when the
 * support server sends an `xelixir_start` command (with an on-screen consent).
 * For now it can be wired to a hidden settings/debug action for bring-up.
 */
object XelixirManager {
    private const val TAG = "XelixirManager"

    val isActive: Boolean get() = XelixirAgentService.isRunning

    /** Launch the consent flow → foreground capture service. Must originate from a UI context. */
    fun requestStart(context: Context) {
        Log.i(TAG, "requestStart")
        val i = Intent(context, XelixirConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    fun stop(context: Context) {
        Log.i(TAG, "stop")
        XelixirAgentService.stop(context)
    }

    // ── Phase B input (AccessibilityService) ────────────────────────────────

    /** Whether operator input injection is available (a11y service enabled). */
    fun isInputEnabled(context: Context): Boolean = XelixirInputService.isEnabled(context)

    /** Open accessibility settings so the user can enable input injection. */
    fun requestInputEnable(context: Context) = XelixirInputService.openSettings(context)
}
