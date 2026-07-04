package com.xelth.eckwms_movfast.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [SettingsManager]. These functions
 * (computeMeshId / orderedEckNodes / parsePhotoFilename) take arguments and
 * return values without touching Context or SharedPreferences, so they are
 * callable in a plain JVM test. SettingsManager's <clinit> only builds a few
 * String lists (ECK_DEFAULT_NODES etc.) — no Android APIs run at class-init,
 * and `prefs`/`appContext` are `lateinit` (no eager initializer), so merely
 * referencing the object does not crash off-device.
 */
class SettingsManagerTest {

    // ── computeMeshId ───────────────────────────────────────────────────
    // Cross-platform contract with the Rust server (owner decision 2026-07-04:
    // the dashed UUID is canonical): mesh_id = first 16 bytes of SHA256(key)
    // formatted as a dashed lowercase UUID, exactly what Rust compute_mesh_id
    // (core/src/utils/identity.rs, Uuid::from_bytes(sha256[..16])) returns.
    // Golden vectors = SHA256 of well-known inputs, first 16 bytes, dashed.
    // (Pre-2026-07 Kotlin emitted sha256[..8] as 16 undashed hex — a real
    // divergence these vectors would have caught, now fixed.)

    @Test
    fun `computeMeshId is deterministic for the same key`() {
        val a = SettingsManager.computeMeshId("sync-network-key")
        val b = SettingsManager.computeMeshId("sync-network-key")
        assertEquals(a, b)
    }

    @Test
    fun `computeMeshId matches Rust dashed-UUID golden vectors`() {
        assertEquals("e3b0c442-98fc-1c14-9afb-f4c8996fb924", SettingsManager.computeMeshId(""))
        assertEquals("ba7816bf-8f01-cfea-4141-40de5dae2223", SettingsManager.computeMeshId("abc"))
        assertEquals("2cf24dba-5fb0-a30e-26e8-3b2ac5b9e29e", SettingsManager.computeMeshId("hello"))
    }

    @Test
    fun `computeMeshId is a well-formed lowercase dashed UUID`() {
        val id = SettingsManager.computeMeshId("some-arbitrary-key")
        assertTrue(
            "was: $id",
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id)
        )
    }

    @Test
    fun `computeMeshId differs for different keys`() {
        assertNotEquals(
            SettingsManager.computeMeshId("key-a"),
            SettingsManager.computeMeshId("key-b")
        )
    }

    // ── orderedEckNodes ─────────────────────────────────────────────────
    // Mirrors Rust compute_primary_index = u32 BE of sha256(mesh)[..4] % 3,
    // then rotate the host-sorted default node list. Golden first-elements
    // computed independently (SHA256 first 4 bytes % 3):
    //   "abc"   -> primary 0 -> eck1
    //   "alpha" -> primary 1 -> eck2
    //   ""      -> primary 2 -> eck3

    @Test
    fun `orderedEckNodes returns a permutation of the three defaults`() {
        val ordered = SettingsManager.orderedEckNodes("any-mesh")
        assertEquals(3, ordered.size)
        assertEquals(SettingsManager.ECK_DEFAULT_NODES.toSet(), ordered.toSet())
    }

    @Test
    fun `orderedEckNodes is deterministic per meshId`() {
        assertEquals(
            SettingsManager.orderedEckNodes("mesh-xyz"),
            SettingsManager.orderedEckNodes("mesh-xyz")
        )
    }

    @Test
    fun `orderedEckNodes first element matches golden primary index`() {
        assertEquals("http://eck1.com:3201", SettingsManager.orderedEckNodes("abc").first())
        assertEquals("http://eck2.com:3202", SettingsManager.orderedEckNodes("alpha").first())
        assertEquals("http://eck3.com:3203", SettingsManager.orderedEckNodes("").first())
    }

    @Test
    fun `orderedEckNodes rotates host-sorted list from the primary`() {
        // "alpha" -> primary 1 -> [eck2, eck3, eck1]
        assertEquals(
            listOf(
                "http://eck2.com:3202",
                "http://eck3.com:3203",
                "http://eck1.com:3201"
            ),
            SettingsManager.orderedEckNodes("alpha")
        )
    }

    @Test
    fun `different meshIds can produce different first elements`() {
        val first1 = SettingsManager.orderedEckNodes("abc").first()
        val first2 = SettingsManager.orderedEckNodes("alpha").first()
        assertNotEquals(first1, first2)
    }

    // ── parsePhotoFilename (round-trip) ─────────────────────────────────
    // buildPhotoFilename is private and uses System.currentTimeMillis(), so we
    // reconstruct a valid filename here with the SAME crc16 algorithm and a
    // fixed timestamp, then assert parsePhotoFilename recovers (id, timestamp).

    /** Byte-for-byte copy of SettingsManager.crc16 (CRC16, poly 0xA001). */
    private fun crc16(data: String): String {
        var crc = 0xFFFF
        for (byte in data.toByteArray(Charsets.UTF_8)) {
            crc = crc xor (byte.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return String.format("%04X", crc and 0xFFFF)
    }

    /** Reconstructs buildPhotoFilename for an alphanumeric (sanitize-neutral) id. */
    private fun buildFilename(internalId: String, timestamp: Long): String {
        val base = "${internalId}__$timestamp"
        return "${base}_${crc16(base)}.webp"
    }

    @Test
    fun `parsePhotoFilename round-trips a built filename`() {
        val id = "i12345"
        val ts = 1700000000000L
        val filename = buildFilename(id, ts)

        val parsed = SettingsManager.parsePhotoFilename(filename)

        assertEquals(id, parsed!!.first)
        assertEquals(ts, parsed.second)
    }

    @Test
    fun `parsePhotoFilename round-trips a box id`() {
        val id = "b0007"
        val ts = 1699999999999L
        val parsed = SettingsManager.parsePhotoFilename(buildFilename(id, ts))
        assertEquals(id, parsed!!.first)
        assertEquals(ts, parsed.second)
    }

    // Malformed inputs that return null WITHOUT hitting the checksum-mismatch
    // branch (which logs via android.util.Log and would need Robolectric).
    @Test
    fun `parsePhotoFilename returns null without double-underscore separator`() {
        assertNull(SettingsManager.parsePhotoFilename("noseparator.webp"))
    }

    @Test
    fun `parsePhotoFilename returns null with non-numeric timestamp`() {
        assertNull(SettingsManager.parsePhotoFilename("id__notanumber_ABCD.webp"))
    }

    @Test
    fun `parsePhotoFilename returns null when suffix lacks checksum part`() {
        assertNull(SettingsManager.parsePhotoFilename("id__1700000000000.webp"))
    }

    @Test
    fun `parsePhotoFilename returns null with too many suffix parts`() {
        assertNull(SettingsManager.parsePhotoFilename("id__123_456_789.webp"))
    }
}
