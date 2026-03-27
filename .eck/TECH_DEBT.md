# Tech Debt

## Active TODOs in Code

1. **`ScanScreen.kt:683`** — `TODO: Extract target location barcode from step params`
   "showMap" workflow step uses placeholder `p-LOC-001` for demo. Should extract real location from WorkflowStep params map.

## Structural Issues

2. **No offline confirm queue for pickings** — PickingExecuteScreen can confirm pick lines, but confirmations aren't queued when offline. They simply fail. Should store in SyncQueue and retry when connectivity returns.

3. **SyncWorker runs on fixed schedule** — Background sync uses JobScheduler with fixed intervals. No event-driven sync (e.g. trigger sync immediately when WiFi reconnects).

4. **Repair slot photos stored on-device only** — Photos in `filesDir/repair_photos/slot_N.webp` are uploaded to server but not backed up. If app data is cleared, photos are lost even if they were uploaded.

5. **No mesh relay sync on Android** — PDA only communicates via direct HTTP to the server URL set during pairing. It does not participate in relay-based mesh sync. If the paired server is unreachable, data sits in local queue.

6. **UserManager singleton coupling** — UserManager uses companion object singleton pattern which makes testing difficult. Should use Hilt/DI injection.

## CRM & Sync Gaps

7. **SyncWorker doesn't handle `crm_update` type** — `WarehouseRepository.queueCrmUpdate()` saves entries to `sync_queue` with `type = "crm_update"`, but `SyncWorker` has no handler to POST these to the Rust server. Updates are queued but never pushed.

8. **CRM screen is write-only** — `CrmEntityScreen` accepts entity type/UUID from SmartTag scan but has no way to fetch existing entity data from the server. Users can add notes/status but can't see current entity details.

9. **No local CRM entity cache** — Unlike products/locations (synced via mesh pull), CRM entities (companies, persons, opportunities) have no Room entity or DAO. Offline browsing of previously scanned CRM entities is not possible.

10. **Pairing code detection still uses `startsWith("ECK")`** — In `ScanRecoveryViewModel.handleGeneralScanResult()`, the pairing code detection (`effectiveCode.startsWith("ECK") && !isLinkBarcode`) still assumes pairing codes start with "ECK". This should be configurable if self-hosted instances use different pairing prefixes.

11. **ActionProof signature is not captured as image** — `ActionProofView.kt` sets `signature_image = "captured"` (boolean flag) instead of rendering the Compose `Path` to a Bitmap and encoding as Base64. Needs a `Path → Bitmap → Base64` utility for actual signature storage/transmission.

12. **ActionProof GPS uses `getLastKnownLocation` only** — No fresh location fix is requested. If last-known is stale or null, no GPS data is included in the proof. Should request a single fresh fix with timeout as fallback.
