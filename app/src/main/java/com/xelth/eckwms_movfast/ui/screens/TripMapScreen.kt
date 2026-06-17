package com.xelth.eckwms_movfast.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/**
 * Trip map (Fahrtenbuch). One Leaflet/WebView surface serving two jobs:
 *  - PAST: the recorded track of the most recent Fahrt as a polyline (later:
 *    slice trips out of it for retroactive classification — see
 *    .eck/TRIP_PURPOSE.md §9; the JS already reports track taps via AndroidMap).
 *  - FUTURE: today's planned destinations as numbered markers (1, 2, 3 …) for
 *    trip planning.
 *
 * Read-only foundation for now. Map HTML/JS lives in assets/trip_map.html.
 */
@Composable
fun TripMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var trackJson by remember { mutableStateOf<String?>(null) }
    var stopsJson by remember { mutableStateOf<String?>(null) }

    // Load both layers once (off the main thread).
    LaunchedEffect(Unit) {
        val data = withContext(Dispatchers.IO) { loadMapData(context) }
        trackJson = data.first
        stopsJson = data.second
    }

    // Push to Leaflet as soon as the page is ready AND the data has loaded.
    LaunchedEffect(pageReady, trackJson, stopsJson, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        trackJson?.let { wv.evaluateJavascript("setTrack($it)", null) }
        stopsJson?.let { wv.evaluateJavascript("setStops($it)", null) }
    }

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
        AndroidView(
            factory = { ctx ->
                @SuppressLint("SetJavaScriptEnabled")
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = true
                        }
                    }
                    addJavascriptInterface(MapBridge(), "AndroidMap")
                    loadUrl("file:///android_asset/trip_map.html")
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** JS → Kotlin bridge. Taps are wired now; acting on them (slicing a trip /
 *  opening a stop) lands with the §9 reverse-Fahrtenbuch step. */
private class MapBridge {
    @JavascriptInterface
    fun onReady() { /* page also signals via onPageFinished */ }

    @JavascriptInterface
    fun onTrackTap(lat: Double, lng: Double) {
        Log.d("TripMap", "track tap @ $lat,$lng (slice point — TODO §9)")
    }

    @JavascriptInterface
    fun onStopTap(ref: String) {
        Log.d("TripMap", "stop tap: $ref")
    }
}

/** Build the two JSON payloads for the map: the recent track polyline and the
 *  numbered planned stops. Returns valid JS array literals. */
private suspend fun loadMapData(context: Context): Pair<String, String> {
    val db = AppDatabase.getInstance(context)

    // Track = the most recent trip's points, resolved to coordinates. Fused
    // points carry lat/lng directly; cell points resolve via the on-device
    // tower cache (same path as TripManager.buildUploadJson).
    val trackArr = JSONArray()
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
                trackArr.put(JSONArray().put(lat).put(lng))
            }
        }
    }

    // Planned stops = Level-A purpose candidates that have coordinates, numbered
    // in the order the server returns them (nearest-first when a position is
    // known; due-date otherwise). True shortest-tour ordering is the open
    // "Routenplaner" item.
    val stopsArr = JSONArray()
    val candidates = try { ScanApiService(context).fetchPurposeCandidates() } catch (e: Exception) { null }
    var n = 1
    candidates?.forEach { c ->
        val lat = c.lat
        val lng = c.lng
        if (lat != null && lng != null) {
            stopsArr.put(JSONObject().apply {
                put("n", n++)
                put("lat", lat)
                put("lng", lng)
                put("label", c.label)
                put("address", c.address ?: JSONObject.NULL)
                put("overdue", c.overdue)
                put("ref", c.purposeRef)
            })
        }
    }

    return Pair(trackArr.toString(), stopsArr.toString())
}
