// app/src/main/java/com/xelth/eckwms_movfast/scanners/ScannerManager.kt
package com.xelth.eckwms_movfast.scanners

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.LicenseState
import com.xelth.eckwms_movfast.utils.SettingsManager
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.RegionSizeType
import com.xcheng.scanner.TextCaseType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

private const val TAG = "ScannerManager"

/** Vendor scan-engine app that owns camera device 2 (the laser/imager sensor). */
private const val SCANNER_PKG = "com.xcheng.scannere3"

// Watchdog tuning
private const val WATCHDOG_INTERVAL_MS = 15_000L      // how often to probe liveness
private const val INITIAL_GRACE_MS = 8_000L           // let init + license activation settle
private const val SOFT_SETTLE_MS = 1_800L             // wait after a soft re-init before re-probe
private const val HARD_RESTART_MIN_INTERVAL_MS = 5 * 60_000L
private const val MAX_HARD_RESTARTS_PER_SESSION = 3
/** Within this long after SCREEN_ON a trigger press uses the assisted (resume-first)
 *  scan path — the engine is likely still warming up from the vendor's own resume. */
private const val WAKE_WARMUP_WINDOW_MS = 6_000L
/** Two trigger signals (KeyEvent + vendor broadcast) within this window = one press. */
private const val TRIGGER_DEDUP_MS = 150L
/** How many times to re-check that we actually came back in front of the vendor UI. */
private const val RETURN_RETRY_MAX = 3
/** Hardware scan-trigger keycodes (vendor config: left=140/F10, front=141/F11,
 *  right=142/F12; 138–139 included for other key layouts of the same family). */
private val TRIGGER_KEYCODES = 138..142
/** Wake-acknowledge vibration: the wake key press is consumed by the system (no
 *  KeyEvent, no vendor broadcast, no laser) — this pulse is the worker's only
 *  feedback that the press DID something. Felt buzz + no laser = "device woke,
 *  press again to scan"; laser = it scanned. */
private const val WAKE_ACK_VIBRATE_MS = 80L

/** Health of the hardware scan engine, surfaced for the UI + startScan self-heal. */
enum class ScannerHealth { HEALTHY, RECOVERING, DOWN }

/** Result of one liveness probe of the vendor scan service. */
private enum class Probe { ALIVE, SUSPECT, DEAD }




/**
 * Синглтон для управления сканером на уровне приложения
 */
class ScannerManager private constructor(private val application: Application) {
    // LiveData для передачи результатов сканирования
    private val _scanResult = MutableLiveData<String>()
    val scanResult: LiveData<String> get() = _scanResult

    // LiveData для типа штрих-кода
    private val _barcodeType = MutableLiveData<String>()
    val barcodeType: LiveData<String> get() = _barcodeType

    // Флаг для отслеживания состояния инициализации
    private var isInitialized = false

