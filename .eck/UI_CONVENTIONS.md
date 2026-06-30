# UI Conventions

## Button text contrast

- **Full hex buttons** (mode/action buttons in the grid): **light text on a colored
  background** — the vivid, attention-grabbing style they have today.
- **System half-buttons** (the small HALF_LEFT / HALF_RIGHT status buttons, e.g. the
  network/server indicator, user indicator): **dark text on a MEDIUM-toned background**,
  so they read clearly but don't glare / distract like a full mode button.

Apply this to every system half-button going forward.

## Network/server half-button (HALF_LEFT, row 0)

`NetworkIndicatorButton` in `ui/screens/pos/components/SelectionAreaSheet.kt`. Two
**independent** axes:

- **Background = transport** (derived from `NetworkHealthState.connectionType`):
  - direct local server reachable (LAN, an IP host) → **green** (`0xFF4CAF50`)
  - reachable only via relay / remote (a domain host, e.g. eckN) → **orange** (`0xFFF57C00`)
  - nothing reachable → **red** (`0xFFC62828`)
- **Text color = mesh status** (`deviceRegistrationStatus`), dark so it reads on the
  medium background:
  - `active` / `running` (accepted) → **pure green** (`0xFF006000`)
  - `pending` (waiting for approval) → **dark amber** (`0xFF6D4C00`)
  - `blocked` / `rejected` / `deleted` / `unregistered` → **dark red** (`0xFF4A0000`)
  - unknown → **dark grey** (`0xFF263238`)

So e.g. *direct + accepted* = green bg + dark-green text; *relay + accepted* = orange bg +
dark-green text; *offline + accepted* = red bg + dark-green text (paired but no live link).
