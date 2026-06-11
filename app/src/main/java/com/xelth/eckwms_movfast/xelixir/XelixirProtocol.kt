package com.xelth.eckwms_movfast.xelixir

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire protocol for the embedded xelixir agent (movFast PDA side).
 *
 * This is the AGENT half of the protocol the Rust `agent_mock` speaks and the
 * `xelixir/android` viewer (com.example.xelixir) consumes. The agent PRODUCES
 * binary screen frames and CONSUMES JSON `ControlEvent`s from the operator.
 *
 * Binary frame: 44-byte little-endian header + JPEG bytes. Layout (byte offsets)
 * is identical to the viewer's decoder (C2Screen.kt) and the Rust encoder:
 *   0   frame_id   u32
 *   4   x          u32   tile x offset (0 for full frame)
 *   8   y          u32   tile y offset (0 for full frame)
 *   12  w          u32   frame width  (streamed resolution)
 *   16  h          u32   frame height (streamed resolution)
 *   20  mouse_x    i32   agent-side cursor (PDA has none → 0)
 *   24  mouse_y    i32
 *   28  agent_ts   f64   epoch millis when captured
 *   36  server_ts  f64   reserved (server fills)
 *   44  ...        JPEG payload
 */
object XelixirProtocol {
    const val HEADER_SIZE = 44

    /** Build a full-frame binary message (header + JPEG). */
    fun buildFrame(
        frameId: Int,
        width: Int,
        height: Int,
        agentTsMs: Double,
        jpeg: ByteArray,
        mouseX: Int = 0,
        mouseY: Int = 0,
        tileX: Int = 0,
        tileY: Int = 0,
    ): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(frameId)
        buf.putInt(tileX)
        buf.putInt(tileY)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(mouseX)
        buf.putInt(mouseY)
        buf.putDouble(agentTsMs)
        buf.putDouble(0.0) // server_ts reserved
        buf.put(jpeg)
        return buf.array()
    }

    /** Agent → operator status line, rendered in the viewer's thought log. */
    fun agentThought(text: String): String =
        JSONObject().apply {
            put("type", "agent_thought")
            put("text", text)
        }.toString()

    /** Agent → operator active-window/title hint. */
    fun activeWindow(title: String): String =
        JSONObject().apply {
            put("type", "active_window")
            put("title", title)
        }.toString()

    /** Parse an incoming operator ControlEvent (JSON text frame). */
    fun parseCommand(text: String): XelixirCommand? {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        return when (val type = json.optString("type")) {
            "set_stream_config" -> XelixirCommand.SetStreamConfig(
                maxW = json.optInt("max_w", 0),
                maxH = json.optInt("max_h", 0),
                quality = json.optInt("quality", 0),
                autoAdapt = json.optBoolean("auto_adapt", false),
            )
            "request_full_frame" -> XelixirCommand.RequestFullFrame
            "observers_active" -> XelixirCommand.ObserversActive(json.optBoolean("active", false))
            "mousedown", "mousemove", "mouseup" -> XelixirCommand.Pointer(
                kind = type,
                x = json.optInt("x", 0),
                y = json.optInt("y", 0),
                button = json.optInt("button", 0),
            )
            "keydown", "keyup" -> XelixirCommand.Key(kind = type, key = json.optString("key"))
            "type_text" -> XelixirCommand.TypeText(json.optString("text"))
            else -> XelixirCommand.Unknown(type)
        }
    }
}

/** Operator → agent commands we recognise (subset mirroring the Rust ControlEvent enum). */
sealed interface XelixirCommand {
    data class SetStreamConfig(val maxW: Int, val maxH: Int, val quality: Int, val autoAdapt: Boolean) : XelixirCommand
    object RequestFullFrame : XelixirCommand
    data class ObserversActive(val active: Boolean) : XelixirCommand
    // Phase B (input injection via AccessibilityService):
    data class Pointer(val kind: String, val x: Int, val y: Int, val button: Int) : XelixirCommand
    data class Key(val kind: String, val key: String) : XelixirCommand
    data class TypeText(val text: String) : XelixirCommand
    data class Unknown(val type: String) : XelixirCommand
}