    /** True only on real MovFast PDA hardware — i.e. the vendor scan-engine
     *  package is actually installed. A bare Android phone/tablet (used for
     *  dev/testing, no laser/imager) will never have it; on such a device the
     *  hardware path must not even attempt to init/bind/watchdog — there is
     *  nothing there to recover, and the endless soft-reinit → rate-limited
     *  hard-restart loop just burns battery and spams the log forever. The
     *  camera-based ML Kit scan (CameraScanScreen) is the only scan path then. */
    val hasHardwareScanner: Boolean by lazy {
        val present = try {
            application.packageManager.getPackageInfo(SCANNER_PKG, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
        if (!present) {
            Log.i(TAG, "$SCANNER_PKG not installed — not PDA hardware, hardware scanner disabled (camera-only scanning)")
        }
        present
    }

    // Обработчик главного потока
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Watchdog / self-heal state ---------------------------------------
    private val _health = MutableLiveData(ScannerHealth.HEALTHY)
    /** Observe from the UI to show a "scanner recovering / down" indicator. */
    val health: LiveData<ScannerHealth> get() = _health

    /** Set by [EckwmsApp]'s ActivityLifecycleCallbacks. ALL watchdog recovery is
     *  gated on this: recovery resumes/reopens the scan camera (device 2), which
     *  must never happen while another app may be using the shared-ISP camera 0
     *  (the system auto-suspends the scan engine for them — see .eck/TECH_DEBT.md
     *  item 9). Defaults to FALSE: a headless process start (WorkManager sync,
     *  boot) has no activity, and assuming foreground there would let the watchdog
     *  fight other apps' camera sessions from the background. */
    @Volatile var appInForeground: Boolean = false

    @Volatile private var suspectStreak = 0
    @Volatile private var hardRestartCount = 0
    @Volatile private var lastHardRestartTs = 0L

    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null
    /** Single-flights [recover] — concurrent recoveries would tear each other's
     *  half-rebound binder apart and burn the hard-restart budget. */
    private val recoverMutex = Mutex()

    /**
     * Инициализирует сканер с использованием ScannerSymResult
     */
    fun initialize() {
        val timestamp = System.currentTimeMillis()
        Log.d(TAG, "[$timestamp] Starting scanner initialization...")

        if (isInitialized) {
            Log.d(TAG, "[$timestamp] Scanner already initialized")
            return
        }

        if (!hasHardwareScanner) return

        Log.d(TAG, "[$timestamp] ⭐ Initializing scanner at application level...")

        // Инициализация XCScannerWrapper с получением типа и штрих-кода
        XCScannerWrapper.initialize(application) { symbology, barcode ->
            val callbackTimestamp = System.currentTimeMillis()
            Log.d(TAG, "[$callbackTimestamp] ⭐ Scan result: type=$symbology, barcode=$barcode")

            // A real decode is the strongest possible liveness signal — the engine
            // is clearly alive, so clear any pending "recovering/down" state.
            lastDecodeTs = SystemClock.elapsedRealtime()
            suspectStreak = 0
            hardRestartCount = 0
            if (_health.value != ScannerHealth.HEALTHY) setHealth(ScannerHealth.HEALTHY)

            // Сохраняем тип штрих-кода
            _barcodeType.postValue(symbology)

            // Сохраняем сам штрих-код
            _scanResult.postValue(barcode)
        }

        // Настройка сканера
        configureScanner()

        isInitialized = true
        val endTimestamp = System.currentTimeMillis()
        Log.d(TAG, "[$endTimestamp] ✓ Scanner successfully initialized (took ${endTimestamp - timestamp}ms)")
    }

    /**
     * Возвращает тип последнего отсканированного штрихкода
     * Использует реальный тип из SDK callback (ScannerSymResult)
     * @return Строковое обозначение типа штрихкода (QRCODE, DATAMATRIX, CODE128, etc.)
     */
    fun getLastBarcodeType(): String {
        return _barcodeType.value ?: "UNKNOWN"
    }

    /**
     * Настраивает сканер с оптимальными параметрами
     */
    private fun configureScanner() {
        Log.d(TAG, "⭐ Настройка сканера...")

        // Максимальная область сканирования
        XCScannerWrapper.setScanRegionSize(RegionSizeType.VIEWSIZE_100)

        // Максимальное время сканирования
        XCScannerWrapper.setTimeout(9)

        // Звуковое и вибро-оповещение при успешном сканировании
        XCScannerWrapper.setSuccessNotification(NotificationType.SOUND_VIBRATOR)

        // Включить LED-индикатор при успешном сканировании
        XCScannerWrapper.enableSuccessIndicator(true)

        // Настройка обработки текста
        XCScannerWrapper.setTextCase(TextCaseType.NONE)

        // Включить распознавание ВСЕХ доступных типов штрих-кодов
        // 2D коды
        XCScannerWrapper.enableBarcodeType(BarcodeType.QRCODE, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.DATAMATRIX, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.PDF417, true)

        // 1D коды - семейство Code
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE128, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE39, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE93, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE11, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODABAR, true)

        // EAN/UPC семейство
        XCScannerWrapper.enableBarcodeType(BarcodeType.EAN13, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.EAN8, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.UPCA, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.UPCE, true)

        // Другие промышленные коды (если поддерживаются)
        XCScannerWrapper.enableBarcodeType(BarcodeType.GS1_128, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.GS1_DATABAR, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.MSI, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.AZTEC, true)

        Log.d(TAG, "✓ Включены все доступные типы штрихкодов (2D: QR, DataMatrix, PDF417; 1D: Code128/93/39/11, Codabar, EAN, UPC, GS1, MSI, Aztec)")

        Log.d(TAG, "✓ Сканер настроен с оптимальными параметрами")
    }

    /**
     * Останавливает сканирование и освобождает ресурсы сканера при завершении работы приложения
     */
    fun cleanup() {
        if (!isInitialized) return

        // Останавливаем сканирование
        XCScannerWrapper.stopScan()
        if (XCScannerWrapper.isLoopScanRunning()) {
            XCScannerWrapper.stopLoopScan()
        }

        // Очистка ресурсов изображений
        cleanupImageResources()

        // Деинициализируем сканер
        XCScannerWrapper.deinitialize(application)

        isInitialized = false
        Log.d(TAG, "✓ Ресурсы сканера освобождены")
    }



    /**
     * Проверяет, инициализирован ли сканер
     * @return true если сканер инициализирован, false в противном случае
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }

    /**
     * Включает/выключает определенный тип штрих-кода
     */
    fun enableBarcodeType(barcodeType: String, enabled: Boolean) {
        if (!isInitialized) initialize()
        XCScannerWrapper.enableBarcodeType(barcodeType, enabled)
    }

    /**
     * Запускает сканирование
     */
    fun startScan() {
        if (!isInitialized) initialize()
        // Opportunistic self-heal: if the watchdog last flagged the engine as DOWN,
        // kick off an async recovery so the NEXT trigger works even if this one is
        // still on a dead engine. Non-blocking — we still issue startScan now.
        if (_health.value == ScannerHealth.DOWN) {
            Log.w(TAG, "startScan while health=DOWN → kicking recovery")
            watchdogScope.launch { recover() }
        }
        XCScannerWrapper.startScan()
    }

    /**
     * Останавливает сканирование
     */
    fun stopScan() {
        if (!isInitialized) return
        XCScannerWrapper.stopScan()
    }

    /**
     * Запускает непрерывное сканирование
     */
    fun startLoopScan(intervalMs: Int = 500) {
        if (!isInitialized) initialize()
        Log.d(TAG, ">>> startLoopScan called with interval=$intervalMs")
        XCScannerWrapper.setLoopScanInterval(intervalMs)
        XCScannerWrapper.startLoopScan()
        Log.d(TAG, ">>> startLoopScan completed, isRunning=${XCScannerWrapper.isLoopScanRunning()}")
    }

    /**
     * Останавливает непрерывное сканирование
     */
    fun stopLoopScan() {
        if (!isInitialized) return
        Log.d(TAG, ">>> stopLoopScan called, isRunning before stop=${XCScannerWrapper.isLoopScanRunning()}")
        XCScannerWrapper.stopLoopScan()
        Log.d(TAG, ">>> stopLoopScan completed, isRunning after stop=${XCScannerWrapper.isLoopScanRunning()}")
    }

    /**
     * Release the hardware scanner's camera so a CameraX / ML Kit session (barcode
     * camera scan, odometer/plate OCR, photos) can use the camera. On this PDA the
     * scan engine and the app camera share one ISP — opening the app camera while
     * the scanner holds it wedges the scan camera until reboot. Suspend before
     * opening any CameraX screen and [resumeScanService] after closing it.
     */
    fun suspendScanService() {
        // Always delegate — the wrapper records the camera hold even when the engine
        // is not initialized, so the watchdog never auto-resumes under an open screen.
        Log.d(TAG, ">>> suspendScanService (free camera for ML Kit)")
        XCScannerWrapper.suspendScanService()
    }

    /** Resume the hardware scanner after a CameraX/ML Kit session is done. */
    fun resumeScanService() {
        Log.d(TAG, ">>> resumeScanService (re-acquire scan camera)")
        XCScannerWrapper.resumeScanService()
    }

    /**
     * Устанавливает режим подсветки
     */
    fun setFlashLightsMode(flashMode: Int) {
        if (!isInitialized) initialize()
        XCScannerWrapper.setFlashLightsMode(flashMode)
    }

    /**
     * Устанавливает режим прицела
     */
    fun setAimerLightsMode(aimerMode: Int) {
        if (!isInitialized) initialize()
        XCScannerWrapper.setAimerLightsMode(aimerMode)
    }

    /**
     * Checks if the device's scanner hardware supports image capture
     * @return true if image capture is supported, false otherwise
     */
    fun isImageCaptureSupported(): Boolean {
        if (!isInitialized()) {
            Log.d(TAG, "Scanner not initialized - cannot check image support")
            return false
        }
        return XCScannerWrapper.isImageCaptureSupported()
    }


    /**
     * Проверяет, запущено ли непрерывное сканирование
     */
    fun isLoopScanRunning(): Boolean {
        return isInitialized && XCScannerWrapper.isLoopScanRunning()
    }

    // =====================================================================
    // Watchdog: detect a hung / dead vendor scan engine and restart it.
    //
    // On this XCheng/MTK PDA the hardware scanner IS camera device 2, owned by
    // the system process `com.xcheng.scannere3`. Two failure modes were observed:
    //   1. The vendor process dies and Android marks it "bad" — a plain re-bind
    //      is refused ("process is bad"); only a foreground activity launch of the
    //      vendor app clears the flag and revives it.
    //   2. The engine wedges after a camera/ISP conflict — the binder still
    //      answers but the license drops to INACTIVE (normally ACTIVED).
    // The watchdog probes for both and escalates: soft SDK re-init → (if needed)
    // a rate-limited foreground relaunch of the vendor app to reset the process.
    // =====================================================================

    private fun setHealth(h: ScannerHealth) {
        if (_health.value != h) {
            _health.postValue(h)
            Log.d(TAG, "scanner health → $h")
        }
    }

    /** Start the periodic health probe. Idempotent. */
    fun startWatchdog() {
        if (!hasHardwareScanner) return
        if (watchdogJob?.isActive == true) return
        Log.d(TAG, "▶ scanner watchdog started (probe every ${WATCHDOG_INTERVAL_MS}ms)")
        watchdogJob = watchdogScope.launch {
            delay(INITIAL_GRACE_MS)
            while (isActive) {
                try { tick() } catch (e: Throwable) { Log.e(TAG, "watchdog tick error", e) }
                delay(WATCHDOG_INTERVAL_MS)
            }
        }
    }

    fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /** elapsedRealtime of the last SCREEN_ON. */
    @Volatile private var lastScreenOnTs = 0L
    /** elapsedRealtime of the last hardware trigger press (KeyEvent or vendor broadcast). */
    @Volatile private var lastTriggerTs = 0L
    /** elapsedRealtime of the last successful decode (set in the scan callback). */
    @Volatile private var lastDecodeTs = 0L

    /**
     * Record a device wake (SCREEN_ON) and acknowledge it with a short vibration.
     * The key press that wakes from sleep is consumed entirely by the system — no
     * KeyEvent, no vendor broadcast, no laser — so waking and scanning are two
     * separate presses by design: press 1 wakes (felt as this buzz, no laser),
     * press 2 scans. The former one-press-from-sleep read the wake keycode from
     * the system log, but READ_LOGS meant the recurring "Geräteprotokolle"
     * consent dialog on every fresh grant state — dropped 2026-07-21 (owner call:
     * "просто упростим").
     */
    fun onScreenOn() {
        lastScreenOnTs = SystemClock.elapsedRealtime()
        try {
            application.getSystemService(android.os.VibratorManager::class.java)
                ?.defaultVibrator
                ?.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        WAKE_ACK_VIBRATE_MS, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
        } catch (e: Exception) {
            Log.w(TAG, "wake-ack vibration failed: ${e.message}")
        }
    }

    /**
     * Called when an Activity resumes. Pre-warms the engine (resume if the vendor
     * left it suspended on SCREEN_OFF / low battery) so the next trigger press
     * scans instantly. Safe: we are foreground, so no other app can be mid-session
     * on the shared-ISP camera, and our own camera screens are counted in
     * [XCScannerWrapper.cameraHoldCount].
     */
    fun onAppResumed() {
        if (!isInitialized) return
        watchdogScope.launch {
            if (XCScannerWrapper.cameraHoldCount() <= 0 &&
                XCScannerWrapper.isScanServiceSuspendingSafe()
            ) {
                Log.d(TAG, "onAppResumed: engine suspended → pre-warm resume")
                withContext(Dispatchers.Main) { XCScannerWrapper.forceResumeScanService() }
            }
        }
    }

    /**
     * ONE entry point for a hardware trigger press, from both signal sources:
     * MainActivity.dispatchKeyEvent (F8–F11) and the system's
     * `com.xcheng.scanner.action.OPEN_SCAN_BROADCAST`. Because this only ever fires
     * on a physical trigger press, it can never beam on a power-button/charger wake.
     *
     * Normally just [startScan]; switches to the assisted path (resume the engine,
     * wait for the camera, then scan) when the engine is suspended — a stranded
     * vendor suspend, or the SECOND press right after a wake (press 1 only woke
     * the device; the engine may not have re-acquired the camera yet).
     */
    fun onScanTriggerPressed(source: String, keyCode: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerTs < TRIGGER_DEDUP_MS) { lastTriggerTs = now; return }
        lastTriggerTs = now
        // The vendor broadcast arrives even while we're backgrounded (the user may
        // be pressing the trigger inside ANOTHER app, possibly mid-camera-session).
        // Neither beam nor resume then — that's the other app's press, not ours.
        if (!appInForeground) {
            Log.d(TAG, "trigger press (source=$source) ignored: app not foreground")
            return
        }
        if (!isInitialized) initialize()
        val justWoke = now - lastScreenOnTs <= WAKE_WARMUP_WINDOW_MS
        val holdFree = XCScannerWrapper.cameraHoldCount() <= 0
        val suspended = holdFree && XCScannerWrapper.isScanServiceSuspendingSafe()
        Log.d(TAG, "trigger press (source=$source key=$keyCode justWoke=$justWoke suspended=$suspended)")
        // Assist ONLY when the engine is actually suspended (the vendor's native
        // trigger handling is deaf then, and can't produce a competing decode).
        // When it is running — including right after a wake, thanks to the
        // onAppResumed pre-warm — the vendor decodes the press natively; our
        // immediate startScan merges into the same decode session, whereas the
        // assisted path's ~400ms delay would start a SECOND session and double-scan
        // (observed on device).
        if (suspended) {
            watchdogScope.launch { assistedScan(pressTs = now) }
        } else {
            startScan()
        }
    }

