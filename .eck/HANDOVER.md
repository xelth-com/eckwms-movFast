# Handover: Current State

**Date:** 2026-03-27

## Architecture Overview
- **Backend:** Rust (Axum + Sea-ORM + PostgreSQL) at `eckwmsr/`, port 3210
- **Android:** Kotlin/Jetpack Compose PDA client at `eckwms-movFast/`
- **Mesh:** Peer-to-peer sync via relay at `9eck.com`, push-on-write replication

## Recently Completed

### Offline-First SmartTag Decryption
- `EckSecurityManager.kt` decrypts V1 (legacy text) and V2 (binary SmartTag) QR codes locally
- AES-192-GCM with custom Base32, SHA-256 nonce derivation
- No network dependency — works immediately after QR pairing provides `enc_key`

### Dynamic QR Prefix Routing
- Server exposes `qr_prefixes` and `qr_tenant_suffix` in `/api/status`
- Android stores dynamic prefixes, merges with hardcoded fallbacks (`9eck.com/`, `xelth.com/`)
- `isTrustedLinkBarcode()` replaces all hardcoded `eck1/2/3.com` checks
- Zero hardcoded ECK domain references remain in Kotlin code

### CRM Entity Screen & Offline Updates
- `CrmEntityScreen.kt` — view/edit companies, persons, opportunities from SmartTag scans
- Status chips (contextual per entity type) + notes field
- Saves as `crm_update` in `SyncQueueEntity` for offline-first sync
- `WarehouseRepository.queueCrmUpdate()` handles persistence + sync scheduling

### ActionProof Component (Server-Driven UI)
- `ActionProofView.kt` — legal Proof of Action for delivery handovers / caregiver confirmations
- Voice-to-text (RecognizerIntent), signature drawing (Compose Canvas), GPS capture (LocationManager)
- Integrated into `DynamicUiRenderer` as `action_proof` component type
- Permissions: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` added to manifest

### Repair Auto-Order
- Slot bind → `device_bound` event → Rust creates pending order (`REP-YYYYMMDD-XXXX`)
- Photos uploaded as CAS files, attached to serial number

## Current Context
- **Server:** Rust backend on `:3210`, Nginx proxy on prod (`ssh antigravity`)
- **DB:** PostgreSQL, all entity IDs migrated from Long to String (UUID)
- **Sync:** 5-min heartbeat, push-on-write (Direct HTTP → WS signal → Relay fallback)

## Known Gaps
- SyncWorker doesn't handle `crm_update` queue type yet (queued but not pushed)
- CRM screen is write-only (no entity data fetch/display from server)
- No offline confirm queue for pickings
- Android doesn't participate in relay-based mesh sync directly
