package com.xelth.eckwms_movfast.trips

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal, dependency-free Mapbox Vector Tile (MVT 2.1) decoder.
 *
 * WHAT: turns the raw bytes of one vector tile into layers of features with
 * their tile-space geometry and decoded attribute tags. Speaks just enough of
 * the protobuf wire format (varint + length-delimited, hand-rolled) to read the
 * MVT schema — no protobuf runtime, no MapLibre, nothing but the JDK.
 *
 * WHY: this feeds the on-device road-snap map matching (Phase 2 of
 * .eck/TRACK_ESTIMATION.md — Viterbi over local road-graph candidates). The
 * phone already carries the corridor vector tiles it renders; to snap a smoothed
 * GPS track onto real road geometry we must read the road LineStrings out of
 * those tiles, on-device, with zero extra dependencies and zero network. The
 * mbgl cache stores tiles gzipped, so we transparently gunzip first.
 *
 * Scope is deliberately narrow: we only need geometry + tags, so unknown
 * protobuf fields are skipped and only the MVT-defined fields are interpreted.
 * Reference: https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 */
object VectorTileDecoder {

    data class Feature(
        val geomType: Int,                 // 1 point, 2 linestring, 3 polygon
        val paths: List<List<IntArray>>,   // each IntArray = [x, y] in tile coords
        val tags: Map<String, Any?>        // decoded key->value
    )

    data class Layer(val name: String, val extent: Int, val features: List<Feature>)

    // MVT geometry command ids (lower 3 bits of a command integer).
    private const val CMD_MOVE_TO = 1
    private const val CMD_LINE_TO = 2
    private const val CMD_CLOSE_PATH = 7

    // Protobuf field numbers for the MVT messages (spec 2.1, §4).
    private const val TILE_LAYERS = 3
    private const val LAYER_NAME = 1
    private const val LAYER_FEATURES = 2
    private const val LAYER_KEYS = 3
    private const val LAYER_VALUES = 4
    private const val LAYER_EXTENT = 5
    private const val FEATURE_TAGS = 2
    private const val FEATURE_TYPE = 3
    private const val FEATURE_GEOMETRY = 4
    private const val DEFAULT_EXTENT = 4096

    /** Decode a (possibly gzipped) MVT tile. Throws on malformed input. */
    fun decode(bytes: ByteArray): List<Layer> {
        // mbgl stores tiles gzipped; the 0x1F 0x8B magic tells us to inflate.
        val data = if (bytes.size >= 2 &&
            bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
        ) gunzip(bytes) else bytes

        val layers = mutableListOf<Layer>()
        val r = ProtoReader(data)
        while (r.hasMore()) {
            val tag = r.readTag()
            if (tag.field == TILE_LAYERS && tag.wire == WIRE_LEN) {
                layers.add(decodeLayer(r.readLengthDelimited()))
            } else {
                r.skip(tag.wire)
            }
        }
        return layers
    }

    private fun decodeLayer(r: ProtoReader): Layer {
        var name = ""
        var extent = DEFAULT_EXTENT
        val keys = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        // Features reference keys/values by index, but the wire order of layer
        // fields is not guaranteed. Capture each feature's byte range and decode
        // it only after the whole layer (keys + values) has been read.
        val featureReaders = mutableListOf<ProtoReader>()

        while (r.hasMore()) {
            val tag = r.readTag()
            when {
                tag.field == LAYER_NAME && tag.wire == WIRE_LEN -> name = r.readString()
                tag.field == LAYER_FEATURES && tag.wire == WIRE_LEN ->
                    featureReaders.add(r.readLengthDelimited())
                tag.field == LAYER_KEYS && tag.wire == WIRE_LEN -> keys.add(r.readString())
                tag.field == LAYER_VALUES && tag.wire == WIRE_LEN ->
                    values.add(decodeValue(r.readLengthDelimited()))
                tag.field == LAYER_EXTENT && tag.wire == WIRE_VARINT ->
                    extent = r.readVarint().toInt()
                else -> r.skip(tag.wire) // version (15) and any unknown field
            }
        }

        val features = featureReaders.map { decodeFeature(it, keys, values) }
        return Layer(name, extent, features)
    }

    /** A Value message carries exactly one typed field; return it as a JVM type. */
    private fun decodeValue(r: ProtoReader): Any? {
        var result: Any? = null
        while (r.hasMore()) {
            val tag = r.readTag()
            when {
                tag.field == 1 && tag.wire == WIRE_LEN -> result = r.readString()
                tag.field == 2 && tag.wire == WIRE_FIXED32 -> result = Float.fromBits(r.readFixed32())
                tag.field == 3 && tag.wire == WIRE_FIXED64 -> result = Double.fromBits(r.readFixed64())
                tag.field == 4 && tag.wire == WIRE_VARINT -> result = r.readVarint()             // int64
                tag.field == 5 && tag.wire == WIRE_VARINT -> result = r.readVarint()             // uint64
                tag.field == 6 && tag.wire == WIRE_VARINT -> result = zigzag(r.readVarint())     // sint64
                tag.field == 7 && tag.wire == WIRE_VARINT -> result = r.readVarint() != 0L       // bool
                else -> r.skip(tag.wire)
            }
        }
        return result
    }

    private fun decodeFeature(r: ProtoReader, keys: List<String>, values: List<Any?>): Feature {
        var geomType = 0
        var tags = LongArray(0)
        var geom = LongArray(0)
        while (r.hasMore()) {
            val tag = r.readTag()
            when {
                tag.field == FEATURE_TAGS && tag.wire == WIRE_LEN -> tags = r.readPackedVarints()
                tag.field == FEATURE_TYPE && tag.wire == WIRE_VARINT -> geomType = r.readVarint().toInt()
                tag.field == FEATURE_GEOMETRY && tag.wire == WIRE_LEN -> geom = r.readPackedVarints()
                else -> r.skip(tag.wire) // id (1) and any unknown field
            }
        }
        return Feature(geomType, decodeGeometry(geom), decodeTags(tags, keys, values))
    }

