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