    /**
     * Resume a suspended engine, wait until it has the camera back, then scan.
     * Used only in response to a physical trigger press ([onScanTriggerPressed]),
     * so firing the beam at the end is exactly what the user asked for. If a decode
     * already landed for this press (the vendor's native path beat us to it), the
     * press has been served — don't start a second session.
     */
    private suspend fun assistedScan(pressTs: Long) {
        withContext(Dispatchers.Main) { XCScannerWrapper.forceResumeScanService() }
        var waited = 0
        while (XCScannerWrapper.isScanServiceSuspendingSafe() && waited < 1500) {
            delay(150); waited += 150
        }
        delay(250)  // small camera warmup margin
        if (!appInForeground || XCScannerWrapper.cameraHoldCount() > 0) return
        if (lastDecodeTs >= pressTs) {
            Log.d(TAG, "assistedScan: press already served by a decode — skipping startScan")
            return
        }
        Log.d(TAG, "assistedScan: firing startScan")
        withContext(Dispatchers.Main) { startScan() }
    }

    /** One liveness probe of the vendor scan service (off the main thread). */
    private fun probe(): Probe {
        val version = XCScannerWrapper.getServiceVersionSafe()
        val license = XCScannerWrapper.getLicenseStateSafe()
        return when {
            // Binder to the vendor process is gone entirely → process dead.
            version == null && license == XCScannerWrapper.LICENSE_UNREACHABLE -> Probe.DEAD
            // Healthy steady state.
            license == LicenseState.ACTIVED -> Probe.ALIVE
            // Activating, or the license server is unreachable (offline warehouse) —
            // the engine itself answers and keeps decoding on its device-local
            // activation; recovering here would churn re-inits forever while offline.
            version != null && (license == LicenseState.ACTIVATING ||
                license == LicenseState.NETWORK_ISSUE) -> Probe.ALIVE
            // Binder answers but the license dropped (camera/ISP wedge) — suspect.
            else -> Probe.SUSPECT
        }
    }