    private fun decodeTags(tags: LongArray, keys: List<String>, values: List<Any?>): Map<String, Any?> {
        // Tags are a flat packed stream of (key_index, value_index) pairs.
        val map = LinkedHashMap<String, Any?>()
        var i = 0
        while (i + 1 < tags.size) {
            val keyIdx = tags[i].toInt()
            val valIdx = tags[i + 1].toInt()
            require(keyIdx in keys.indices) { "tag key index $keyIdx out of range" }
            require(valIdx in values.indices) { "tag value index $valIdx out of range" }
            map[keys[keyIdx]] = values[valIdx]
            i += 2
        }
        return map
    }

    private fun decodeGeometry(geom: LongArray): List<List<IntArray>> {
        val paths = mutableListOf<MutableList<IntArray>>()
        var current: MutableList<IntArray>? = null
        // The cursor is absolute tile-space; every parameter is a delta from it.
        var x = 0
        var y = 0
        var i = 0
        while (i < geom.size) {
            val cmdInt = geom[i++].toInt()
            val cmd = cmdInt and 0x7
            val count = cmdInt ushr 3
            when (cmd) {
                CMD_MOVE_TO -> repeat(count) {
                    require(i + 1 < geom.size) { "truncated MoveTo parameters" }
                    x += zigzag(geom[i++]).toInt()
                    y += zigzag(geom[i++]).toInt()
                    // Each MoveTo point begins a new path (a new sub-line/ring).
                    val path = mutableListOf(intArrayOf(x, y))
                    paths.add(path)
                    current = path
                }
                CMD_LINE_TO -> {
                    val path = current ?: throw IllegalStateException("LineTo before MoveTo")
                    repeat(count) {
                        require(i + 1 < geom.size) { "truncated LineTo parameters" }
                        x += zigzag(geom[i++]).toInt()
                        y += zigzag(geom[i++]).toInt()
                        path.add(intArrayOf(x, y))
                    }
                }
                CMD_CLOSE_PATH -> {
                    val path = current ?: throw IllegalStateException("ClosePath before MoveTo")
                    // Close the ring by repeating its first vertex. ClosePath takes
                    // no parameters and does NOT move the cursor (matches the
                    // mapbox/vector-tile-js reference implementation).
                    if (path.isNotEmpty()) {
                        val first = path.first()
                        path.add(intArrayOf(first[0], first[1]))
                    }
                }
                else -> throw IllegalArgumentException("unknown geometry command $cmd")
            }
        }
        return paths
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    /** Protobuf/MVT zigzag decode: maps unsigned varint back to a signed value. */
    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1L)

    // Protobuf wire types (the low 3 bits of a field tag).
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    private data class Tag(val field: Int, val wire: Int)

    /**
     * Cursor over a byte range decoding the subset of the protobuf wire format
     * MVT uses. Sub-readers share the backing array but hold their own [pos]/[end]
     * so a captured length-delimited chunk can be decoded later.
     */
    private class ProtoReader(
        private val buf: ByteArray,
        private var pos: Int,
        private val end: Int
    ) {
        constructor(buf: ByteArray) : this(buf, 0, buf.size)

        fun hasMore(): Boolean = pos < end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end) { "truncated varint" }
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b.toLong() and 0x7FL) shl shift)
                if (b < 0x80) break
                shift += 7
                require(shift < 64) { "varint too long" }
            }
            return result
        }

        fun readTag(): Tag {
            val t = readVarint().toInt()
            return Tag(t ushr 3, t and 0x7)
        }

        fun readFixed32(): Int {
            require(pos + 4 <= end) { "truncated fixed32" }
            val v = (buf[pos].toInt() and 0xFF) or
                ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun readFixed64(): Long {
            require(pos + 8 <= end) { "truncated fixed64" }
            var v = 0L
            for (k in 0 until 8) {
                v = v or ((buf[pos + k].toLong() and 0xFFL) shl (8 * k))
            }
            pos += 8
            return v
        }

        fun readString(): String {
            val len = readVarint().toInt()
            require(len >= 0 && pos + len <= end) { "truncated string" }
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        /** Return a sub-reader over the next length-delimited chunk. */
        fun readLengthDelimited(): ProtoReader {
            val len = readVarint().toInt()
            require(len >= 0 && pos + len <= end) { "truncated length-delimited field" }
            val sub = ProtoReader(buf, pos, pos + len)
            pos += len
            return sub
        }

        /** Read a length-delimited packed varint array (MVT tags & geometry). */
        fun readPackedVarints(): LongArray {
            val len = readVarint().toInt()
            require(len >= 0 && pos + len <= end) { "truncated packed field" }
            val sub = ProtoReader(buf, pos, pos + len)
            pos += len
            val out = ArrayList<Long>()
            while (sub.hasMore()) out.add(sub.readVarint())
            return out.toLongArray()
        }

        /** Skip a field of the given wire type (spec-defined unknown handling). */
        fun skip(wire: Int) {
            when (wire) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> { require(pos + 8 <= end) { "truncated fixed64" }; pos += 8 }
                WIRE_LEN -> {
                    val len = readVarint().toInt()
                    require(len >= 0 && pos + len <= end) { "truncated length-delimited field" }
                    pos += len
                }
                WIRE_FIXED32 -> { require(pos + 4 <= end) { "truncated fixed32" }; pos += 4 }
                else -> throw IllegalArgumentException("unknown wire type $wire")
            }
        }
    }
}
