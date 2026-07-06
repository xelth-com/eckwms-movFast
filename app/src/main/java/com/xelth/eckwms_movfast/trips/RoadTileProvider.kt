package com.xelth.eckwms_movfast.trips

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Corridor road geometry for the Viterbi snap (.eck/TRACK_ESTIMATION.md §4).
 *
 * Roads come from the SAME vector-tile source the app's map already renders
 * (OpenFreeMap / OpenMapTiles schema) — z14 tiles along the smoothed track
 * only: ~60–100 tiles × 30–80 KB per long trip, disk-cached in cacheDir so a
 * re-upload or a habitual route costs no network at all. No country-wide
 * graph, no routing server — the corridor IS known after smoothing.
 *
 * Fails soft everywhere: any fetch/decode problem just yields fewer roads,
 * and RoadMatcher's matched-share threshold decides whether the result is
 * still worth reporting.
 */
object RoadTileProvider {
    private const val TAG = "RoadTileProvider"
    private const val ZOOM = 14
    private const val TILEJSON_URL = "https://tiles.openfreemap.org/planet"
    private const val TILEJSON_TTL_MS = 24 * 3_600_000L
    private const val EDGE_MARGIN_M = 300.0
    private const val MAX_TILES = 240
    private const val FETCH_BUDGET_MS = 30_000L
    private const val UA = "eckwms-movfast/1.0 (fahrtenbuch road-matching)"

