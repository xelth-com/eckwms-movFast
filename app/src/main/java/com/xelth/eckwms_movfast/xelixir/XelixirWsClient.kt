package com.xelth.eckwms_movfast.xelixir

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext

/**
 * Agent-side WebSocket connection to the xelixir C2 server.
 *
 * Dials `wss://<host>/X/ws?role=agent&token=<WS_AUTH_TOKEN>&agent_id=<instanceId>`,
 * the exact endpoint the Rust `agent_mock` uses. Sends binary screen frames and
 * JSON status; receives operator `ControlEvent`s and forwards them to [onCommand].
 * Auto-reconnects with linear backoff until [close] is called.
 */
class XelixirWsClient(
    private val baseWsUrl: String,
    private val token: String,
    private val agentId: String,
    private val onCommand: (XelixirCommand) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onClosed: () -> Unit = {},
) {
    companion object {
        private const val TAG = "XelixirWs"
        private const val RECONNECT_DELAY_MS = 4_000L
        private const val LOST_TIMEOUT_SEC = 20
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: WebSocketClient? = null
    @Volatile private var stopped = false
    // Backing field is named `connected` (not `isOpen`) to avoid clashing with
    // WebSocketClient.isOpen() inside the anonymous subclass below.
    @Volatile private var connected: Boolean = false
    val isOpen: Boolean get() = connected

    private fun buildUri(): URI {
        val sb = StringBuilder(baseWsUrl).append("?role=agent")
        if (token.isNotEmpty()) sb.append("&token=").append(token)
        if (agentId.isNotEmpty()) sb.append("&agent_id=").append(agentId)
        return URI(sb.toString())
    }

    fun connect() {
        stopped = false
        open()
    }

    private fun open() {
        if (stopped) return
        val uri = buildUri()
        Log.i(TAG, "connecting as agent_id=$agentId -> ${uri.scheme}://${uri.host}:${uri.port}${uri.path}")
        val sock = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                this@XelixirWsClient.connected = true
                Log.i(TAG, "WS open")
                onConnected()
            }

            override fun onMessage(message: String?) {
                message ?: return
                XelixirProtocol.parseCommand(message)?.let { onCommand(it) }
            }

            override fun onMessage(bytes: ByteBuffer?) {
                // Operators do not send binary to the agent; ignore.
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                this@XelixirWsClient.connected = false
                Log.w(TAG, "WS closed ($code): $reason (remote=$remote)")
                onClosed()
                scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WS error: ${ex?.message}")
            }
        }
        // wss: wrap in TLS using the platform trust store (xelth.com has a valid cert).
        if (uri.scheme.equals("wss", ignoreCase = true)) {
            try {
                sock.setSocketFactory(SSLContext.getDefault().socketFactory)
            } catch (e: Exception) {
                Log.e(TAG, "TLS socket factory failed: ${e.message}")
            }
        }
        sock.connectionLostTimeout = LOST_TIMEOUT_SEC
        socket = sock
        try {
            sock.connect()
        } catch (e: Exception) {
            Log.e(TAG, "connect() threw: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!stopped && isActive && !connected) {
                Log.i(TAG, "reconnecting…")
                open()
            }
        }
    }

    fun sendBinary(bytes: ByteArray) {
        val s = socket ?: return
        if (!connected) return
        try {
            s.send(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "sendBinary failed: ${e.message}")
        }
    }

    fun sendText(text: String) {
        val s = socket ?: return
        if (!connected) return
        try {
            s.send(text)
        } catch (e: Exception) {
            Log.w(TAG, "sendText failed: ${e.message}")
        }
    }

    fun close() {
        stopped = true
        connected = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        scope.cancel()
    }
}