    private suspend fun tick() {
        if (!isInitialized) return
        // EVERYTHING the watchdog does (resume, rebind, vendor relaunch) can reopen
        // the scan camera. In the background that would fight other apps' sessions
        // on the shared-ISP camera — the system auto-suspends the scan engine for
        // them, and we must not undo that. Probe/heal only while we're in front.
        if (!appInForeground) return
        // Stuck-suspend recovery. The vendor's E3Util releases the scan camera on
        // SCREEN_OFF / low battery (state → SUSPENDING) and can strand it there with
        // no event to resume — the binder + license stay healthy, so probe() below
        // reports ALIVE and would never notice. If nothing of ours legitimately holds
        // the camera (no CameraX screen open) and we're foreground, resume it.
        if (XCScannerWrapper.cameraHoldCount() <= 0 &&
            XCScannerWrapper.isScanServiceSuspendingSafe()
        ) {
            Log.w(TAG, "watchdog: engine stuck SUSPENDED (no camera hold) → resuming")
            withContext(Dispatchers.Main) { XCScannerWrapper.forceResumeScanService() }
        }
        when (probe()) {
            Probe.ALIVE -> {
                suspectStreak = 0
                hardRestartCount = 0
                setHealth(ScannerHealth.HEALTHY)
            }
            Probe.SUSPECT -> {
                suspectStreak++
                Log.w(TAG, "watchdog: engine SUSPECT (streak=$suspectStreak) — nudging license")
                XCScannerWrapper.activateLicenseSafe()
                // One transient inactive tick is normal; act on a persistent one.
                if (suspectStreak >= 2) recover() else setHealth(ScannerHealth.RECOVERING)
            }
            Probe.DEAD -> {
                Log.e(TAG, "watchdog: engine DEAD (binder gone) — recovering")
                suspectStreak = 0
                recover()
            }
        }
    }

