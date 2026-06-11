package com.xelth.eckwms_movfast.utils

/**
 * MurmurHash3 x64_128 — deterministic content-addressable UUID generation.
 *
 * Produces a UUID string from file bytes. The same bytes always produce the same UUID,
 * regardless of platform (matching Rust server implementation).
 *
 * Based on Austin Appleby's public domain MurmurHash3 reference implementation.
 * Seed is always 0 for CAS usage.
 */
object ContentHash {

    /**
     * Compute a deterministic UUID from raw bytes using MurmurHash3 x64_128.
     * Format: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
     */
    fun uuidFromBytes(data: ByteArray): String {
        val (h1, h2) = murmur3x64_128(data, 0)
        // Format as UUID: h1 provides first 8 bytes, h2 provides last 8 bytes (big-endian)
        return String.format(
            "%08x-%04x-%04x-%04x-%012x",
            (h1 ushr 32).toInt(),                          // 4 bytes
            ((h1 ushr 16) and 0xFFFF).toInt(),             // 2 bytes
            (h1 and 0xFFFF).toInt(),                       // 2 bytes
            ((h2 ushr 48) and 0xFFFF).toInt(),             // 2 bytes
            h2 and 0x0000FFFFFFFFFFFF                      // 6 bytes
        )
    }

    /**
     * MurmurHash3 x64_128 implementation.
     * Returns (h1, h2) as two Long values.
     */
    fun murmur3x64_128(data: ByteArray, seed: Int): Pair<Long, Long> {
        val len = data.size
        val nblocks = len / 16

        var h1 = seed.toLong()
        var h2 = seed.toLong()

        val c1 = -0x783C846EEEBDAC2BL // 0x87c37b91114253d5
        val c2 = 0x4cf5ad432745937fL

        // body — process 16-byte blocks
        for (i in 0 until nblocks) {
            var k1 = getLittleEndianLong(data, i * 16)
            var k2 = getLittleEndianLong(data, i * 16 + 8)

            k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1
            h1 = java.lang.Long.rotateLeft(h1, 27); h1 += h2; h1 = h1 * 5 + 0x52dce729

            k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2
            h2 = java.lang.Long.rotateLeft(h2, 31); h2 += h1; h2 = h2 * 5 + 0x38495ab5
        }

        // tail — process remaining bytes
        val tail = nblocks * 16
        var k1 = 0L
        var k2 = 0L

        when (len and 15) {
            15 -> { k2 = k2 xor ((data[tail + 14].toLong() and 0xFF) shl 48); k2 = k2 xor ((data[tail + 13].toLong() and 0xFF) shl 40); k2 = k2 xor ((data[tail + 12].toLong() and 0xFF) shl 32); k2 = k2 xor ((data[tail + 11].toLong() and 0xFF) shl 24); k2 = k2 xor ((data[tail + 10].toLong() and 0xFF) shl 16); k2 = k2 xor ((data[tail + 9].toLong() and 0xFF) shl 8); k2 = k2 xor (data[tail + 8].toLong() and 0xFF); k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2; k1 = k1 xor ((data[tail + 7].toLong() and 0xFF) shl 56); k1 = k1 xor ((data[tail + 6].toLong() and 0xFF) shl 48); k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            14 -> { k2 = k2 xor ((data[tail + 13].toLong() and 0xFF) shl 40); k2 = k2 xor ((data[tail + 12].toLong() and 0xFF) shl 32); k2 = k2 xor ((data[tail + 11].toLong() and 0xFF) shl 24); k2 = k2 xor ((data[tail + 10].toLong() and 0xFF) shl 16); k2 = k2 xor ((data[tail + 9].toLong() and 0xFF) shl 8); k2 = k2 xor (data[tail + 8].toLong() and 0xFF); k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2; k1 = k1 xor ((data[tail + 7].toLong() and 0xFF) shl 56); k1 = k1 xor ((data[tail + 6].toLong() and 0xFF) shl 48); k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            13 -> { k2 = k2 xor ((data[tail + 12].toLong() and 0xFF) shl 32); k2 = k2 xor ((data[tail + 11].toLong() and 0xFF) shl 24); k2 = k2 xor ((data[tail + 10].toLong() and 0xFF) shl 16); k2 = k2 xor ((data[tail + 9].toLong() and 0xFF) shl 8); k2 = k2 xor (data[tail + 8].toLong() and 0xFF); k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2; k1 = k1 xor ((data[tail + 7].toLong() and 0xFF) shl 56); k1 = k1 xor ((data[tail + 6].toLong() and 0xFF) shl 48); k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            12 -> { k2 = k2 xor ((data[tail + 11].toLong() and 0xFF) shl 24); k2 = k2 xor ((data[tail + 10].toLong() and 0xFF) shl 16); k2 = k2 xor ((data[tail + 9].toLong() and 0xFF) shl 8); k2 = k2 xor (data[tail + 8].toLong() and 0xFF); k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2; k1 = k1 xor ((data[tail + 7].toLong() and 0xFF) shl 56); k1 = k1 xor ((data[tail + 6].toLong() and 0xFF) shl 48); k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            11 -> { k2 = k2 xor ((data[tail + 10].toLong() and 0xFF) shl 16); k2 = k2 xor ((data[tail + 9].toLong() and 0xFF) shl 8); k2 = k2 xor (data[tail + 8].toLong() and 0xFF); k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2; k1 = k1 xor ((data[tail + 7].toLong() and 0xFF) shl 56); k1 = k1 xor ((data[tail + 6].toLong() and 0xFF) shl 48); k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            10 -> { k2 = k2 xor ((data[tail + 9].toLong() and 0xFF) shl 8); k2 = k2 xor (data[tail + 8].toLong() and 0xFF); k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2; k1 = k1 xor ((data[tail + 7].toLong() and 0xFF) shl 56); k1 = k1 xor ((data[tail + 6].toLong() and 0xFF) shl 48); k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            9 -> { k2 = k2 xor (data[tail + 8].toLong() and 0xFF); k2 *= c2; k2 = java.lang.Long.rotateLeft(k2, 33); k2 *= c1; h2 = h2 xor k2; k1 = k1 xor ((data[tail + 7].toLong() and 0xFF) shl 56); k1 = k1 xor ((data[tail + 6].toLong() and 0xFF) shl 48); k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            8 -> { k1 = k1 xor ((data[tail + 7].toLong() and 0xFF) shl 56); k1 = k1 xor ((data[tail + 6].toLong() and 0xFF) shl 48); k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            7 -> { k1 = k1 xor ((data[tail + 6].toLong() and 0xFF) shl 48); k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            6 -> { k1 = k1 xor ((data[tail + 5].toLong() and 0xFF) shl 40); k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            5 -> { k1 = k1 xor ((data[tail + 4].toLong() and 0xFF) shl 32); k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            4 -> { k1 = k1 xor ((data[tail + 3].toLong() and 0xFF) shl 24); k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            3 -> { k1 = k1 xor ((data[tail + 2].toLong() and 0xFF) shl 16); k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            2 -> { k1 = k1 xor ((data[tail + 1].toLong() and 0xFF) shl 8); k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
            1 -> { k1 = k1 xor (data[tail].toLong() and 0xFF); k1 *= c1; k1 = java.lang.Long.rotateLeft(k1, 31); k1 *= c2; h1 = h1 xor k1 }
        }

        // finalization
        h1 = h1 xor len.toLong()
        h2 = h2 xor len.toLong()
        h1 += h2
        h2 += h1
        h1 = fmix64(h1)
        h2 = fmix64(h2)
        h1 += h2
        h2 += h1

        return Pair(h1, h2)
    }

    private fun fmix64(k: Long): Long {
        var h = k
        h = h xor (h ushr 33)
        h *= -0x49B0D1C09580AB0FL // 0xff51afd7ed558ccd
        h = h xor (h ushr 33)
        h *= -0x51F4C5A33D883A25L // 0xc4ceb9fe1a85ec53
        h = h xor (h ushr 33)
        return h
    }

    private fun getLittleEndianLong(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24) or
                ((data[offset + 4].toLong() and 0xFF) shl 32) or
                ((data[offset + 5].toLong() and 0xFF) shl 40) or
                ((data[offset + 6].toLong() and 0xFF) shl 48) or
                ((data[offset + 7].toLong() and 0xFF) shl 56)
    }
}
