// app/src/main/java/com/xelth/eckwms_movfast/ui/screens/UserPhotosScreen.kt
package com.xelth.eckwms_movfast.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.api.ScanResult
import com.xelth.eckwms_movfast.ui.viewmodels.UserManager
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale

/** One user-parked (temp) photo row from GET /api/files/attachments. */
private data class TempPhoto(
    val casUuid: String,
    val name: String,
    val sizeBytes: Long,
    val attachedAt: String,
    val label: String
)

/**
 * Einstellungen → "Meine Fotos": photos that could not be attached to anything
 * (no order / no serial at upload time) and were parked on the logged-in user
 * with a one-week TTL. From here they can be deleted for good or redirected
 * onto the open repair order of a scanned/entered serial. Whatever is left
 * untouched is swept by the server after the week.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPhotosScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { ScanApiService(context) }
    val user = UserManager.currentUser.value

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf<List<TempPhoto>>(emptyList()) }
    val thumbs = remember { mutableStateMapOf<String, Bitmap>() }
    var redirectTarget by remember { mutableStateOf<TempPhoto?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun reload() {
        val uid = user?.id ?: run {
            error = "Nicht angemeldet — Fotos werden pro Benutzer geführt."
            loading = false
            return
        }
        loading = true
        error = null
        scope.launch {
            when (val res = api.listAttachments("user", uid)) {
                is ScanResult.Success -> {
                    try {
                        val arr = JSONArray(res.data)
                        val list = ArrayList<TempPhoto>(arr.length())
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            // Only temp parks — a user could in principle have
                            // real (non-temp) attachments too.
                            if (o.optString("label") != "temp") continue
                            list.add(
                                TempPhoto(
                                    casUuid = o.optString("cas_uuid"),
                                    name = o.optString("original_name", "photo"),
                                    sizeBytes = o.optLong("size_bytes", 0),
                                    attachedAt = o.optString("attached_at", ""),
                                    label = o.optString("label", "")
                                )
                            )
                        }
                        photos = list
                        // Thumbnails lazily, sequential — avatar payloads are tiny.
                        list.forEach { p ->
                            if (!thumbs.containsKey(p.casUuid)) {
                                api.fetchFileBytes(p.casUuid)?.let { bytes ->
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        ?.let { thumbs[p.casUuid] = it }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        error = "Antwort unlesbar: ${e.message}"
                    }
                }
                is ScanResult.Error -> error = res.message
                else -> {}
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meine Fotos (unzugeordnet)") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Zurück") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Text(
                "Fotos ohne Auftrag/Seriennummer landen hier beim Benutzer " +
                    "${user?.getDisplayLabel() ?: "?"} und werden nach 1 Woche " +
                    "automatisch gelöscht. Umleiten bindet das Foto an den offenen " +
                    "Reparaturauftrag einer Seriennummer.",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(8.dp))
            status?.let {
                Text(it, fontSize = 13.sp, color = Color(0xFF2E7D32))
                Spacer(Modifier.height(4.dp))
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Keine unzugeordneten Fotos 🎉", color = Color.Gray)
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos, key = { it.casUuid }) { p ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val bmp = thumbs[p.casUuid]
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.LightGray.copy(alpha = 0.4f)),
                                        contentAlignment = Alignment.Center
                                    ) { Text("📷", fontSize = 24.sp) }
                                }
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        p.attachedAt.take(16).replace('T', ' '),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "${p.sizeBytes / 1024} KB · ${p.casUuid.take(8)}…",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                                IconButton(onClick = { redirectTarget = p }) {
                                    Text("↪", fontSize = 22.sp)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        when (val r = api.deleteTempPhoto(p.casUuid)) {
                                            is ScanResult.Success -> {
                                                status = "Foto gelöscht"
                                                photos = photos.filterNot { it.casUuid == p.casUuid }
                                            }
                                            is ScanResult.Error -> status = "Löschen fehlgeschlagen: ${r.message}"
                                            else -> {}
                                        }
                                    }
                                }) {
                                    Text("🗑", fontSize = 20.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Redirect dialog: enter (or hardware-scan into the field) the serial.
    redirectTarget?.let { target ->
        var serial by remember(target.casUuid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { redirectTarget = null },
            title = { Text("Foto umleiten") },
            text = {
                Column {
                    Text(
                        "Seriennummer des Geräts — das Foto wird an dessen offenen " +
                            "Reparaturauftrag angehängt.",
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serial,
                        onValueChange = { serial = it.trim() },
                        label = { Text("Seriennummer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = serial.isNotBlank(),
                    onClick = {
                        val cas = target.casUuid
                        redirectTarget = null
                        scope.launch {
                            when (val r = api.redirectTempPhoto(cas, serial)) {
                                is ScanResult.Success -> {
                                    val on = try {
                                        org.json.JSONObject(r.data).optString("order_number")
                                    } catch (e: Exception) { "" }
                                    status = "Umgeleitet${if (on.isNotEmpty()) " → $on" else ""}"
                                    photos = photos.filterNot { it.casUuid == cas }
                                }
                                is ScanResult.Error -> status = "Umleiten fehlgeschlagen: ${r.message}"
                                else -> {}
                            }
                        }
                    }
                ) { Text("Umleiten") }
            },
            dismissButton = {
                TextButton(onClick = { redirectTarget = null }) { Text("Abbrechen") }
            }
        )
    }
}
