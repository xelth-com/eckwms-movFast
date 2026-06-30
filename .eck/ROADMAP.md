# Roadmap

## Phase 3: Visual Intelligence & Evidence (Completed)
- [x] **Smart Crop Engine**: Android-side edge processing for optimal bandwidth usage.
- [x] **Dual Upload**: Logic for simultaneous Avatar (DB) and Original (Disk) storage.
- [x] **Backend API**: Endpoints for serving files (`/api/files/{id}`) and listing attachments (`/api/attachments`).
- [x] **Web Gallery**: Visual evidence viewer in the Dashboard.
- [x] **Android Gallery**: Native view for browsing attached evidence in Explorer.

## Phase 4: Intelligence & Analytics (Active)
- [x] **Backend Infrastructure**: Secure API endpoint for image analysis (`/api/ai/analyze-image`).
- [x] **Safety Mechanisms**: Killswitch logic and feature flags for AI control.
- [x] **Web UI Integration**: "AI Inspect" button in dashboard gallery.
- [ ] **Android UI Integration**: Native AI analysis feedback on handhelds.
- [ ] **Anomaly Detection**: Background jobs to flag suspicious stock movements.

## Phase 5: Picking with Route Optimization (2026-02-12)
- [x] **Room Entities**: PickingOrderEntity, PickLineEntity in Room DB (version 8)
- [x] **PickingDao**: Queries with Flow, progress updates
- [x] **API Client**: fetchActivePickings, fetchPickingRoute, confirmPickLine, validatePicking
- [x] **PickingViewModel**: State management, scan handling, route stops
- [x] **PickingListScreen**: List of assigned pickings with priority, progress bar
- [x] **PickingExecuteScreen**: Step-by-step picking with location/product barcode validation
- [x] **Map Route Overlay**: Path lines (dashed orange), numbered sequence circles, color coding (green=current, grey=done, yellow=upcoming)
- [x] **Navigation**: pickingList, pickingExecute, pickingMap routes in MainActivity
- [x] **Wire hardware scanner**: Connect ScannerManager to PickingExecuteScreen for barcode events
- [x] **Offline confirm queue**: `picking_confirm`/`picking_validate` jobs in SyncQueue, queued on failure, retried by SyncWorker (2026-06-11)

## Phase 6: CRM Ecosystem Integration
**Architecture**: Rust backend acts as high-performance middleware between warehouse hardware (PDAs) and external CRM/ERP systems.

### Integration Targets
1. **Twenty** (Primary Open-Source CRM) — self-hostable, GraphQL API, priority target
2. **Odoo** (ERP/Warehouse standard) — JSON-RPC, existing warehouse module alignment
3. **Salesforce** (Enterprise) — REST/SOAP, large customer segment
4. **HubSpot** (SMB Market) — REST API, deals & contacts sync

### Tasks
- [x] **Unified Mapping Layer (Crypto)**: V2 SmartTag decryption maps encrypted Smart Codes to typed UUIDs (`p-uuid`, `i-uuid`, `company-uuid`) on device
- [x] **Dynamic QR Prefixes**: Server exposes `qr_prefixes` + `qr_tenant_suffix` in `/api/status`; Android fetches and merges with hardcoded fallbacks (`9eck.com/`, `xelth.com/`). Zero hardcoded ECK domain references.
- [x] **CRM Entity Screen**: `CrmEntityScreen.kt` — offline view/edit for Company, Person, Opportunity with status chips and notes
- [x] **Offline CRM Queue**: `crm_update` entries saved to `SyncQueueEntity` via `WarehouseRepository.queueCrmUpdate()`, scheduled for sync on connectivity restore
- [x] **CRM Entity Fetch**: `GET /api/crm/:type/:id` on the 9eck WMS + `WarehouseRepository.getCrmEntity()` (network-first, Room cache fallback) populates CrmEntityScreen (2026-06-11)
- [x] **SyncWorker CRM Handler**: `crm_update` jobs POST to `POST /api/crm/update` (UPSERT + `crm_update_log` audit on server) (2026-06-11)
- [ ] **OAuth2 Connectors**: Per-CRM OAuth2 auth flows in Rust (Salesforce, HubSpot); API-key flows (Twenty, Odoo)
- [ ] **Data Sync Workers**: Async Rust workers for contact, deal, and inventory event propagation
- [ ] **Webhook Receiver**: Inbound CRM webhooks → internal event queue
- [ ] **Conflict Resolution**: Last-write-wins + manual override UI for sync conflicts

## Phase 7: Adaptive Ergonomics & "Blind" Mode (2026-03-07)
**Context**: Warehouse workers outdoors in bright sunlight cannot rely on the PDA screen.

