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

## Resolved (2026-06-11)

- ~~No offline confirm queue for pickings~~ ✅ `picking_confirm` / `picking_validate` SyncQueue job types; PickingViewModel queues on failure, SyncWorker retries.
- ~~SyncWorker doesn't handle `crm_update` type~~ ✅ `crm_update` branch POSTs to WMS `/api/crm/update` via `ScanApiService.pushCrmUpdate()`.
- ~~CRM screen is write-only~~ ✅ `CrmEntityScreen` fetches entity via `WarehouseRepository.getCrmEntity()` (network-first, cache fallback), shows name, prefills status.
- ~~No local CRM entity cache~~ ✅ `crm_entities` Room table (`CrmEntityEntity` + `CrmEntityDao`, DB v12) caches fetched entities for offline browsing.
- ~~ActionProof signature is not captured as image~~ ✅ Strokes are rasterized to Bitmap → WebP lossy 75 → Base64 (`signature_image` + `signature_mime`).
- ~~ActionProof GPS uses `getLastKnownLocation` only~~ ✅ Two-stage fix: instant last-known fallback + `LocationManager.getCurrentLocation()` fresh fix; proof records `location.source` = `fresh_fix`/`last_known`.
