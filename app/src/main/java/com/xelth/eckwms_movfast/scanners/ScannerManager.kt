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
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.RegionSizeType
import com.xcheng.scanner.TextCaseType
import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
/** Auto-scan-on-wake only if the app resumes within this long after SCREEN_ON. */
private const val WAKE_SCAN_WINDOW_MS = 20_000L

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

    // Обработчик главного потока
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Watchdog / self-heal state ---------------------------------------
    private val _health = MutableLiveData(ScannerHealth.HEALTHY)
    /** Observe from the UI to show a "scanner recovering / down" indicator. */
    val health: LiveData<ScannerHealth> get() = _health

    /** elapsedRealtime of the last successful scan — a positive liveness signal. */
    @Volatile private var lastScanTs: Long = 0L

    /** Set by [EckwmsApp]'s ActivityLifecycleCallbacks. A hard restart (which
     *  briefly launches the vendor UI) is only attempted while we're foreground. */
    @Volatile var appInForeground: Boolean = true

    private var suspectStreak = 0
    private var hardRestartCount = 0
    private var lastHardRestartTs = 0L

    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

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

        Log.d(TAG, "[$timestamp] ⭐ Initializing scanner at application level...")

        // Инициализация XCScannerWrapper с получением типа и штрих-кода
        XCScannerWrapper.initialize(application) { symbology, barcode ->
            val callbackTimestamp = System.currentTimeMillis()
            Log.d(TAG, "[$callbackTimestamp] ⭐ Scan result: type=$symbology, barcode=$barcode")

            // A real decode is the strongest possible liveness signal — the engine
            // is clearly alive, so clear any pending "recovering/down" state.
            lastScanTs = SystemClock.elapsedRealtime()
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
        if (!isInitialized) { Log.d(TAG, "suspendScanService: not initialized"); return }
        Log.d(TAG, ">>> suspendScanService (free camera for ML Kit)")
        XCScannerWrapper.suspendScanService()
    }

    /** Resume the hardware scanner after a CameraX/ML Kit session is done. */
    fun resumeScanService() {
        if (!isInitialized) { Log.d(TAG, "resumeScanService: not initialized"); return }
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

    /**
     * The MINIMAL scanner reset: rebind the AIDL connection to the vendor service.
     * No process restart, no vendor-UI flash — just deInit → force-null the SDK's
     * stuck statics → init (see [XCScannerWrapper.forceReinitialize]). This revives
     * the common "binder went stale" hang (vendor recycled / stale after a dev
     * reinstall). Suitable for a manual "restart scanner" button. Returns true if
     * the engine answers as alive afterwards.
     *
     * If this returns false the vendor process itself is down/bad or the camera is
     * wedged — that needs [recover]'s escalation (vendor relaunch), which the
     * watchdog does automatically.
     */
    suspend fun rebindScanner(): Boolean {
        Log.w(TAG, "rebindScanner: minimal reset (AIDL rebind, no app restart)")
        setHealth(ScannerHealth.RECOVERING)
        withContext(Dispatchers.Main) {
            XCScannerWrapper.forceReinitialize(application)
            configureScanner()
        }
        delay(SOFT_SETTLE_MS)
        val alive = probe() == Probe.ALIVE
        setHealth(if (alive) ScannerHealth.HEALTHY else ScannerHealth.DOWN)
        Log.d(TAG, "rebindScanner: engine alive=$alive")
        return alive
    }

    /** elapsedRealtime of the last SCREEN_ON. */
    @Volatile private var lastScreenOnTs = 0L
    /** false = a wake is pending an auto-scan; true = already fired / none pending. */
    @Volatile private var wakeScanConsumed = true

    /**
     * Record a device wake (SCREEN_ON) and try to auto-scan. On this PDA the scan
     * trigger doubles as a wake key, so a single press should both wake AND scan.
     * With MainActivity's setShowWhenLocked the app is already foreground over the
     * (non-secure) swipe keyguard, so SCREEN_ON is the reliable trigger; [onAppResumed]
     * is a backstop for the case where the app resumes a moment later.
     */
    fun onScreenOn() {
        lastScreenOnTs = SystemClock.elapsedRealtime()
        wakeScanConsumed = false
        // Let the lifecycle settle (activity resume over keyguard) then try.
        watchdogScope.launch { delay(400); tryConsumeWakeScan() }
    }

    /**
     * Called when an Activity resumes. Pre-warms the engine (resume if the vendor left
     * it suspended) and, if a wake is still pending, fires the one-press-from-sleep scan.
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
        tryConsumeWakeScan()
    }

    /**
     * Fire exactly one scan per wake, from whichever trigger (SCREEN_ON / onResume)
     * first finds the app foreground within the wake window. Never scans behind a
     * lockscreen (gated on [appInForeground]) or while a camera screen owns the sensor.
     */
    private fun tryConsumeWakeScan() {
        if (!isInitialized || wakeScanConsumed) return
        if (!SettingsManager.getAutoScanOnWake()) return
        if (SystemClock.elapsedRealtime() - lastScreenOnTs >= WAKE_SCAN_WINDOW_MS) return
        if (!appInForeground) return                       // locked / woke elsewhere → wait
        if (XCScannerWrapper.cameraHoldCount() > 0) return // a camera screen owns it
        wakeScanConsumed = true
        watchdogScope.launch {
            withContext(Dispatchers.Main) { XCScannerWrapper.forceResumeScanService() }
            var waited = 0
            while (XCScannerWrapper.isScanServiceSuspendingSafe() && waited < 1500) {
                delay(150); waited += 150
            }
            delay(250)  // small camera warmup margin
            if (!appInForeground || XCScannerWrapper.cameraHoldCount() > 0) return@launch
            Log.d(TAG, "tryConsumeWakeScan: firing startScan (one-press-from-sleep)")
            withContext(Dispatchers.Main) { startScan() }
        }
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
            // Just (re)initialized and still activating — treat as fine.
            license == LicenseState.ACTIVATING && version != null -> Probe.ALIVE
            // Binder answers but the license dropped (camera/ISP wedge) — suspect.
            else -> Probe.SUSPECT
        }
    }

    private suspend fun tick() {
        if (!isInitialized) return
        // Stuck-suspend recovery. The vendor's E3Util releases the scan camera on
        // SCREEN_OFF / low battery (state → SUSPENDING) and can strand it there with
        // no event to resume — the binder + license stay healthy, so probe() below
        // reports ALIVE and would never notice. If nothing of ours legitimately holds
        // the camera (no CameraX screen open) and we're foreground, resume it.
        if (appInForeground &&
            XCScannerWrapper.cameraHoldCount() <= 0 &&
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
     */
    private suspend fun recover() {
        setHealth(ScannerHealth.RECOVERING)

        // Step 1 — soft: deInit + re-init the SDK (rebinds to the service, which
        // Android auto-starts if the process is merely gone but not "bad").
        Log.w(TAG, "recover: soft re-init of the scan SDK")
        withContext(Dispatchers.Main) {
            XCScannerWrapper.forceReinitialize(application)
            configureScanner()
        }
        delay(SOFT_SETTLE_MS)
        if (probe() == Probe.ALIVE) {
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
            withContext(Dispatchers.Main) {
                XCScannerWrapper.forceReinitialize(application)
                configureScanner()
            }
            delay(SOFT_SETTLE_MS)
            if (probe() != Probe.DEAD) {
                Log.d(TAG, "recover: hard restart revived the engine")
                suspectStreak = 0
                setHealth(ScannerHealth.HEALTHY)
                return
            }
        }

        Log.e(TAG, "recover: engine still down after soft+hard recovery")
        setHealth(ScannerHealth.DOWN)
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
                mainHandler.postDelayed({ returnToOurApp() }, 700)
            } catch (e: Throwable) {
                Log.e(TAG, "hard restart failed to launch $SCANNER_PKG: ${e.message}")
            }
        }
        return true
    }

    /** Bring our own task back to the front after the vendor UI flash. */
    private fun returnToOurApp() {
        // Step 1: move our OWN task forward. This is permitted with REORDER_TASKS
        // even while we have no visible window (unlike a plain startActivity, which
        // Android blocks as a background-activity-start once the vendor covers us).
        var moved = false
        try {
            val am = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.firstOrNull()?.let { task ->
                am.moveTaskToFront(task.taskInfo.taskId, 0)
                moved = true
                Log.d(TAG, "returnToOurApp: moved our task to front")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "returnToOurApp: moveTaskToFront failed: ${e.message}")
        }
        // Step 2 (belt-and-suspenders): once we're front again a normal relaunch is
        // no longer BAL-blocked, so re-issue our launcher intent to be sure.
        try {
            application.packageManager
                .getLaunchIntentForPackage(application.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ?.let { application.startActivity(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "returnToOurApp: relaunch fallback failed (moved=$moved): ${e.message}")
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