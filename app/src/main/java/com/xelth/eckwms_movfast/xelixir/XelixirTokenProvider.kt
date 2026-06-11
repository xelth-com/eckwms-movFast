package com.xelth.eckwms_movfast.xelixir

import android.util.Log
import com.xelth.eckwms_movfast.utils.SettingsManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase C — WS token acquisition for the embedded xelixir agent.
 *
 * The xelixir C2 `ws_handler` only honours the master `WS_AUTH_TOKEN` (or a
 * single-use ticket) for `role=agent`; there is no per-device WS token yet (see
 * the server's "Overloaded WS_AUTH_TOKEN" tech-debt). So a token is obtained the
 * same SECURE way dno2 devices and the kiosk do it: POST a provisioned
 * **license token** to `/api/licensing/claim` (TOFU — binds this PDA's
 * instance_id, capacity-checked) and cache the `access_token` it returns.
 *
 * A claim endpoint keyed on `{instance_id, mesh_id}` alone was rejected: with no
 * secret it would hand the master token to any caller. The license token IS the
 * gating secret.
 *
 * Blocking on purpose: callers invoke it from a background (capture) thread.
 */
object XelixirTokenProvider {
    private const val TAG = "XelixirToken"
    private const val CLAIM_PATH = "/api/licensing/claim"
    private const val TIMEOUT_MS = 8_000

    /** Saved WS token, or one freshly claimed via the license token (best-effort). May be empty. */
    fun obtainBlocking(): String {
        val saved = SettingsManager.getXelixirToken()
        if (saved.isNotEmpty()) return saved
        return claimBlocking()
    }

    private fun claimBlocking(): String {
        val base = httpBase() ?: return ""
        val license = SettingsManager.getXelixirLicenseToken()
        if (license.isEmpty()) {
            Log.w(TAG, "no license token provisioned — set SettingsManager.saveXelixirLicenseToken() (or a master WS token) to connect")
            return ""
        }
        return try {
            val conn = (URL("$base$CLAIM_PATH").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().apply {
                put("token", license)
                put("instance_id", SettingsManager.getInstanceId())
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (code in 200..299) {
                val token = JSONObject(text).optString("access_token", "")
                if (token.isNotEmpty()) {
                    SettingsManager.saveXelixirToken(token)
                    Log.i(TAG, "claimed WS token via /api/licensing/claim")
                }
                token
            } else {
                Log.w(TAG, "license claim HTTP $code: ${text.take(200)} — connecting without a token")
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "license claim failed: ${e.message} — connecting without a token")
            ""
        }
    }

    /** Derive the HTTPS base (e.g. https://xelth.com:3221) from the configured WS URL. */
    private fun httpBase(): String? {
        var url = SettingsManager.getXelixirWsUrl()
        if (url.isEmpty()) return null
        url = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
        val idx = url.indexOf("/X/ws")
        if (idx >= 0) url = url.substring(0, idx)
        return url
    }
}
