package com.xelth.eckwms_movfast.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math tests for [TripManager.haversineKm] (widened from private to
 * internal for testing). Reference distances computed independently; bands
 * are wide enough to absorb great-circle-vs-actual differences.
 *
 * TripManager is an `object`; its <clinit> only constructs a MutableLiveData
 * (androidx.lifecycle, JVM-constructible — no Looper needed for construction),
 * so referencing TripManager.haversineKm off-device is safe.
 */
class TripManagerTest {

    private val eps = 1e-6

    @Test
    fun `zero distance for identical points`() {
        val d = TripManager.haversineKm(50.1440, 8.5710, 50.1440, 8.5710)
        assertEquals(0.0, d, eps)
    }

    @Test
    fun `known distance Eschborn to Wuppertal is about 159 km`() {
        // Independently computed great-circle distance ~= 159.05 km.
        val d = TripManager.haversineKm(50.1440, 8.5710, 51.2562, 7.1508)
        assertTrue("expected ~159 km, was $d", d in 150.0..170.0)
    }

    @Test
    fun `known distance Frankfurt to Berlin is about 423 km`() {
        // Independently computed great-circle distance ~= 423.53 km.
        val d = TripManager.haversineKm(50.1109, 8.6821, 52.5200, 13.4050)
        assertTrue("expected ~423 km, was $d", d in 410.0..435.0)
    }

    @Test
    fun `haversine is symmetric`() {
        val ab = TripManager.haversineKm(50.1440, 8.5710, 51.2562, 7.1508)
        val ba = TripManager.haversineKm(51.2562, 7.1508, 50.1440, 8.5710)
        assertEquals(ab, ba, eps)
    }

    // ── openTripIsStale (orphaned-open-trip guard, the 44e9ee52 phantom case) ──

    private val min = 60_000L

    @Test
    fun `fresh trip with recent point is not stale`() {
        val now = 1_000_000_000_000L
        assertFalse(TripManager.openTripIsStale(now - 60 * min, now - 2 * min, now))
    }

    @Test
    fun `trip whose last point is days old is stale`() {
        val now = 1_000_000_000_000L
        val fourDays = 4 * 24 * 60 * min
        assertTrue(TripManager.openTripIsStale(now - fourDays, now - fourDays + 87 * min, now))
    }

    @Test
    fun `point-less trip falls back to startedAt`() {
        val now = 1_000_000_000_000L
        // Just started, no points yet (private trip / first seconds) — not stale
        assertFalse(TripManager.openTripIsStale(now - 5 * min, null, now))
        // Started hours ago, never got a point — stale
        assertTrue(TripManager.openTripIsStale(now - 3 * 60 * min, null, now))
    }

    @Test
    fun `exactly at the 30 min cutoff is not yet stale`() {
        val now = 1_000_000_000_000L
        assertFalse(TripManager.openTripIsStale(now - 60 * min, now - 30 * min, now))
        assertTrue(TripManager.openTripIsStale(now - 60 * min, now - 30 * min - 1, now))
    }

    @Test
    fun `stray point older than startedAt does not make a fresh trip stale`() {
        val now = 1_000_000_000_000L
        // lastActivity = max(startedAt, lastPointTs): a leftover point with an
        // old timestamp must not outweigh a trip started 1 min ago.
        assertFalse(TripManager.openTripIsStale(now - 1 * min, now - 5 * 60 * min, now))
    }

    // ── plausibleOdoStop (odometer-photo stop signal) ──────────────────────────

    @Test
    fun `odo stop needs a start reading to validate against`() {
        assertFalse(TripManager.plausibleOdoStop(null, 206_100.0, 140.0))
    }

    @Test
    fun `same or lower reading is not a stop signal`() {
        assertFalse(TripManager.plausibleOdoStop(206_000.0, 206_000.0, 5.0))
        assertFalse(TripManager.plausibleOdoStop(206_000.0, 205_900.0, 5.0))
    }

    @Test
    fun `diff roughly matching the track arms`() {
        // Drove ~140 km by track; odometer says 150 km more — plausible.
        assertTrue(TripManager.plausibleOdoStop(206_000.0, 206_150.0, 140.0))
        // Fused under-counts: track 120, odometer diff 150 — still plausible.
        assertTrue(TripManager.plausibleOdoStop(206_000.0, 206_150.0, 120.0))
    }

    @Test
    fun `diff wildly off the track does not arm`() {
        // Track says ~5 km driven but the reading claims 800 km — typo/misOCR.
        assertFalse(TripManager.plausibleOdoStop(206_000.0, 206_800.0, 5.0))
        // Track says 140 km but the reading claims only 2 km — stale photo.
        assertFalse(TripManager.plausibleOdoStop(206_000.0, 206_002.0, 140.0))
    }

    @Test
    fun `short trips keep absolute slack`() {
        // 1 km track, 3 km odometer diff — inside the +10 km absolute slack.
        assertTrue(TripManager.plausibleOdoStop(206_000.0, 206_003.0, 1.0))
    }

    @Test
    fun `no usable track falls back to sanity bound`() {
        assertTrue(TripManager.plausibleOdoStop(206_000.0, 206_150.0, null))
        assertFalse(TripManager.plausibleOdoStop(206_000.0, 208_500.0, null))
    }

    @Test
    fun `armed end expires after six hours`() {
        val now = 1_000_000_000_000L
        val h = 3_600_000L
        assertFalse(TripManager.armedEndExpired(now - 5 * h, now))
        assertFalse(TripManager.armedEndExpired(now - 6 * h, now))
        assertTrue(TripManager.armedEndExpired(now - 6 * h - 1, now))
    }

    // ── bridgeableGap (battery-death gap bridge, Eschborn→Speyer 2026-07-23) ──

    private val h = 3_600_000L

    @Test
    fun `the Eschborn field case bridges - 90 km appearing after 100 min is a drive`() {
        val died = 1_000_000_000_000L
        assertTrue(TripManager.bridgeableGap(died, died + 100 * min, 90.0))
    }

    @Test
    fun `same parking lot does not bridge`() {
        // Phone rebooted where it died — the plain stale close is correct.
        val died = 1_000_000_000_000L
        assertFalse(TripManager.bridgeableGap(died, died + 45 * min, 0.3))
        assertFalse(TripManager.bridgeableGap(died, died + 45 * min, 1.9))
    }

    @Test
    fun `impossible speed does not bridge`() {
        // 300 km in one hour — no car did that; close honestly instead of lying.
        val died = 1_000_000_000_000L
        assertFalse(TripManager.bridgeableGap(died, died + 1 * h, 300.0))
        // Same displacement over 2.5 h is plausible again (120 km/h avg).
        assertTrue(TripManager.bridgeableGap(died, died + 150 * min, 300.0))
    }

    @Test
    fun `gap longer than six hours is not one continuous journey`() {
        val died = 1_000_000_000_000L
        assertFalse(TripManager.bridgeableGap(died, died + 6 * h + 1, 50.0))
        assertTrue(TripManager.bridgeableGap(died, died + 6 * h, 50.0))
    }

    @Test
    fun `fix from before the gap carries no information`() {
        val died = 1_000_000_000_000L
        assertFalse(TripManager.bridgeableGap(died, died, 50.0))
        assertFalse(TripManager.bridgeableGap(died, died - 10 * min, 50.0))
    }
}
