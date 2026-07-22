// app/src/main/java/com/xelth/eckwms_movfast/EckwmsApp.kt
// Обновлено с добавлением автоматического тестирования API при запуске
package com.xelth.eckwms_movfast

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.xelth.eckwms_movfast.data.WarehouseRepository
import com.xelth.eckwms_movfast.diagnostics.ScannerApiTester
import com.xelth.eckwms_movfast.net.HybridMessageSender
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.sync.SyncManager
import com.xelth.eckwms_movfast.utils.CryptoManager
import com.xelth.eckwms_movfast.utils.SettingsManager
import com.xelth.eckwms_movfast.utils.AdaptiveAudioManager
import com.xelth.eckwms_movfast.utils.SunlightModeManager

class EckwmsApp : Application() {
    private val TAG = "EckwmsApp"

    // Ссылка на ScannerManager для удобного доступа
    lateinit var scannerManager: ScannerManager
        private set

    // Ссылка на WarehouseRepository для доступа к данным
    lateinit var repository: WarehouseRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Приложение запускается...")

        // Initialize SettingsManager
        SettingsManager.init(this)

        // Initialize CryptoManager for secure key storage
        CryptoManager.initialize(this)
        Log.d(TAG, "CryptoManager initialized")

        // Persistent per-process file log + last-death reasons (field post-mortem
        // without adb: logcat main buffer churns in seconds on this device).
        com.xelth.eckwms_movfast.trips.TripLog.init(
            this, com.xelth.eckwms_movfast.utils.ProcessUtils.shortTag(this)
        )
        com.xelth.eckwms_movfast.trips.TripLog.logProcessStart(this)

        // The `:trips` process hosts ONLY the trip recording stack (FGS +
        // auto-detect receivers + Room invalidation hub). Everything below —
        // scanner SDK binder, watchdog, WebSocket, mic/light sensors,
        // WorkManager — stays out so the process is small enough to survive
        // the memory pressure that kills the main process mid-drive.
        if (com.xelth.eckwms_movfast.utils.ProcessUtils.isTripsProcess()) {
            Log.d(TAG, ":trips process — lightweight init only")
            return
        }

        // Initialize HybridMessageSender for WebSocket+HTTP transport
        HybridMessageSender.init(this)
        Log.d(TAG, "HybridMessageSender initialized")

        // Инициализация ScannerManager
        scannerManager = ScannerManager.getInstance(this)
        scannerManager.initialize()

        // Watchdog: detect a hung/dead vendor scan engine (com.xcheng.scannere3,
        // which owns the scanner camera) and auto-recover it — soft SDK re-init,
        // escalating to a foreground relaunch of the vendor app for a "bad process".
        scannerManager.startWatchdog()
        // Track foreground so the watchdog only does the (screen-flashing) hard
        // restart while we're actually in front of the user.
        registerScannerForegroundTracking()
        // Wake ack (buzz, no laser) + stamp: the NEXT trigger press right after a
        // wake takes the assisted resume-then-scan path.
        registerScanOnWake()
        // Trigger presses also arrive as a system broadcast (works without key focus).
        registerScanTriggerBroadcast()

        // Initialize WarehouseRepository for offline-first data management
        repository = WarehouseRepository.getInstance(this)
        Log.d(TAG, "WarehouseRepository initialized")

        // Schedule periodic sync to keep data in sync with server
        SyncManager.schedulePeriodicSync(this)
        Log.d(TAG, "Periodic sync scheduled")

        // Event-driven sync: trigger an immediate sync the moment connectivity
        // returns, instead of waiting up to 15 min for the next periodic run.
        SyncManager.registerConnectivityTrigger(this)
        Log.d(TAG, "Connectivity sync trigger registered")

        // Re-arm trip auto-detection on EVERY launch. The ActivityRecognition
        // transition registration is process-level and is lost when the OS kills
        // the app; it was previously only (re)armed on reboot or a manual toggle.
        // The pref defaults ON, so the trip UI shows "Auto 🟢" while nothing was
        // actually registered → no IN_VEHICLE transitions → no auto-recording.
        // requestActivityTransitionUpdates is idempotent (same PendingIntent).
        if (SettingsManager.getTripConsent() &&
            SettingsManager.getTripAutoDetect() &&
            com.xelth.eckwms_movfast.trips.TripManager.hasActivityPermission(this)
        ) {
            val armed = com.xelth.eckwms_movfast.trips.TripManager.enableAutoDetect(this)
            Log.d(TAG, "Trip auto-detect re-armed on launch: $armed")
        } else {
            Log.d(TAG, "Trip auto-detect NOT armed (consent/pref/permission missing)")
        }

