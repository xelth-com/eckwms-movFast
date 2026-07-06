package com.xelth.eckwms_movfast.trips

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * JVM unit tests for [VectorTileDecoder]. Each test builds a minimal, valid MVT
 * tile by hand with a tiny protobuf ENCODER (below) — the inverse of the decoder
 * — so the fixtures are transparent and there is no golden-binary blob to trust.
 */
class VectorTileDecoderTest {

    // --- Minimal protobuf encoder (mirrors the decoder's wire subset) --------

    private class ProtoWriter {
        private val out = ByteArrayOutputStream()

        /** Write a base-128 varint. */
        fun varint(value: Long): ProtoWriter {
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) out.write(b or 0x80) else { out.write(b); break }
            }
            return this
        }

        private fun tag(field: Int, wire: Int) = varint((field.toLong() shl 3) or wire.toLong())

        fun varintField(field: Int, value: Long): ProtoWriter {
            tag(field, 0); return varint(value)
        }

        fun lengthDelimited(field: Int, bytes: ByteArray): ProtoWriter {
            tag(field, 2); varint(bytes.size.toLong()); out.write(bytes); return this
        }

        fun string(field: Int, s: String): ProtoWriter =
            lengthDelimited(field, s.toByteArray(Charsets.UTF_8))

        fun bytes(): ByteArray = out.toByteArray()
    }

    /** Protobuf zigzag encode (Int -> unsigned varint value). */
    private fun zig(n: Int): Long = (n.toLong() shl 1) xor (n.toLong() shr 63)

    /** Pack a sequence of varint values into a length-prefixed byte array. */
    private fun packed(values: LongArray): ByteArray {
        val w = ProtoWriter()
        for (v in values) w.varint(v)
        return w.bytes()
    }

    private fun cmd(id: Int, count: Int): Long = ((count shl 3) or id).toLong()

    // --- Fixture builders ----------------------------------------------------

    /** Encode one MVT Value message holding a string. */
    private fun stringValue(s: String): ByteArray = ProtoWriter().string(1, s).bytes()

    /**
     * Build a single-layer tile. [geom] is the raw command/parameter stream;
     * [tags] the flat (key,value) index pairs; [extraLayer]/[extraFeature] inject
     * unknown protobuf fields to exercise the skip path.
     */
    private fun buildTile(
        layerName: String = "transportation",
        extent: Int = 4096,
        keys: List<String> = listOf("class"),
        values: List<String> = listOf("motorway"),
        geomType: Int = 2,
        tags: LongArray = longArrayOf(0, 0),
        geom: LongArray,
        extraLayer: Boolean = false,
        extraFeature: Boolean = false
    ): ByteArray {
        val feature = ProtoWriter().apply {
            varintField(3, geomType.toLong())         // Feature.type
            lengthDelimited(2, packed(tags))          // Feature.tags (packed)
            lengthDelimited(4, packed(geom))          // Feature.geometry (packed)
            if (extraFeature) {
                varintField(99, 12345L)               // unknown varint field
                lengthDelimited(98, byteArrayOf(1, 2, 3)) // unknown length-delimited field
            }
        }.bytes()

        val layer = ProtoWriter().apply {
            varintField(15, 2L)                       // Layer.version
            string(1, layerName)                      // Layer.name
            lengthDelimited(2, feature)               // Layer.features
            for (k in keys) string(3, k)              // Layer.keys
            for (v in values) lengthDelimited(4, stringValue(v)) // Layer.values
            varintField(5, extent.toLong())           // Layer.extent
            if (extraLayer) {
                varintField(99, 777L)                 // unknown varint field
                lengthDelimited(98, byteArrayOf(9, 9)) // unknown length-delimited field
            }
        }.bytes()

        return ProtoWriter().lengthDelimited(3, layer).bytes() // Tile.layers
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    // --- Tests ---------------------------------------------------------------

    // MoveTo(2,2) LineTo(+10,0) LineTo(0,+10) as one MoveTo + one 2-count LineTo.
    private val lineGeom = longArrayOf(
        cmd(1, 1), zig(2), zig(2),                 // MoveTo (2,2)
        cmd(2, 2), zig(10), zig(0), zig(0), zig(10) // LineTo +(10,0) then +(0,10)
    )

    @Test
    fun decodesBasicLineString() {
        val layers = VectorTileDecoder.decode(buildTile(geom = lineGeom))

        assertEquals(1, layers.size)
        val layer = layers[0]
        assertEquals("transportation", layer.name)
        assertEquals(4096, layer.extent)
        assertEquals(1, layer.features.size)

        val f = layer.features[0]
        assertEquals(2, f.geomType)
        assertEquals(1, f.paths.size)
        val path = f.paths[0]
        assertEquals(3, path.size)
        assertArrayEquals(intArrayOf(2, 2), path[0])
        assertArrayEquals(intArrayOf(12, 2), path[1])
        assertArrayEquals(intArrayOf(12, 12), path[2])
        assertEquals(mapOf<String, Any?>("class" to "motorway"), f.tags)
    }

    @Test
    fun gzippedTileDecodesIdentically() {
        val raw = buildTile(geom = lineGeom)
        val gz = gzip(raw)
        // Sanity: gzip magic present so the decoder takes the inflate path.
        assertEquals(0x1F.toByte(), gz[0])
        assertEquals(0x8B.toByte(), gz[1])

        val fromRaw = VectorTileDecoder.decode(raw)
        val fromGz = VectorTileDecoder.decode(gz)

        assertEquals(fromRaw[0].name, fromGz[0].name)
        assertEquals(fromRaw[0].extent, fromGz[0].extent)
        val a = fromRaw[0].features[0]
        val b = fromGz[0].features[0]
        assertEquals(a.geomType, b.geomType)
        assertEquals(a.tags, b.tags)
        assertEquals(a.paths.size, b.paths.size)
        assertArrayEquals(a.paths[0][0], b.paths[0][0])
        assertArrayEquals(a.paths[0][2], b.paths[0][2])
    }

    @Test
    fun multiPathLineStringSplitsOnEachMoveTo() {
        // Two independent sub-lines in one geometry stream.
        val geom = longArrayOf(
            cmd(1, 1), zig(2), zig(2), cmd(2, 1), zig(10), zig(0),   // path A: (2,2)->(12,2)
            cmd(1, 1), zig(3), zig(3), cmd(2, 1), zig(-5), zig(0)    // path B: (15,15)->(10,15)
        )
        val f = VectorTileDecoder.decode(buildTile(geom = geom))[0].features[0]

        assertEquals(2, f.paths.size)
        assertArrayEquals(intArrayOf(2, 2), f.paths[0][0])
        assertArrayEquals(intArrayOf(12, 2), f.paths[0][1])
        // Second MoveTo is relative to the cursor left by path A (12,2).
        assertArrayEquals(intArrayOf(15, 5), f.paths[1][0])
        assertArrayEquals(intArrayOf(10, 5), f.paths[1][1])
    }

    @Test
    fun polygonClosePathAppendsFirstPoint() {
        val geom = longArrayOf(
            cmd(1, 1), zig(2), zig(2),                 // MoveTo (2,2)
            cmd(2, 2), zig(10), zig(0), zig(0), zig(10), // LineTo (12,2),(12,12)
            cmd(7, 1)                                   // ClosePath
        )
        val f = VectorTileDecoder.decode(buildTile(geomType = 3, geom = geom))[0].features[0]

        assertEquals(3, f.geomType)
        assertEquals(1, f.paths.size)
        val ring = f.paths[0]
        assertEquals(4, ring.size) // 3 vertices + closing repeat of the first
        assertArrayEquals(intArrayOf(2, 2), ring[0])
        assertArrayEquals(intArrayOf(12, 2), ring[1])
        assertArrayEquals(intArrayOf(12, 12), ring[2])
        assertArrayEquals(ring[0], ring[3]) // ring closed
    }

    @Test
    fun unknownFieldsAreSkipped() {
        val layers = VectorTileDecoder.decode(
            buildTile(geom = lineGeom, extraLayer = true, extraFeature = true)
        )
        // Despite injected unknown fields (99 varint, 98 length-delimited) in both
        // the layer and the feature, decoding must succeed unchanged.
        val f = layers[0].features[0]
        assertEquals("transportation", layers[0].name)
        assertEquals(3, f.paths[0].size)
        assertEquals("motorway", f.tags["class"])
    }

    @Test
    fun packedTagsWithMultiplePairsDecode() {
        val f = VectorTileDecoder.decode(
            buildTile(
                keys = listOf("class", "surface"),
                values = listOf("motorway", "paved"),
                tags = longArrayOf(0, 0, 1, 1),
                geom = lineGeom
            )
        )[0].features[0]

        assertEquals(2, f.tags.size)
        assertEquals("motorway", f.tags["class"])
        assertEquals("paved", f.tags["surface"])
    }

    @Test
    fun negativeZigzagDeltasDecode() {
        val geom = longArrayOf(
            cmd(1, 1), zig(2), zig(2),   // MoveTo (2,2)
            cmd(2, 1), zig(-1), zig(-1)  // LineTo (1,1)
        )
        val path = VectorTileDecoder.decode(buildTile(geom = geom))[0].features[0].paths[0]

        assertEquals(2, path.size)
        assertArrayEquals(intArrayOf(2, 2), path[0])
        assertArrayEquals(intArrayOf(1, 1), path[1])
        assertTrue(path[1][0] < path[0][0]) // moved back, deltas were negative
    }
}
