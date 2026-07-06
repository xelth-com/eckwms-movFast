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
}
