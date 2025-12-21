package com.xelth.eckwms_movfast.net

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

    fun init() {
        connectWebSocket()
    }

    private fun connectWebSocket() {
        try {
            val serverUrl = SettingsManager.getServerUrl().replace("http", "ws") // Simple replace for now
            val uri = URI("$serverUrl/ws")
            Log.d(TAG, "Connecting WS to $uri")

            webSocket = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.i(TAG, "WS Connected")
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
            if (json.has("ack")) {
                val msgId = json.getString("ack")
                pendingAcks[msgId]?.complete(true)
                pendingAcks.remove(msgId)
                Log.d(TAG, "Received ACK for $msgId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WS message", e)
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
}
