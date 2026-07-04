package com.xelth.eckwms_movfast.trips

import org.junit.Assert.assertEquals
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
}
