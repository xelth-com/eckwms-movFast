# Server spec — PDA onboarding by temporary code (for the 9eck.com coder)

**Status:** Android client is DONE and live (commit `3306db0`) and **needs no changes**.
Server side = a code **rendezvous on the EXISTING discovery board** (the one servers
already use to find each other). **No** parallel stored-QR table, **no** mesh-synced
`pair_code` table of pre-built strings, **nothing** hosting-specific. See ROADMAP Phase 11.

**Decided (architect):** the resolver returns **`{"qr":"ECK$…"}`** built on the fly from the
rendezvous record (variant A). One canonical pairing format; the scan-path and code-path
converge on the same parser (`handlePairingQrCode`). The `ECK$` here is just a compact
encoding of the resolve result — **not** a stored QR.

## Model (read this first — it's the whole point)
The code is a **secret classified-ad on the rendezvous board**, NOT a key into a QR store:

- A **master** that wants to onboard a PDA posts a short rendezvous entry keyed by the code:
  **`code → { master UUID, master public KEY, mesh, invite-token, reachable relay }`**
  (short TTL, single-use).
- A **PDA types the same code** → looks it up on the board → gets back the assembled `ECK$`
  pairing string → runs the existing **relay-forwarded** pairing (`device_register` over the
  relay queue `/E/m/*`). Exactly how a server finds a peer — just initiated by a typed secret.
- **Free tier → the public board** (the relay everyone already polls, e.g. 9eck.com).
  **Paid tier → eckN.** Same split as the relay polygon.
- **Reuse the existing discovery / node-advertisement mechanism.** Do **NOT** build a
  parallel `pair_code` table that stores full `ECK$…` strings and needs its own mesh sync,
  and there's nothing hosting-specific — it runs wherever the board already runs.

## ⚠️ The rendezvous record MUST carry the master's KEY
The resolver builds the full `ECK$…` on the fly, so the record needs every field the QR
needs — including the master's **public key** (field `KEY`). `KEY` is the master's identity
anchor (the scan-QR pins it deliberately); it is public, so posting it on the board is safe.
So the master posts **`{UUID, KEY, mesh, invite-token, relay}`**, not just
`{UUID, invite-token, relay}`. The resolver then assembles:

- **free** → `ECK$2$UUID$KEY$<relay-urls>$<invite-jwt>`
- **paid** → `ECK$3$UUID$KEY$MESH$<own/relay-urls>$<invite-jwt>`

(`$`-separated; UUID/KEY/MESH compact uppercase, exactly the existing QR format — reuse
`build_pairing_qr`'s field builder.) For **free**, `<relay-urls>` MUST include a public
relay reachable by the NAT'd master (the PDA pairs relay-forwarded). `INVITE_JWT` =
auto-approve token (`role=invite`) so the device lands `active` with no manual approval.

## Client contract (already built — do not change)
`POST {board}/E/pair/code`  body `{ "code": "ABC123" }` (client uppercases/trims):

| Code | Body | Client behaviour |
|------|------|------------------|
| `200` | `{ "qr": "ECK$2$UUID$KEY$URLS$INVITE_JWT" }` (built on the fly from the record) | feeds `qr` to `handlePairingQrCode` → normal pairing |
| `404` | — | **authoritative**: unknown / expired / used → stop, show "invalid/expired" (does NOT try the next board) |
| `5xx` / transport | — | try the next board (`9eck.com` → `xelth.com`) |

## Master side (mint)
- Generate a short code (your call — e.g. 6-char Crockford base32), publish the rendezvous
  entry `{UUID, KEY, mesh, invite-token, relay}` on the board keyed by the code, TTL ≈10 min,
  single-use; **burn on first successful resolve**. The master UI shows the code to read out
  / type into the PDA.

## Acceptance
- A master posts a code; a PDA on mobile data types it (Network mode → 🔑 Code) and lands
  `status=active` on that master via the relay — **no printed QR, no LAN**.
- Free resolves on the public board, paid on eckN.
- Unknown / expired / used code → `404` → PDA shows "invalid/expired".

## Out of scope (Android follow-up, not server)
- Replace the soft-keyboard code dialog with an on-grid **hex keypad**. System Scan button
  stays the 🔲 square.
