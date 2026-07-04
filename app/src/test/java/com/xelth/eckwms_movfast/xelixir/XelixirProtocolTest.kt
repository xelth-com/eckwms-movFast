package com.xelth.eckwms_movfast.xelixir

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [XelixirProtocol] — the wire format shared with the Rust `agent_mock`
 * encoder and the `xelixir/android` viewer decoder.
 *
 * The 44-byte little-endian header layout (byte offsets) is a hard contract; the frame
 * tests assert every field round-trips at its exact documented offset.
 */
class XelixirProtocolTest {

    // ---------------------------------------------------------------------------
    // buildFrame
    // ---------------------------------------------------------------------------

    @Test
    fun buildFrame_layout_roundTripsEveryFieldAtExactOffset() {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val frame = XelixirProtocol.buildFrame(
            frameId = 0x11223344,
            width = 1280,
            height = 720,
            agentTsMs = 1_700_000_000_123.5,
            jpeg = jpeg,
            mouseX = -7,
            mouseY = 42,
            tileX = 5,
            tileY = 9,
        )

        assertEquals("total size = header + jpeg", XelixirProtocol.HEADER_SIZE + jpeg.size, frame.size)

        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x11223344, buf.getInt(0))          // frameId @0
        assertEquals(5, buf.getInt(4))                   // tileX   @4
        assertEquals(9, buf.getInt(8))                   // tileY   @8
        assertEquals(1280, buf.getInt(12))               // width   @12
        assertEquals(720, buf.getInt(16))                // height  @16
        assertEquals(-7, buf.getInt(20))                 // mouseX  @20
        assertEquals(42, buf.getInt(24))                 // mouseY  @24
        assertEquals(1_700_000_000_123.5, buf.getDouble(28), 0.0) // agentTs @28
        assertEquals(0.0, buf.getDouble(36), 0.0)        // server_ts reserved @36

