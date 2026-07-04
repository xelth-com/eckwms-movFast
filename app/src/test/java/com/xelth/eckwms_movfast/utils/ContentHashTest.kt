package com.xelth.eckwms_movfast.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Unit tests for [ContentHash] (MurmurHash3 x64_128 → deterministic CAS UUID).
 *
 * The most important property under test is CROSS-PLATFORM AGREEMENT with the Rust
 * server. The golden vectors below are copied verbatim from the Rust reference test
 * `content_hash_uuid` in the 9eck monorepo (core/src/utils/filestore.rs,
 * `test_content_hash_uuid_vectors`). If either side changes its hashing, one of these
 * assertions fails — that is exactly the guardrail we want.
 */
class ContentHashTest {

    private val uuidRegex =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    // ---- Cross-platform golden vectors (must match Rust content_hash_uuid) ----

    // These vectors caught a REAL cross-platform bug (fixed 2026-07-03):
    // ContentHash.fmix64 had two miscomputed finalization multipliers — the Kotlin
    // literals -0x49B0D1C09580AB0FL / -0x51F4C5A33D883A25L decoded to
    // 0xb64f2e3f6a7f54f1 / 0xae0b3a5cc277c5db instead of the canonical MurmurHash3
    // 0xff51afd7ed558ccd / 0xc4ceb9fe1a85ec53 their own comments claimed. That made
    // the Android CAS UUID diverge from the server's for ALL non-empty content
    // (empty passed only because fmix(0)=0), silently breaking cross-platform dedup.
    // Now that the constants are corrected (via 0x…UL.toLong()), these assert the
    // fix holds and guard against a regression on either platform.
    @Test
    fun goldenVector_test() {
        assertEquals(
            "ac7d28cc-74bd-e19d-9a12-8231f9bd4d82",
            ContentHash.uuidFromBytes("test".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun goldenVector_hello() {
        assertEquals(
            "cbd8a7b3-41bd-9b02-5b1e-906a48ae1d19",
            ContentHash.uuidFromBytes("hello".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun goldenVector_empty() {
        // Empty input finalizes to h1=h2=0 → the nil UUID. Verified against the Rust
        // reference vector, so this value is a real cross-platform contract, not a guess.
        assertEquals(
            "00000000-0000-0000-0000-000000000000",
            ContentHash.uuidFromBytes(ByteArray(0)),
        )
    }

    // ---- Determinism ----

    @Test
    fun sameBytes_produceSameUuid() {
        val a = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val b = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        assertEquals(ContentHash.uuidFromBytes(a), ContentHash.uuidFromBytes(b))
    }

    @Test
    fun differentBytes_produceDifferentUuid() {
        val a = "content-A".toByteArray(Charsets.UTF_8)
        val b = "content-B".toByteArray(Charsets.UTF_8)
        assertNotEquals(ContentHash.uuidFromBytes(a), ContentHash.uuidFromBytes(b))
    }

    @Test
    fun singleBitDifference_produceDifferentUuid() {
        val a = byteArrayOf(0x00)
        val b = byteArrayOf(0x01)
        assertNotEquals(ContentHash.uuidFromBytes(a), ContentHash.uuidFromBytes(b))
    }

    // ---- UUID format / tail-branch coverage ----

    @Test
    fun uuidFormat_isWellFormedAcrossAllTailBranches() {
        // Lengths 0..17 cover every tail branch (len & 15 == 0..15), the empty case,
        // a single 16-byte block, and one block + 1-byte tail. 1000 bytes exercises the
        // multi-block body loop (62 blocks) plus an 8-byte tail.
        val lengths = (0..17).toList() + listOf(1000)
        for (n in lengths) {
            val data = ByteArray(n) { (it and 0xFF).toByte() }
            val uuid = ContentHash.uuidFromBytes(data)
            assertNotNull("uuid for len=$n should be non-null", uuid)
            assertTrue(
                "uuid for len=$n not well-formed: $uuid",
                uuidRegex.matches(uuid),
            )
        }
    }

    @Test
    fun uuidFormat_matchesForSpecificRequestedSizes() {
        // Explicitly the sizes called out by the contract: empty, 1, 15, 16, 17, 1000.
        for (n in listOf(0, 1, 15, 16, 17, 1000)) {
            val data = ByteArray(n) { (it and 0xFF).toByte() }
            val uuid = ContentHash.uuidFromBytes(data)
            assertTrue("len=$n → $uuid", uuidRegex.matches(uuid))
        }
    }

    // ---- Stability of specific inputs ----

    @Test
    fun emptyInput_isStableAndWellFormed() {
        val first = ContentHash.uuidFromBytes(ByteArray(0))
        val second = ContentHash.uuidFromBytes(ByteArray(0))
        assertNotNull(first)
        assertEquals("empty-input hash must be stable across calls", first, second)
        assertTrue("empty-input hash must be well-formed: $first", uuidRegex.matches(first))
    }

    @Test
    fun asciiBytes_areStableAndWellFormed() {
        val bytes = "hello".toByteArray(Charsets.UTF_8)
        val first = ContentHash.uuidFromBytes(bytes)
        val second = ContentHash.uuidFromBytes(bytes)
        assertEquals(first, second)
        assertTrue(uuidRegex.matches(first))
    }
}