- [x] **Ambient Light Detection**: `SunlightModeManager` uses `Sensor.TYPE_LIGHT` with hysteresis (10k lux ON / 3k lux OFF, 5-sample debounce)
- [x] **Audio Boost**: Auto-maximizes media volume in sunlight mode, restores on exit
- [x] **Haptic Vocabulary**: Three `VibrationEffect` patterns via `SunlightModeManager`:
  - Success (scan confirmed): two quick ticks (50ms-gap-50ms)
  - Error (mismatch/failure): one long buzz (400ms)
  - Attention (action required): three rhythmic pulses (150ms x3)
- [x] **Audio Tones**: ToneGenerator chimes in sunlight mode (ACK/NACK/BEEP2)
- [x] **High-Contrast UI**: `HighContrastColorScheme` (black/yellow) auto-applied via `EckwmsmovFastTheme(highContrast=)`
- [x] **Adaptive Audio**: `AdaptiveAudioManager` — trigger-based mic polling with EMA + dynamic cooldown, adjusts `STREAM_MUSIC` by ambient dB
- [ ] **TTS Voice Prompts**: Speak scan results and status changes using Android TTS

## Phase 8: Server-Driven UI & Legal Proofs (2026-03-27)
**Context**: Backend sends JSON layout definitions; Android renders them dynamically without app updates.

- [x] **DynamicUiRenderer**: Renders JSON-defined layouts (text, buttons, toggles, inputs, dropdowns, cards, sections)
- [x] **ActionProof Component**: Legal Proof of Action — voice-to-text name, signature canvas, GPS coordinates. Packaged as JSON for WorkflowEngine
- [x] **Signature-to-Image**: Strokes rasterized to Bitmap → WebP lossy 75 → Base64 (`signature_image` + `signature_mime`) (2026-06-11)
- [x] **Fresh GPS Fix**: Two-stage location — instant last-known + `getCurrentLocation()` fresh fix, source recorded in proof (2026-06-11)
- [ ] **Photo Capture Component**: Server-driven photo capture with annotation overlay
- [ ] **Multi-Step Wizard**: Sequential form pages with back/forward navigation driven by server JSON

## Phase 9: Trips / Fahrtenbuch (2026-06-12)
**Context**: Passive trip recording via cell towers (no GPS), odometer log with
photo+OCR, server-side cell geocoding (OpenCelliD) and estimated distances.
Movement control + task-visit verification, eventually a tax-grade Fahrtenbuch
(unveränderlich via the existing Hedera sealing).

- [x] **Trip recording core**: `TripRecordingService` (FGS location) samples registered cells (MCC/MNC/TAC/CID) + fused balanced-power locations every 30s; Room `trips`/`trip_points` (DB v13)
- [x] **Auto-detect**: Activity Recognition IN_VEHICLE enter/exit → start / graceful stop (3-min debounce for traffic lights/fuel stops); manual 🚗 toggle too
- [x] **Odometer (Kilometerstand)**: dialog with manual entry + 📷 TakePicturePreview → ML Kit text-recognition OCR prefill; photo uploaded via CAS pipeline; source recorded (photo/manual)
- [x] **Sync**: `trip_sync` SyncQueue job → `POST /api/trips` (idempotent by trip_uuid)
- [x] **Server**: trip upsert/list/get endpoints; `cell_resolver` worker — cell_tower cache + OpenCelliD lookups, accuracy-aware distance (jitter + cell-bounce filters, ×1.25 road factor, `distance_is_estimate`)
- [x] **DSGVO privacy retrofit (Phase 1.5, 2026-06-12)**: Privatfahrt mode (zero position sampling, km-delta only), consent gate (opt-in, revocable, enforced at every entry point), 14-day raw-point retention on device AND server. See 9eck.com/.eck/PRIVACY_BY_DESIGN.md
- [x] **Visit-tasks (Phase 2, 2026-06-12)**: check-in/check-out model (VG Lüneburg) — NOT silent track-matching. visit_task table + /api/visits endpoints; PDA pulls daily plan, geofences LOCALLY during business trips → prompt notification, worker confirms; one-shot position only at the moment of the tap; status-based end-of-day reminder (16-20h, once/day)
- [x] **Fahrtenbuch report (Phase 3, 2026-06-12)**: server seals every resolved trip (SHA-256 canonical aggregate → Hedera HCS when configured, seal survives point pruning, re-resolution appends a new seal version), odometer chain validation per device (gap flagged, e.g. +25 km unaccounted), monthly export GET /api/trips/export?month=&format=csv|pdf (German Finanzamt columns incl. GoBD-Siegel)
- [x] **Navigation assist (Phase 4, 2026-06-12)**: 🧭 google.navigation intent on visit cards (coords stay on device), one-shot GPS precise-arrival check within 1 km of open targets (5-min per-visit cooldown). ActionProof-on-arrival deferred (check-in events already carry position)
- [ ] Trips overlay on the WMS dashboard Leaflet map
- [ ] Open-trip checkpoint upload (currently uploads only on finalize)
- [x] OPENCELLID_API_KEY provisioned on pda.repair + eck1/2/3 (2026-06-13); cells resolve to coords, cell_tower cache fills (resolve-once)
- [x] **paid/free product-flavor split (2026-06-13)**: paid = sideload/MDM (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in src/paid/, battery-exemption dialog, BuildConfig.ENTERPRISE=true); free = Google Play (no restricted permission, .free app id, battery-settings deep-link). Build `assemblePaidDebug` / `assembleFreeDebug`. aapt-verified.
- [x] **Trip-recording OS-kill hardening (2026-06-13)**: finalizeAndStop DB-fallback (Fahrt beenden survives an OS-killed FGS); battery/background-restriction detection + guard UI; per-paid-PDA provisioning via adb (`dumpsys deviceidle whitelist +pkg` + `appops set pkg RUN_ANY_IN_BACKGROUND allow`) or device-owner
- [ ] FULL Play-ready free flavor also needs the xelixir agent (AccessibilityService + MediaProjection FGS) flavor-gated out — only the battery permission is split so far
- [ ] On-device cell resolution (offline OpenCelliD DACH extract) — end-state where coordinates never leave the phone
- [ ] Visit creation UI on the dashboard (orders already geocoded; currently visits are created via POST /api/visits)