    /**
     * Escalating recovery: (1) soft SDK re-init to rebind; if that doesn't bring
     * the engine back, (2) a rate-limited foreground relaunch of the vendor app to
     * reset a "bad" process. Runs on the watchdog (Default) dispatcher.
     *
     * Single-flighted via [recoverMutex] and deferred (not failed) while the app is
     * backgrounded or one of our camera screens holds the sensor — recovery reopens
     * the scan camera, which must never race a live camera-0 session on the shared
     * ISP. The next watchdog tick / trigger press retries.
     */
    private suspend fun recover() {
        if (!recoverMutex.tryLock()) {
            Log.d(TAG, "recover: already in progress — skipped")
            return
        }
        try {
            if (!appInForeground) {
                Log.w(TAG, "recover: app not foreground — deferred")
                return
            }
            if (XCScannerWrapper.cameraHoldCount() > 0) {
                Log.w(TAG, "recover: a camera screen holds the sensor — deferred")
                setHealth(ScannerHealth.RECOVERING)
                return
            }
            setHealth(ScannerHealth.RECOVERING)

            // Step 1 — soft: deInit + re-init the SDK (rebinds to the service, which
            // Android auto-starts if the process is merely gone but not "bad").
            Log.w(TAG, "recover: soft re-init of the scan SDK")
            if (softReinit() && probe() == Probe.ALIVE) {
                Log.d(TAG, "recover: soft re-init succeeded")
                suspectStreak = 0
                hardRestartCount = 0
                setHealth(ScannerHealth.HEALTHY)
                return
            }

            // Step 2 — hard: relaunch the vendor app to clear a "bad process" mark.
            if (tryHardRestart()) {
                delay(SOFT_SETTLE_MS)
                // Rebind to the freshly-started vendor process.
                softReinit()
                if (probe() != Probe.DEAD) {
                    Log.d(TAG, "recover: hard restart revived the engine")
                    suspectStreak = 0
                    setHealth(ScannerHealth.HEALTHY)
                    return
                }
            }

            Log.e(TAG, "recover: engine still down after soft+hard recovery")
            setHealth(ScannerHealth.DOWN)
        } finally {
            recoverMutex.unlock()
        }
    }

