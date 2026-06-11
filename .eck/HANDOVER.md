# Handover: Current State

**Date:** 2026-06-11

## Architecture Overview
- **Backend:** Rust (Axum + SurrealDB) — the `9eck.com` monorepo WMS, port 3210.
  The legacy `eckwmsr` (Sea-ORM/PostgreSQL) repo is GONE from disk; its data was
  migrated to SurrealDB and its scraper moved into `9eck.com/scraper`.
- **Android:** Kotlin/Jetpack Compose PDA client at `eckwms-movFast/`
- **Mesh:** Peer-to-peer sync via relay at `9eck.com`, push-on-write replication

## Backend contract (restored 2026-06-11 in 9eck.com WMS)
The new WMS serves the full PDA surface under BOTH `/api/*` and `/E/api/*`
(legacy pairing QRs produce base URLs ending in `/E`). Implemented in
`wms/src/handlers/pda.rs`:
- `GET /api/status` — heartbeat: device status, `enc_key`, `repair_order_prefix`
  (env `REPAIR_ORDER_PREFIX`), `qr_prefixes` (env `QR_PREFIXES`), `qr_tenant_suffix`
- `POST /api/scan` — msgId dedup, device gate, `{prefix}-{uuid}` SmartTag routing,
  raw barcode resolution (product/order/location), ambiguous→candidates, stub-create
- `POST /api/internal/register-device` — alias of `/api/public/devices/register`
- `POST /api/repair/event` — audit row + `device_bound` auto-creates `REP-…` order
- `POST /api/upload/image` — files::upload (CAS); repair photos auto-attach to the
  open order by serial
- `GET /api/users/active`, `POST /api/users/verify-pin` — PIN user switching
- `GET /api/pickings/active`, `GET /api/pickings/:id/route`,
  `POST /api/pickings/:id/lines/:lineId/confirm`, `POST /api/pickings/:id/validate`
- `GET /api/explorer/locations[/:id/contents]`, `GET /api/explorer/products[/:id/locations]`
- `POST /api/sync/pull` — file_resources + attachments metadata for Room cache
- `GET /api/crm/:type/:id`, `POST /api/crm/update` — CRM fetch + offline edit apply

## Recently Completed (Android, 2026-06-11)
- **CRM sync loop closed:** SyncWorker handles `crm_update`; CrmEntityScreen fetches
  entity (name/status) network-first with Room cache (`crm_entities`, DB v12)
- **Picking offline queue:** `picking_confirm`/`picking_validate` SyncQueue jobs
- **ActionProof:** real signature image (strokes → Bitmap → WebP 75 → Base64),
  fresh GPS fix via `getCurrentLocation()` with last-known fallback + source flag
- **Xelixir embedded support agent (PR #1, 5c72ccd):** Phase A (MediaProjection
  view), B (AccessibilityService input), C (push triggers + license claim).
  Dormant by default. License token baked from `local.properties`
  (`xelixir.licenseToken`) or provisioned at runtime.

## Known Gaps
- ExplorerScreen still parses ids as Long (server now returns String ids)
- WMS `/E/ws` exists but the PDA push-channel protocol compatibility is unverified
  (HybridMessageSender falls back to HTTP, so non-blocking)
- Xelixir agent not yet live-tested: needs an admin-issued license token
  (`saveXelixirLicenseToken`) or master `WS_AUTH_TOKEN` for bring-up
- `POST /api/documents` (submitDocument, X-API-Key only) not ported — PDA sends
  no JWT there; endpoint absent in new WMS
