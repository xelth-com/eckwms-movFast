package com.xelth.eckwms_movfast.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * Synthetic-geometry tests for the Viterbi road snap. Real-tile behaviour is
 * exercised in the field (RoadTileProvider is a thin fetch/decode shim); the
 * matching PHYSICS — hug one road, don't flip to parallels, follow turns,
 * refuse to invent distance where there is no coverage — is what lives here.
 */
class RoadMatcherTest {

    private val mPerDegLat = 111_320.0
    private fun mPerDegLng(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))

    private fun road(vararg pts: Pair<Double, Double>, cls: String = "primary") =
        RoadMatcher.Road(pts.map { TrackEstimator.LatLng(it.first, it.second) }, cls)

    /** Straight south→north road at lng 8.5, 50.00..50.10 (~11.1 km). */
    private fun straightRoad() = road(50.00 to 8.5, 50.10 to 8.5)

    /** Noisy track along that road, [offM] meters of alternating lateral noise. */
    private fun noisyTrack(offM: Double, n: Int = 60, lngCenter: Double = 8.5): List<TrackEstimator.LatLng> =
        (0 until n).map { i ->
            val lat = 50.0 + 0.10 * i / (n - 1)
            val off = (if (i % 2 == 0) offM else -offM) / mPerDegLng(lat)
            TrackEstimator.LatLng(lat, lngCenter + off)
        }

    @Test
    fun straightRoad_snapsAndMeasuresRoadDistance() {
        val res = RoadMatcher.match(noisyTrack(80.0), listOf(straightRoad()))!!
        assertEquals(1.0, res.matchedShare, 1e-9)
        // Road distance of the traveled stretch is 11.13 km; the zigzag chord
        // sum of the raw track is noticeably longer — the snap must recover
        // the road length, not the zigzag.
        assertEquals(11.13, res.distanceKm, 0.3)
        // Every snapped point sits ON the road line (lng 8.5).
        val worstOffM = res.path.maxOf { Math.abs(it.lng - 8.5) * mPerDegLng(it.lat) }
        assertTrue("worst off-road $worstOffM m", worstOffM < 1.0)
    }

    @Test
    fun parallelRoads_noFlipFlop() {
        val roadA = straightRoad()
        val roadB = road(50.00 to 8.5028, 50.10 to 8.5028) // ~200 m east
        // Track noise is ±60 m around road A: single closest-point snapping
        // would occasionally prefer B; the transition cost must keep us on A.
        val res = RoadMatcher.match(noisyTrack(60.0), listOf(roadA, roadB))!!
        val onA = res.path.count { Math.abs(it.lng - 8.5) * mPerDegLng(it.lat) < 60.0 }
        assertTrue("only $onA/${res.path.size} points on road A", onA.toDouble() / res.path.size > 0.95)
    }

    @Test
    fun lTurn_followsBothLegs() {
        val legA = road(50.00 to 8.5, 50.05 to 8.5)          // ~5.57 km north
        val legB = road(50.05 to 8.5, 50.05 to 8.58)         // ~5.72 km east
        val track = ArrayList<TrackEstimator.LatLng>()
        for (i in 0..30) track.add(TrackEstimator.LatLng(50.0 + 0.05 * i / 30, 8.5))
        for (i in 1..30) track.add(TrackEstimator.LatLng(50.05, 8.5 + 0.08 * i / 30))
        val res = RoadMatcher.match(track, listOf(legA, legB))!!
        val expected = 0.05 * mPerDegLat / 1000 + 0.08 * mPerDegLng(50.05) / 1000
        assertEquals(expected, res.distanceKm, expected * 0.05)
        assertEquals(1.0, res.matchedShare, 1e-9)
    }

    @Test
    fun partialCoverage_reportsShare_andPoorCoverageRefuses() {
        // Roads exist only for the first 70% of the drive.
        val partial = road(50.00 to 8.5, 50.07 to 8.5)
        val res = RoadMatcher.match(noisyTrack(50.0), listOf(partial))!!
        assertTrue("share ${res.matchedShare}", res.matchedShare in 0.6..0.85)
        // No roads at all → refuse rather than invent.
        assertNull(RoadMatcher.match(noisyTrack(50.0), emptyList()))
        // Roads far away (>250 m from every sample) → share 0 → null.
        val faraway = road(50.00 to 8.53, 50.10 to 8.53) // ~2 km east
        assertNull(RoadMatcher.match(noisyTrack(50.0), listOf(faraway)))
    }

    @Test
    fun degenerateInputs() {
        assertNull(RoadMatcher.match(emptyList(), listOf(straightRoad())))
        assertNull(RoadMatcher.match(listOf(TrackEstimator.LatLng(50.0, 8.5)), listOf(straightRoad())))
        // Road with a single point (no segments) → null, no crash.
        assertNull(
            RoadMatcher.match(
                noisyTrack(50.0),
                listOf(RoadMatcher.Road(listOf(TrackEstimator.LatLng(50.0, 8.5)), "primary"))
            )
        )
    }
}
