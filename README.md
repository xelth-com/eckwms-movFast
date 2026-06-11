# eckwms-movFast
ECKWMS client for the movFast device.

## Features
- **POS-Style Hexagonal Grid:** Touch-optimized warehouse operations with configurable button layout
- **Hardware Scanner Integration:** XCScannerWrapper SDK + CameraX ML Kit barcode fallback
- **Repair Mode:** 18-slot device repair workflow with photo capture, timer escalation, and auto-order creation
- **True Offline-First SmartTags:** Zero-latency local decryption of V2 Binary SmartTags (AES-192-GCM) without network dependency
- **Dynamic QR Routing:** QR prefixes fetched from server `/api/status` with hardcoded relay fallbacks (`9eck.com/`, `xelth.com/`)
- **Offline CRM Editing:** View and edit CRM entities (Company, Person, Opportunity) on the PDA with changes queued for mesh sync
- **Mesh Network Sync:** Peer-to-peer data replication via relay discovery, push-on-write, and periodic heartbeat
- **Multi-User System:** PIN-based login with role/permission management from server
- **Inventory Mode:** Location-based stock counting with expected inventory comparison and photo evidence
- **Picking Mode:** Route-optimized order picking with warehouse map overlay
