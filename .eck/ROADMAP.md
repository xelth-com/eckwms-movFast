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
- [ ] **Fahrtenbuch report (Phase 3)**: business/private classification UI, monthly PDF/CSV export, Hedera sealing of closed trips, odometer chain validation
- [ ] **Navigation assist (Phase 4)**: Maps intent to target, arrival detection with GPS, ActionProof on arrival
- [ ] Trips overlay on the WMS dashboard Leaflet map
- [ ] Open-trip checkpoint upload (currently uploads only on finalize)
- [ ] OPENCELLID_API_KEY provisioning on the server (without it only cached towers resolve)
- [ ] On-device cell resolution (offline OpenCelliD DACH extract) — end-state where coordinates never leave the phone
- [ ] Visit creation UI on the dashboard (orders already geocoded; currently visits are created via POST /api/visits)
