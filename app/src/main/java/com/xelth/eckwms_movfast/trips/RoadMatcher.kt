package com.xelth.eckwms_movfast.trips

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Road-constrained track matching — phase 2 of .eck/TRACK_ESTIMATION.md.
 *
 * Takes the least-action smoothed track (TrackEstimator output) and snaps it
 * onto road geometry with a Viterbi pass: emission = how far a sample sits
 * from a road, transition = "we drive ALONG one road, switching roads is the
 * exception" (the discrete form of the same action minimization, boundary
 * condition included). No routing graph, no training: candidates come from
 * whatever road polylines the corridor tiles provide.
 *
 * Pure JVM code — the Android side (tile fetch + decode) lives in
 * [RoadTileProvider]; this core is unit-tested on synthetic geometry.
 */
object RoadMatcher {
    const val VERSION = "vit1"

    /** One drivable polyline as it came out of a vector tile. */
    data class Road(val points: List<TrackEstimator.LatLng>, val cls: String)

    /**
     * [path] snapped polyline (with road vertices between same-road samples),
     * [distanceKm] its length — the ROAD distance, [matchedShare] fraction of
     * samples that found a road candidate (quality signal: low share = poor
     * tile coverage, treat the result with suspicion).
     */
    data class MatchResult(
        val path: List<TrackEstimator.LatLng>,
        val distanceKm: Double,
        val matchedShare: Double,
        val version: String = VERSION
    )

    // Candidate search radius around a sample. The smoothed track can sit a
    // few hundred meters off the road on cell-only stretches; beyond ~250 m
    // a candidate is more likely a parallel road than the true one.
    private const val SEARCH_RADIUS_M = 250.0

    // Emission scale: how much off-road distance one "cost unit" buys.
    private const val EMISSION_SIGMA_M = 50.0

    // Transition: mismatch between along-road distance and track distance,
    // and a flat penalty for changing roads (tile borders split one physical
    // road into several polylines, so the penalty must stay mild).
    private const val TRANSITION_SIGMA_M = 30.0
    private const val ROAD_SWITCH_PENALTY = 2.0

    // Below this share of matched samples the corridor coverage was too poor
    // to claim a road distance at all.
    private const val MIN_MATCHED_SHARE = 0.5

    // Resample the track to this spacing before matching: Viterbi states stay
    // bounded (~600 for a 60 km leg) and short jitter does not add states.
    private const val SAMPLE_SPACING_M = 100.0
    private const val MAX_SAMPLES = 800

    private const val MAX_CANDIDATES = 10

