package com.xelth.eckwms_movfast.trips

import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * LIVE end-to-end check of the whole phase-2 pipeline on a REAL field track:
 * fixture observations → least-action smoother → corridor tiles fetched from
 * the actual OpenFreeMap CDN → MVT decode → Viterbi snap → road km.
 *
 * Network-dependent by design, so it only runs when explicitly asked for:
 *   ROAD_MATCH_IT=1 ./gradlew :app:testPaidDebugUnitTest --tests "*RoadMatchIntegrationTest"
 * (tiles land in a temp cache dir; a re-run is offline thanks to the cache).
 */
class RoadMatchIntegrationTest {

    @Test
    fun karlsruheSpeyer_liveTiles_roadDistance() {
        assumeTrue("set ROAD_MATCH_IT=1 to run the live-tile test", System.getenv("ROAD_MATCH_IT") == "1")

        val text = javaClass.classLoader!!.getResourceAsStream("track_fixtures.json")!!
            .readBytes().decodeToString()
        val arr = JSONObject(text).getJSONArray("karlsruhe_speyer")
        val obs = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TrackEstimator.Obs(o.getLong("ts"), o.getDouble("lat"), o.getDouble("lng"), o.getDouble("sigma"))
        }
        val smoothed = TrackEstimator.smooth(obs)!!

        val cache = File(System.getProperty("java.io.tmpdir"), "eck_road_tiles_it").apply { mkdirs() }
        val t0 = System.currentTimeMillis()
        val matched = RoadTileProvider.matchTrack(cache, smoothed.path)
        val ms = System.currentTimeMillis() - t0

        assertNotNull("road match returned null (tile fetch failed?)", matched)
        matched!!
        println(
            "ROAD MATCH: smoothed=${"%.1f".format(smoothed.distanceKm)} km → " +
                "road=${"%.1f".format(matched.distanceKm)} km, share=${"%.2f".format(matched.matchedShare)}, " +
                "path=${matched.path.size} pts, took ${ms} ms"
        )
        // The drive was ~57 km smoothed; the road distance must be in the same
        // ballpark (roads are never SHORTER than the great-circle chain by much,
        // and the A5/B9 corridor adds a little length).
        assertTrue("road km ${matched.distanceKm}", matched.distanceKm in 50.0..70.0)
        assertTrue("share ${matched.matchedShare}", matched.matchedShare > 0.8)
    }
}
