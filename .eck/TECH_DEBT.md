# Tech Debt

## Active TODOs in Code

1. **`ScanScreen.kt:683`** — `TODO: Extract target location barcode from step params`
   "showMap" workflow step uses placeholder `p-LOC-001` for demo. Should extract real location from WorkflowStep params map.

## Structural Issues

2. **SyncWorker runs on fixed schedule** — Background sync uses JobScheduler with fixed intervals. No event-driven sync (e.g. trigger sync immediately when WiFi reconnects).

3. **Repair slot photos stored on-device only** — Photos in `filesDir/repair_photos/slot_N.webp` are uploaded to server but not backed up. If app data is cleared, photos are lost even if they were uploaded.

4. **No mesh relay sync on Android** — PDA only communicates via direct HTTP to the server URL set during pairing. It does not participate in relay-based mesh sync. If the paired server is unreachable, data sits in local queue.

5. **UserManager singleton coupling** — UserManager uses companion object singleton pattern which makes testing difficult. Should use Hilt/DI injection.

6. **Pairing code detection still uses `startsWith("ECK")`** — In `ScanRecoveryViewModel.handleGeneralScanResult()`, the pairing code detection (`effectiveCode.startsWith("ECK") && !isLinkBarcode`) still assumes pairing codes start with "ECK". This should be configurable if self-hosted instances use different pairing prefixes.

7. **ExplorerScreen still parses entity IDs as Long** — `loadProductLocations(prod.optLong("id"))` etc. The new 9eck WMS returns String UUIDs / record ids; Explorer navigation will get `0` for non-numeric ids. Migrate Explorer to String ids like the rest of the app (commit d768363 did this everywhere else).

8. **picking_confirm retry can mask legit rejection** — `confirmPickLine()` returns `false` for both network failure and server rejection. The offline queue retries up to MAX_RETRIES then drops. Server should distinguish 4xx (drop) from network error (retry).

9. **Hardware scanner ↔ app camera share one ISP (fragile) — mitigated 2026-06-18.**
   On this XCheng/MTK PDA the laser/imager scan engine IS a camera sensor
   (`persist.sys.scanner=ov9281_mipi_raw`, `vendor.debug.scan.camid=2`), while
   ML Kit / CameraX barcode-scan + odometer/plate OCR open camera id **0**. The
   two share one ISP, so opening the app camera while the scan engine holds the
   camera **wedges the scan camera until reboot** — system-wide (even XCheng's own
   `com.xcheng.scannere3` scan-test app stops reacting; the camera icon no longer
   appears on a hardware-trigger press). No root on the device → `cameraserver`
   HAL can't be reset live (`killall cameraserver` = Operation not permitted), so
   only a reboot recovers it.
   - **Diagnosis trail (adb):** `getevent -p` → side buttons = device `xctech-key`
     emitting `KEY_F10`/`KEY_F11`; `getevent` confirmed presses reach the kernel
     but NOT our app (XCheng service intercepts them — so handling keys in-app is
     a dead end). `dumpsys media.camera` → `Camera ID 2 = com.xcheng.scannere3`,
     our app had connected/disconnected `Camera ID 0`. Scanner SDK init logs fine
     (`License state: Inactive` after the wedge; normally Active). `MovStage`
     (`com.xcheng.movstage`) silence-mode also `deInit`s the scanner on app-switch.
   - **Mitigation:** `ScannerManager.suspendScanService()` / `resumeScanService()`
     (wrap `XcBarcodeScanner.suspendScanService()` — "releases camera resources")
     called from a `DisposableEffect` around the whole `CameraScanScreen`
     lifecycle: free the scan engine's camera while any CameraX/ML Kit screen is
     open, re-acquire on exit. Verified: the camera→exit→side-button cycle that
     used to kill the scanner now survives.
   - **Residual risk:** if `CameraScanScreen`'s process is killed without
     `onDispose`, `resumeScanService` won't run (app restart re-inits the scanner,
     so it self-heals). Other camera entry points (pairing/QR in `ScanScreen`) are
     NOT yet bracketed. The scan SDK is a vendor `.aar` (`xcscanner_qrcode_v1.3.56.1.7`,
     newest in git; the older `1.1.x` line is also in `app/libs/` but unused) — a
     newer XCheng SDK may handle coexistence natively. The bug was intermittent;
     keep watching. If it recurs, grab `dumpsys media.camera` at the moment of death.

## Resolved (2026-06-18)

- ~~Trip auto-detect never armed on app launch~~ ✅ `EckwmsApp.onCreate` now
  re-arms `TripManager.enableAutoDetect()` (consent + pref + ACTIVITY_RECOGNITION
  guarded). Previously only armed on reboot (`BootReceiver`) or manual toggle, so
  the pref defaulted ON and the UI showed "Auto 🟢" while no IN_VEHICLE transition
  was actually registered (lost on process death) → nothing recorded.
- ~~Trip map showed the old track (centred near the last trip), not where you are~~
  ✅ `TripMapView(liveLocation=true)` from the trip console: MapLibre
  LocationComponent puck, `CameraMode.TRACKING` + `zoomWhileTracking(15)` so it
  centres on the current position at street zoom on entry; the recorded track is
  only framed on the full history screen (`liveLocation=false`).
- Hardware scan-trigger keys F8–F11 routed to `startScan()` in
  `MainActivity.dispatchKeyEvent` — kept as a harmless fallback, but note the
  XCheng service intercepts these keys before the app, so it does not fire (the
  real scan path is the SDK callback / camera engine, see item 9).
- Voice Commands P0–P3 shipped (registry + Gemini fallback) — see `VOICE_COMMANDS.md`.

## Resolved (2026-06-11)

- ~~No offline confirm queue for pickings~~ ✅ `picking_confirm` / `picking_validate` SyncQueue job types; PickingViewModel queues on failure, SyncWorker retries.
- ~~SyncWorker doesn't handle `crm_update` type~~ ✅ `crm_update` branch POSTs to WMS `/api/crm/update` via `ScanApiService.pushCrmUpdate()`.
- ~~CRM screen is write-only~~ ✅ `CrmEntityScreen` fetches entity via `WarehouseRepository.getCrmEntity()` (network-first, cache fallback), shows name, prefills status.
- ~~No local CRM entity cache~~ ✅ `crm_entities` Room table (`CrmEntityEntity` + `CrmEntityDao`, DB v12) caches fetched entities for offline browsing.
- ~~ActionProof signature is not captured as image~~ ✅ Strokes are rasterized to Bitmap → WebP lossy 75 → Base64 (`signature_image` + `signature_mime`).
- ~~ActionProof GPS uses `getLastKnownLocation` only~~ ✅ Two-stage fix: instant last-known fallback + `LocationManager.getCurrentLocation()` fresh fix; proof records `location.source` = `fresh_fix`/`last_known`.