    /**
     * ForceReinitialize + wait for the (asynchronous) bindService to actually
     * connect, THEN configure — config calls against a null binder are silent
     * no-ops and would leave the engine with default symbologies/output settings.
     * @return true if the binder answered within the settle window.
     */
    private suspend fun softReinit(): Boolean {
        withContext(Dispatchers.Main) { XCScannerWrapper.forceReinitialize(application) }
        var waited = 0L
        while (XCScannerWrapper.getServiceVersionSafe() == null && waited < SOFT_SETTLE_MS * 2) {
            delay(150); waited += 150
        }
        val bound = XCScannerWrapper.getServiceVersionSafe() != null
        if (bound) {
            withContext(Dispatchers.Main) { configureScanner() }
            delay(SOFT_SETTLE_MS)
        } else {
            Log.w(TAG, "softReinit: binder did not come back within ${SOFT_SETTLE_MS * 2}ms")
        }
        return bound
    }

    /**
     * Launch the vendor scan app's activity for ~1s to reset its process, then
     * bring our app back to the front. Rate-limited and foreground-gated so it
     * never loops or steals the screen from a backgrounded app.
     * @return true if a relaunch was actually attempted.
     */
    private fun tryHardRestart(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!appInForeground) {
            Log.w(TAG, "hard restart skipped: app not in foreground")
            return false
        }
        if (now - lastHardRestartTs < HARD_RESTART_MIN_INTERVAL_MS) {
            Log.w(TAG, "hard restart skipped: rate-limited")
            return false
        }
        if (hardRestartCount >= MAX_HARD_RESTARTS_PER_SESSION) {
            Log.w(TAG, "hard restart skipped: max attempts reached this session")
            return false
        }
        lastHardRestartTs = now
        hardRestartCount++
        Log.w(TAG, "▶ hard restart #$hardRestartCount: relaunching $SCANNER_PKG to reset the engine")
        mainHandler.post {
            try {
                val vendor = Intent()
                    .setClassName(SCANNER_PKG, "$SCANNER_PKG.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                application.startActivity(vendor)
                // Come straight back to our app so the vendor UI only flashes. We
                // must use moveTaskToFront (REORDER_TASKS) here — a plain
                // startActivity is blocked by Android's background-activity-start
                // rule the moment the vendor window covers us (we lose our visible
                // window), whereas moving our OWN task forward is permitted.
                mainHandler.postDelayed({ returnToOurApp(1) }, 700)
            } catch (e: Throwable) {
                Log.e(TAG, "hard restart failed to launch $SCANNER_PKG: ${e.message}")
            }
        }
        return true
    }

