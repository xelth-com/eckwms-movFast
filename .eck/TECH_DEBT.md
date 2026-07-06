# Tech Debt

## Trips — open field-test issues (2026-07-06)

- **2 h auto-trip with ZERO points (14:36–16:45 Walldorf→Würzburg leg).** The trip row
  was created on IN_VEHICLE ENTER and finalized at arrival (EXIT → graceful stop resolved
  the open trip through the DB), but not a single point — not even cell — was recorded in
  between. Most likely the FGS died right after the row insert and the sticky restart never
  came (or came without resuming sampling); the EXIT then started the service purely as a
  command carrier. Needs a periodic alive-check (e.g. WorkManager heartbeat that restarts
  the FGS when an open non-private trip has no fresh points for >10 min).
- **A false IN_VEHICLE ENTER consumes the armed intent.** 18:39 the armed start fired on a
  spurious ENTER while parked (9 stationary points), the driver closed the 2-min phantom
  manually — and the intent was gone, so the REAL drive 20 min later would have started as a
  plain unlabeled trip (it didn't start at all that day only because of the
  package-replace bug, now fixed). Consider re-arming the intent when a from-intent trip
  ends within ~3 min with <200 m of track.

## Active TODOs in Code

1. ~~**`ScanScreen.kt:683`** — `TODO: Extract target location barcode from step params`~~
   ✅ **FIXED 2026-06-21.** `WorkflowStep` already carries `params: Map<String,String>?`, so
   the `showMap` step now reads the target barcode from `params` (keys `target` /
   `targetLocation` / `location` / `barcode`, first non-blank wins, URL-encoded). If no
   target is configured it opens the map with **no** highlight (the `warehouseMap` route's
   `target` arg is nullable) instead of the fake `p-LOC-001` placeholder.

## Release & distribution

- **Self-service + forced password-change UI — ✅ SHIPPED (`0fc42d5`, 2026-07-04).** Dialog in
  the user/session menu + a non-dismissable FORCED variant after PIN login when the account is
  flagged `mustChangePassword` (bulk-seeded staff). Calls `POST {base}/api/auth/change-password`
  with the device Bearer + relay failover; `PasswordPolicy` validator (+7 JVM tests, suite 63
  green). Activates automatically once the backend returns `mustChangePassword` on
  `/api/users/verify-pin` or `/api/users/active` (9eck `9dbee89`).
- **APK rollout to the rest of the PDA fleet — OPEN.** The `0fc42d5` paid-debug build was
  installed (`adb install -r`) only on the one USB-connected Ranger2 (`MT15AEM24120007`). Every
  other managed PDA still runs an older build and needs the same manual `adb`/MDM install — there
  is NO in-app OTA/self-update path and no APK-serving server route, and the release keystore
  (`../keystore.jks`) is absent on the dev box (only the machine's debug keystore signs, so
  `install -r` upgrades cleanly on a device that already has a debug-signed build). Decide a real
  distribution channel (MDM push / Play `free` flavor / a hosted APK on `movfast.de`).
- **`versionCode` still `1`.** Never bumped across builds. `adb install -r` (same signature) works
  regardless, but nothing can tell "newer" from "older", and any future update-check by
  versionCode is a no-op. Bump per release.

## Structural Issues

2. ~~**SyncWorker runs on fixed schedule**~~ ✅ **FIXED 2026-06-28.**
   `SyncManager.registerConnectivityTrigger()` registers a
   `ConnectivityManager.NetworkCallback` (NET_CAPABILITY_INTERNET) whose
   `onAvailable` calls `scheduleSync()` — so a sync fires the moment connectivity
   returns instead of waiting up to 15 min for the next periodic run. Registered
   in `EckwmsApp.onCreate`. `scheduleSync` uses `ExistingWorkPolicy.KEEP`, so a
   flapping network can't pile up jobs, and the worker no-ops cheaply when nothing
   is pending. Added `ACCESS_NETWORK_STATE` permission. (The periodic 15-min run
   stays as a backstop.)

3. **Repair slot photos stored on-device only** — Photos in `filesDir/repair_photos/slot_N.webp` are uploaded to server but not backed up. If app data is cleared, photos are lost even if they were uploaded.

4. ~~**Relay-forwarded pairing fell back to a single hardcoded FREE relay**~~
    ✅ **FIXED 2026-06-30.** `registerViaRelay` used `SettingsManager.getRelayUrl()`
    (default `https://9eck.com`) and dispatched `device_register` to that one relay
    with no failover — so paid pairings leaked onto the free relay and any relay
    hiccup aborted pairing. The `orderedEckNodes()` deterministic-polygon helper
    already existed but was **dead code** (never called).
    - **Polygon from the QR** (`ScanRecoveryViewModel.handlePairingWithEckProtocol`):
      paid `ECK$3$…` ⇒ `SettingsManager.orderedEckNodes(meshId)` (baked-in eckN,
      ordered by `compute_primary_index`); free `ECK$2$…` ⇒ the non-LAN relay URL(s)
      carried IN the QR (`candidates.filterNot { isLanOrLinkLocal(it) }`). No hardcoded
      free fallback.
    - **Skip-to-next walk**: `RelayClient.meshDispatch` returns
      `RelayDispatch { Ok | Retryable | Fatal }` (transport/5xx → next, 4xx/parse →
      stop, 2xx → ack-wait then next-on-timeout). `registerViaRelay` sweeps the whole
      polygon; `awaitRelayResult` polls ~45 s per relay.
    - **Anchor**: saves the working relay as `relay_url`; only a paid eckN (real WMS)
      is also promoted to `server_url`. See ROADMAP Phase 11.
    - **`normalizeRelayBase`** strips a trailing `/E` because `RelayClient` re-appends
      `/E/m/…` (eckN defaults ship `https://eckN.com/E`).

