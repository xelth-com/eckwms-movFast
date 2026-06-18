package com.xelth.eckwms_movfast.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.geojson.FeatureCollection
import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Trip map (Fahrtenbuch) — native MapLibre GPU map (no WebView). One surface,
 * two jobs:
 *  - PAST: the recorded track of the most recent Fahrt as a line (later: slice
 *    trips out of it for retroactive classification — TRIP_PURPOSE.md §9).
 *  - FUTURE: today's planned destinations as numbered markers (1, 2, 3 …).
 *
 * Style/tiles from OpenFreeMap (vector, no API key). Read-only foundation.
 */
private const val STYLE_BRIGHT = "https://tiles.openfreemap.org/styles/bright"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

/** Reusable MapLibre trip map (recorded track + numbered planned stops). Used
 *  full-screen by [TripMapScreen] and as the dark background of trip mode's
 *  console. `dark = true` selects the dark OpenFreeMap style (matches the
 *  desktop dashboard). */
@SuppressLint("MissingPermission")
@Composable
fun TripMapView(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    // Live mode: show the device's CURRENT position (blue puck) and keep the
    // camera on it, instead of framing the last recorded track. Used by the trip
    // console on entry. The full history screen leaves this false → fits the track.
    liveLocation: Boolean = false,
) {
    val context = LocalContext.current
    val styleUrl = if (dark) STYLE_DARK else STYLE_BRIGHT
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var data by remember { mutableStateOf<MapData?>(null) }
    var mapRef by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf<Style?>(null) }

    // MapView has its own lifecycle; drive it from the composition's presence.
    DisposableEffect(Unit) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Load trip data in the background — must NOT gate the base map appearing.
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.IO) { loadMapData(context) }
    }

    // Show the base map ASAP (independent of the data load). Also relocate the
    // logo + attribution to the top band (into the status-bar gap).
    LaunchedEffect(styleUrl) {
        mapView.getMapAsync { map ->
            mapRef = map
            map.uiSettings.apply {
                logoGravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                attributionGravity = android.view.Gravity.TOP or android.view.Gravity.END
                setLogoMargins(0, 8, 0, 0)
                setAttributionMargins(0, 0, 8, 0)
            }
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                styleReady = style
                // Live current-position puck (blue dot) — only when requested AND
                // location permission is granted. Camera tracks the user.
                if (liveLocation &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        val lc = map.locationComponent
                        lc.activateLocationComponent(
                            LocationComponentActivationOptions.builder(context, style)
                                .useDefaultLocationEngine(true)
                                .build()
                        )
                        lc.isLocationComponentEnabled = true
                        lc.cameraMode = CameraMode.TRACKING
                        lc.renderMode = RenderMode.COMPASS
                        // Zoom to a street-level scale (default is world view, which
                        // shows the puck against Africa/America). zoomWhileTracking
                        // applies once a fix arrives; zoomTo sets it immediately.
                        map.moveCamera(CameraUpdateFactory.zoomTo(15.0))
                        lc.zoomWhileTracking(15.0)
                    } catch (e: Exception) {
                        android.util.Log.w("TripMapView", "location component failed: ${e.message}")
                    }
                }
            }
        }
    }

    // Add the track + stop layers once BOTH the style and the data are ready.
    LaunchedEffect(data, styleReady) {
        val d = data ?: return@LaunchedEffect
        val style = styleReady ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (style.getSource("track-src") == null) {
            style.addSource(GeoJsonSource("track-src", FeatureCollection.fromJson(d.trackGeoJson)))
            style.addLayer(
                LineLayer("track-line", "track-src").withProperties(
                    PropertyFactory.lineColor("#1769aa"),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
            style.addSource(GeoJsonSource("stops-src", FeatureCollection.fromJson(d.stopsGeoJson)))
            style.addLayer(
                CircleLayer("stops-circle", "stops-src").withProperties(
                    PropertyFactory.circleRadius(13f),
                    PropertyFactory.circleColor(
                        Expression.switchCase(
                            Expression.get("overdue"), Expression.literal("#c62828"),
                            Expression.literal("#1769aa")
                        )
                    ),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2f)
                )
            )
            style.addLayer(
                SymbolLayer("stops-num", "stops-src").withProperties(
                    PropertyFactory.textField(Expression.get("num")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(13f),
                    PropertyFactory.textColor("#ffffff"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true)
                )
            )
        }

        // Frame the recorded track ONLY when not in live mode. In live mode the
        // LocationComponent owns the camera (it follows the user) — framing the old
        // track here would yank the view back to where the last trip ended.
        if (!liveLocation) {
            when {
                d.points.size >= 2 -> {
                    val b = LatLngBounds.Builder().apply { d.points.forEach { include(it) } }.build()
                    try { map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 80)) }
                    catch (e: Exception) { map.moveCamera(CameraUpdateFactory.newLatLngZoom(d.points.first(), 11.0)) }
                }
                d.points.size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(d.points.first(), 13.0))
                else -> { /* leave the default world view */ }
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Full-screen trip map with a header (the 🗺 entry from the Fahrten screen). */
@Composable
fun TripMapScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1769AA))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ Zurück", color = Color.White) }
            Text(
                "🗺  Streckenhistorie & Planung",
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        TripMapView(modifier = Modifier.fillMaxSize())
    }
}