    /**
     * Bring our own task back to the front after the vendor UI flash. The vendor's
     * cold start can finish AFTER our first move and resume on top again (observed
     * on device), so verify via [appInForeground] (our lifecycle tracker) and retry
     * a few times instead of trusting one shot.
     */
    private fun returnToOurApp(attempt: Int) {
        // Step 1: move our OWN task forward. This is permitted with REORDER_TASKS
        // even while we have no visible window (unlike a plain startActivity, which
        // Android blocks as a background-activity-start once the vendor covers us).
        var moved = false
        try {
            val am = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.firstOrNull()?.let { task ->
                am.moveTaskToFront(task.taskInfo.taskId, 0)
                moved = true
                Log.d(TAG, "returnToOurApp: moved our task to front (attempt $attempt)")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "returnToOurApp: moveTaskToFront failed: ${e.message}")
        }
        // Step 2 (fallback only): if we could not move our task, try the launcher
        // intent. Skipped when the move succeeded — firing both would re-order the
        // freshly moved task a second time and can flash/relayout for nothing.
        if (!moved) {
            try {
                application.packageManager
                    .getLaunchIntentForPackage(application.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    ?.let { application.startActivity(it) }
            } catch (e: Throwable) {
                Log.w(TAG, "returnToOurApp: relaunch fallback failed: ${e.message}")
            }
        }
        // Verify we actually ended up in front; if the vendor UI re-covered us,
        // retry with backoff. appInForeground is maintained by our
        // ActivityLifecycleCallbacks, so it flips false when the vendor covers us.
        if (attempt < RETURN_RETRY_MAX) {
            mainHandler.postDelayed({
                if (!appInForeground) {
                    Log.w(TAG, "returnToOurApp: still behind the vendor UI — retry ${attempt + 1}")
                    returnToOurApp(attempt + 1)
                }
            }, 900L * attempt)
        }
    }

    companion object {
        @Volatile
        private var instance: ScannerManager? = null

        fun getInstance(application: Application): ScannerManager {
            return instance ?: synchronized(this) {
                instance ?: ScannerManager(application).also { instance = it }
            }
        }
    }
}