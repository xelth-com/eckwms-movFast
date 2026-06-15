package com.xelth.eckwms_movfast.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Relay API client for communicating with the blind relay (9eck.com).
 * Supports heartbeat registration, encrypted packet push/pull, and mesh status queries.
 */
class RelayClient(
    private val baseUrl: String,
    private val instanceId: String,
    private val meshId: String
) {
    companion object {
        private const val TAG = "RelayClient"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 15_000
    }

    // -- Heartbeat --

    /**
     * Sends a heartbeat (register) to the relay so other nodes can discover us.
     */
    suspend fun sendHeartbeat(
        externalIp: String = "0.0.0.0",
        port: Int = 0,
        status: String? = null
    ): Result<RegisterResponse> = withContext(Dispatchers.IO) {
        try {
            val conn = openPost("$baseUrl/E/register")
            val payload = JSONObject().apply {
                put("instance_id", instanceId)
                put("mesh_id", meshId)
                put("external_ip", externalIp)
                put("port", port)
                status?.let { put("status", it) }
            }
            conn.outputStream.write(payload.toString().toByteArray())

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(RuntimeException("Heartbeat HTTP $code"))
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val resp = RegisterResponse(
                ok = json.getBoolean("ok"),
                instanceId = json.getString("instance_id"),
                meshId = json.getString("mesh_id"),
                status = json.getString("status")
            )
            Log.d(TAG, "Heartbeat OK: [$meshId] $instanceId -> ${resp.status}")
            Result.success(resp)
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed: ${e.message}")
            Result.failure(e)
        }
    }

    // -- Push/Pull packets --

    /**
     * Pushes an encrypted packet to the relay for a specific target.
     */
    suspend fun pushPacket(
        targetInstanceId: String,
        payloadCipher: ByteArray,
        nonce: ByteArray,
        ttlSeconds: Long? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conn = openPost("$baseUrl/E/push")
            val payload = JSONObject().apply {
                put("mesh_id", meshId)
                put("target_instance_id", targetInstanceId)
                put("sender_instance_id", instanceId)
                put("payload_cipher", android.util.Base64.encodeToString(payloadCipher, android.util.Base64.NO_WRAP))
                put("nonce", android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP))
                ttlSeconds?.let { put("ttl_seconds", it) }
            }
            conn.outputStream.write(payload.toString().toByteArray())

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(RuntimeException("Push HTTP $code"))
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val packetId = json.getString("packet_id")
            Log.d(TAG, "Push OK: $instanceId -> $targetInstanceId (packet: $packetId)")
            Result.success(packetId)
        } catch (e: Exception) {
            Log.e(TAG, "Push failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Pulls pending encrypted packets from the relay for this instance.
     */
    suspend fun pullPackets(): Result<List<RawPacket>> = withContext(Dispatchers.IO) {
        try {
            val conn = openGet("$baseUrl/E/pull/$meshId/$instanceId")

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(RuntimeException("Pull HTTP $code"))
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val packetsArray = json.getJSONArray("packets")

            val packets = mutableListOf<RawPacket>()
            for (i in 0 until packetsArray.length()) {
                val p = packetsArray.getJSONObject(i)
                packets.add(
                    RawPacket(
                        id = p.getString("id"),
                        meshId = p.getString("mesh_id"),
                        targetInstanceId = p.getString("target_instance_id"),
                        senderInstanceId = p.getString("sender_instance_id"),
                        payloadCipher = android.util.Base64.decode(p.getString("payload_cipher"), android.util.Base64.NO_WRAP),
                        nonce = android.util.Base64.decode(p.getString("nonce"), android.util.Base64.NO_WRAP),
                        createdAt = p.getString("created_at"),
                        ttl = p.getString("ttl")
                    )
                )
            }

            Log.d(TAG, "Pull OK: [$meshId] $instanceId got ${packets.size} packets")
            Result.success(packets)
        } catch (e: Exception) {
            Log.e(TAG, "Pull failed: ${e.message}")
            Result.failure(e)
        }
    }

    // -- Mesh status --

    /**
     * Gets the list of nodes registered in our mesh from the relay.
     */
    suspend fun getMeshStatus(): Result<List<NodeInfo>> = withContext(Dispatchers.IO) {
        try {
            val conn = openGet("$baseUrl/E/mesh/$meshId/status")

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(RuntimeException("Mesh status HTTP $code"))
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val nodesArray = json.getJSONArray("nodes")

            val nodes = mutableListOf<NodeInfo>()
            for (i in 0 until nodesArray.length()) {
                val n = nodesArray.getJSONObject(i)
                nodes.add(
                    NodeInfo(
                        instanceId = n.getString("instance_id"),
                        externalIp = n.getString("external_ip"),
                        port = n.getInt("port"),
                        status = n.getString("status"),
                        lastSeen = n.getString("last_seen")
                    )
                )
            }

            Log.d(TAG, "Mesh status: [$meshId] ${nodes.size} nodes")
            Result.success(nodes)
        } catch (e: Exception) {
            Log.e(TAG, "Mesh status failed: ${e.message}")
            Result.failure(e)
        }
    }

    // -- Mesh-task reverse-fetch queue (NAT traversal) --

    /**
     * Queue a mesh task for a NAT'd target node on the (paid) relay. The relay
     * is a blind queue; the target's `mesh_relay_poller` interprets `kind`.
     * Returns the relay-assigned task_id.
     *
     * Used for relay-forwarded pairing: the phone (mobile data, no directly
     * reachable full WMS) dispatches `device_register` to the master's UUID,
     * which the master fulfills by polling — so eckN stay pure relays.
     * Envelope shape mirrors Rust `RelayClient::mesh_dispatch`.
     */
    suspend fun meshDispatch(
        targetUuid: String,
        kind: String,
        payload: JSONObject,
        senderUuid: String = instanceId
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conn = openPost("$baseUrl/E/m/dispatch/$targetUuid")
            val body = JSONObject().apply {
                put("envelope", JSONObject().apply {
                    put("target_uuid", targetUuid)
                    put("sender_uuid", senderUuid)
                    put("kind", kind)
                    put("payload", payload)
                })
            }
            conn.outputStream.write(body.toString().toByteArray())

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull() ?: ""
                return@withContext Result.failure(RuntimeException("meshDispatch HTTP $code $err"))
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val taskId = json.getString("task_id")
            Log.d(TAG, "meshDispatch OK: kind=$kind target=$targetUuid task=$taskId")
            Result.success(taskId)
        } catch (e: Exception) {
            Log.e(TAG, "meshDispatch failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Poll a dispatched task's result. Returns the result body once the target
     * acks, or null while still pending (HTTP 404 = not yet, or status=pending).
     */
    suspend fun meshResult(taskId: String): Result<JSONObject?> = withContext(Dispatchers.IO) {
        try {
            val conn = openGet("$baseUrl/E/m/result/$taskId")
            val code = conn.responseCode
            if (code == 404) return@withContext Result.success(null)
            if (code !in 200..299) {
                return@withContext Result.failure(RuntimeException("meshResult HTTP $code"))
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            if (json.optString("status") == "completed") {
                Result.success(json.optJSONObject("result") ?: JSONObject())
            } else {
                Result.success(null) // still pending
            }
        } catch (e: Exception) {
            Log.e(TAG, "meshResult failed: ${e.message}")
            Result.failure(e)
        }
    }

    // -- HTTP helpers --

    private fun openPost(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        return conn
    }

    private fun openGet(urlStr: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        return conn
    }
}

// -- Data classes --

data class RegisterResponse(
    val ok: Boolean,
    val instanceId: String,
    val meshId: String,
    val status: String
)

data class RawPacket(
    val id: String,
    val meshId: String,
    val targetInstanceId: String,
    val senderInstanceId: String,
    val payloadCipher: ByteArray,
    val nonce: ByteArray,
    val createdAt: String,
    val ttl: String
)

data class NodeInfo(
    val instanceId: String,
    val externalIp: String,
    val port: Int,
    val status: String,
    val lastSeen: String
)
