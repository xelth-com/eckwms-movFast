# Server spec — PDA onboarding by temporary code (for the 9eck.com coder)

**Status:** Android client is DONE and live (commit `3306db0`). Only the **server
resolver endpoint** below is missing. Once it exists, typing a code onboards a PDA with
no printed QR. See ROADMAP Phase 11.

## Goal
Let a PDA attach to a mesh by **typing a short-lived code** instead of scanning a QR. The
phone POSTs the code to a public resolver (`9eck.com`, then `xelth.com`), gets back a
**normal pairing-QR string**, and runs the existing pairing flow (direct or
relay-forwarded). Nothing else on the client changes.

## The one endpoint to implement
`POST {resolver}/E/pair/code` — on **9eck.com** (and ideally xelth.com).

**Request**
```json
{ "code": "ABC123" }
```
`code` = the user-typed code (the client already uppercases + trims it).

**Responses**
| Code | Body | Client behaviour |
|------|------|------------------|
| `200` | `{ "qr": "ECK$2$<UUID>$<KEY>$<RELAY_URLS>$<INVITE_JWT>" }` | parse `qr` → run pairing |
| `404` | — | **authoritative**: invalid / expired / already used → stop, show "invalid/expired" (does NOT try the next resolver) |
| `5xx` / transport | — | try the next resolver in the list |

- The `qr` is a **normal pairing-QR string** the client already parses
  (`handlePairingQrCode`). Reuse the master's existing QR generator.
- For **free** onboarding the `RELAY_URLS` field MUST include a public relay the phone can
  reach (e.g. `https://9eck.com`) — the master is behind NAT, so pairing goes
  **relay-forwarded** over `/E/m/*`. (A paid `ECK$3$…` QR is fine too if the code maps to a
  paid mesh — then the client uses the baked-in eckN polygon.)

## QR payload (existing format — do not invent a new one)
- v2 (free): `ECK$2$UUID$KEY$URLS[$TOKEN]`
- v3 (paid): `ECK$3$UUID$KEY$MESH$OWN_URLS[$TOKEN]`

`$`-separated; UUID/KEY/MESH are compact uppercase. `TOKEN` = the **invite-JWT**
(`role=invite`, auto-approve) so the device lands `active` without manual approval, exactly
like a scanned invite QR.

## Code lifecycle (server side)
1. An admin / the master **mints** a short code bound to: target **master UUID**, **mesh**,
   an **invite-token**, and the (free) **relay** to advertise in the QR.
2. **Short-lived** (≈5–15 min TTL), ideally **single-use**.
3. On resolve: look up the unexpired code → build the `ECK$…` QR (same generator as the
   printed/scanned QR) → return `{ "qr": … }`. Optionally burn the code on success.

## How the client consumes it (context)
- `ScanApiService.resolvePairingCode(code)` walks
  `SettingsManager.getOnboardingResolvers()` (default `https://9eck.com`,
  `https://xelth.com`): transport/5xx → next resolver, `404` → stop, `2xx {qr}` → success.
- `ScanRecoveryViewModel.pairWithCode(code)` → resolves → `handlePairingQrCode(qr)` → the
  normal direct / relay-forwarded pairing runs (relay-forwarded `device_register` rides the
  relay queue `/E/m/dispatch|poll|ack|result`).
- UI: **Network mode** (single-tap the server half-button) → 🔑 **Code** → type → Connect.

## Acceptance
- `POST https://9eck.com/E/pair/code {"code":"<valid>"}` → `200` with a parseable
  `ECK$2$…` QR whose relay URLs include a reachable public relay.
- A PDA typing that code (Network mode → 🔑 Code) lands `status=active` on the target
  master, **no printed QR**.
- Expired / used / garbage code → `404` → PDA shows "invalid/expired".

## Out of scope (Android follow-up, not server)
- Replace the soft-keyboard code dialog with an on-grid **hex keypad** (type the code on
  the hexagons). The system Scan button stays the usual 🔲 square.