    /** Snap [track] onto [roads]. Null when inputs are degenerate or coverage
     *  is below [MIN_MATCHED_SHARE]. */
    fun match(track: List<TrackEstimator.LatLng>, roads: List<Road>): MatchResult? {
        if (track.size < 2 || roads.isEmpty()) return null

        // Shared local ENU frame (meters).
        val lat0 = track.first().lat
        val lng0 = track.first().lng
        val mLat = 111_320.0
        val mLng = 111_320.0 * cos(Math.toRadians(lat0))
        fun xy(p: TrackEstimator.LatLng) = doubleArrayOf((p.lng - lng0) * mLng, (p.lat - lat0) * mLat)
        fun ll(x: Double, y: Double) = TrackEstimator.LatLng(lat0 + y / mLat, lng0 + x / mLng)

        // Roads → segment soup with per-road cumulative length, indexed by a
        // uniform grid (cell = search radius) for O(1) candidate lookups.
        data class Seg(
            val road: Int, val idx: Int,
            val ax: Double, val ay: Double, val bx: Double, val by: Double,
            val cumStart: Double // along-road meters at segment start
        )
        val segs = ArrayList<Seg>()
        val roadXY = roads.map { r -> r.points.map { xy(it) } }
        for ((ri, pts) in roadXY.withIndex()) {
            var cum = 0.0
            for (i in 1 until pts.size) {
                val (ax, ay) = pts[i - 1]
                val (bx, by) = pts[i]
                segs.add(Seg(ri, i - 1, ax, ay, bx, by, cum))
                cum += kotlin.math.hypot(bx - ax, by - ay)
            }
        }
        if (segs.isEmpty()) return null

        val cell = SEARCH_RADIUS_M
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
        for ((si, s) in segs.withIndex()) {
            val minX = (minOf(s.ax, s.bx) / cell).toInt() - 1
            val maxX = (maxOf(s.ax, s.bx) / cell).toInt() + 1
            val minY = (minOf(s.ay, s.by) / cell).toInt() - 1
            val maxY = (maxOf(s.ay, s.by) / cell).toInt() + 1
            for (cx in minX..maxX) for (cy in minY..maxY) {
                grid.getOrPut(key(cx, cy)) { ArrayList() }.add(si)
            }
        }

        // Resample the track at ~SAMPLE_SPACING_M.
        val samples = ArrayList<DoubleArray>()
        var last: DoubleArray? = null
        for (p in track) {
            val q = xy(p)
            if (last == null || kotlin.math.hypot(q[0] - last[0], q[1] - last[1]) >= SAMPLE_SPACING_M) {
                samples.add(q); last = q
            }
        }
        run { // always keep the final position
            val q = xy(track.last())
            if (samples.size < 2 || samples.last() !== q) samples.add(q)
        }
        while (samples.size > MAX_SAMPLES) {
            // halve until bounded (rare: very long trips)
            for (i in samples.size - 2 downTo 1 step 2) samples.removeAt(i)
        }
        val n = samples.size
        if (n < 2) return null

        // Candidate = projection of a sample onto a segment.
        data class Cand(
            val seg: Seg, val t: Double,
            val px: Double, val py: Double,
            val perpM: Double,
            val cum: Double // along-road meters of the projection
        ) {
            val virtual get() = perpM < 0
        }

        fun candidatesFor(q: DoubleArray): List<Cand> {
            val cx = (q[0] / cell).toInt()
            val cy = (q[1] / cell).toInt()
            val seen = HashSet<Int>()
            val out = ArrayList<Cand>()
            for (dx in -1..1) for (dy in -1..1) {
                grid[key(cx + dx, cy + dy)]?.forEach { si ->
                    if (!seen.add(si)) return@forEach
                    val s = segs[si]
                    val vx = s.bx - s.ax; val vy = s.by - s.ay
                    val len2 = vx * vx + vy * vy
                    val t = if (len2 == 0.0) 0.0
                        else (((q[0] - s.ax) * vx + (q[1] - s.ay) * vy) / len2).coerceIn(0.0, 1.0)
                    val px = s.ax + t * vx; val py = s.ay + t * vy
                    val d = kotlin.math.hypot(q[0] - px, q[1] - py)
                    if (d <= SEARCH_RADIUS_M) {
                        out.add(Cand(s, t, px, py, d, s.cumStart + t * sqrt(len2)))
                    }
                }
            }
            // Best candidate per road — many segments of one road would flood
            // the beam with near-duplicates.
            return out.groupBy { it.seg.road }
                .map { (_, cs) -> cs.minByOrNull { it.perpM }!! }
                .sortedBy { it.perpM }
                .take(MAX_CANDIDATES)
        }

        // Viterbi. Samples with no road nearby get a single VIRTUAL candidate
        // at the raw position (perpM = -1): the path passes through unmatched
        // territory instead of breaking, and matchedShare reports the damage.
        var prev: List<Cand> = emptyList()
        var prevCost = DoubleArray(0)
        val back = ArrayList<IntArray>(n)
        val cands = ArrayList<List<Cand>>(n)
        for (i in 0 until n) {
            val q = samples[i]
            var cs = candidatesFor(q)
            if (cs.isEmpty()) {
                cs = listOf(Cand(segs[0], 0.0, q[0], q[1], -1.0, 0.0))
            }
            cands.add(cs)
            val cost = DoubleArray(cs.size)
            val bp = IntArray(cs.size)
            for (j in cs.indices) {
                val c = cs[j]
                val emission = if (c.virtual) 4.0 else sq(c.perpM / EMISSION_SIGMA_M)
                if (i == 0) {
                    cost[j] = emission; bp[j] = -1
                } else {
                    var best = Double.MAX_VALUE
                    var bestK = 0
                    val dObs = kotlin.math.hypot(
                        samples[i][0] - samples[i - 1][0],
                        samples[i][1] - samples[i - 1][1]
                    )
                    for (k in prev.indices) {
                        val p = prev[k]
                        val trans = when {
                            p.virtual || c.virtual ->
                                abs(kotlin.math.hypot(c.px - p.px, c.py - p.py) - dObs) / TRANSITION_SIGMA_M
                            p.seg.road == c.seg.road ->
                                abs(abs(c.cum - p.cum) - dObs) / TRANSITION_SIGMA_M
                            else ->
                                abs(kotlin.math.hypot(c.px - p.px, c.py - p.py) - dObs) / TRANSITION_SIGMA_M +
                                    ROAD_SWITCH_PENALTY
                        }
                        val total = prevCost[k] + trans
                        if (total < best) { best = total; bestK = k }
                    }
                    cost[j] = best + emission; bp[j] = bestK
                }
            }
            back.add(bp)
            prev = cs; prevCost = cost
        }

        // Backtrack the cheapest terminal state.
        var j = prevCost.indices.minByOrNull { prevCost[it] } ?: return null
        val chosen = arrayOfNulls<Cand>(n)
        for (i in n - 1 downTo 0) {
            chosen[i] = cands[i][j]
            j = if (i > 0) back[i][j] else 0
        }

        // Assemble the snapped path: same-road stretches follow the actual
        // road vertices between the two projections; road switches and
        // virtual stretches connect straight.
        val outPath = ArrayList<TrackEstimator.LatLng>(n * 2)
        var dist = 0.0
        fun push(x: Double, y: Double) {
            val p = ll(x, y)
            if (outPath.isNotEmpty()) dist += TrackEstimator.haversineKm(outPath.last(), p)
            outPath.add(p)
        }
        push(chosen[0]!!.px, chosen[0]!!.py)
        for (i in 1 until n) {
            val a = chosen[i - 1]!!
            val b = chosen[i]!!
            if (!a.virtual && !b.virtual && a.seg.road == b.seg.road) {
                val pts = roadXY[a.seg.road]
                if (a.seg.idx < b.seg.idx) {
                    for (v in a.seg.idx + 1..b.seg.idx) push(pts[v][0], pts[v][1])
                } else if (a.seg.idx > b.seg.idx) {
                    for (v in a.seg.idx downTo b.seg.idx + 1) push(pts[v][0], pts[v][1])
                }
            }
            push(b.px, b.py)
        }

        val matched = chosen.count { !it!!.virtual }
        val share = matched.toDouble() / n
        if (share < MIN_MATCHED_SHARE) return null
        return MatchResult(outPath, dist, share)
    }

    private fun sq(v: Double) = v * v
}