private data class MapData(
    val trackGeoJson: String,
    val stopsGeoJson: String,
    val points: List<LatLng>
)

/** Build the GeoJSON for the track (LineString) and the numbered stops (Points),
 *  plus the flat point list used to frame the camera. */
private suspend fun loadMapData(context: Context): MapData {
    val db = AppDatabase.getInstance(context)
    val all = mutableListOf<LatLng>()

    // Track = the most recent trip's points, resolved to coordinates. Fused
    // points carry lat/lng directly; cell points resolve via the on-device
    // tower cache (same path as TripManager.buildUploadJson). GeoJSON is [lng,lat].
    val trackCoords = JSONArray()
    val trip = db.tripDao().observeTrips(1).first().firstOrNull()
    if (trip != null) {
        val cellDao = db.cellTowerDao()
        for (p in db.tripDao().getPoints(trip.id)) {
            var lat = p.lat
            var lng = p.lng
            if ((lat == null || lng == null) && p.source == "cell" && p.cid != null && p.mcc != null) {
                val tower = cellDao.get("${p.mcc}-${p.mnc}-${p.tac}-${p.cid}")
                if (tower != null) { lat = tower.lat; lng = tower.lng }
            }
            if (lat != null && lng != null) {
                trackCoords.put(JSONArray().put(lng).put(lat))
                all.add(LatLng(lat, lng))
            }
        }
    }
    val trackGeoJson = JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", JSONArray().apply {
            if (trackCoords.length() >= 2) {
                put(JSONObject().apply {
                    put("type", "Feature")
                    put("properties", JSONObject())
                    put("geometry", JSONObject().apply {
                        put("type", "LineString")
                        put("coordinates", trackCoords)
                    })
                })
            }
        })
    }.toString()

    // Planned stops = Level-A purpose candidates with coordinates, numbered in
    // the order the server returns them (nearest-first with a position, else
    // due-date). True shortest-tour ordering is the open "Routenplaner" item.
    val features = JSONArray()
    val candidates = try { ScanApiService(context).fetchPurposeCandidates() } catch (e: Exception) { null }
    var n = 1
    candidates?.forEach { c ->
        val lat = c.lat
        val lng = c.lng
        if (lat != null && lng != null) {
            features.put(JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("num", (n).toString())
                    put("label", c.label)
                    put("overdue", c.overdue)
                })
                put("geometry", JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", JSONArray().put(lng).put(lat))
                })
            })
            all.add(LatLng(lat, lng))
            n++
        }
    }
    val stopsGeoJson = JSONObject().apply {
        put("type", "FeatureCollection")
        put("features", features)
    }.toString()

    return MapData(trackGeoJson, stopsGeoJson, all)
}
