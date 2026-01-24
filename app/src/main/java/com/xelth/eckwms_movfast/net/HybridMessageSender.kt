package com.xelth.eckwms_movfast.net

import android.content.Context
import android.util.Log
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.api.ScanResult
import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object HybridMessageSender {
    private const val TAG = "HybridSender"
    private const val HEDGE_DELAY_MS = 300L
    private var webSocket: WebSocketClient? = null
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deviceId: String = "unknown"
    private var statusListener: ((String) -> Unit)? = null
    private var layoutListener: ((String) -> Unit)? = null
    private var aiInteractionListener: ((com.xelth.eckwms_movfast.ui.data.AiInteraction) -> Unit)? = null

    fun setLayoutListener(listener: (String) -> Unit) {
        layoutListener = listener
        Log.d(TAG, "Layout listener registered")
    }

    fun setAiInteractionListener(listener: (com.xelth.eckwms_movfast.ui.data.AiInteraction) -> Unit) {
        aiInteractionListener = listener
        Log.d(TAG, "AI Interaction listener registered")
    }

    fun init(context: Context) {
        deviceId = SettingsManager.getDeviceId(context)
        Log.d(TAG, "Initializing with deviceId: $deviceId")
        connectWebSocket()
    }

    fun setStatusListener(listener: (String) -> Unit) {
        statusListener = listener
        Log.d(TAG, "Status listener registered")
    }

    private fun connectWebSocket() {
        try {
            val serverUrl = SettingsManager.getServerUrl().replace("http", "ws") // Simple replace for now
            val uri = URI("$serverUrl/ws")
            Log.d(TAG, "Connecting WS to $uri")

            webSocket = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.i(TAG, "WS Connected")
                    // --- HANDSHAKE: Identify Device ---
                    val identityMsg = JSONObject().apply {
                        put("type", "DEVICE_IDENTIFY")
                        put("deviceId", deviceId)
                        put("timestamp", System.currentTimeMillis())
                    }
                    send(identityMsg.toString())
                    Log.d(TAG, "Sent DEVICE_IDENTIFY for $deviceId")
                }

                override fun onMessage(message: String?) {
                    message?.let { handleMessage(it) }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.w(TAG, "WS Closed: $reason")
                    // Simple reconnect logic could go here
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WS Error", ex)
                }
            }
            webSocket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "WS Init failed", e)
        }
    }

    private fun handleMessage(msg: String) {
        try {
            val json = JSONObject(msg)

            // 1. Handle ACKs (Response to our scans)
            if (json.has("ack")) {
                val msgId = json.getString("ack")
                pendingAcks[msgId]?.complete(true)
                pendingAcks.remove(msgId)
                Log.d(TAG, "Received ACK for $msgId")

                // Check for AI interaction in ACK response
                if (json.has("ai_interaction")) {
                    try {
                        val aiJson = json.getJSONObject("ai_interaction")
                        val aiInteraction = parseAiInteraction(aiJson)
                        aiInteraction?.let {
                            Log.i(TAG, "⚡ AI Interaction in ACK: ${it.type} - ${it.message}")
                            aiInteractionListener?.invoke(it)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse ai_interaction from ACK", e)
                    }
                }
                return
            }

            // 2. Handle PUSH Commands from Server
            val type = json.optString("type")
            if (type == "STATUS_UPDATE") {
                val newStatus = json.getString("status")
                Log.i(TAG, "⚡ Received PUSH STATUS_UPDATE: $newStatus")
                statusListener?.invoke(newStatus)
            } else if (type == "ROLE_UPDATE") {
                val role = json.getString("role")
                val permissionsJson = json.getJSONArray("permissions")
                val permissions = mutableSetOf<String>()
                for (i in 0 until permissionsJson.length()) {
                    permissions.add(permissionsJson.getString(i))
                }

                Log.i(TAG, "⚡ Received PUSH ROLE_UPDATE: $role with ${permissions.size} permissions")

                // Save immediately
                SettingsManager.saveUserRole(role)
                SettingsManager.savePermissions(permissions)

                // Notify listener (we reuse the status listener for now, or could add a dedicated one)
                // Ideally, ViewModel observes SettingsManager or a new LiveData
            } else if (type == "LAYOUT_UPDATE") {
                val layoutJson = json.getJSONObject("layout").toString()
                Log.i(TAG, "⚡ Received PUSH LAYOUT_UPDATE")
                layoutListener?.invoke(layoutJson)
            } else if (type == "AI_INTERACTION") {
                try {
                    val aiInteraction = parseAiInteraction(json)
                    aiInteraction?.let {
                        Log.i(TAG, "⚡ Received PUSH AI_INTERACTION: ${it.type} - ${it.message}")
                        aiInteractionListener?.invoke(it)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse AI_INTERACTION message", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WS message", e)
        }
    }

    /**
     * Parse AI interaction from JSON object
     */
    private fun parseAiInteraction(json: JSONObject): com.xelth.eckwms_movfast.ui.data.AiInteraction? {
        return try {
            val id = json.optString("id", null)
            val type = json.optString("type", "info")
            val message = json.optString("message", "")
            val barcode = json.optString("barcode", null)

            // Support both "options" (Standard) and "suggestedActions" (Gemini AI)
            val options = if (json.has("options")) {
                val optionsArray = json.getJSONArray("options")
                (0 until optionsArray.length()).map { optionsArray.getString(it) }
            } else if (json.has("suggestedActions")) {
                val optionsArray = json.getJSONArray("suggestedActions")
                (0 until optionsArray.length()).map { optionsArray.getString(it) }
            } else null

            val data = if (json.has("data")) {
                val dataJson = json.getJSONObject("data")
                dataJson.keys().asSequence().associateWith { dataJson.get(it) }
            } else null

            com.xelth.eckwms_movfast.ui.data.AiInteraction(
                id = id,
                type = type,
                message = message,
                options = options,
                data = data,
                barcode = barcode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing AI interaction: ${e.message}", e)
            null
        }
    }

    suspend fun sendScan(apiService: ScanApiService, barcode: String, type: String): ScanResult {
        val msgId = UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("msgId", msgId)
            put("barcode", barcode)
            put("type", type)
            put("timestamp", System.currentTimeMillis())
        }

        val ackDeferred = CompletableDeferred<Boolean>()
        pendingAcks[msgId] = ackDeferred

        // 1. Send via WebSocket (Fast Path)
        val wsJob = scope.launch {
            if (webSocket?.isOpen == true) {
                Log.d(TAG, "Sending WS: $msgId")
                webSocket?.send(payload.toString())
            } else {
                Log.d(TAG, "WS not open, skipping fast path")
            }
        }

        // 2. Hedge / Race
        return try {
            withTimeout(HEDGE_DELAY_MS) {
                ackDeferred.await()
                Log.d(TAG, "WS delivery confirmed fast ($msgId)")
                ScanResult.Success("scan", "Fast delivery", msgId)
            }
        } catch (e: TimeoutCancellationException) {
            Log.i(TAG, "Hedge delay passed, sending HTTP fallback ($msgId)")
            // 3. Fallback to HTTP
            // We pass msgId to HTTP so server can deduplicate
            apiService.processScanWithId(barcode, type, msgId)
        }
    }

    /**
     * Send AI interaction response to server using hybrid transport (WebSocket + HTTP hedge)
     * @param apiService The API service for HTTP fallback
     * @param interactionId The unique ID of the interaction being responded to
     * @param response The user's chosen response/option
     * @param barcode The barcode context for this interaction
     * @return Result of the response submission
     */
    suspend fun sendAiResponse(apiService: ScanApiService, interactionId: String?, response: String, barcode: String?): ScanResult {
        val msgId = UUID.randomUUID().toString()
        Log.d(TAG, "Sending AI response via hybrid transport: interactionId=$interactionId, response=$response")

        val payload = JSONObject().apply {
            put("msgId", msgId)
            put("type", "AI_RESPONSE")
            if (interactionId != null) {
                put("interactionId", interactionId)
            }
            put("response", response)
            if (barcode != null) {
                put("barcode", barcode)
            }
            put("timestamp", System.currentTimeMillis())
        }

        val ackDeferred = CompletableDeferred<Boolean>()
        pendingAcks[msgId] = ackDeferred

        // 1. Send via WebSocket (Fast Path)
        scope.launch {
            if (webSocket?.isOpen == true) {
                Log.d(TAG, "Sending AI response via WS: $msgId")
                webSocket?.send(payload.toString())
            } else {
                Log.d(TAG, "WS not open for AI response, will use HTTP")
            }
        }

        // 2. Hedge / Race
        return try {
            withTimeout(HEDGE_DELAY_MS) {
                ackDeferred.await()
                Log.d(TAG, "AI response WS delivery confirmed fast ($msgId)")
                ScanResult.Success("ai_response", "Fast AI response delivery", msgId)
            }
        } catch (e: TimeoutCancellationException) {
            Log.i(TAG, "Hedge delay passed for AI response, sending HTTP fallback ($msgId)")
            // 3. Fallback to HTTP
            apiService.sendAiResponse(interactionId, response, barcode)
        }
    }
}
