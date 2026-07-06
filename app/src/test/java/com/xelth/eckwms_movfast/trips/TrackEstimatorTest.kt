package com.xelth.eckwms_movfast.trips

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the least-action smoother against the Python prototype run
 * on the REAL 2026-07-06 field tracks (see .eck/TRACK_ESTIMATION.md). The
 * fixture holds the located observations exactly as the device would build
 * them: fused fixes (σ = accuracyM) + OpenCelliD-resolved cells (σ = range).
 *
 * Prototype reference numbers (Python/numpy, sigma_a=2.0, gate=9.0):
 *   eschborn_karlsruhe: 394 obs → 154.4 km, 4 rejected (worst tower m²≈28000)
 *   karlsruhe_speyer:   190 obs →  57.3 km, 6 rejected
 */
class TrackEstimatorTest {

    private fun fixture(name: String): List<TrackEstimator.Obs> {
        val text = javaClass.classLoader!!.getResourceAsStream("track_fixtures.json")!!
            .readBytes().decodeToString()
        val arr = JSONObject(text).getJSONArray(name)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TrackEstimator.Obs(o.getLong("ts"), o.getDouble("lat"), o.getDouble("lng"), o.getDouble("sigma"))
        }
    }

    @Test
    fun eschbornKarlsruhe_matchesPrototype() {
        val res = TrackEstimator.smooth(fixture("eschborn_karlsruhe"))!!
        // Raw chord sum over the same mixed observations is ~1072 km of zigzag;
        // the smoothed length must land on the real A5 road distance (~150 km).
        assertEquals(154.4, res.distanceKm, 2.0)
        // The gate must reject exactly the 4 mislocated towers (Kassel & co).
        assertEquals(4, res.rejectedCount)
        assertEquals(394, res.path.size)
        // Continuity: no 100-km teleports survive in the smoothed polyline.
        val maxStepKm = (1 until res.path.size).maxOf {
            TrackEstimator.haversineKm(res.path[it - 1], res.path[it])
        }
        assertTrue("max step $maxStepKm km", maxStepKm < 3.0)
    }

    @Test
    fun karlsruheSpeyer_matchesPrototype() {
        val res = TrackEstimator.smooth(fixture("karlsruhe_speyer"))!!
        assertEquals(57.3, res.distanceKm, 2.0)
        // Borderline gate decisions (m²≈9) may flip with float order-of-ops —
        // allow ±1 around the prototype's 6.
        assertTrue("rejected=${res.rejectedCount}", res.rejectedCount in 5..7)
        val maxStepKm = (1 until res.path.size).maxOf {
            TrackEstimator.haversineKm(res.path[it - 1], res.path[it])
        }
        assertTrue("max step $maxStepKm km", maxStepKm < 2.0)
    }

    @Test
    fun syntheticOutlier_isRejectedByPhysics() {
        // A straight 60 km/h drive with one 100-km teleport in the middle:
        // the gate must reject it and the length must stay the line length.
        val obs = mutableListOf<TrackEstimator.Obs>()
        var t = 1_000_000L
        for (i in 0 until 60) {
            // ~500 m per 30 s northward = 60 km/h along a meridian
            obs.add(TrackEstimator.Obs(t, 50.0 + i * 0.0045, 8.5, 50.0))
            t += 30_000L
        }
        val clean = TrackEstimator.smooth(obs)!!
        val spiked = obs.toMutableList()
        spiked[30] = spiked[30].copy(lat = 51.0, lng = 9.5, sigmaM = 900.0) // ~130 km off
        val res = TrackEstimator.smooth(spiked)!!
        assertEquals(1, res.rejectedCount)
        assertEquals(clean.distanceKm, res.distanceKm, 0.5)
    }

    @Test
    fun mixedSigmas_gpsDominatesCell() {
        // Alternating 5 m GPS fixes on a line and 1500 m towers offset 1 km east:
        // the smoothed path must hug the GPS line, not the tower centroids.
        val obs = mutableListOf<TrackEstimator.Obs>()
        var t = 1_000_000L
        for (i in 0 until 40) {
            if (i % 2 == 0) obs.add(TrackEstimator.Obs(t, 50.0 + i * 0.002, 8.5, 5.0))
            else obs.add(TrackEstimator.Obs(t, 50.0 + i * 0.002, 8.514, 1500.0)) // ~1 km east
            t += 30_000L
        }
        val res = TrackEstimator.smooth(obs)!!
        // With σ_a=2 m/s² a 30 s gap leaves a ~900 m position prior, so a
        // 1500 m tower legitimately pulls a fraction of its 1 km offset —
        // the path must still stay well inside the tower offset (esp. at the
        // unanchored tail), and on average hug the GPS line.
        val offsets = res.path.map { Math.abs(it.lng - 8.5) * 111_320.0 * Math.cos(Math.toRadians(50.0)) }
        assertTrue("worst lateral offset ${offsets.max()} m", offsets.max() < 500.0)
        assertTrue("mean lateral offset ${offsets.average()} m", offsets.average() < 150.0)
    }

    @Test
    fun degenerateInputs() {
        assertNull(TrackEstimator.smooth(emptyList()))
        assertNull(TrackEstimator.smooth(List(2) { TrackEstimator.Obs(1000L * it, 50.0, 8.5, 10.0) }))
        // Identical timestamps must not divide by zero.
        val same = List(5) { TrackEstimator.Obs(1_000_000L, 50.0 + it * 0.001, 8.5, 10.0) }
        val res = TrackEstimator.smooth(same)
        assertTrue(res != null && res.distanceKm >= 0.0)
    }

    @Test
    fun decimate_keepsEndpointsAndSpacing() {
        val res = TrackEstimator.smooth(fixture("karlsruhe_speyer"))!!
        val dec = TrackEstimator.decimate(res.path, minStepM = 30.0)
        assertTrue(dec.size < res.path.size)
        assertEquals(res.path.first(), dec.first())
        assertEquals(res.path.last(), dec.last())
        // Decimated length stays within 2% of the full smoothed length.
        var d = 0.0
        for (i in 1 until dec.size) d += TrackEstimator.haversineKm(dec[i - 1], dec[i])
        assertEquals(res.distanceKm, d, res.distanceKm * 0.02)
    }
}