        // Initialize Sunlight Mode (ambient light → audio-haptic feedback)
        SunlightModeManager.init(this)
        Log.d(TAG, "SunlightModeManager initialized")

        // Initialize Adaptive Audio (mic-based ambient noise → volume adjustment)
        AdaptiveAudioManager.init(this)
        Log.d(TAG, "AdaptiveAudioManager initialized")

        // Main-process UI bridge: TripManager.activeTrip now mirrors the Room
        // open-trip row, so the UI keeps seeing recording state even though the
        // recorder publishes from the separate :trips process.
        com.xelth.eckwms_movfast.trips.TripManager.startUiBridge(this)

        // Запуск автоматического тестирования API сканера при запуске
        // Log.d(TAG, "Запуск автоматического тестирования API сканера...")
        // ScannerApiTester(scannerManager).testAndLogAllApiFunctions()
        // Log.d(TAG, "Автоматическое тестирование API сканера завершено")
    }

    /**
     * Maintain [ScannerManager.appInForeground] via a simple started-activity
     * count. The scanner watchdog reads it to gate the hard-restart step (which
     * briefly launches the vendor UI) to moments the user is actually looking at us.
     */
    private fun registerScannerForegroundTracking() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                scannerManager.appInForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    scannerManager.appInForeground = false
                }
            }

            override fun onActivityResumed(activity: Activity) {
                // Pre-warm the scan engine on foreground: the vendor may have left it
                // suspended (screen-off / battery power mgmt) — resume so the next
                // trigger works without needing a throw-away press.
                scannerManager.onAppResumed()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Wake hook (must be context-registered — the OS won't deliver SCREEN_ON to a
     * manifest receiver). [ScannerManager.onScreenOn] stamps the wake and vibrates
     * a short ack: the wake key press is consumed by the system (no KeyEvent, no
     * laser), so the worker's contract is buzz-without-laser = "woke up, press
     * again to scan". Registered app-lifetime.
     */
    private fun registerScanOnWake() {
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON)
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == android.content.Intent.ACTION_SCREEN_ON) {
                    Log.d(TAG, "SCREEN_ON → stamp wake + vibrate ack")
                    scannerManager.onScreenOn()
                }
            }
        }, filter)
    }

    /**
     * Second signal source for hardware trigger presses: the system broadcasts
     * `com.xcheng.scanner.action.OPEN_SCAN_BROADCAST` (extra "scankey" = keycode)
     * when a scan key goes down — found in the vendor's ScanTestReceiver. The
     * primary source is MainActivity.dispatchKeyEvent (F8–F11 KeyEvents); this one
     * also arrives when our activity does NOT have key focus (e.g. mid-wake, behind
     * the keyguard). ScannerManager dedups the two. RECEIVER_EXPORTED: the sender is
     * the system/vendor, not us, and the action is not a protected broadcast.
     */
    private fun registerScanTriggerBroadcast() {
        val filter = android.content.IntentFilter("com.xcheng.scanner.action.OPEN_SCAN_BROADCAST")
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.xcheng.scanner.action.OPEN_SCAN_BROADCAST") {
                    scannerManager.onScanTriggerPressed("broadcast", intent.getIntExtra("scankey", -1))
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    /** Memory-pressure breadcrumbs: a TRIM_MEMORY line followed by silence in
     *  the per-process trip log is the signature of an LMK kill under load. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.xelth.eckwms_movfast.trips.TripLog.w(TAG, "onTrimMemory level=$level")
    }

    override fun onTerminate() {
        super.onTerminate()

        // Освобождение ресурсов сканера (not initialized in the :trips process)
        if (::scannerManager.isInitialized) {
            scannerManager.stopWatchdog()
            scannerManager.cleanup()
        }
        Log.d(TAG, "Приложение завершает работу")
    }
}