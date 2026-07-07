# XCheng Scanner Engine — Reverse-Engineering Reference

Everything we learned about the vendor scanner stack on the XCheng/MTK PDA
("Ranger2", test device `MT15AEM24120007`, Android 14). Compiled 2026-07-07
after the watchdog + one-press-from-sleep work (PR #2). **Read this before
debugging any scanner bug** — it took a full day of decompilation and
on-device experiments to establish; nothing here is guesswork, every claim was
verified on the device.

**Raw artifacts** (decompiled vendor app, exploded SDK, native libs) are NOT
in git — this repo is public and that code is proprietary. Local archive:
`C:\Users\Dmytro\vendor-re\xcheng-ranger2\` (see [Artifacts](#artifacts)).

---

## 1. Stack overview

| Component | What it actually is |
|---|---|
| Scan engine | **Camera device 2** — the imager is just another camera on the MTK ISP |
| Owner | System app `com.xcheng.scannere3` (uid 1000, persistent) |
| Decoder | **CortexDecoder** by Code Corporation, licensed per device (license states: `ACTIVED` / `ACTIVATING` / `NETWORK_ISSUE` / `Inactive`) |
| "Zebra" HAL | `vendor.mediatek.hardware.zebra@1.0-service` — a **MediaTek** component, NOT Zebra Technologies |
| App SDK | Vendor `.aar` `xcscanner_qrcode_v1.3.56.1.7` in `app/libs` (an older `1.1.x` line is also there, unused), wrapped by our `XCScannerWrapper` |
| Native libs | `libXCScanner.so`, `libcortexdecoder.so` (inside the .aar) |

**The one constraint that explains most failures:** the scan engine (camera 2)
and the app camera (camera 0) share **one ISP**. Opening both at once wedges
the cameras until reboot. The system knows this and arbitrates (see §3).

## 2. SDK internals (`XcBarcodeScanner`) — why re-init used to be a no-op

The SDK keeps its state in **static** fields (obfuscated names, v1.3.56.1.7):

- `a` — the AIDL binder to the vendor service
- `d` — the `ServiceConnection`
- `e` — init flag; `c` — callback holder

Two bugs interact:

1. `init()` **early-returns when `d != null`** — it assumes a non-null
   connection means "already bound".
2. `deInit()` is **not exception-safe** — `unbindService` throws once the
   vendor process has died, and the statics stay set.

So after the vendor process recycles: binder dead → `deInit()` throws →
`d` still set → `init()` no-ops → scanner dead until the **app process**
restarts (the only thing that used to clear the statics).

**Minimal reset** (no app restart): `XCScannerWrapper.forceReinitialize()` =
`deInit()` (exceptions swallowed) → reflectively null `a/d/c/e` → `init()`.
Config calls against a not-yet-connected binder are **silent no-ops**, so
always await `getServiceVersion() != null` before re-applying configuration
(`ScannerManager.softReinit` does this).

## 3. Vendor app internals (`com.xcheng.scannere3`)

Decompiled sources in the archive under `scannere3_src/sources/com/xcheng/scannere3/`.

- **`ScanTestReceiver`** — the key discovery. The system broadcasts
  `ACTION_STOP_SERVICE_AUTO` (extra `cameraid`) **whenever ANY app opens a
  camera**, and the vendor responds by releasing camera 2; a matching START
  arrives when the camera closes. This is the ISP arbitration. ⚠️ Corollary:
  **never force-resume the scan engine from the background** — you are
  fighting this mechanism and will wedge the camera for the other app (this
  is exactly how we once broke WhatsApp's camera). All our watchdog recovery
  is foreground-gated for this reason.
- **`ScanAccService`** — an AccessibilityService that implements the vendor's
  global trigger handling: on trigger key-down it resumes the engine and
  calls `doDecode` after ~300 ms. This is why a trigger press decodes even
  when no SDK client is running.
- **`E3Util`** — power state machine. Releases the scan camera on
  `SCREEN_OFF` / low battery (state `SUSPENDING`) and can **strand it there**
  with no resume event. Binder and license stay healthy, so a liveness probe
  won't notice — you must check the suspend state explicitly.
- **Vendor's own output**: when the vendor decodes on its own (our app not
  foreground), it types the barcode into the focused window as injected key
  events + Enter (keyboard wedge) and posts a notification. If barcodes ever
  "type themselves" into random fields — that's this path.
- Config from `scannere3_res/resources/res/values/integers.xml`:
  trigger keycodes **left = 140 (F10), front = 141 (F11), right = 142 (F12)**.

## 4. Trigger signal paths (how a press reaches our app)

Two independent signals per press; `ScannerManager.onScanTriggerPressed`
dedups them within 150 ms:

1. **KeyEvents** F8–F11 (keycodes 138–141) from input device `xctech-key`
   (scanCode 68 → F10/140, 87 → F11/141), delivered to the **focused**
   activity → `MainActivity.dispatchKeyEvent`. Note: the right trigger (142 =
   F12) is NOT in this range and only arrives via path 2.
2. **System broadcast** `com.xcheng.scanner.action.OPEN_SCAN_BROADCAST`
   (extra `scankey` = keycode) — sent by the framework on trigger key-down,
   arrives **even without key focus** (mid-wake, behind keyguard). It is a
   **protected broadcast**: `adb shell am broadcast` gets a
   `SecurityException` (uid 2000 may not send it). The framework emits it
   even for `adb shell input keyevent 141` while interactive — that's the
   way to simulate a press.

Scans are fired ONLY from these signals — never from `SCREEN_ON` (power
button / charger / gestures must not beam).

## 5. Sleep & wake behavior (all verified on device)

- `persist.sys.xcheng.anykey.wakeup=1` — any key wakes the device
  (`Generic.kl` has no WAKE flags; the policy does it).
- **The wake press is consumed entirely by the system**: no KeyEvent, no
  broadcast, to anyone — not even to the vendor app. Nothing in userspace
  sees which key woke the device… except one log line:

  ```
  D wakeup  : ----deal anykeyWakeup keyCode = 141
  ```

  Written **only for key wakes**, with the exact keycode. Power button,
  charger and gestures write **no** `anykeyWakeup` line. This is the entire
  basis of one-press-from-sleep (`ScannerManager.scanIfWokenByTriggerKey`
  reads it via `logcat -t` — needs `READ_LOGS`, see §7).

- `PowerManagerService: Waking up from Asleep` reasons by source:

  | Wake source | reason | details |
  |---|---|---|
  | Any key (incl. triggers) | `WAKE_REASON_UNKNOWN` | `wakeUp` |
  | Power button | `WAKE_REASON_POWER_BUTTON` | `android.policy:POWER` |
  | Charger plug | `WAKE_REASON_PLUGGED_IN` | `android.server.power:PLUGGED:true` |

  The reason alone canNOT distinguish a trigger wake from other key wakes —
  only the `anykeyWakeup` keycode can.

- **Double-press from sleep**: press 1 wakes (consumed), press 2 is delivered
  ~1.2 s later once interactive. If our app isn't up yet, the **vendor**
  handles press 2 (decodes + keyboard-wedges the result — §3). The device
  has `double_tap_to_wake=1` and `wake_gesture_enabled=1` in secure settings,
  but screen gestures produce no `anykeyWakeup` line and no scan-key events —
  what looked like "tap-to-wake scans" during testing was actually double
  trigger presses.

## 6. Failure modes → recovery (as implemented)

| Failure | Symptom | Detection | Recovery |
|---|---|---|---|
| Stale AIDL binder (§2) | Press does nothing; re-init no-ops | probe: version==null && license unreachable → DEAD | `forceReinitialize` (minimal reset), await binder, re-configure |
| Vendor "bad process" | Rebind refused ("process is bad") | soft re-init fails | Rate-limited foreground **flash of the vendor activity** (clears the bad mark) + `moveTaskToFront` back (`REORDER_TASKS`; plain startActivity is blocked by BAL) with verified retries |
| Stranded camera suspend (§3) | Engine "alive" but blind | `isScanServiceSuspending` while no camera screen of ours is open | Foreground-gated force-resume (watchdog tick + `onAppResumed` pre-warm) |
| License drop after ISP wedge | Binder answers, license `Inactive` | probe → SUSPECT streak | Nudge `activateLicense`, then escalate to recover() |

Probe semantics: `ACTIVATING` / `NETWORK_ISSUE` with a live binder = **ALIVE**
(offline warehouse must not trigger recovery churn). All recovery is
single-flighted (`recoverMutex`) and foreground-gated (`appInForeground`,
default **false** — a headless start must never assume foreground).

## 7. Provisioning & operational notes

- **One-press-from-sleep needs `READ_LOGS`** (development permission). Per
  device, once:
  `adb shell pm grant com.xelth.eckwms_movfast android.permission.READ_LOGS`
  Otherwise the user gets Android's one-time "allow access to all device
  logs" dialog; if declined, the feature silently degrades to
  wake-without-scan (second press scans). Gated by the `AutoScanOnWake`
  setting (also controls show-over-lockscreen).
- Our app forces SDK output `NONE` (SDK-callback only) — if keyboard-wedge
  output appears, the vendor is decoding on its own path (§3).

## 8. Diagnostic cookbook (adb)

```bash
ADB=/c/Users/Dmytro/AppData/Local/Android/Sdk/platform-tools/adb.exe

# Who owns which camera right now (scan engine = camera 2):
$ADB shell dumpsys media.camera | grep -A5 "Device 2"

# Wake history (reason per wake):
$ADB shell "logcat -d | grep 'Waking up from'"
# Which KEY woke the device (only present for key wakes):
$ADB shell "logcat -d -s wakeup:V"

# Bigger log buffer for long experiments (default drowns in minutes):
$ADB shell logcat -G 16M

# Simulate a trigger press (ONLY while awake/interactive — framework also
# emits OPEN_SCAN_BROADCAST for it):
$ADB shell input keyevent 141
```

**Pitfalls learned the hard way:**

- ⚠️ **Never inject key events while the device sleeps** — the event waits
  5 s for a focused window during the slow wake and ANRs the foreground app
  ("Input dispatching timed out: Application does not have a focused
  window"). Use physical presses for sleep tests.
- `am broadcast` cannot send `OPEN_SCAN_BROADCAST` (protected) — don't waste
  time on it; inject the keyevent instead.
- Streamed `adb logcat` over flaky USB dies silently — prefer `-G 16M` +
  `logcat -d` after the experiment.
- A reboot clears an ISP wedge; `dumpsys media.camera` proves coexistence
  (scannere3 must release camera 2 within ~1 s of another app opening
  camera 0, and re-acquire after it closes).

## 9. Artifacts

Local archive `C:\Users\Dmytro\vendor-re\xcheng-ranger2\` (107 MB, NOT in git):

| Path | Contents |
|---|---|
| `scannere3.apk` | Vendor scan app pulled from the device |
| `scannere3_src/` | Full jadx decompile (sources; its `resources/` came out empty) |
| `scannere3_res/` | Second jadx run `--no-src` — the resources (incl. `integers.xml` with the trigger keycodes) |
| `sdk-aar-exploded/` | `xcscanner_qrcode_v1.3.56.1.7.aar` unzipped: `classes.jar`, `aidl/`, `cls/` (javap dumps of the obfuscated statics) |
| `sdk-native-libs/` | `libXCScanner.so`, `libcortexdecoder.so` |
| `scannere3_pkg.txt` | `dumpsys package com.xcheng.scannere3` (receivers, actions, permissions) |

To regenerate from scratch (jadx 1.5.5):

```bash
$ADB shell pm path com.xcheng.scannere3      # → /system_ext/priv-app/.../scannere3.apk
$ADB pull <that path> scannere3.apk
jadx -d scannere3_src scannere3.apk          # sources (resources may be empty)
jadx --no-src -d scannere3_res scannere3.apk # resources
unzip xcscanner_qrcode_v1.3.56.1.7.aar -d sdk-aar-exploded
javap -p -classpath sdk-aar-exploded/classes.jar com.xcheng.scanner.XcBarcodeScanner
```

Key files to read first: `ScanTestReceiver.java` (camera arbitration),
`ScanAccService.java` (global trigger path), `E3Util` (power state machine),
`integers.xml` (keycodes).