5. **UserManager singleton coupling** — UserManager uses companion object singleton pattern which makes testing difficult. Should use Hilt/DI injection.

6. ~~**Pairing code detection still uses `startsWith("ECK")`**~~ ✅ **FIXED 2026-06-28.**
   Prefix extracted to `SettingsManager.getPairingPrefix()` (default `"ECK"`);
   `ScanRecoveryViewModel.handleGeneralScanResult()` now matches against it.
   `ScanApiService` absorbs a server-pushed `pairing_prefix` from `/api/status`
   (same mechanism as `repair_order_prefix`), so a self-hosted instance can set
   its own prefix for re-pairing / additional devices. **Bootstrap caveat:** the
   very first pairing happens *before* any server is known, so `"ECK"` must remain
   the default fallback; a blank stored value is coerced back to the default so
   `startsWith("")` can never match every code.

7. ~~**ExplorerScreen still parses entity IDs as Long**~~ ✅ **FIXED 2026-06-21.**
   Migrated the whole screen to String ids (the WMS returns `record::id(id)` String ids;
   `optLong` was yielding `0`): `BreadcrumbItem.id`, `loadLocations`/`loadLocationContents`/
   `loadProductLocations` params, `LocationCard` callbacks, and `loc.optString("id")`. Ids put
   into URLs are URL-encoded via a local `encId()` helper (matches the app's
   `java.net.URLEncoder` convention).

8. ~~**picking_confirm retry can mask legit rejection**~~ ✅ **FIXED 2026-06-21.**
   Introduced `enum SyncOutcome { SUCCESS, REJECTED, FAILED }`; `confirmPickLine()` /
   `validatePicking()` now return it (`classifyWrite`: 2xx→SUCCESS, 4xx→REJECTED,
   5xx/IO→FAILED). `SyncWorker.handleSyncOutcome()` **drops** a REJECTED job (marks any
   linked scan FAILED) instead of retrying-until-silently-dropped, and only retries FAILED.
   Online path: `PickingViewModel.onProductScanned` rolls back the optimistic local update
   and surfaces the rejection on REJECTED (queues only on FAILED); `validateAndComplete`
   refuses to mark a picking complete on REJECTED.

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
   - **Update 2026-06-19:** audited all camera opens — the only in-process CameraX
     (`ProcessCameraProvider`) is `CameraScanScreen` (bracketed; pairing/QR route
     through it too). The external camera in `OdometerDialog` (odometer + plate
     photos, in the trip flow) is now ALSO bracketed (suspend on launch, resume in
     the result callback). No unbracketed camera opens remain.
   - **Residual risk:** if `CameraScanScreen`'s process is killed without
     `onDispose`, `resumeScanService` won't run (app restart re-inits the scanner,
     so it self-heals). The scan SDK is a vendor `.aar` (`xcscanner_qrcode_v1.3.56.1.7`,
     newest in git; the older `1.1.x` line is also in `app/libs/` but unused) — a
     newer XCheng SDK may handle coexistence natively. The bug was intermittent;
     keep watching. If it recurs, grab `dumpsys media.camera` at the moment of death.

10. ~~**`registered_device` keyed by legacy `Settings.Secure.ANDROID_ID`, not UUID.**~~
    ✅ **FIXED 2026-06-28** (two-repo change: Rust server `eckwms` + this app).
    Canonical device id is now a **server-minted UUID**; the Ed25519 `public_key` is
    the stable identity anchor (survives factory reset / ANDROID_ID change), and
    `android_id` is kept as a secondary lookup hint.
    - **Server** (`wms/src/handlers/device.rs`): `register_device` verifies the
      signature, resolves the device by `public_key` → record key → `android_id`
      (all ignoring tombstones), reuses the matched row's UUID or mints a fresh
      `Uuid::new_v4()`, persists under the UUID key with `android_id`, sets the JWT
      subject to the UUID, and returns `device_uuid`. `DeviceRecord` /
      `RegisteredDevice` gained an `android_id` field.
    - **Transition safety** (`wms/src/handlers/pda.rs`): `resolve_device` falls back
      from record-key lookup to `WHERE android_id = …`, so a device still
      authenticating with its pre-migration ANDROID_ID subject keeps resolving;
      `/api/status` echoes `device_uuid` so already-paired devices adopt their UUID
      on the next heartbeat — no re-pairing.
    - **Migration** (`migrator --devices`): SurrealDB-only, idempotent re-key of every
      legacy ANDROID_ID-keyed row to a UUID (old id → `android_id`, old row
      tombstoned for sync). **The user must run it on prod after deploying the new
      server:** `migrator --devices` (env `SURREAL_DB_PATH`).
    - **App**: `SettingsManager.getDeviceUuid()/saveDeviceUuid()` + canonical
      `getDeviceId()` (UUID if known, else ANDROID_ID); `ScanApiService` adopts
      `device_uuid` from register + `/api/status`, and `registerDevice` sends the
      signed id explicitly (`signedDeviceId`) so the signature still matches.
    - **Deploy order**: ship + deploy the UUID-aware server, run `migrator --devices`,
      then roll out the app. Devices re-resolve via the `public_key` anchor, so order
      is forgiving. **Out of scope (follow-up):** `instance_id` is still
      `pda_<android_id>` for existing installs (mesh node identity, separate from
      `registered_device`).

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
