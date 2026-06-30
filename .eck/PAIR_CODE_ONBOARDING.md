# Server spec — PDA onboarding by temporary code (for the 9eck.com coder)

**Status:** Android client is DONE and live (commit `3306db0`). Server side = a code
**rendezvous on the EXISTING discovery board** (the one servers already use to find each
other). **No** parallel stored-QR table, **no** mesh-synced `pair_code` table of pre-built
strings, **nothing** hosting-specific. See ROADMAP Phase 11.

## Model (read this first — it's the whole point)
The code is a **secret classified-ad on the rendezvous board**, NOT a key into a QR store:

- A **master** that wants to onboard a PDA posts a short rendezvous entry
  **`code → { master UUID, invite-token, reachable relay }`** on the board (short TTL,
  single-use).
- A **PDA types the same code** → looks it up on the board → gets the master's pairing
  payload → runs the existing **relay-forwarded** pairing (`device_register` over the relay
  queue `/E/m/*`). This is exactly how a server finds a peer — just initiated by a typed
  secret instead of mesh gossip.
- **Free tier → the public board** (the relay everyone already polls, e.g. 9eck.com).
  **Paid tier → eckN.** Same split as the relay polygon.
- **Reuse the existing discovery / node-advertisement mechanism.** Do **NOT** build a
  parallel `pair_code` table that stores full `ECK$…` strings and needs its own mesh sync,
  and there's nothing hosting-specific — it runs wherever the board already runs.

## Why not the stored-QR approach
Storing pre-built QR strings in a new synced table duplicates the QR concept and adds sync
surface. The code only needs to let the PDA **find the master**; the board already does
"find a node". Keep just an ephemeral rendezvous record (code → master + invite + relay +
expiry), and build the pairing payload on the fly at resolve time if you want to reuse the
existing `build_pairing_qr` field builder.

## Client contract (already built — keep it)
`POST {board}/E/pair/code`  body `{ "code": "ABC123" }` (client uppercases/trims):

| Code | Body | Client behaviour |
|------|------|------------------|
| `200` | the master's pairing payload (see below) | run the normal pairing flow |
| `404` | — | **authoritative**: unknown / expired / used → stop, show "invalid/expired" (does NOT try the next board) |
| `5xx` / transport | — | try the next board (`9eck.com` → `xelth.com`) |

**200 body — pick one:**
- **Default (zero client change):** `{ "qr": "ECK$2$UUID$KEY$RELAY_URLS$INVITE_JWT" }` —
  the pairing string **built on the fly from the rendezvous entry** (an *encoding of the
  rendezvous result*, not a stored QR). `RELAY_URLS` must include a public relay reachable
  by the NAT'd master (free). The client feeds it straight to `handlePairingQrCode`.
- **Alternative:** `{ "master_uuid", "relay", "invite_token", "mesh", "paid" }` — if you
  prefer structured fields, say so; it's a ~3-line client tweak (we'd call
  `registerViaRelay` directly).

`INVITE_JWT` = the auto-approve token (`role=invite`) so the device lands `active` with no
manual approval, exactly like a scanned invite QR.

## Master side (mint)
- Generate a short code (your call — e.g. 6-char Crockford base32), publish the rendezvous
  entry on the board keyed by the code, TTL ≈10 min, single-use; burn on first successful
  resolve. The master UI shows the code to read out / type into the PDA.

## Acceptance
- A master posts a code; a PDA on mobile data types it (Network mode → 🔑 Code) and lands
  `status=active` on that master via the relay — **no printed QR, no LAN**.
- Free resolves on the public board, paid on eckN.
- Unknown / expired / used code → `404` → PDA shows "invalid/expired".

## Out of scope (Android follow-up, not server)
- Replace the soft-keyboard code dialog with an on-grid **hex keypad**. System Scan button
  stays the 🔲 square.