    // OpenMapTiles `transportation` classes a car actually drives on.
    private val DRIVABLE = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary",
        "minor", "service", "unclassified", "residential", "road", "busway"
    )

    /** Match a smoothed track against corridor roads. Null = no/poor coverage
     *  or oversized corridor — the caller simply omits the matched layer. */
    fun matchTrack(context: Context, track: List<TrackEstimator.LatLng>): RoadMatcher.MatchResult? =
        matchTrack(File(context.cacheDir, "road_tiles"), track)

    /** Context-free core (JVM-testable end-to-end against the live tile CDN). */
    fun matchTrack(cacheDir: File, track: List<TrackEstimator.LatLng>): RoadMatcher.MatchResult? {
        if (track.size < 2) return null
        val tiles = corridorTiles(track) ?: return null
        val roads = loadRoads(cacheDir, tiles)
        if (roads.isEmpty()) return null
        return RoadMatcher.match(track, roads)
    }

    // ── tile math ─────────────────────────────────────────────────────────────

    private data class Tile(val x: Int, val y: Int)

    private fun tileOf(lat: Double, lng: Double): Pair<Double, Double> {
        val n = (1 shl ZOOM).toDouble()
        val x = (lng + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat)
        val y = (1.0 - asinh(tan(latRad)) / Math.PI) / 2.0 * n
        return x to y
    }

    /** Tiles covering the track, padded where a point sits near a tile edge. */
    private fun corridorTiles(track: List<TrackEstimator.LatLng>): List<Tile>? {
        val n = (1 shl ZOOM).toDouble()
        val out = LinkedHashSet<Tile>()
        for (p in track) {
            val (fx, fy) = tileOf(p.lat, p.lng)
            val tx = floor(fx).toInt()
            val ty = floor(fy).toInt()
            // Tile extent in meters at this latitude (x-width; y is close enough).
            val tileM = 40_075_017.0 * Math.cos(Math.toRadians(p.lat)) / n
            val margin = (EDGE_MARGIN_M / tileM).coerceAtMost(0.49)
            val xs = buildList {
                add(tx)
                if (fx - tx < margin) add(tx - 1)
                if (tx + 1 - fx < margin) add(tx + 1)
            }
            val ys = buildList {
                add(ty)
                if (fy - ty < margin) add(ty - 1)
                if (ty + 1 - fy < margin) add(ty + 1)
            }
            for (x in xs) for (y in ys) out.add(Tile(x, y))
            if (out.size > MAX_TILES) {
                logw("corridor exceeds $MAX_TILES tiles — skipping road match")
                return null
            }
        }
        return out.toList()
    }

    // ── fetch + decode ────────────────────────────────────────────────────────

    /** Current tile URL template from TileJSON (the path embeds a snapshot
     *  date), disk-cached for a day; stale copy beats a failed refresh. */
    private fun tileTemplate(cacheDir: File): String? {
        val f = File(cacheDir, "tilejson.url")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < TILEJSON_TTL_MS
        if (fresh) return f.readText().trim().ifEmpty { null }
        val fetched = try {
            val body = httpGet(TILEJSON_URL) ?: return staleOrNull(f)
            // minimal extraction: "tiles":["<template>"]
            Regex("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(body.decodeToString())
                ?.groupValues?.get(1)
        } catch (e: Exception) {
            logw("tilejson refresh failed: ${e.message}")
            null
        }
        return if (fetched != null) {
            f.writeText(fetched); fetched
        } else staleOrNull(f)
    }

    private fun staleOrNull(f: File): String? =
        if (f.exists()) f.readText().trim().ifEmpty { null } else null

    private fun loadRoads(cacheDir: File, tiles: List<Tile>): List<RoadMatcher.Road> {
        cacheDir.mkdirs()
        val template = tileTemplate(cacheDir) ?: return emptyList()
        val roads = ArrayList<RoadMatcher.Road>()
        val deadline = System.currentTimeMillis() + FETCH_BUDGET_MS
        var fetched = 0; var cached = 0; var failed = 0
        for (t in tiles) {
            val bytes = tileBytes(cacheDir, template, t)?.also {
                if (it.second) fetched++ else cached++
            }?.first
            if (bytes == null) { failed++; continue }
            try {
                decodeRoads(bytes, t, roads)
            } catch (e: Exception) {
                logw("tile ${t.x}/${t.y} decode failed: ${e.message}")
                failed++
            }
            if (System.currentTimeMillis() > deadline) {
                logw("road-tile budget exhausted after ${fetched + cached} tiles")
                break
            }
        }
        logi("corridor roads: ${roads.size} polylines from ${tiles.size} tiles " +
            "($cached cached, $fetched fetched, $failed failed)")
        return roads
    }

    /** Tile bytes from disk cache or network. Second = freshly fetched. */
    private fun tileBytes(cacheDir: File, template: String, t: Tile): Pair<ByteArray, Boolean>? {
        // The template's snapshot segment changes over time — key the cache on
        // it so stale-snapshot tiles age out naturally.
        val snap = template.hashCode().toUInt().toString(16)
        val f = File(cacheDir, "${snap}_${ZOOM}_${t.x}_${t.y}.pbf")
        if (f.exists()) return f.readBytes() to false
        val url = template
            .replace("{z}", ZOOM.toString())
            .replace("{x}", t.x.toString())
            .replace("{y}", t.y.toString())
        val bytes = try { httpGet(url) } catch (e: Exception) {
            logw("tile fetch ${t.x}/${t.y}: ${e.message}"); null
        } ?: return null
        try { f.writeBytes(bytes) } catch (_: Exception) { /* cache is best-effort */ }
        return bytes to true
    }

    private fun decodeRoads(bytes: ByteArray, t: Tile, out: MutableList<RoadMatcher.Road>) {
        val n = (1 shl ZOOM).toDouble()
        for (layer in VectorTileDecoder.decode(bytes)) {
            if (layer.name != "transportation") continue
            val extent = layer.extent.toDouble()
            for (feat in layer.features) {
                if (feat.geomType != 2) continue
                val cls = feat.tags["class"] as? String ?: continue
                if (cls !in DRIVABLE) continue
                for (path in feat.paths) {
                    if (path.size < 2) continue
                    val pts = path.map { xy ->
                        val lng = (t.x + xy[0] / extent) / n * 360.0 - 180.0
                        val lat = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * (t.y + xy[1] / extent) / n))))
                        TrackEstimator.LatLng(lat, lng)
                    }
                    out.add(RoadMatcher.Road(pts, cls))
                }
            }
        }
    }

    // android.util.Log is unavailable on the JVM (unit/integration tests run
    // this provider against the live CDN) — fall back to println there.
    private fun logw(msg: String) = try { Log.w(TAG, msg) } catch (e: RuntimeException) { println("W/$TAG: $msg") }
    private fun logi(msg: String) = try { Log.i(TAG, msg) } catch (e: RuntimeException) { println("I/$TAG: $msg") }

    private fun httpGet(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
