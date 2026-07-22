# UI Conventions

## Button text contrast

- **Full hex buttons** (mode/action buttons in the grid): **light text on a colored
  background** — the vivid, attention-grabbing style they have today.
- **System half-buttons** (the small HALF_LEFT / HALF_RIGHT status buttons, e.g. the
  network/server indicator, user indicator): **dark text on a MEDIUM-toned background**,
  so they read clearly but don't glare / distract like a full mode button.

Apply this to every system half-button going forward.

## Red ✕ half-button (HALF_RIGHT, row 1) — back vs minimize

The red ✕ exit half-slot exists in EVERY mode **including the root grid**
(`_exitButton` in `MainScreenViewModel`; rendered in `SelectionAreaSheet.kt`).

- **Short press** = one step back: exit the current mode to the root grid,
  cancel the smart context (`act_smart_cancel`), etc. — whatever action the
  `HalfButtonState` carries. At the root it's a deliberate no-op.
- **Long press** = **minimize the app** (`moveTaskToBack(true)`), like a Home
  press: process stays alive, mode/state untouched — reopening returns exactly
  where the user left. This is dispatched as a FIXED `"act_minimize_app"` from
  the half-slot itself, independent of the short-press action, so it works at
  every menu depth (even when the ✕ is dimmed to `act_noop` on the receiving
  Contents step). Keep it that way when adding new modes: set the short-press
  action in `HalfButtonState`, never touch the long-press path.
- History: before 2026-07-21 long-press meant "jump straight to the root".
  That was replaced by minimize at the owner's request.

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