        // Payload starts at offset 44.
        assertArrayEquals(jpeg, frame.copyOfRange(44, frame.size))
    }

    @Test
    fun buildFrame_emptyJpeg_isHeaderOnly() {
        val frame = XelixirProtocol.buildFrame(
            frameId = 1,
            width = 640,
            height = 480,
            agentTsMs = 12345.0,
            jpeg = ByteArray(0),
        )
        assertEquals(XelixirProtocol.HEADER_SIZE, frame.size)

        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, buf.getInt(0))
        assertEquals(640, buf.getInt(12))
        assertEquals(480, buf.getInt(16))
        assertEquals(12345.0, buf.getDouble(28), 0.0)
    }

    @Test
    fun buildFrame_defaultMouseAndTileArgsAreZero() {
        val frame = XelixirProtocol.buildFrame(
            frameId = 99,
            width = 100,
            height = 200,
            agentTsMs = 0.0,
            jpeg = byteArrayOf(7),
        )
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("default tileX", 0, buf.getInt(4))
        assertEquals("default tileY", 0, buf.getInt(8))
        assertEquals("default mouseX", 0, buf.getInt(20))
        assertEquals("default mouseY", 0, buf.getInt(24))
    }

    // ---------------------------------------------------------------------------
    // parseCommand — one test per branch
    // ---------------------------------------------------------------------------

    @Test
    fun parseCommand_setStreamConfig_allFields() {
        val cmd = XelixirProtocol.parseCommand(
            """{"type":"set_stream_config","max_w":1920,"max_h":1080,"quality":75,"auto_adapt":true}"""
        )
        assertEquals(
            XelixirCommand.SetStreamConfig(maxW = 1920, maxH = 1080, quality = 75, autoAdapt = true),
            cmd,
        )
    }

    @Test
    fun parseCommand_setStreamConfig_defaultsWhenFieldsAbsent() {
        val cmd = XelixirProtocol.parseCommand("""{"type":"set_stream_config"}""")
        assertEquals(
            XelixirCommand.SetStreamConfig(maxW = 0, maxH = 0, quality = 0, autoAdapt = false),
            cmd,
        )
    }

    @Test
    fun parseCommand_requestFullFrame() {
        val cmd = XelixirProtocol.parseCommand("""{"type":"request_full_frame"}""")
        assertSame(XelixirCommand.RequestFullFrame, cmd)
    }

    @Test
    fun parseCommand_observersActive() {
        assertEquals(
            XelixirCommand.ObserversActive(active = true),
            XelixirProtocol.parseCommand("""{"type":"observers_active","active":true}"""),
        )
        // Default when "active" absent → false.
        assertEquals(
            XelixirCommand.ObserversActive(active = false),
            XelixirProtocol.parseCommand("""{"type":"observers_active"}"""),
        )
    }

    @Test
    fun parseCommand_pointer_allThreeKinds() {
        assertEquals(
            XelixirCommand.Pointer(kind = "mousedown", x = 10, y = 20, button = 1),
            XelixirProtocol.parseCommand("""{"type":"mousedown","x":10,"y":20,"button":1}"""),
        )
        assertEquals(
            XelixirCommand.Pointer(kind = "mousemove", x = 33, y = 44, button = 0),
            XelixirProtocol.parseCommand("""{"type":"mousemove","x":33,"y":44}"""),
        )
        assertEquals(
            XelixirCommand.Pointer(kind = "mouseup", x = 1, y = 2, button = 2),
            XelixirProtocol.parseCommand("""{"type":"mouseup","x":1,"y":2,"button":2}"""),
        )
    }

    @Test
    fun parseCommand_pointer_defaultsWhenFieldsAbsent() {
        assertEquals(
            XelixirCommand.Pointer(kind = "mousedown", x = 0, y = 0, button = 0),
            XelixirProtocol.parseCommand("""{"type":"mousedown"}"""),
        )
    }

    @Test
    fun parseCommand_key_bothKinds() {
        assertEquals(
            XelixirCommand.Key(kind = "keydown", key = "Enter"),
            XelixirProtocol.parseCommand("""{"type":"keydown","key":"Enter"}"""),
        )
        assertEquals(
            XelixirCommand.Key(kind = "keyup", key = "a"),
            XelixirProtocol.parseCommand("""{"type":"keyup","key":"a"}"""),
        )
    }

    @Test
    fun parseCommand_key_defaultKeyIsEmptyString() {
        assertEquals(
            XelixirCommand.Key(kind = "keydown", key = ""),
            XelixirProtocol.parseCommand("""{"type":"keydown"}"""),
        )
    }

    @Test
    fun parseCommand_typeText() {
        assertEquals(
            XelixirCommand.TypeText("hello world"),
            XelixirProtocol.parseCommand("""{"type":"type_text","text":"hello world"}"""),
        )
        // Default when "text" absent → empty string.
        assertEquals(
            XelixirCommand.TypeText(""),
            XelixirProtocol.parseCommand("""{"type":"type_text"}"""),
        )
    }

    // ---------------------------------------------------------------------------
    // parseCommand — malformed / unknown
    // ---------------------------------------------------------------------------

    @Test
    fun parseCommand_nonJson_returnsNull() {
        assertNull(XelixirProtocol.parseCommand("not json {"))
        assertNull(XelixirProtocol.parseCommand(""))
    }

    @Test
    fun parseCommand_unknownType_returnsUnknown() {
        assertEquals(
            XelixirCommand.Unknown("foobar"),
            XelixirProtocol.parseCommand("""{"type":"foobar"}"""),
        )
    }

    @Test
    fun parseCommand_emptyObject_returnsUnknownEmpty() {
        // Valid JSON, but no "type" → optString returns "" → Unknown("").
        assertEquals(
            XelixirCommand.Unknown(""),
            XelixirProtocol.parseCommand("{}"),
        )
    }

    // ---------------------------------------------------------------------------
    // agentThought / activeWindow
    // ---------------------------------------------------------------------------

    @Test
    fun agentThought_encodesTypeAndText() {
        val json = JSONObject(XelixirProtocol.agentThought("thinking about it"))
        assertEquals("agent_thought", json.getString("type"))
        assertEquals("thinking about it", json.getString("text"))
    }

    @Test
    fun activeWindow_encodesTypeAndTitle() {
        val json = JSONObject(XelixirProtocol.activeWindow("Main Window"))
        assertEquals("active_window", json.getString("type"))
        assertEquals("Main Window", json.getString("title"))
    }
}
