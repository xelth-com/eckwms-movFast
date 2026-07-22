# eckwms-movFast — Android PDA Client

## Description
Android warehouse management PDA app with barcode scanning, repair mode, CRM integration, and POS-style hexagonal grid UI. Communicates with eckwmsr (Rust/Axum) backend via REST + WebSocket. Fully offline-capable with local SmartTag decryption and sync queue.

## Architecture
- **Language:** Kotlin
- **UI:** Jetpack Compose (single Activity, NavController routing)
- **State:** ViewModel + LiveData
- **Camera:** CameraX (ImageCapture + ML Kit barcode detection)
- **Network:** Retrofit/OkHttp → eckwmsgo server
- **Scanner:** XCScannerWrapper (hardware PDA scanner SDK) + camera fallback
- **Two processes (since 2026-07-20):** the trip recording stack
  (TripRecordingService + auto-detect receivers + Room invalidation hub) runs
  in the small `:trips` process so it survives the memory pressure that kills
  the heavy UI process mid-drive. `EckwmsApp.onCreate` gates init by process
  (`ProcessUtils`); `:trips` must never write plain SharedPreferences (see
  TECH_DEBT "Process split"); cross-process state rides Room (WAL +
  multi-instance invalidation), the heartbeat file and `files/trip_intent.json`.
  Per-process post-mortem log: `files/trip_logs/` (TripLog + ApplicationExitInfo).

## Image Format Convention
**All image processing uses WebP lossy.** No PNG, no JPEG anywhere in the pipeline.

| Context | Format | Quality |
|---------|--------|---------|
| Server upload (ScanApiService) | WebP lossy | 75% |
| Repair slot photos (disk cache) | WebP lossy | 75% |
| Gallery save (FileUtils) | WebP lossy | 75% |

**Quality is 75% everywhere.** This is the inflection point where quality is sufficient for AI/Human inspection (224x224 Smart Crop) but file size drops significantly (5-8KB per avatar), enabling instant synchronization across the mesh network.

## Server-Driven UI (Dynamic Components)
`DynamicUiRenderer.kt` renders JSON-described layouts from the backend. Supported component types:
- `text`, `info_row`, `boolean_toggle`, `single_choice`, `button`, `text_input`, `date_input`, `dropdown_select`, `card`, `spacing`
- `action_proof` — legal Proof of Action component (`ActionProofView.kt`): voice-to-text name capture (RecognizerIntent, de-DE), signature drawing (Compose Canvas), GPS coordinates (LocationManager). Outputs JSON via `onValueChange`.

## Key Screens
- `MainScreen` — POS grid (hexagonal buttons) + console/repair photo panel
- `CameraScanScreen` — barcode scan (BarcodeAnalyzer) or photo capture (ImageCapture)
- `ScanScreen` — barcode workflow with interlocking console
- `CrmEntityScreen` — offline CRM entity viewer/editor (company, person, opportunity)

## Repair Mode
- 18 slots in hexagonal grid (3 actions + 18 = 7 rows × 3)
- Slot data: barcode (SharedPreferences) + photo (filesDir/repair_photos/slot_N.webp)
- Non-destructive restore: enterRepairMode() updates existing slot objects, preserving in-memory photos
- Scanner barcode LiveData consumed after processing to prevent replay on recomposition

## Local Cryptography & SmartTags
The app decrypts V1 (legacy text) and V2 (binary SmartTag) encrypted QR codes entirely offline using `EckSecurityManager.kt`.

**Key storage:** The AES-192 encryption key (`enc_key`, 48 hex chars = 24 bytes) is fetched from the server's `/api/status` endpoint during device heartbeat and stored in `SettingsManager`. A hardcoded dev key is used as fallback before first pairing.

**V2 Binary SmartTag decryption flow:**
1. `isEncryptedEckUrl()` — heuristic check: any URL with `/` where the body after the last slash has >= 58 Base32 chars.
2. Strip prefix (everything before and including last `/`) and 2-char tenant suffix.
3. First 56 chars = Base32-encoded ciphertext (custom alphabet: `0123456789ABCDEFGHJKLMNPQRTUVWXY`, excludes I/O/S/Z).
4. Remaining chars = IV string. Nonce = `SHA-256(ivString)[:12 bytes]`.
5. Base32 decode 56 chars → 35 bytes (19 payload + 16 GCM auth tag).
6. AES-192-GCM decrypt with 12-byte nonce → 19-byte SmartTag: `[16B UUID][1B entity_type][2B flags]`.
7. Entity type mapped to routing prefix: `0x00`=item, `0x02`=place, `0x10`=company, `0x11`=person, `0x12`=opportunity, etc.

**Dynamic QR prefix routing:** QR prefixes are fetched from server (`qr_prefixes` array in `/api/status`) and stored in `SettingsManager`. Merged with hardcoded fallbacks (`9eck.com/`, `xelth.com/`). `EckSecurityManager.isTrustedLinkBarcode()` checks scanned barcodes against this dynamic list for the security filter (anti-spoofing).

## Offline CRM Updates
When a CRM SmartTag is scanned (entity types `company`, `person`, or `opp`), the scan router in `ScanRecoveryViewModel` or `MainScreenViewModel` intercepts the decrypted `{type}-{uuid}` code via regex and navigates to `CrmEntityScreen`.

**Edit & save flow:**
1. User selects a status (contextual chips per entity type) and/or adds notes.
2. On save, `WarehouseRepository.queueCrmUpdate()` creates a `SyncQueueEntity` with `type = "crm_update"` and JSON payload: `{entity_type, entity_id, changes: {notes, status}, timestamp}`.
3. `SyncManager.scheduleSync()` triggers the sync worker to push queued updates to the Rust server when connectivity is restored.
4. This decouples UI responsiveness from network state — the PDA remains fully functional offline.
