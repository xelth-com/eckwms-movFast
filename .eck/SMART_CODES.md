# Smart Codes Specification

All Smart Codes are **exactly 19 characters** long (required for AES encryption into 76-char QR).

**Base36 Alphabet**: `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ`

## 1. Smart Item (`i`) — Variable Serial + EAN

**Format**: `i[L][Serial_padded][EAN/RefID]` = 19 chars

| Field | Size | Description |
|-------|------|-------------|
| `i` | 1 | Prefix |
| `L` | 1 | Base36 length of RefID suffix |
| Serial | 17-len(RefID) | Zero-padded serial number |
| RefID | L chars | EAN-13, UPC, internal ref |

**Length indicator `L`**: `0`=0, `1`=1, ..., `9`=9, `A`=10, `B`=11, `C`=12, **`D`=13** (EAN-13), `E`=14, ..., `Z`=35

**Example** (EAN-13 `4262373862555`, no serial):
```
iD00004262373862555
│││    └─────────────── EAN-13 (13 chars)
││└──────────────────── Serial (4 chars, zero-padded)
│└───────────────────── L = D (13 in base36)
└────────────────────── Prefix
Total: 1 + 1 + 4 + 13 = 19
```

**Example** (EAN-13, serial `AB12`):
```
iDAB124262373862555
```

**Parsing**: last `L` chars = RefID, middle = serial.

## 2. Smart Box (`b`) — Dimensions + Weight + Type + Serial

**Format**: `bLLWWHHMMMTSSSSSSSS` = 19 chars (fixed)

| Field | Size | Description |
|-------|------|-------------|
| `b` | 1 | Prefix |
| LL | 2 | Length cm (base36, 0-1295) |
| WW | 2 | Width cm |
| HH | 2 | Height cm |
| MMM | 3 | Weight (tiered precision) |
| T | 1 | Type: P=Pallet, B=Box, C=Crate |
| SSSSSSSS | 8 | Serial (base36, up to 2.8T) |

**Weight tiers**:
- 0-20kg: 10g precision (values 0-2000)
- 20-1000kg: 100g precision (2000-11800)
- 1000-30000kg: 1kg precision (11800-40800)

**Example** (40x30x25cm, 5.5kg, Box, serial #12345):
```
b140U0P0FAB000009IX
```

## 3. Smart Label (`l`) — Date + Type + Payload

**Format**: `lDDDTPPPPPPPPPPPPPP` = 19 chars (fixed)

| Field | Size | Description |
|-------|------|-------------|
| `l` | 1 | Prefix |
| DDD | 3 | Days since 2025-01-01 (base36, ~128 years) |
| T | 1 | Type: A=Action, S=Status, U=User |
| P... | 14 | Payload (right-padded with 0) |

**Example** (June 15 2025, Action, payload "TESTPAYLOAD123"):
```
l04LATESTPAYLOAD123
```

## 4. Smart Place (`p`) — Odoo Location

**Format**: `p[zero-padded Odoo location ID]` = 19 chars

| Field | Size | Description |
|-------|------|-------------|
| `p` | 1 | Prefix |
| ID | 18 | Odoo location ID, zero-padded |

**Example** (location ID 7):
```
p000000000000000007
```

## Encrypted QR Code Format

### V1 (Legacy Text-Based)
Each Smart Code (19 chars) is encrypted with AES-192-GCM into a 76-char URL:

```
{PREFIX}/[56 chars encrypted][9 chars IV][2 chars tenant suffix]
```

- 16-byte nonce reconstructed from 9-char IV (repeat-padded)
- Decrypts to plaintext string (e.g. `p000000000000000031`)

### V2 (Binary SmartTag)

The V2 payload is always **19 bytes**: 16B UUID + 1B entity type + 2B flags.
The type field is 1 byte regardless of the entity — names below are display
prefixes, they cost nothing on the wire.

**Canonical entity alphabet (2026-07-14): the type byte IS the ASCII letter.**
One letter per entity. The source system (Odoo / Twenty / native) is NOT
encoded in the type — a partner mirrored from Odoo scans identically to a
native one; origin lives in `external_ref` on the record. Conversations,
messages and tickets have **no scan type**: they attach to scannable entities
via graph edges (`regarding`), so you scan the item/order/partner and see its
communication, not the other way around.

| Letter | Entity | Notes |
|--------|--------|-------|
| `i` | Item | serialized unit |
| `b` | Box | package/pallet |
| `p` | Place | location |
| `o` | Order | any kind: sale/repair/rma/opportunity |
| `l` | Label | |
| `u` | User | staff |
| `c` | Company | partner (kind=company) |
| `h` | Person | partner (kind=person), "human" |
| `d` | Deal | opportunity (folds into order lifecycle later) |
| `a` | Article | product; reserved — no scan route wired yet |

**Legacy numeric type bytes** (already printed on physical tags — decode-only,
never generated anymore; they collapse into canonical letters on decode):
`0x00-0x05` → `i b p o l u`, `0x10` → `c`, `0x11` → `h`, `0x12` → `d`,
`0x20` → `a`, `0x21` → `c`.

**Two V2 containers exist, with DIFFERENT field order — both are printed on
physical labels, so neither may change:**

1. **Plain label container** (server `print.rs` → `SmartTag::encode()`,
   URL-safe Base64, 26 chars, e.g. `ECK1.COM/{26ch}`):
   `[1B type][2B flags (BE, 0x0002)][16B UUID]`

2. **Encrypted container** (AES-192-GCM, decoded on PDA by
   `EckSecurityManager`):
   ```
   {PREFIX}/[56 chars Base32 data][dynamic IV][2 chars tenant suffix]
   ```
   - 12-byte nonce derived via `SHA-256(ivString)[:12]`
   - Base32 alphabet: `0123456789ABCDEFGHJKLMNPQRTUVWXY` (excludes I, O, S, Z)
   - 56 Base32 chars → 35 bytes (19 payload + 16 GCM auth tag)
   - 19-byte payload: `[16B UUID][1B entity_type][2B flags (big-endian)]`

After decode both yield the same route string `{letter}-{uuid}`
(e.g. `c-550e8400-e29b-41d4-a716-446655440000`). Android intercepts
`c-/h-/d-` locally (CRM screen; legacy `company-/person-/opp-` still
accepted), everything else goes to the server scan route
(`parse_typed_code` in `wms/src/handlers/pda.rs`).

### QR Prefix Configuration
- **Server config:** `QR_PREFIXES` env var (comma-separated, default: `ECK1.COM/`)
- **Exposed to clients:** `/api/status` → `qr_prefixes` array + `qr_tenant_suffix` string
- **Android:** Dynamic prefixes fetched from server, merged with hardcoded fallbacks (`9eck.com/`, `xelth.com/`)
- **Tenant suffix:** `QR_TENANT_SUFFIX` env var (default: `IB`), 2 chars appended to every QR

## Odoo Mapping

| Code | Odoo Model | Odoo Field |
|------|-----------|-----------|
| `i...` | stock.lot | name (serial), ref (EAN) |
| `b...` | stock.quant.package | name |
| `l...` | (custom) | - |
| `p...` | stock.location | barcode |

## Code Generation

**Rust**: `SmartCode::generate_item("", "4262373862555")` → `iD00004262373862555`
**Android**:
```kotlin
val base36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
val serialLen = 17 - ean.length
"i${base36[ean.length]}${"0".repeat(serialLen)}$ean"
```