## Phase 10: Device Identity — ANDROID_ID → UUID ✅ DONE 2026-06-28
**Context**: `registered_device` was keyed by the legacy `Settings.Secure.ANDROID_ID`
(16-hex), a weak/unstable identity that also couldn't be told apart from UUID-keyed
node-self rows. Now PDA devices are keyed by a **UUID** like everything else, with the
Ed25519 `public_key` as the identity anchor.

- [x] **UUID is the canonical device id**; `android_id` kept as a secondary lookup
  field. App sends its current id (ANDROID_ID on first pairing) → server resolves via
  the public-key anchor → returns `device_uuid` → app adopts it (register response +
  `/api/status` heartbeat). `register_device` signs/sends an explicit `signedDeviceId`
  so the signature still matches. (`wms/src/handlers/device.rs`, app
  `SettingsManager` + `ScanApiService`.)
- [x] **Migration**: `migrator --devices` (SurrealDB-only, idempotent) re-keys every
  legacy ANDROID_ID row to a UUID, moves the old id to `android_id`, tombstones the
  old row. ⚠️ **Run once on prod after deploying the new server.**
- [x] **Transition safety**: `resolve_device` falls back to `WHERE android_id = …`,
  so devices keep working across the deploy/migration window.
- [ ] **Follow-up**: `instance_id` (mesh node id) is still `pda_<android_id>` for
  existing installs — separate from `registered_device`; migrate later if needed.
- [ ] 9eck.com mesh `claim-home` can now rely on device rows being UUID-keyed (see
  9eck.com [[project_sync_ownership]]).

## Phase 11: Pairing relay-failover & free-tier onboarding (2026-06-30)
**Context**: Relay-forwarded pairing (used when the master is behind NAT and no LAN
WMS is directly reachable) used to dispatch `device_register` to a SINGLE hardcoded
**free** relay (`9eck.com`), so paid pairings leaked onto the free relay and any
relay hiccup aborted pairing. ТЗ: paid pairing must sweep the eckN polygon; the free
relay must never be the authoritative path for a paid mesh.

- [x] **One app, two tiers, decided from the QR** — no separate paid/free build for
  pairing. `ECK$3$…` (paid) ⇒ relay polygon = baked-in eckN service nodes; `ECK$2$…`
  (free) ⇒ relay polygon = the public relay URL(s) carried INSIDE the QR. Nothing
  hardcoded as a free fallback.
- [x] **Deterministic polygon order** — paid meshes order eckN by
  `SettingsManager.orderedEckNodes(meshId)` (`compute_primary_index` = u32 BE of
  `sha256(mesh_id)[..4] % n`, then rotate), so phone and master independently pick the
  same primary relay. Previously this helper was dead code.
- [x] **Skip-to-next failover** — `RelayClient.meshDispatch` now returns a classified
  `RelayDispatch { Ok | Retryable | Fatal }`: transport error / 5xx → next relay;
  4xx / unparseable 2xx → authoritative stop; 2xx → wait for the master ack, and on
  ack-timeout advance to the next relay, sweeping the WHOLE polygon (mirrors Rust
  `relay_client` `payload_order` / `relay_is_down`).
- [x] **Relay ≠ authoritative server for free meshes** — a successful relay pairing
  saves the working relay as `relay_url`; only a PAID eckN node (also a real WMS) is
  additionally promoted to `server_url`. A free blind relay is never treated as the
  WMS.
- [ ] **PDAs onboarded via 9eck.com acting as a SERVER (temporary-code entry)** —
  future: let a phone attach to `9eck.com` as its WMS server (not just a blind relay)
  using the same temporary-code / invite-token mechanism as QR pairing — type a
  short-lived code instead of scanning a QR. Reuses the relay-forwarded
  `device_register` envelope + invite-token auto-approve; the code resolves to the
  target master UUID + (free) relay polygon. Enables free-tier onboarding without a
  printed QR.
