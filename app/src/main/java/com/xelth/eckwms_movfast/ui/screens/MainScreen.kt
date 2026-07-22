package com.xelth.eckwms_movfast.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xelth.eckwms_movfast.ui.dynamic.DynamicUiRenderer
import com.xelth.eckwms_movfast.ui.screens.components.ConsoleList
import com.xelth.eckwms_movfast.ui.screens.components.ConsoleRow
import com.xelth.eckwms_movfast.ui.screens.pos.components.ConsoleView
import com.xelth.eckwms_movfast.ui.screens.pos.components.SelectionAreaSheet
import com.xelth.eckwms_movfast.ui.viewmodels.MainScreenViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import android.graphics.Bitmap

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    viewModel: ScanRecoveryViewModel
) {
    val mainViewModel: MainScreenViewModel = viewModel()
    val networkHealthState by viewModel.networkHealthState.observeAsState(NetworkHealthState.Checking)
    val deviceRegistrationStatus by viewModel.deviceRegistrationStatus.observeAsState("unknown")

    val renderCells by mainViewModel.renderCells.observeAsState(emptyList())
    val consoleLogs by mainViewModel.consoleLogs.observeAsState(emptyList())
    val scannerEnabled by mainViewModel.scannerEnabled.observeAsState(true)

    // Pairing log → main hex console bridge. The dedicated Pairing Console screen was
    // removed: pairing now runs in place and its feedback streams into this console,
    // while ok/fail shows on the network half-button color. The forward counter lives
    // in MainScreenViewModel (not a Compose `remember`) so it survives MainScreen
    // leaving/re-entering composition during the camera round-trip.
    val pairingLog by viewModel.pairingLog.observeAsState(emptyList())
    LaunchedEffect(pairingLog) { mainViewModel.forwardPairingLog(pairingLog) }

    // Log transport switches (direct ↔ relay ↔ offline) to the console — these were
    // silent before, only the half-button color changed.
    LaunchedEffect(networkHealthState) {
        val st = networkHealthState
        // Ignore the transient checking/restoring states — they aren't a real transport
        // and would spam a false "connection lost" every poll.
        if (st !is NetworkHealthState.Checking && st !is NetworkHealthState.Restoring) {
            // Tag with the mesh's first UUID segment (e.g. "7e6fe40d"), else the home node's.
            val mesh = com.xelth.eckwms_movfast.utils.SettingsManager.getHomeMeshId()
                ?.substringBefore("-")?.takeIf { it.isNotBlank() }
                ?: com.xelth.eckwms_movfast.utils.SettingsManager.getHomeInstanceId()
                    .substringBefore("-").takeIf { it.isNotBlank() }
                ?: "—"
            val (transport, label) = when {
                !st.isConnected() ->
                    "offline" to "🔴 Connection lost (mesh $mesh) — no server reachable"
                st.connectionType == com.xelth.eckwms_movfast.ui.data.ConnectionType.LOCAL_IP ->
                    "direct" to "🟢 Switched to DIRECT — mesh $mesh (local server)"
                else ->
                    "relay" to "🟠 Switched to RELAY — mesh $mesh"
            }
            mainViewModel.noteTransport(transport, label)
        }
    }

    // Repair mode state
    val isRepairMode by mainViewModel.isRepairMode.observeAsState(false)
    val repairStatus by mainViewModel.repairStatus.observeAsState("")

    // Active slot history and photos
    val activeSlotHistory by mainViewModel.activeSlotHistory.observeAsState(emptyList())
    val activeSlotPhotosList by mainViewModel.activeSlotPhotosList.observeAsState(emptyList())
    var fullScreenPhoto by remember { mutableStateOf<Bitmap?>(null) }

    // Receiving mode state
    val isReceivingMode by mainViewModel.isReceivingMode.observeAsState(false)
    val receivingStatus by mainViewModel.receivingStatus.observeAsState("")
    val receivingModalJson by mainViewModel.showReceivingModal.observeAsState(null)

    // Network mode (single-tap on the server half-button)
    val isNetworkMode by mainViewModel.isNetworkMode.observeAsState(false)

    // Trip mode state (Fahrtenbuch)
    val isTripMode by mainViewModel.isTripMode.observeAsState(false)
    val tripStatus by mainViewModel.tripStatus.observeAsState("")
    val tripActive by com.xelth.eckwms_movfast.trips.TripManager.activeTrip.observeAsState(null)
    LaunchedEffect(tripActive) { mainViewModel.setTripRecording(tripActive != null) }

    // Device Check mode state
    val isDeviceCheckMode by mainViewModel.isDeviceCheckMode.observeAsState(false)
    val deviceCheckStatus by mainViewModel.deviceCheckStatus.observeAsState("")

    // Restock mode state
    val isRestockMode by mainViewModel.isRestockMode.observeAsState(false)

    // Inventory mode state
    val isInventoryMode by mainViewModel.isInventoryMode.observeAsState(false)

    // Multi-user state (collected early for SelectionAreaSheet)
    val currentUserState by com.xelth.eckwms_movfast.ui.viewmodels.UserManager.currentUser.collectAsState()
    val viewingUserState by com.xelth.eckwms_movfast.ui.viewmodels.UserManager.viewingUser.collectAsState()

    // Native half-slot buttons
    val exitButtonState by mainViewModel.exitButton.observeAsState(null)

    // Read shared settings from ScanRecoveryViewModel
    val sharedGridRowCount by viewModel.gridRowCount.observeAsState(7)
    val sharedIsLeftHanded by viewModel.isLeftHanded.observeAsState(false)

    val density = LocalDensity.current
    val context = LocalContext.current

    // Trip mode: refresh active trip on enter + auto-detect permission launcher.
    // reconcileOpenTrip also closes an orphaned open trip (stale phantom) at its
    // last activity so the console offers a clean start, not a ghost recording.
    LaunchedEffect(isTripMode) {
        if (isTripMode) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.xelth.eckwms_movfast.trips.TripManager.publishActiveTrip(
                    com.xelth.eckwms_movfast.trips.TripManager.reconcileOpenTrip(context)
                )
            }
        }
    }
    val tripAutoPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        mainViewModel.setTripAutoDetect(
            granted && com.xelth.eckwms_movfast.trips.TripManager.enableAutoDetect(context)
        )
    }
    // "Allow all the time" location — the OS shows its own settings page for
    // this one (Android 11+). Without it an auto-detected recording is
    // location-starved while the screen is off (0 track points).
    val tripBgLocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        mainViewModel.tripBgLocationGranted = granted
        mainViewModel.addLog(
            if (granted) "🌍 Standort „immer erlauben\" aktiv — Auto-Fahrten zeichnen jetzt auch bei ausgeschaltetem Bildschirm auf"
            else "⚠ Ohne „immer erlauben\" bleibt der Track einer Auto-Fahrt leer, solange der Bildschirm aus ist"
        )
        mainViewModel.refreshTripGrid()
    }
    // Km hex shows the ESTIMATED current odometer while a trip is recording
    // (start reading + track distance so far) — refreshed once a minute.
    LaunchedEffect(isTripMode) {
        if (!isTripMode) {
            mainViewModel.setTripKmEstimate(null)
            return@LaunchedEffect
        }
        while (true) {
            val est = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.xelth.eckwms_movfast.trips.TripManager.estimateCurrentOdometer(context)
                } catch (e: Exception) { null }
            }
            mainViewModel.setTripKmEstimate(est?.toInt()?.toString())
            kotlinx.coroutines.delay(60_000)
        }
    }

    // Trip mode: console search (text + push-to-talk voice) + start (ticket tap
    // OR dictated/typed address) + odometer
    val tripScope = rememberCoroutineScope()
    val tripDestinations by mainViewModel.tripDestinations.observeAsState(emptyList())
    // Long-press history overlay for the trip console (offline, straight from
    // Room): 🚗 = last trips, 🧾 = last expenses. Any destination load (city
    // tap / search) or leaving trip mode swaps the ticket list back in.
    var tripHistoryRows by remember { mutableStateOf<List<ConsoleRow>?>(null) }
    var tripQuery by remember { mutableStateOf("") }
    var tripListening by remember { mutableStateOf(false) }
    val speech = remember { com.xelth.eckwms_movfast.utils.SpeechToText(context) }
    // Voice Commands P2 — Gemini fallback + best-effort parallel audio capture
    // (kept in memory until a resolution finishes, for the multimodal re-listen).
    val voiceRec = remember { com.xelth.eckwms_movfast.voice.VoiceAudioRecorder() }
    val voiceApi = remember { com.xelth.eckwms_movfast.api.ScanApiService(context) }
    var lastVoiceAudioB64 by remember { mutableStateOf<String?>(null) }
    // Bumped by the "📍 Zu mir" hex → TripMapView recenters on the current position.
    var mapRecenterTick by remember { mutableStateOf(0) }
    var tripPendingRef by remember { mutableStateOf<String?>(null) }
    var tripPendingLabel by remember { mutableStateOf<String?>(null) }
    var tripPendingSource by remember { mutableStateOf("planned") }
    var tripPendingPurpose by remember { mutableStateOf("business") }
    // Start hex = INTENT (like the spoken declaration): arm instead of starting;
    // the next IN_VEHICLE transition starts the trip. Destination-based and
    // private starts stay immediate.
    var tripPendingArm by remember { mutableStateOf(false) }
    var tripOdometerStart by remember { mutableStateOf(false) }
    var tripOdometerEnd by remember { mutableStateOf(false) }
    // Known vehicles for the start picker (Fahrtenbuch); auto-fill when one.
    var tripVehicles by remember {
        mutableStateOf<List<com.xelth.eckwms_movfast.data.local.entity.VehicleEntity>>(emptyList())
    }
    LaunchedEffect(Unit) {
        mainViewModel.tripBgLocationGranted =
            com.xelth.eckwms_movfast.trips.TripManager.hasBackgroundLocation(context)
        com.xelth.eckwms_movfast.trips.VehicleManager.refresh(context)
        tripVehicles = com.xelth.eckwms_movfast.trips.VehicleManager.knownVehicles(context)
        // Feed known plates to the Kennzeichen field sub-menu as quick-pick hexes.
        mainViewModel.tripKnownPlates = tripVehicles.mapNotNull { it.plate?.takeIf { p -> p.isNotBlank() } }.distinct()
        // Feed recent free-text purposes to the Purpose field sub-menu as quick-picks.
        try {
            val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
            mainViewModel.tripKnownPurposes = db.tripDao().recentPurposeLabels()
        } catch (e: Exception) {
            android.util.Log.w("MainScreen", "recentPurposeLabels failed: ${e.message}")
        }
    }

    // Debounced live search across ALL tickets while typing/dictating
    LaunchedEffect(tripQuery, isTripMode) {
        if (isTripMode) {
            kotlinx.coroutines.delay(350)
            mainViewModel.searchTripDestinations(tripQuery)
        }
    }
    // Fresh destinations (city tap / search result) replace the history overlay;
    // leaving trip mode drops it entirely.
    LaunchedEffect(tripDestinations) {
        if (tripDestinations.isNotEmpty()) tripHistoryRows = null
    }
    LaunchedEffect(isTripMode) {
        if (!isTripMode) tripHistoryRows = null
    }
    DisposableEffect(Unit) { onDispose { speech.destroy(); voiceRec.cancel() } }

    // Odometer-photo stop signal: a user-set Km while a trip records arms the
    // tentative end (validated against the track; 6 h without driving → the
    // trip auto-ends at the photo moment with that reading).
    LaunchedEffect(Unit) {
        mainViewModel.onTripKmCaptured = { km ->
            tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val armed = com.xelth.eckwms_movfast.trips.TripManager.odometerCheckpoint(
                    context, km, mainViewModel.pendingTripKmPhotoId()
                )
                if (armed) {
                    mainViewModel.addLog("📍 Odometer stop recorded ($km km) — trip auto-ends in 6 h unless driving resumes")
                } else {
                    // No open trip: a reading captured while a start intent is
                    // armed (photo shortly AFTER pressing Start / the voice
                    // command) upgrades its estimated odometer to a real reading.
                    com.xelth.eckwms_movfast.trips.TripManager.attachIntentOdometer(
                        km,
                        if (mainViewModel.pendingTripKmPhotoId() != null) "photo" else "manual",
                        mainViewModel.pendingTripKmPhotoId()
                    )
                    mainViewModel.refreshTripGrid()  // Armed-hex checklist update
                }
            }
        }
        mainViewModel.onTripKmPhotoCaptured = { photoId ->
            tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.xelth.eckwms_movfast.trips.TripManager.attachArmedOdoPhoto(context, photoId)
                com.xelth.eckwms_movfast.trips.TripManager.attachIntentOdoPhoto(photoId)
            }
        }
        // Purpose typed/dictated while a trip is RECORDING → merge it onto the
        // open trip now (the field alone only feeds the NEXT start). While a
        // start intent is ARMED instead → it becomes the intent's destination.
        mainViewModel.onTripPurposeCaptured = { label ->
            tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val open = com.xelth.eckwms_movfast.trips.TripManager.reconcileOpenTrip(context)
                if (open != null && open.purpose != "private") {
                    com.xelth.eckwms_movfast.trips.TripManager.startTrip(
                        context, manual = true,
                        purpose = "business",
                        purposeLabel = label,
                        purposeSource = "text"
                    )
                    mainViewModel.addLog("🚗 Ziel übernommen: „$label\" (laufende Fahrt)")
                } else if (open == null &&
                    com.xelth.eckwms_movfast.trips.TripManager.peekTripIntent() != null
                ) {
                    com.xelth.eckwms_movfast.trips.TripManager.bindTripIntentClient(null, label)
                    mainViewModel.addLog("🕐 Armed trip destination: „$label\"")
                    mainViewModel.refreshTripGrid()  // Armed-hex checklist update
                }
            }
        }
    }

    val tripStartLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            // Arming only makes sense when something can consume the intent —
            // without Activity Recognition (permission + toggle) the armed trip
            // could NEVER begin and silently expired after 2 h (2026-07-13 field
            // case). No detector → start directly instead.
            val canAutoStart = com.xelth.eckwms_movfast.trips.TripManager.hasActivityPermission(context) &&
                com.xelth.eckwms_movfast.utils.SettingsManager.getTripAutoDetect()
            if (tripPendingArm && canAutoStart) {
                // Start hex = declared INTENT (same semantics as the spoken
                // declaration): the trip starts on the next vehicle movement;
                // no movement within the 2 h TTL → silently forgotten (the
                // yellow hex force-starts it any time before that).
                val armLabel = tripPendingLabel ?: ""
                val armSource = tripPendingSource
                tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val open = com.xelth.eckwms_movfast.trips.TripManager.reconcileOpenTrip(context)
                    if (open != null) {
                        mainViewModel.addLog("🚗 Trip already running")
                        return@launch
                    }
                    com.xelth.eckwms_movfast.trips.TripManager
                        .armTripIntent(context, armLabel, ref = null, source = armSource)
                    // Explicitly set field values beat the last-trip presets.
                    mainViewModel.pendingTripKm()?.let { km ->
                        com.xelth.eckwms_movfast.trips.TripManager.attachIntentOdometer(
                            km,
                            if (mainViewModel.pendingTripKmPhotoId() != null) "photo" else "manual",
                            mainViewModel.pendingTripKmPhotoId()
                        )
                    }
                    mainViewModel.pendingTripPlate()?.let { p ->
                        com.xelth.eckwms_movfast.trips.TripManager.bindTripIntentVehicle(null, p)
                    }
                    mainViewModel.addLog(
                        "🕐 Start armed — trip begins on movement (auto-expires in 2 h)" +
                            (armLabel.takeIf { it.isNotBlank() }?.let { " · „$it\"" } ?: "")
                    )
                    // Auto-started recording is location-starved without "allow
                    // all the time" — surface the system grant flow right here,
                    // while the driver is still standing at the car.
                    if (!com.xelth.eckwms_movfast.trips.TripManager.hasBackgroundLocation(context)) {
                        mainViewModel.addLog("⚠ Standort „immer erlauben\" fehlt — ohne ihn bleibt der Track leer, bis der Bildschirm angeht")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            tripBgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    }
                    mainViewModel.refreshTripGrid()
                }
            } else {
                if (tripPendingArm) {
                    mainViewModel.addLog("▶ Auto-Erkennung aus — Fahrt startet sofort")
                }
                // Plate/Km pre-set on the hex field menus ride IN the start intent —
                // the service applies them atomically with the trip row. (The old
                // post-start getOpenTrip() write raced the service's async insert
                // and silently lost the scanned odometer.) OCR photos are already
                // in CAS at capture time; only the ids are linked here.
                val pPlate = mainViewModel.pendingTripPlate()
                val pKm = mainViewModel.pendingTripKm()
                com.xelth.eckwms_movfast.trips.TripManager.startTrip(
                    context, manual = true, purpose = tripPendingPurpose,
                    purposeRef = tripPendingRef, purposeLabel = tripPendingLabel,
                    purposeSource = tripPendingSource,
                    startOdometerKm = pKm,
                    startOdometerSource = if (pKm != null) "manual" else null,
                    startOdometerPhotoId = mainViewModel.pendingTripKmPhotoId(),
                    plate = pPlate,
                    platePhotoId = mainViewModel.pendingTripPlatePhotoId()
                )
                if (pPlate != null || pKm != null) {
                    mainViewModel.markTripFieldsAuto()
                } else {
                    // Nothing pre-set → fall back to the odometer/vehicle dialog.
                    tripOdometerStart = true
                }
            }
        }
    }
    // Destination-based start (from a ticket / typed address) → always business.
    val tripStart: (String?, String?, String) -> Unit = { ref, label, src ->
        tripPendingPurpose = "business"
        tripPendingArm = false
        tripPendingRef = ref; tripPendingLabel = label; tripPendingSource = src
        tripStartLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }
    // While a trip is RECORDING the ticket rows declare the NEXT destination
    // instead of starting a trip: at a checkpoint stop it arms the next leg
    // (survives a trip close), mid-drive it retargets the running trip.
    val tripDeclareNext: (String?, String?, String) -> Unit = { ref, label, src ->
        tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val mode = com.xelth.eckwms_movfast.trips.TripManager.declareNextDestination(
                context, label.orEmpty(), ref?.takeIf { it.isNotBlank() }, src
            )
            mainViewModel.addLog(
                if (mode == com.xelth.eckwms_movfast.trips.TripManager.NextDest.MERGED_LIVE)
                    "🚗 Ziel übernommen: „${label.orEmpty()}\" (laufende Fahrt)"
                else
                    "🎯 Nächstes Ziel: „${label.orEmpty()}\" — gilt ab Weiterfahrt (2 h)"
            )
        }
    }
    // Hex start (no destination): business ARMS an intent (starts on movement,
    // like the spoken declaration); private starts immediately (records no
    // positions anyway). A free-text Purpose typed on the Purpose field
    // hex-menu rides along as the label.
    val tripStartWithPurpose: (String) -> Unit = { purpose ->
        tripPendingPurpose = purpose
        tripPendingArm = purpose != "private"
        val purposeText = mainViewModel.pendingTripPurposeText()
        tripPendingRef = null
        tripPendingLabel = if (purpose == "private") null else purposeText
        tripPendingSource = if (purpose != "private" && purposeText != null) "text" else "manual"
        tripStartLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted → user presses the mic again to talk */ }

    // Trip field OCR (📷 on the Plate/Km hex sub-menu). Which field the capture is
    // for; the camera returns a preview bitmap → ML Kit OCR → applyTripFieldValue.
    var tripOcrField by remember { mutableStateOf<String?>(null) }
    val tripScanMgr = (context.applicationContext as? com.xelth.eckwms_movfast.EckwmsApp)?.scannerManager
    val tripFieldOcrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: android.graphics.Bitmap? ->
        tripScanMgr?.resumeScanService()   // camera returned → re-acquire scan engine
        val field = tripOcrField
        tripOcrField = null
        if (bitmap != null && field != null) {
            tripScope.launch {
                val text = if (field == "plate")
                    com.xelth.eckwms_movfast.utils.OdometerOcr.recognizePlate(bitmap)
                else
                    com.xelth.eckwms_movfast.utils.OdometerOcr.recognizeKm(bitmap)?.toInt()?.toString()
                if (!text.isNullOrBlank()) {
                    mainViewModel.applyTripFieldValue(field, text)
                } else {
                    mainViewModel.addLog("OCR: nothing recognised")
                }
                // Save the shot to CAS regardless of OCR success (evidence). The
                // CAS id is derived from the compressed bytes inside uploadImage
                // (the server verifies the claim) — read it back from the result
                // and stash it on the field to attach to the trip at start.
                try {
                    val api = com.xelth.eckwms_movfast.api.ScanApiService(context)
                    val deviceId = com.xelth.eckwms_movfast.utils.SettingsManager.getDeviceId(context)
                    val kind = if (field == "plate") "plate_photo" else "odometer_photo"
                    val res = api.uploadImage(bitmap, deviceId, kind, null, quality = 75)
                    if (res is com.xelth.eckwms_movfast.api.ScanResult.Success) {
                        val photoId = org.json.JSONObject(res.data).optString("image_id")
                        if (photoId.isNotEmpty()) mainViewModel.setTripFieldPhotoId(field, photoId)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MainScreen", "field OCR photo upload failed: ${e.message}")
                }
            }
        }
    }

    // Trip text-field entry via the normal Android keyboard (⌨ on the plate/purpose
    // field menu). tripKbdField = which field; tripKbdText = the live text. A 🎤
    // button dictates straight into the field (Google's speech UI, decoupled from
    // the trip destination search that push-to-talk drives).
    var tripKbdField by remember { mutableStateOf<String?>(null) }
    var tripKbdText by remember { mutableStateOf("") }
    val tripKbdSpeechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val spoken = res.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            tripKbdText = if (tripKbdField == "plate") spoken.uppercase() else spoken
        }
    }
    val launchKbdSpeech: () -> Unit = {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,
                if (tripKbdField == "plate") "Kennzeichen" else "Purpose")
        }
        try { tripKbdSpeechLauncher.launch(intent) }
        catch (e: Exception) { mainViewModel.addLog("No speech app installed") }
    }

    // ── Refuel (⛽): everything lives on the hexes (no modal). This holds only the
    // receipt photo id; the odometer buffer + receipt-ok flag live in the VM. ──
    var fuelReceiptPhotoId by remember { mutableStateOf<String?>(null) }
    val fuelReceiptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: android.graphics.Bitmap? ->
        tripScanMgr?.resumeScanService()
        if (bitmap != null) {
            android.widget.Toast.makeText(context, "🧾 Receipt captured", android.widget.Toast.LENGTH_SHORT).show()
            tripScope.launch {
                // A receipt is a tax document — persist it to DISK first (the
                // 2026-07-13 field day lost one: upload-only + LTE + dead
                // process = photo gone). The id is the ContentHash CAS UUID of
                // the exact compressed bytes — the server verifies the claim,
                // so a random UUID would never land. The id is valid
                // immediately; the local-photo pipeline retries the upload
                // until the master becomes reachable.
                try {
                    val receiptBytes = java.io.ByteArrayOutputStream().let { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
                        out.toByteArray()
                    }
                    val photoId = com.xelth.eckwms_movfast.utils.ContentHash.uuidFromBytes(receiptBytes)
                    val dir = java.io.File(context.filesDir, "photos").apply { mkdirs() }
                    val photoFile = java.io.File(dir, "orig_$photoId.webp")
                    photoFile.writeBytes(receiptBytes)
                    val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
                    db.localPhotoDao().insert(
                        com.xelth.eckwms_movfast.data.local.entity.LocalPhotoEntity(
                            uuid = photoId,
                            receiverId = "expense:" + mainViewModel.currentExpenseType(),
                            originalPath = photoFile.absolutePath,
                            avatarPath = null,
                            syncStatus = com.xelth.eckwms_movfast.data.local.entity.LocalPhotoEntity.STATUS_PENDING
                        )
                    )
                    fuelReceiptPhotoId = photoId
                    mainViewModel.setFuelReceiptOk(true)
                    // Best-effort immediate delivery; a miss is fine — the
                    // SyncWorker photo pipeline owns the retry.
                    val api = com.xelth.eckwms_movfast.api.ScanApiService(context)
                    val deviceId = com.xelth.eckwms_movfast.utils.SettingsManager.getDeviceId(context)
                    val res = api.uploadImageFile(
                        photoFile.absolutePath, deviceId, "fuel_receipt", null, null, photoId, null
                    )
                    if (res is com.xelth.eckwms_movfast.api.ScanResult.Success) {
                        db.localPhotoDao().updateSyncStatus(
                            photoId,
                            com.xelth.eckwms_movfast.data.local.entity.LocalPhotoEntity.STATUS_SYNCED
                        )
                    } else {
                        mainViewModel.addLog("🧾 Beleg lokal gespeichert — Upload folgt später")
                        com.xelth.eckwms_movfast.sync.SyncManager.scheduleSync(context)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MainScreen", "receipt save: ${e.message}")
                    mainViewModel.addLog("Receipt save failed: ${e.message}")
                }
            }
        }
    }
    val fuelOdoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: android.graphics.Bitmap? ->
        tripScanMgr?.resumeScanService()
        if (bitmap != null) {
            tripScope.launch {
                val km = com.xelth.eckwms_movfast.utils.OdometerOcr.recognizeKm(bitmap)
                if (km != null) mainViewModel.setFuelOdometer(km.toInt().toString())
                else mainViewModel.addLog("Odometer OCR: nothing recognised")
            }
        }
    }

    // Network-mode "Enter code" dialog (onboard via 9eck.com without a QR).
    var showCodeDialog by remember { mutableStateOf(false) }

    // Dispatch a grid action — shared by button taps AND voice commands. Runs the
    // ViewModel handler, then performs the MainScreen-only side effects
    // (navigation, camera, dialogs, permission flows).
    val onGridAction: (String) -> Unit = { action ->
        val result = mainViewModel.onButtonClick(action)
        android.util.Log.e("NAV_CAMERA", ">>> onButtonClick action=$action result=$result")
        when (result) {
            "capture_photo" -> {
                android.util.Log.e("NAV_CAMERA", ">>> BUTTON→CAMERA (photo) action=$action")
                navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
            }
            "capture_barcode" -> {
                android.util.Log.e("NAV_CAMERA", ">>> BUTTON→CAMERA (barcode) action=$action")
                navController.navigate("cameraScanScreen?scan_mode=barcode")
            }
            "navigate_scan" -> navController.navigate("scanScreen")
            "navigate_ai" -> navController.navigate("scanScreen")
            "navigate_settings" -> navController.navigate("settings")
            "navigate_qc" -> navController.navigate("qcScreen")
            "navigate_explorer" -> navController.navigate("explorerScreen")
            "navigate_picking" -> navController.navigate("pickingList")
            "navigate_pos" -> navController.navigate("pos")
            // Network mode actions
            "net_scan_qr" -> {
                viewModel.clearPairingLog()
                navController.navigate("cameraScanScreen?scan_mode=pairing")
            }
            "net_enter_code" -> showCodeDialog = true
            "net_refresh" -> viewModel.triggerManualHealthCheck()
            // Trip start/stop sub-menu (replaces the TripsScreen panel)
            "trip_start_business" -> tripStartWithPurpose("business")
            "trip_start_private" -> tripStartWithPurpose("private")
            // Yellow armed hex: the driver forces the start NOW — detection
            // didn't come (or won't). Consumes the intent with all its fields;
            // started from the UI the FGS has full location rights.
            "trip_force_start" -> {
                if (com.xelth.eckwms_movfast.trips.TripManager.startTripFromIntent(context, manual = true)) {
                    mainViewModel.addLog("🚗 Fahrt manuell gestartet (armed Intent übernommen)")
                } else {
                    mainViewModel.addLog("🕐 Kein armed Intent mehr — normal starten")
                    mainViewModel.refreshTripGrid()
                }
            }
            // ✕/✓ BG-Standort hex in the trip settings sub-menu.
            "trip_bg_location" -> {
                if (com.xelth.eckwms_movfast.trips.TripManager.hasBackgroundLocation(context)) {
                    mainViewModel.addLog("🌍 Standort „immer erlauben\" ist bereits aktiv")
                } else {
                    tripBgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            "trip_stop" -> tripOdometerEnd = true   // end-km dialog → finalize
            "trip_odometer" -> tripOdometerStart = true  // km + Kfz dialog
            "trip_recenter" -> mapRecenterTick++    // pan map to current position
            // Drop a stop on the current trip (multi-stop). Uses the current Purpose
            // as the stop label if one is set.
            "trip_checkpoint" -> {
                com.xelth.eckwms_movfast.trips.TripManager.checkpointNow(
                    context, mainViewModel.pendingTripPurposeText()
                )
                android.widget.Toast.makeText(context, "📍 Checkpoint saved", android.widget.Toast.LENGTH_SHORT).show()
                // Owner wish 2026-07-20: the natural moment to name the next leg.
                mainViewModel.addLog("📍 Checkpoint — nächstes Ziel? Ticket antippen oder 🎤 ansagen")
            }
            // 🧾 Expense (fuel/parking/toll/receipt): open the expense HEX
            // sub-menu, prefilling the odometer estimate.
            "trip_fuel" -> {
                fuelReceiptPhotoId = null
                tripScope.launch {
                    val est = com.xelth.eckwms_movfast.trips.TripManager.estimateCurrentOdometer(context)
                    mainViewModel.openFuelMenu(est?.toInt()?.toString())
                }
            }
            "trip_fuel_ocr" -> { tripScanMgr?.suspendScanService(); fuelOdoLauncher.launch(null) }
            "trip_fuel_receipt" -> { tripScanMgr?.suspendScanService(); fuelReceiptLauncher.launch(null) }
            "trip_fuel_save" -> {
                val expenseType = mainViewModel.currentExpenseType()
                com.xelth.eckwms_movfast.trips.TripManager.logExpense(
                    context, expenseType, mainViewModel.currentFuelOdometer(),
                    mainViewModel.currentFuelSource(), fuelReceiptPhotoId
                )
                android.widget.Toast.makeText(context, "🧾 Expense logged ($expenseType)", android.widget.Toast.LENGTH_SHORT).show()
            }
            // 📷 OCR on the Plate/Km field hex-menu → capture a photo, ML Kit reads
            // it, applyTripFieldValue fills the field (green). Suspend the hardware
            // scanner first (it shares the ISP with the camera app).
            "trip_ocr:plate", "trip_ocr:km" -> {
                tripOcrField = result.removePrefix("trip_ocr:")
                tripScanMgr?.suspendScanService()
                tripFieldOcrLauncher.launch(null)
            }
            // ⌨ Keyboard on a text field → open the Android soft keyboard (prefilled).
            "trip_kbd:plate", "trip_kbd:purpose" -> {
                val f = result.removePrefix("trip_kbd:")
                tripKbdText = if (f == "plate") (mainViewModel.pendingTripPlate() ?: "")
                              else (mainViewModel.pendingTripPurposeText() ?: "")
                tripKbdField = f
            }

            "trip_toggle_autodetect" -> {
                val tm = com.xelth.eckwms_movfast.trips.TripManager
                if (mainViewModel.tripAutoDetect.value == true) {
                    tm.disableAutoDetect(context)
                    mainViewModel.setTripAutoDetect(false)
                } else if (tm.hasActivityPermission(context)) {
                    mainViewModel.setTripAutoDetect(tm.enableAutoDetect(context))
                } else {
                    tripAutoPermLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }
        }
    }

    // State for the Intake Bottom Sheet
    var showIntakeSheet by remember { mutableStateOf(false) }
    var intakeConfigJson by remember { mutableStateOf("{}") }
    var intakeLongPressAction by remember { mutableStateOf("") }
    val intakeFormState = remember { mutableStateMapOf<String, Any>() }

    // State for slot long-press menu (delete + change background)
    var showSlotMenu by remember { mutableStateOf(false) }
    var slotMenuAction by remember { mutableStateOf("") }

    // State for receiving workflow modal
    val receivingModalFormState = remember { mutableStateMapOf<String, Any>() }

    // Sync shared settings into MainScreenViewModel
    LaunchedEffect(sharedGridRowCount) {
        mainViewModel.setGridRowCount(sharedGridRowCount)
    }
    LaunchedEffect(sharedIsLeftHanded) {
        if (sharedIsLeftHanded != (mainViewModel.isLeftHanded.value ?: false)) {
            mainViewModel.toggleHandedness()
        }
    }

    // Initialize network health monitoring (same as ScanScreen does)
    LaunchedEffect(Unit) {
        viewModel.onViewModelReady()
    }

    // Wire repair event callbacks from MainScreenViewModel to ScanRecoveryViewModel
    LaunchedEffect(Unit) {
        mainViewModel.onRepairEventSend = { targetDeviceId, eventType, data ->
            viewModel.sendRepairEvent(targetDeviceId, eventType, data)
        }
        mainViewModel.onRepairPhotoUpload = { targetDeviceId, bitmap ->
            viewModel.uploadRepairPhoto(targetDeviceId, bitmap)
        }
        mainViewModel.onSaveRepairSlots = { slots ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveRepairSlots(slots)
        }
        mainViewModel.onLoadRepairSlots = {
            com.xelth.eckwms_movfast.utils.SettingsManager.loadRepairSlots()
        }
        mainViewModel.onSaveRepairPhoto = { index, bitmap ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveRepairPhoto(index, bitmap)
        }
        mainViewModel.onLoadRepairPhoto = { index ->
            com.xelth.eckwms_movfast.utils.SettingsManager.loadRepairPhoto(index)
        }
        mainViewModel.onDeleteRepairPhoto = { index ->
            com.xelth.eckwms_movfast.utils.SettingsManager.deleteRepairPhoto(index)
        }
        // CAS-id photo persistence: the id IS the ContentHash of the stored
        // bytes (the server rejects any other claim), so hash-then-write.
        val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
        val localPhotoDao = db.localPhotoDao()
        mainViewModel.onSavePhoto = { slotIndex, bitmap ->
            val bytes = java.io.ByteArrayOutputStream().let { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
                out.toByteArray()
            }
            val uuid = com.xelth.eckwms_movfast.utils.ContentHash.uuidFromBytes(bytes)
            val origPath = com.xelth.eckwms_movfast.utils.SettingsManager.savePhotoOriginalBytes(uuid, bytes)
            // DB insert is fire-and-forget via ScanRecoveryViewModel's scope
            viewModel.insertLocalPhoto(uuid, slotIndex, origPath)
            uuid
        }
        mainViewModel.onLoadSlotPhotoUuids = { slotIndex ->
            localPhotoDao.getBySlotIndex(slotIndex).map { it.uuid }
        }
        mainViewModel.onDeleteSlotPhotos = { slotIndex ->
            val photos = localPhotoDao.getBySlotIndex(slotIndex)
            photos.forEach { com.xelth.eckwms_movfast.utils.SettingsManager.deletePhoto(it.uuid) }
            localPhotoDao.deleteBySlotIndex(slotIndex)
        }
        mainViewModel.onBindSlotPhotos = { slotIndex, receiverId ->
            localPhotoDao.bindSlotPhotos(slotIndex, receiverId)
        }
        // Device Check persistence
        mainViewModel.onSaveDeviceCheckSlots = { slots ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveDeviceCheckSlots(slots)
        }
        mainViewModel.onLoadDeviceCheckSlots = {
            com.xelth.eckwms_movfast.utils.SettingsManager.loadDeviceCheckSlots()
        }
        // Item photo persistence (global, by internal ID)
        // Internal ID prefix defines type: i=item, b=box, p=place, l=label
        mainViewModel.onSaveItemPhoto = { internalId, bitmap ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveItemPhoto(internalId, bitmap)
        }
        val repo = com.xelth.eckwms_movfast.data.WarehouseRepository.getInstance(context)
        mainViewModel.onLoadItemPhoto = { internalId ->
            // 1. Try Local Disk (High Resolution, User Generated)
            var bitmap = com.xelth.eckwms_movfast.utils.SettingsManager.loadItemPhoto(internalId)
            // 2. Fallback to Database (Synced Avatars)
            if (bitmap == null) {
                bitmap = repo.getAvatarForEntity(internalId)
            }
            bitmap
        }
    }

    // Wire shipment fetching callback
    LaunchedEffect(Unit) {
        val scanApiService = com.xelth.eckwms_movfast.api.ScanApiService(context)
        mainViewModel.onFetchShipments = { limit -> scanApiService.getShipments(limit) }
        mainViewModel.onFetchDestinations = { q, city, ai -> scanApiService.fetchDestinations(q, city, ai) }
        mainViewModel.onInventorySubmit = { target, event, data ->
            scanApiService.sendRepairEvent(target, event, data)
        }
        mainViewModel.onFetchLocationContents = { locationId ->
            scanApiService.fetchExplorerData("/api/explorer/locations/$locationId/contents")
        }
        // Stocktake: soll = warehouse Exact reconcile; counted lines booked via put-away.
        mainViewModel.onFetchExpectedSoll = { wh -> scanApiService.fetchReconcile(wh) }
        mainViewModel.onPutAwayItem = { item, shelf, wh, qty ->
            val outcome = try {
                scanApiService.putAway(item, shelf, wh, qty, "set")
            } catch (e: Exception) {
                com.xelth.eckwms_movfast.api.SyncOutcome.FAILED
            }
            // Only a transport failure is retryable; a 4xx (e.g. unknown part) is permanent.
            if (outcome == com.xelth.eckwms_movfast.api.SyncOutcome.FAILED) {
                com.xelth.eckwms_movfast.data.WarehouseRepository.getInstance(context)
                    .queuePutAway(item, shelf, wh, qty)
            }
        }
        // Multi-user callbacks
        mainViewModel.onFetchUsers = { scanApiService.fetchActiveUsers() }
        mainViewModel.onVerifyPin = { userId, pin -> scanApiService.verifyUserPin(userId, pin) }
        mainViewModel.onChangePassword = { oldPw, newPw -> scanApiService.changePassword(oldPw, newPw) }
        // Restore saved user + cached roster FIRST (usable offline / off-LAN),
        // then refresh from server (a failed refresh keeps the cache).
        com.xelth.eckwms_movfast.ui.viewmodels.UserManager.restoreFromSettings()
        com.xelth.eckwms_movfast.ui.viewmodels.UserManager.restoreRosterFromCache()
        mainViewModel.loadAvailableUsers()
    }

    // Wire Fat Client offline lookup callbacks
    LaunchedEffect(Unit) {
        val repo = com.xelth.eckwms_movfast.data.WarehouseRepository.getInstance(context)
        mainViewModel.onLookupProduct = { barcode -> repo.getLocalProduct(barcode) }
        mainViewModel.onLookupLocation = { barcode -> repo.getLocalLocation(barcode) }
        mainViewModel.onUpdateProductQty = { barcode, qty -> repo.updateLocalProductQty(barcode, qty) }
        mainViewModel.onSaveInventoryRecords = { loc, records -> repo.saveInventoryRecords(loc, records) }
        mainViewModel.onLoadInventoryRecords = { loc -> repo.getInventoryRecords(loc) }

        // Wire haptic feedback through SunlightModeManager (replaces raw vibrator)
        mainViewModel.onLongVibrate = { com.xelth.eckwms_movfast.utils.SunlightModeManager.playAttention() }
        mainViewModel.onHapticSuccess = { com.xelth.eckwms_movfast.utils.SunlightModeManager.playSuccess() }
        mainViewModel.onHapticError = { com.xelth.eckwms_movfast.utils.SunlightModeManager.playError() }
        mainViewModel.onHapticAttention = { com.xelth.eckwms_movfast.utils.SunlightModeManager.playAttention() }
    }

    // Load receiving workflow JSON
    LaunchedEffect(Unit) {
        mainViewModel.loadReceivingWorkflow(context)
    }

    // Receiving mode: camera navigation
    val receivingCameraNav by mainViewModel.receivingCameraNav.observeAsState(null)
    LaunchedEffect(receivingCameraNav) {
        val scanMode = receivingCameraNav
        if (scanMode != null) {
            android.util.Log.e("NAV_CAMERA", ">>> receivingCameraNav: $scanMode")
            mainViewModel.consumeReceivingCameraNav()
            navController.navigate("cameraScanScreen?scan_mode=$scanMode")
        }
    }

    // Bridge: forward scanner results — device check auto-enter takes priority
    val scannedBarcode by viewModel.scannedBarcode.observeAsState(null)
    LaunchedEffect(scannedBarcode) {
        if (scannedBarcode != null) {
            // Haptic: confirm scan received (two quick ticks)
            com.xelth.eckwms_movfast.utils.SunlightModeManager.playSuccess()

            // Cross-mode auto-enter: device check takes priority over ALL modes
            if (mainViewModel.checkDeviceCheckAutoEnter(scannedBarcode!!)) {
                viewModel.consumeScannedBarcode()
                return@LaunchedEffect
            }
            // Then existing mode routing
            if (isRestockMode) {
                mainViewModel.onRestockScan(scannedBarcode!!)
            } else if (isInventoryMode) {
                mainViewModel.onInventoryScan(scannedBarcode!!, viewModel.lastScanWasEncrypted)
            } else if (isReceivingMode) {
                mainViewModel.onReceivingScan(scannedBarcode!!)
            } else if (isDeviceCheckMode) {
                mainViewModel.onDeviceCheckScan(scannedBarcode!!)
            } else if (isTripMode) {
                // Phase 4 will bind this as a vehicle link-barcode; for now log it
                mainViewModel.addLog("🚗 Fahrzeug-Barcode: ${scannedBarcode}")
            } else {
                mainViewModel.onRepairScan(scannedBarcode!!)
            }
            viewModel.consumeScannedBarcode()
        }
    }

    // Bridge: drain the camera→workflow photo queue into MainScreenViewModel.
    // A loop, not a single take — a photo SERIES from the combined camera lands
    // as several queued bitmaps that all arrive while this screen was hidden.
    val photoQueueVersion by viewModel.photoQueueVersion.observeAsState(0)
    LaunchedEffect(photoQueueVersion, isRepairMode, isReceivingMode, isDeviceCheckMode, isInventoryMode) {
        while (true) {
            val photo = viewModel.consumeRepairPhotoBitmap() ?: break
            if (isInventoryMode) {
                mainViewModel.onInventoryPhotoCaptured(photo)
            } else if (isReceivingMode) {
                mainViewModel.onReceivingPhotoCaptured(photo)
            } else if (isDeviceCheckMode) {
                mainViewModel.onDeviceCheckPhotoCaptured(photo)
            } else if (isRepairMode) {
                mainViewModel.onRepairPhotoCaptured(photo)
            }
        }
    }

    // Auto-navigate to camera after slot binding
    val shouldNavigateToCamera by mainViewModel.navigateToCamera.observeAsState(false)
    LaunchedEffect(shouldNavigateToCamera) {
        if (shouldNavigateToCamera) {
            android.util.Log.e("NAV_CAMERA", ">>> navigateToCamera TRIGGERED (mode=inventory=${mainViewModel.isInventoryMode.value})")
            mainViewModel.consumeNavigateToCamera()
            navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
        }
    }

    // No Scaffold, no TopBar — edge-to-edge layout, ignore nav bar insets
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val gridRowCount by mainViewModel.gridRowCount.observeAsState(7)

        // Recalculate grid when dimensions or settings change
        LaunchedEffect(containerWidth, containerHeight, mainViewModel.isLeftHanded.value, gridRowCount) {
            if (containerWidth > 0.dp && containerHeight > 0.dp) {
                mainViewModel.updateLayoutDimensions(containerWidth, containerHeight, density)
            }
        }

        val gridConfig = mainViewModel.gridConfig
        val gridHeight = mainViewModel.gridTotalHeight
        // Console gets whatever is left above the grid
        val consoleHeight = (containerHeight - gridHeight).coerceAtLeast(60.dp)

        // Console extends 1/4 button height under the grid to fill hex gaps
        val overlap = gridConfig.cellHeight / 4
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Console / Repair Status — extends slightly under grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(consoleHeight + overlap)
                    .align(Alignment.TopCenter)
                    .background(Color.Black)
            ) {
                if (isNetworkMode) {
                    // Live connection status (replaces the old bottom-sheet panel).
                    val (tLabel, tColor) = when {
                        !networkHealthState.isConnected() -> "OFFLINE" to Color(0xFFF44336)
                        networkHealthState.connectionType == com.xelth.eckwms_movfast.ui.data.ConnectionType.LOCAL_IP ->
                            "DIRECT · local server" to Color(0xFF4CAF50)
                        else -> "RELAY · ${networkHealthState.serverHash}" to Color(0xFFFF9800)
                    }
                    val mesh = com.xelth.eckwms_movfast.utils.SettingsManager.getHomeMeshId()
                        ?.substringBefore("-")?.takeIf { it.isNotBlank() }
                        ?: com.xelth.eckwms_movfast.utils.SettingsManager.getHomeInstanceId()
                            .substringBefore("-").ifBlank { "—" }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .padding(bottom = overlap),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🌐 Network", color = Color.Gray, fontSize = 12.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Connection: $tLabel",
                            color = tColor,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Server: ${com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().ifEmpty { "—" }}",
                            color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1
                        )
                        Text(
                            "Mesh: $mesh   ·   Reg: ${deviceRegistrationStatus.uppercase()}",
                            color = Color.White, fontSize = 12.sp
                        )
                    }
                } else if (isRepairMode) {
                    val activeSlotPhoto by mainViewModel.activeSlotPhoto.observeAsState(null)
                    val activeSlotAction = mainViewModel.getActiveSlotAction()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (activeSlotAction != null) {
                                        slotMenuAction = activeSlotAction
                                        showSlotMenu = true
                                    }
                                }
                            )
                    ) {
                        if (activeSlotPhoto != null) {
                            Image(
                                bitmap = activeSlotPhoto!!.asImageBitmap(),
                                contentDescription = "Device photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.4f),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // Overlay with history, thumbnails, status
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .padding(bottom = overlap),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            // History items (last 3)
                            activeSlotHistory.takeLast(3).forEach { msg ->
                                Text(
                                    text = msg,
                                    color = Color(0xFFE0E0E0),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .padding(bottom = 4.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            // Thumbnails row
                            if (activeSlotPhotosList.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    activeSlotPhotosList.take(4).forEach { bmp ->
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "Thumbnail",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(50.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.Black)
                                                .clickable { fullScreenPhoto = bmp }
                                        )
                                    }
                                    if (activeSlotPhotosList.size > 4) {
                                        Box(
                                            modifier = Modifier
                                                .size(50.dp)
                                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("+${activeSlotPhotosList.size - 4}", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            // Current status
                            Text(
                                text = repairStatus,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color(0xFF4CAF50).copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                } else if (isDeviceCheckMode) {
                    val activeSlotPhoto by mainViewModel.activeSlotPhoto.observeAsState(null)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                    ) {
                        if (activeSlotPhoto != null) {
                            Image(
                                bitmap = activeSlotPhoto!!.asImageBitmap(),
                                contentDescription = "Device check photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.2f),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = deviceCheckStatus,
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (isRestockMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .background(Color.Black)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val status by mainViewModel.restockStatus.observeAsState("Restock Mode")
                            Text(
                                text = status,
                                color = Color(0xFF00BCD4),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        ConsoleView(
                            logs = consoleLogs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(bottom = 8.dp),
                            scannerEnabled = false,
                            onScannerToggle = {}
                        )
                    }
                } else if (isInventoryMode) {
                    val inventoryItemPhoto by mainViewModel.inventoryItemPhoto.observeAsState(null)
                    val inventoryLocationPhoto by mainViewModel.inventoryLocationPhoto.observeAsState(null)
                    // Show location photo when place is last scanned, item photo otherwise
                    val displayPhoto = inventoryLocationPhoto ?: inventoryItemPhoto
                    val canTakePhoto = mainViewModel.canTakeInventoryPhoto()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .background(Color.Black)
                    ) {
                        // Background photo - tap to take new photo
                        if (displayPhoto != null) {
                            Image(
                                bitmap = displayPhoto.asImageBitmap(),
                                contentDescription = "Photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.25f)
                                    .focusProperties { canFocus = false }
                                    .clickable(enabled = canTakePhoto) {
                                        android.util.Log.e("NAV_CAMERA", ">>> PHOTO_BG tapped")
                                        navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
                                    },
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Status bar - tap to take photo if possible
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .focusProperties { canFocus = false }
                                    .clickable(enabled = canTakePhoto) {
                                        android.util.Log.e("NAV_CAMERA", ">>> STATUS_BAR tapped")
                                        navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val status by mainViewModel.inventoryStatus.observeAsState("Inventory Mode")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = status,
                                        color = Color(0xFFFFC107),  // Amber
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (canTakePhoto) {
                                        Text(
                                            text = " 📷",
                                            color = Color(0xFF9C27B0),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            }
                            ConsoleView(
                                logs = consoleLogs,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(bottom = 8.dp),
                                scannerEnabled = false,
                                onScannerToggle = {}
                            )
                        }
                    }
                } else if (isReceivingMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .background(Color.Black)
                    ) {
                        // Status bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = receivingStatus,
                                color = Color(0xFFFF9800),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // Console logs below status
                        ConsoleView(
                            logs = consoleLogs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(bottom = 8.dp),
                            scannerEnabled = false,
                            onScannerToggle = {}
                        )
                    }
                } else if (isTripMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                    ) {
                        // Dark MapLibre map behind the trip console — recorded
                        // track + planned stops, matching the desktop dark map.
                        com.xelth.eckwms_movfast.ui.screens.TripMapView(
                            modifier = Modifier.fillMaxSize(),
                            dark = true,
                            liveLocation = true,   // show current position on entry
                            recenterTick = mapRecenterTick   // 📍 hex → pan to me
                        )
                        // Console stripped to a clean map for now — only the
                        // (transparent) ticket list overlays; tap a city hex to
                        // populate it. Status/input/mic intentionally removed.
                        Column(modifier = Modifier.fillMaxSize()) {
                            ConsoleList(
                                // History overlay (long-press 🚗/🧾) wins over
                                // the destination/ticket list while it's shown.
                                rows = tripHistoryRows ?: tripDestinations.map { dest ->
                                    ConsoleRow(
                                        id = dest.purposeRef,
                                        primary = dest.label,
                                        secondary = listOfNotNull(
                                            dest.address?.takeIf { it.isNotBlank() },
                                            dest.city?.takeIf { it.isNotBlank() }
                                        ).joinToString(" · "),
                                        trailing = if (tripActive != null) "→" else "🚗",
                                        onClick = {
                                            if (tripActive != null)
                                                tripDeclareNext(dest.purposeRef, dest.label, "planned")
                                            else tripStart(dest.purposeRef, dest.label, "planned")
                                        }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(vertical = 4.dp),
                                emptyHint = ""
                            )
                        }
                    }
                } else {
                    // Idle / Smart Context mode
                    val smartStatus by mainViewModel.smartStatus.observeAsState("")
                    val smartPhoto by mainViewModel.activeSlotPhoto.observeAsState(null)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight)
                    ) {
                        if (smartPhoto != null) {
                            Image(
                                bitmap = smartPhoto!!.asImageBitmap(),
                                contentDescription = "Smart context photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.15f),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (smartStatus.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = smartStatus,
                                        color = Color(0xFF4CAF50),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            ConsoleView(
                                logs = consoleLogs,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(bottom = 8.dp),
                                scannerEnabled = scannerEnabled,
                                onScannerToggle = { mainViewModel.toggleScanner() }
                            )
                        }
                    }
                }
            }

            // Grid area — transparent background, hex gaps show console beneath
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .align(Alignment.BottomCenter)
            ) {
                SelectionAreaSheet(
                    renderCells = renderCells,
                    cellWidth = gridConfig.cellWidth,
                    cellHeight = gridConfig.cellHeight,
                    buttonGap = gridConfig.buttonGap,
                    networkState = networkHealthState,
                    regStatus = deviceRegistrationStatus,
                    currentUser = currentUserState,
                    viewingUser = viewingUserState,
                    exitButton = exitButtonState,
                    onButtonClick = onGridAction,
                    onButtonLongClick = { action ->
                        val result = mainViewModel.onButtonLongClick(action)
                        when (result) {
                            "handled" -> { /* Already handled by ViewModel (e.g. user button) */ }
                            // Long-press red ✕: background the app like a Home
                            // press — task stays alive, state untouched.
                            "minimize_app" -> {
                                var c: android.content.Context = context
                                while (c is android.content.ContextWrapper) {
                                    if (c is android.app.Activity) break
                                    c = c.baseContext
                                }
                                (c as? android.app.Activity)?.moveTaskToBack(true)
                            }
                            "capture_photo_continuous" -> {
                                navController.navigate("cameraScanScreen?scan_mode=workflow_capture_continuous")
                            }
                            "capture_barcode_continuous" -> {
                                navController.navigate("cameraScanScreen?scan_mode=barcode_continuous")
                            }
                            // Long-press on the merged Scan button: combined camera,
                            // photo-first (analyzer off, torch on).
                            "capture_photo_direct" -> {
                                navController.navigate("cameraScanScreen?scan_mode=photo_direct")
                            }
                            // 🚗 long-press → the last 20 trips as console rows
                            // (local Room data — works with no server at all).
                            "trip_history" -> tripScope.launch {
                                val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
                                val df = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.GERMANY)
                                val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY)
                                tripHistoryRows = db.tripDao().getRecentTrips(20).map { t ->
                                    val span = df.format(java.util.Date(t.startedAt)) +
                                        (t.endedAt?.let { "–" + tf.format(java.util.Date(it)) } ?: " · läuft")
                                    val km = if (t.startOdometerKm != null && t.endOdometerKm != null)
                                        "${(t.endOdometerKm - t.startOdometerKm).toInt()} km"
                                    else t.endOdometerKm?.let { "bis ${it.toInt()} km" }
                                    ConsoleRow(
                                        id = "trip_${t.id}",
                                        primary = "🚗 $span",
                                        secondary = listOfNotNull(
                                            t.purposeLabel?.takeIf { it.isNotBlank() } ?: t.purpose,
                                            t.vehiclePlate?.takeIf { it.isNotBlank() },
                                            km
                                        ).joinToString(" · "),
                                        trailing = when (t.status) {
                                            "synced" -> "✓"
                                            "recording" -> "⏺"
                                            else -> "⏳"
                                        }
                                    )
                                }
                            }
                            // 🧾 long-press → the last 20 logged expenses.
                            "trip_expense_history" -> tripScope.launch {
                                val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
                                val df = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.GERMANY)
                                val icons = mapOf(
                                    "fuel" to "⛽", "parking" to "🅿️",
                                    "toll" to "🛣️", "receipt" to "🧾"
                                )
                                val rows = db.tripDao().getRecentExpenses(20).map { p ->
                                    ConsoleRow(
                                        id = "exp_${p.id}",
                                        primary = (icons[p.kind] ?: "🧾") + " " +
                                            df.format(java.util.Date(p.ts)) +
                                            (p.odometerKm?.let { " · ${it.toInt()} km" } ?: ""),
                                        secondary = p.label,
                                        trailing = if (p.photoId != null) "📷" else null
                                    )
                                }
                                if (rows.isEmpty()) {
                                    mainViewModel.addLog("🧾 Keine Ausgaben erfasst")
                                } else {
                                    tripHistoryRows = rows
                                }
                            }
                            else -> {
                                // Intake sheet for repair slots only
                                if (action.startsWith("slot_")) {
                                    intakeLongPressAction = action
                                    intakeFormState.clear()
                                    try {
                                        val inputStream = context.assets.open("workflows/device_intake.json")
                                        val json = inputStream.bufferedReader().use { it.readText() }
                                        intakeConfigJson = json
                                        showIntakeSheet = true
                                    } catch (e: Exception) {
                                        mainViewModel.addLog("Error loading intake config: ${e.message}")
                                    }
                                }
                            }
                        }
                    },
                    onNetworkIndicatorClick = {
                        // Single tap → enter Network mode (status in console, hex actions).
                        mainViewModel.enterNetworkMode()
                    },
                    onNetworkIndicatorLongClick = {
                        // Pair in place: jump straight to the QR scanner; the result
                        // routes through handlePairingQrCode and the log streams into
                        // this console (no dedicated Pairing Console screen anymore).
                        viewModel.clearPairingLog()
                        navController.navigate("cameraScanScreen?scan_mode=pairing")
                    },
                    // 🎤 GLOBAL push-to-talk (action "voice_command"), pinned in
                    // every mode. Hold to talk. Trip mode feeds the live search
                    // box; every mode gets console feedback (Voice Commands P0).
                    onMicPress = {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            tripListening = true
                            lastVoiceAudioB64 = null
                            voiceRec.start()   // best-effort parallel capture (P2)
                            speech.start { text, isFinal ->
                                if (!isFinal) {
                                    // Live partials only drive the trip search box.
                                    if (isTripMode) tripQuery = text
                                    return@start
                                }
                                // Final transcript → 1) local registry match.
                                val local = mainViewModel.matchVoiceLocal(text)
                                if (local != null) {
                                    onGridAction(local)              // dispatch like a button tap
                                    return@start
                                }
                                // 1b) Spoken trip declaration ("я поехал в Karlsruhe" /
                                // "zu Doktor Steiner") → ARM a trip intent; the next
                                // IN_VEHICLE transition starts the trip with the armed
                                // fields (spec: .eck/TRIP_PURPOSE.md §10).
                                val intentPhrase =
                                    com.xelth.eckwms_movfast.voice.VoiceCommandManager.parseTripIntent(text)
                                if (intentPhrase != null) {
                                    tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        // A trip is ALREADY recording → the spoken destination
                                        // is the NEXT leg: at a checkpoint stop it is armed
                                        // (survives a trip close), mid-drive it merges onto
                                        // the running trip (editable until trip end).
                                        val open = com.xelth.eckwms_movfast.trips.TripManager
                                            .reconcileOpenTrip(context)
                                        if (open != null) {
                                            val mode = com.xelth.eckwms_movfast.trips.TripManager
                                                .declareNextDestination(
                                                    context, intentPhrase.destination, null, "voice"
                                                )
                                            mainViewModel.logVoiceInfo(
                                                if (mode == com.xelth.eckwms_movfast.trips.TripManager.NextDest.MERGED_LIVE)
                                                    "🚗 Ziel übernommen: „${intentPhrase.destination}\" (laufende Fahrt)"
                                                else
                                                    "🎯 Nächstes Ziel: „${intentPhrase.destination}\" — gilt ab Weiterfahrt"
                                            )
                                            return@launch
                                        }
                                        val ti = com.xelth.eckwms_movfast.trips.TripManager
                                            .armTripIntent(context, intentPhrase.destination)
                                        // An odometer reading already pre-set on the Km hex
                                        // (photo taken shortly BEFORE the voice command)
                                        // upgrades the estimate to a real reading.
                                        mainViewModel.pendingTripKm()?.let { km ->
                                            com.xelth.eckwms_movfast.trips.TripManager.attachIntentOdometer(
                                                km,
                                                if (mainViewModel.pendingTripKmPhotoId() != null) "photo" else "manual",
                                                mainViewModel.pendingTripKmPhotoId()
                                            )
                                        }
                                        mainViewModel.logVoiceInfo(
                                            "🚗 Fahrt-Absicht: „${intentPhrase.destination}\" — startet bei Bewegung" +
                                                (ti.odoKm?.let { " · ab ≈${it.toInt()} km" } ?: "") +
                                                (ti.plate?.let { " · $it" } ?: "")
                                        )
                                        mainViewModel.refreshTripGrid()  // Start hex → yellow "Armed"
                                        // Client binding: a NAMED client binds directly on an
                                        // unambiguous match (the naming IS the declaration);
                                        // an inferred single candidate is prefilled and logged
                                        // (Ja/Nein console buttons = next iteration; purpose
                                        // stays editable until trip end either way).
                                        try {
                                            val dest = voiceApi.fetchDestinations(query = intentPhrase.destination)
                                            val hits = dest?.results.orEmpty().filter { it.purposeRef.isNotBlank() }
                                            if (hits.size == 1) {
                                                val h = hits.first()
                                                com.xelth.eckwms_movfast.trips.TripManager
                                                    .bindTripIntentClient(h.purposeRef, h.label)
                                                mainViewModel.logVoiceInfo(
                                                    if (intentPhrase.clientNamed)
                                                        "· Kunde: ${h.label} (aus Ansage übernommen)"
                                                    else
                                                        "· Kunde angenommen: ${h.label} — bis Fahrtende änderbar"
                                                )
                                            } else if (hits.size > 1) {
                                                mainViewModel.logVoiceInfo(
                                                    "· ${hits.size} Kandidaten — Ziel bleibt „${intentPhrase.destination}\""
                                                )
                                            }
                                        } catch (e: Exception) {
                                            // binding is best-effort; the intent stays armed
                                        }
                                    }
                                    return@start
                                }
                                // 2) Trip mode's default = destination search.
                                if (isTripMode) {
                                    tripQuery = text
                                    return@start
                                }
                                // 3) Other modes: Gemini fallback on a local miss (P2).
                                val mode = mainViewModel.currentVoiceMode()
                                val commands = com.xelth.eckwms_movfast.voice.VoiceCommandManager.commandsFor(mode)
                                if (commands.isEmpty()) { mainViewModel.logVoiceMiss(text); return@start }
                                val audioB64 = lastVoiceAudioB64
                                tripScope.launch {
                                    mainViewModel.logVoiceInfo("🤖 frage KI…")
                                    // Pass 1: text only (cheap — no audio tokens).
                                    var res = voiceApi.resolveVoice(mode, text, commands, null)
                                    if (res == null || res.source == "off") { mainViewModel.logVoiceMiss(text); return@launch }
                                    res.action?.let { act ->
                                        mainViewModel.logVoiceGemini(text, commands.firstOrNull { it.action == act }?.description ?: act)
                                        onGridAction(act)
                                        return@launch
                                    }
                                    // Pass 2 (escalation, ≤1): multimodal re-listen with retained audio.
                                    if (res.needsAudio && audioB64 != null) {
                                        mainViewModel.logVoiceInfo("🤖 höre Aufnahme…")
                                        res = voiceApi.resolveVoice(mode, text, commands, audioB64)
                                        res?.action?.let { act ->
                                            mainViewModel.logVoiceGemini(text, commands.firstOrNull { it.action == act }?.description ?: act)
                                            onGridAction(act)
                                            return@launch
                                        }
                                    }
                                    mainViewModel.logVoiceMiss(text)
                                }
                            }
                        } else {
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onMicRelease = {
                        speech.stop()
                        tripListening = false
                        lastVoiceAudioB64 = try { voiceRec.stopAndGetWavBase64() } catch (e: Exception) { null }
                    }
                )
            }
        }
    }

    // Trip field: normal Android keyboard for plate/purpose (⌨ Keyboard hex). The
    // text field auto-focuses to raise the soft keyboard; 🎤 dictates into it.
    tripKbdField?.let { f ->
        val kbdFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { tripKbdField = null },
            title = { Text(if (f == "plate") "Kennzeichen" else "Purpose") },
            text = {
                Column {
                    OutlinedTextField(
                        value = tripKbdText,
                        onValueChange = { tripKbdText = if (f == "plate") it.uppercase() else it },
                        singleLine = f == "plate",
                        label = { Text(if (f == "plate") "Plate" else "Purpose") },
                        modifier = Modifier.fillMaxWidth().focusRequester(kbdFocus)
                    )
                    TextButton(onClick = launchKbdSpeech) { Text("🎤 Dictate") }
                }
                LaunchedEffect(Unit) { kbdFocus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = tripKbdText.trim()
                    if (t.isNotEmpty()) mainViewModel.applyTripFieldValue(f, t)
                    tripKbdField = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { tripKbdField = null }) { Text("Cancel") }
            }
        )
    }

    // Network mode: "Enter code" → onboard via 9eck.com by typing a short pairing code.
    if (showCodeDialog) {
        var pairCode by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCodeDialog = false },
            title = { Text("Connect via 9eck.com") },
            text = {
                Column {
                    Text("Type the short pairing code from your administrator.")
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = pairCode,
                        onValueChange = { pairCode = it.uppercase().trim() },
                        singleLine = true,
                        label = { Text("Pairing code") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCodeDialog = false
                        viewModel.pairWithCode(pairCode)
                    },
                    enabled = pairCode.isNotBlank()
                ) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showCodeDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Intake ModalBottomSheet
    if (showIntakeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showIntakeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
            ) {
                DynamicUiRenderer(
                    layoutJson = intakeConfigJson,
                    stateValues = intakeFormState.toMap(),
                    onValueChange = { key, value ->
                        intakeFormState[key] = value
                    },
                    onAction = { action, _ ->
                        mainViewModel.addLog("Intake action: $action ($intakeLongPressAction)")
                        when (action) {
                            "save_intake_details" -> {
                                val jsonPayload = org.json.JSONObject(intakeFormState as Map<*, *>).toString(2)
                                mainViewModel.addLog("--- Intake Payload ---")
                                mainViewModel.addLog(jsonPayload)
                                mainViewModel.addLog("--- End Payload ---")
                                viewModel.sendRepairEvent(
                                    intakeLongPressAction,
                                    "intake_save",
                                    org.json.JSONObject(intakeFormState as Map<*, *>).toString()
                                )
                                showIntakeSheet = false
                            }
                            "capture_evidence_box", "capture_evidence_device" -> {
                                showIntakeSheet = false
                                navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
                            }
                            "scan_link_hwb", "scan_link_sn" -> {
                                showIntakeSheet = false
                                navController.navigate("cameraScanScreen?scan_mode=barcode")
                            }
                        }
                    }
                )
            }
        }
    }

    // Shipment Picker BottomSheet (swipeable list)
    val showShipmentPicker by mainViewModel.showShipmentPicker.observeAsState(false)
    if (showShipmentPicker) {
        ModalBottomSheet(
            onDismissRequest = { mainViewModel.dismissShipmentPicker() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            // Observe shipment loading state to trigger recomposition
            val shipmentsLoaded by mainViewModel.shipmentsLoaded.observeAsState(false)
            val shipmentsError by mainViewModel.shipmentsError.observeAsState(null)
            val shipments = remember(shipmentsLoaded) { mainViewModel.getShipmentDisplayList() }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
            ) {
                // Header
                Text(
                    text = "Select Shipment  (long-press = manual)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))

                if (shipmentsError != null) {
                    // Show error message
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠️ Error loading shipments", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(shipmentsError!!, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (shipments.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No shipments available", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(shipments) { item ->
                            // OPAL reports raw German statuses ("Zugestellt");
                            // fold them into the same buckets as the English ones.
                            val statusNorm = item.status.lowercase()
                            val isDelivered = statusNorm == "delivered" || statusNorm.contains("zugestellt")
                            val bgColor = when {
                                item.isMatched -> Color(0xFF1B5E20) // dark green for auto-match
                                isDelivered || item.status == "cancelled" -> Color(0xFF212121)
                                else -> Color(0xFF2E2E2E)
                            }
                            val statusIcon = when {
                                isDelivered -> "✅"
                                item.status == "cancelled" -> "❌"
                                item.status == "error" -> "⚠️"
                                item.status == "shipped" || statusNorm.contains("transport") -> "\uD83D\uDE9A"
                                else -> "\uD83D\uDCE6"
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            mainViewModel.selectShipment(item.id)
                                        },
                                        onLongClick = {
                                            mainViewModel.openManualClientEntry()
                                        }
                                    ),
                                colors = CardDefaults.cardColors(containerColor = bgColor)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    // Line 1: icon + tracking | product type | date
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "$statusIcon ${item.trackingNumber}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (item.productType.isNotEmpty()) {
                                            Text(
                                                text = item.productType,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFFF9800),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    // Line 2: Client (smart-detected) bold
                                    Text(
                                        text = item.clientName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFE0E0E0)
                                    )
                                    if (item.clientAddress.isNotEmpty()) {
                                        Text(
                                            text = item.clientAddress,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    // Line 4: status | received by | date | MATCH
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        val infoLeft = buildString {
                                            append(item.status)
                                            if (item.receivedBy.isNotEmpty()) append(" | ${item.receivedBy}")
                                        }
                                        Text(
                                            text = infoLeft,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                        Row {
                                            if (item.isMatched) {
                                                Text(
                                                    text = "MATCH ",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF4CAF50),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Text(
                                                text = item.date,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFAAAAAA)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Receiving Workflow Step Modal (manual entry or device info)
    if (receivingModalJson != null) {
        LaunchedEffect(Unit) {
            mainViewModel.receivingData.forEach { (k, v) ->
                if (v is String) receivingModalFormState.putIfAbsent(k, v)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { mainViewModel.dismissReceivingModal() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
            ) {
                DynamicUiRenderer(
                    layoutJson = receivingModalJson!!,
                    stateValues = receivingModalFormState.toMap(),
                    onValueChange = { key, value ->
                        receivingModalFormState[key] = value
                    },
                    onAction = { action, _ ->
                        mainViewModel.addLog("Receiving modal: $action")
                        when (action) {
                            "close_modal" -> {
                                receivingModalFormState.forEach { (k, v) ->
                                    mainViewModel.setReceivingDataValue(k, v)
                                }
                                mainViewModel.dismissReceivingModal()
                                mainViewModel.advanceToNextStep()
                                receivingModalFormState.clear()
                            }
                            "scan_serial" -> {
                                mainViewModel.dismissReceivingModal()
                                navController.navigate("cameraScanScreen?scan_mode=barcode")
                            }
                        }
                    }
                )
            }
        }
    }

    // Slot long-press menu: delete + change background from photo avatars
    if (showSlotMenu) {
        val slotBarcode = mainViewModel.getSlotBarcode(slotMenuAction)
        val slotPhotos = remember(slotMenuAction) { mainViewModel.getActiveSlotPhotos() }
        var showDeleteConfirm by remember { mutableStateOf(false) }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                title = { Text("Delete Slot?") },
                text = { Text("Unbind device $slotBarcode and clear all data?") },
                confirmButton = {
                    TextButton(onClick = {
                        mainViewModel.deleteSlot(slotMenuAction)
                        showDeleteConfirm = false
                        showSlotMenu = false
                    }) { Text("Delete", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = Color.White) }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showSlotMenu = false },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                title = { Text("Slot: ${slotBarcode.takeLast(10)}") },
                text = {
                    Column {
                        // Delete button
                        TextButton(onClick = { showDeleteConfirm = true }) {
                            Text("Delete Slot", color = Color.Red)
                        }
                        // Change background section
                        if (slotPhotos.size > 1) {
                            HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))
                            Text(
                                "Change Background:",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            // Scrollable row of photo thumbnails
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                slotPhotos.forEachIndexed { idx, photo ->
                                    val isCurrentBg = mainViewModel.activeSlotPhoto.value == photo
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .background(
                                                if (isCurrentBg) Color(0xFF4CAF50) else Color.DarkGray,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(if (isCurrentBg) 2.dp else 0.dp)
                                            .combinedClickable(
                                                onClick = {
                                                    mainViewModel.changeSlotBackground(idx)
                                                    showSlotMenu = false
                                                }
                                            )
                                    ) {
                                        Image(
                                            bitmap = photo.asImageBitmap(),
                                            contentDescription = "Photo #${idx + 1}",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        } else if (slotPhotos.isEmpty()) {
                            Text(
                                "No photos yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSlotMenu = false }) { Text("Close", color = Color.White) }
                }
            )
        }
    }

    // --- Multi-User Dialogs ---
    val showUserDialog by mainViewModel.showUserDialog.observeAsState(false)
    val userDialogMode by mainViewModel.userDialogMode.observeAsState("view")
    val showPinDialog by mainViewModel.showPinDialog.observeAsState(false)
    val availableUsers by com.xelth.eckwms_movfast.ui.viewmodels.UserManager.availableUsers.collectAsState()

    // User Selection Dialog
    if (showUserDialog) {
        AlertDialog(
            onDismissRequest = { mainViewModel.dismissUserDialog() },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = {
                Text(if (userDialogMode == "login") "Anmeldung" else "View User")
            },
            text = {
                if (availableUsers.isEmpty()) {
                    Text("No users available. Check server connection.", color = Color.Gray)
                } else {
                    LazyColumn {
                        items(availableUsers) { user ->
                            val isCurrent = currentUserState?.id == user.id
                            val isViewing = viewingUserState?.id == user.id
                            val bgColor = when {
                                isCurrent && isViewing -> Color(0xFF1B5E20).copy(alpha = 0.3f)
                                isViewing -> Color(0xFFFDD835).copy(alpha = 0.2f)
                                else -> Color.Transparent
                            }
                            TextButton(
                                onClick = { mainViewModel.onUserSelected(user) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        user.getDialogName(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isCurrent) {
                                        Text(
                                            "current",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF4CAF50)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mainViewModel.dismissUserDialog() }) { Text("Cancel", color = Color.White) }
            },
            dismissButton = {
                // Session action: the logged-in user can change their own password.
                if (currentUserState != null) {
                    TextButton(onClick = {
                        mainViewModel.dismissUserDialog()
                        mainViewModel.openChangePasswordDialog()
                    }) { Text("🔑 Change password", color = Color(0xFFFF9800)) }
                }
            }
        )
    }

    // PIN Input Dialog
    if (showPinDialog) {
        // PIN length varies per user (admins may exceed the usual 6) — the server
        // is the only real validator, the UI just needs a sane min/max.
        val minPinLength = 4
        val maxPinLength = 12
        var pinValue by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { mainViewModel.dismissPinDialog() },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("PIN eingeben") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // PIN display (dots)
                    Text(
                        text = "●".repeat(pinValue.length) + "○".repeat((6 - pinValue.length).coerceAtLeast(0)),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    if (pinError) {
                        Text("Wrong PIN", color = Color.Red, style = MaterialTheme.typography.bodySmall)
                    }
                    // Numpad grid
                    val buttons = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("⌫", "0", "✓")
                    )
                    buttons.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            row.forEach { digit ->
                                TextButton(
                                    onClick = {
                                        pinError = false
                                        when (digit) {
                                            "⌫" -> if (pinValue.isNotEmpty()) pinValue = pinValue.dropLast(1)
                                            "✓" -> if (pinValue.length >= minPinLength) mainViewModel.onPinSubmitted(pinValue)
                                            else -> if (pinValue.length < maxPinLength) pinValue += digit
                                        }
                                    },
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Text(digit, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { mainViewModel.dismissPinDialog() }) { Text("Cancel", color = Color.White) }
            }
        )
    }

    // Change Password Dialog (self-service + forced variant for seeded accounts)
    val showChangePassword by mainViewModel.showChangePasswordDialog.observeAsState(false)
    val changePwForced by mainViewModel.changePasswordForced.observeAsState(false)
    val changePwError by mainViewModel.changePasswordError.observeAsState(null)
    val changePwBusy by mainViewModel.changePasswordBusy.observeAsState(false)
    if (showChangePassword) {
        var oldPw by remember { mutableStateOf("") }
        var newPw by remember { mutableStateOf("") }
        var confirmPw by remember { mutableStateOf("") }
        val pwFieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFFFF9800),
            unfocusedBorderColor = Color.Gray,
            focusedLabelColor = Color(0xFFFF9800),
            unfocusedLabelColor = Color.Gray,
            cursorColor = Color.White
        )
        AlertDialog(
            onDismissRequest = { if (!changePwForced) mainViewModel.dismissChangePasswordDialog() },
            properties = DialogProperties(
                dismissOnBackPress = !changePwForced,
                dismissOnClickOutside = !changePwForced
            ),
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text(if (changePwForced) "Set your password" else "Change password") },
            text = {
                Column {
                    if (changePwForced) {
                        Text(
                            "Your account was set up with a temporary password. Choose a new one to continue.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    OutlinedTextField(
                        value = oldPw,
                        onValueChange = { oldPw = it },
                        label = { Text("Current password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = pwFieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = newPw,
                        onValueChange = { newPw = it },
                        label = { Text("New password (min 8)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = pwFieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = confirmPw,
                        onValueChange = { confirmPw = it },
                        label = { Text("Confirm new password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = pwFieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    if (changePwError != null) {
                        Text(
                            changePwError!!,
                            color = Color.Red,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !changePwBusy,
                    onClick = { mainViewModel.submitChangePassword(oldPw, newPw, confirmPw) }
                ) { Text(if (changePwBusy) "Saving…" else "Save", color = Color(0xFFFF9800)) }
            },
            dismissButton = {
                if (!changePwForced) {
                    TextButton(onClick = { mainViewModel.dismissChangePasswordDialog() }) {
                        Text("Cancel", color = Color.White)
                    }
                }
            }
        )
    }

    // Full-screen photo overlay
    if (fullScreenPhoto != null) {
        Dialog(
            onDismissRequest = { fullScreenPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable { fullScreenPhoto = null },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = fullScreenPhoto!!.asImageBitmap(),
                    contentDescription = "Full screen",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }

    // Trip mode: start-odometer dialog (after starting a trip from a ticket)
    if (tripOdometerStart) {
        com.xelth.eckwms_movfast.ui.screens.components.OdometerDialog(
            isStart = true,
            onDismiss = { tripOdometerStart = false },
            vehicles = tripVehicles,
            onSave = { km, source, photoId, vehicle ->
                tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
                    val target = db.tripDao().getOpenTrip()?.id
                    if (target != null) {
                        db.tripDao().setStartOdometer(target, km, source, photoId)
                        if (vehicle != null) {
                            com.xelth.eckwms_movfast.trips.VehicleManager.resolveAndAttach(
                                context, target, vehicle.vehicleId, vehicle.plate, vehicle.photoId
                            )
                        }
                        com.xelth.eckwms_movfast.trips.TripManager.publishActiveTrip(db.tripDao().getOpenTrip())
                    }
                }
                tripOdometerStart = false
            }
        )
    }

    // Trip mode: end-odometer dialog (Stop hex) → write end km, then finalize.
    if (tripOdometerEnd) {
        com.xelth.eckwms_movfast.ui.screens.components.OdometerDialog(
            isStart = false,
            // "Überspringen" / tapping outside STILL stops the trip — the end km is
            // optional (the distance is estimated from the track, as the dialog
            // says). Previously this only closed the dialog and left the trip open,
            // so the "Fahrt●" dot stayed and the trip could not be stopped.
            onDismiss = {
                tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    com.xelth.eckwms_movfast.trips.TripManager.stopTrip(context, graceful = false)
                }
                tripOdometerEnd = false
            },
            vehicles = tripVehicles,
            onSave = { km, source, photoId, _ ->
                tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
                    val target = db.tripDao().getOpenTrip()?.id
                    if (target != null) {
                        db.tripDao().setEndOdometer(target, km, source, photoId)
                    }
                    com.xelth.eckwms_movfast.trips.TripManager.stopTrip(context, graceful = false)
                }
                tripOdometerEnd = false
            }
        )
    }
}

