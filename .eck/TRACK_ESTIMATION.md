# Track Estimation — least-action smoothing + road-constrained matching (on-device)

> Owner design session 2026-07-06 (after the Eschborn→Karlsruhe→Speyer field day).
> Status: **S1–S4 BUILT + FLASHED same night.** TrackEstimator.kt (RTS+gate, golden
> tests vs the Python prototype), VectorTileDecoder.kt (dependency-free MVT 2.1),
> RoadMatcher.kt (Viterbi snap, synthetic-geometry tests), RoadTileProvider.kt
> (OpenFreeMap z14 corridor tiles, disk-cached), wired into estimateCurrentOdometer /
> odometerCheckpoint / finalize (estimated end odometer) / buildUploadJson
> (smoothed_* on every non-private upload, matched_* on final upload). Server stores
> the derived fields; dashboard draws matched > smoothed > filtered-raw.
> LIVE E2E on the real Karlsruhe→Speyer track: 57.3 km smoothed → **56.9 km road**,
> matched share 1.00, 64 corridor tiles (~2 MB once, then offline), 888 ms total.
> Deviation from spec: Tier S is an on-demand full pass cached by point count (same
> math, simpler than incremental state); live-share still sends raw fused fixes
> (they are already precise). Tests: `TrackEstimatorTest`, `RoadMatcherTest`,
> `VectorTileDecoderTest`, `RoadMatchIntegrationTest` (live tiles, ROAD_MATCH_IT=1).
>
> DEPLOYED 2026-07-07 night: movFast `c619c2f` flashed to Ranger2; 9eck `4d42595`
> live on eck1/eck2/9eck (OVH) + eck3 (Netcup), kiosk OTA channel staged
> (`.version=4d42595`, self-update ≤16 min). Commits: movFast `f801f3a` (armed-start
> rework + MY_PACKAGE_REPLACED fix) → `d3d0e75` (this spec) → `c619c2f` (S1–S4);
> 9eck `5a6fae5` (expense kinds) → `4d42595` (derived layers + tower distrust +
> dashboard). First real-world validation = the next actual drive: expect a smooth
> road-hugging line + " · N km road" in the trip popup on any node's dashboard.
>
> Follow-ups (deliberately open): matched_km into the Fahrtenbuch export/seal
> (canonical v4 decision pending — derived layers are NOT sealed today); per-tower
> learned offset/σ table from the charging (cell,fused,gps) triples (9eck ROADMAP
> "Trip track calibration model", incl. the CC BY-SA rule: never train on
> OpenCelliD-resolved coords); tile-cache size cap/LRU (OS clears cacheDir under
> pressure — fine for now); blind-recording watchdog still open in TECH_DEBT.

## 1. Problem (measured, 2026-07-06 tracks)

- The drawn track is chaos: the dashboard draws a LineString through ALL located
  points — fused GPS interleaved with cell-tower resolutions (median error 0.7–0.9 km,
  p90 ~2.6 km vs the fused truth). Raw mixed-track "length": **1072 km** for a 150 km
  trip.
- Tower junk: 4 of 169 towers resolved 19–163 km off-route (one → Kassel). All four
  have OpenCelliD `samples=1` (single crowdsourced observation). Not radio reality —
  bad DB rows.
- Distance error: naive chord-summing over fused points gave 181 km for a ~150 km road
  trip (jitter inflation); on another calibration trip the server filter UNDER-counted
  (149 vs odometer 174). Neither is the road distance.
- GPS IS recorded: fused every ~30 s on both trips (189 + 104 pts; accuracy 2 m with a
  real GPS lock, ~200 m as network-fix). The clean line already exists in the data.

## 2. Owner's framing (the core idea)

This is NOT a neural-net problem and NOT a gigabyte-map problem. It is **constrained
interpolation minimizing the action integral** (Wirkungsintegral):

- minimize Σ (observation residual² / σ²) + ∫|a|² dt   (physics: least action,
  a car does not teleport or produce impossible accelerations),
- subject to the boundary condition: **we move along a road, continuously, without
  jumping to another road**.

Both parts have closed-form / classical solutions — zero training, zero model weights:

| Physics requirement | Classical algorithm | Cost |
|---|---|---|
| least-action trajectory through noisy points | Kalman filter + RTS smoother (constant-velocity state) | O(n), one forward + one backward pass |
| "that reception was impossible" rejection | innovation gating (Mahalanobis² > threshold → drop) | free, inside the filter |
| "on a road, no jumps" constraint | Viterbi over local road-graph candidates (discrete least action; HMM map matching, Newson & Krumm 2009) | O(n·k²), k≈10–20 candidates |

Prototype (Python, real data): Eschborn→KA smoothed to **154.4 km** (road ≈150), gate
auto-rejected exactly the 4 junk towers **by physics alone** (m²=5k–28k), max step 2 km.
KA→Speyer: 57.3 km, max step 860 m. **55 ms per 400-pt trip in interpreted Python** →
~10 ms in Kotlin. Battery cost ≈ zero.

## 3. Two-tier compute model (owner directive)

### Tier S — streaming, approximate, continuous
A forward-only Kalman filter kept incrementally in `TripRecordingService` memory,
updated per incoming point (O(1) per point, microseconds). Maintains at all times:

- current best position + velocity (smoothed, gated),
- **running track-km** = integral of smoothed displacement (replaces the naive
  haversine chord sum),
- **live odometer estimate** = start reading + running km.

Consumers (all exist today and currently eat raw chord sums):
- Km hex live "≈" estimate (`TripManager.estimateCurrentOdometer` — replace),
- 🧾 Expense prefill ("вычислить пробег для чека" — odometer estimate at receipt time),
- `plausibleOdoStop` validation band (currently raw track km),
- live dashboard marker (send the smoothed position instead of the raw fix),
- blind-recording watchdog signal (filter starving = no usable observations → the
  0-point-trip alarm, see TECH_DEBT).

### Tier F — final, exact, once
Full RTS backward smoothing (+ gate) over the whole point set. **Triggers** (owner):
1. **trip end** (finalizeAndStop) — the definitive polyline + km before upload/seal;
2. **expense save needs a mileage** for the receipt and no odometer photo was taken —
   run Tier F over points-so-far for the best available number (source stays
   "estimated", odometer photos remain authoritative);
3. **stop without an odometer photo** — graceful stop / tentative-end arming: compute
   the best end-km estimate at the stop moment;
4. (cheap enough to also run at each 5-min checkpoint upload if we want the server
   trace to be pre-smoothed — optional, revisit.)

Tier F output rides in the upload JSON as a DERIVED layer: `smoothed_polyline`
(or per-point smoothed lat/lng), `smoothed_km`, `estimation_version`. Raw points stay
untouched — they are the GoBD evidence; derived layers are versioned and recomputable.

## 4. Phase 2 — road constraint (Viterbi snap), still on-device

- Road geometry source: **the vector tiles the phone already has** — MapLibre offline
  cache (`mbgl-offline.db`, ~52 MB on the Ranger2) holds the `transportation` layer.
  Needed: corridor tiles (z14) along the smoothed track — ~60 tiles × 30–80 KB per
  150 km trip, mostly already cached; fetch the missing ones (few MB, WiFi-deferrable).
  NO country-wide graph, no Valhalla/OSRM server (rejected: gigabyte-class, off-device).
- Candidates: road segments within ~300 m of the smoothed path; emission cost =
  perpendicular distance²/σ²; transition cost = along-graph continuity (connected
  segments cheap, road switches penalized, U-turn/teleport forbidden).
- Output: snapped polyline + **road distance** `matched_km` (closes the chord-vs-road
  gap for good; under/over-count both die), plus "which named road" per stretch (nice
  for the Fahrtenbuch narrative later).
- Open questions: MVT decode straight from the mbgl cache (sqlite + gzip + MVT proto —
  doable, needs a small decoder) vs fetching corridor tiles from the style source
  independently; tunnels/parking garages (no fix → filter coasts on dynamics — fine);
  zoom level with complete road coverage (z14 should carry all drivable classes in
  OpenMapTiles schema — verify our style's source).

## 5. Server/dashboard quick wins (independent of the above, 9eck repo)

- `cell_resolver`: store `samples` in the `cell_tower` cache; towers with `samples=1`
  get a huge accuracy (or "untrusted") so the existing jitter/bounce filters and the
  device σ both treat them honestly.
- `DashboardMap.svelte`: until smoothed polylines arrive — draw fused/gps points first,
  use cell points only to bridge gaps >2 min, speed-gate any point demanding >250 km/h
  to both neighbours. (Today it draws everything, hence the chaos.)
- When `smoothed_polyline`/`matched_km` appear in uploads: draw those, keep raw as a
  debug toggle.

## 6. Learned layer — a lookup table, not a network (future, optional)

Per-tower correction (position offset + real σ) learned from our own
(cell, fused, gps) triples collected while charging (shipping since `540c66f`).
Plugs into the SAME framework as a better observation σ for cell points — no
architecture change. Central training + guardrails already specced in
`9eck.com/.eck/ROADMAP.md` ("Trip track calibration model (central)"): DSGVO
(owner-vehicle/opt-in only) + CC BY-SA (train ONLY on our own observation pairs,
never on OpenCelliD-resolved coordinates). A transformer/seq2seq over road segments
is explicitly rejected: data-hungry, unnecessary — Viterbi already does that inference.

## 7. Implementation slices (when green-lit)

- **S1** Tier F smoother+gate in Kotlin (`trips/TrackEstimator.kt`, pure function,
  unit-tested against the Python prototype numbers) + wire into finalize + upload JSON
  fields + server passthrough (`trips.rs` stores derived fields; no seal change).
- **S2** Tier S incremental filter in the service; replace `estimateCurrentOdometer`,
  Expense prefill, `plausibleOdoStop` input, live-share position.
- **S3** Dashboard: draw smoothed polyline when present; quick-win filters for legacy
  trips.
- **S4** Viterbi road snap (corridor MVT) + `matched_km`; then the fuel/expense and
  Fahrtenbuch km chain prefers matched > smoothed > odometer-interpolated, with
  odometer PHOTOS always overriding (legal truth).
- Somewhere in S1: kill the OpenCelliD-resolve of `samples=1` towers feeding junk into
  σ (server quick win rides along).

## 8. Non-goals

- No continuous CPU burn: Tier S is O(1)/point at 30 s cadence; Tier F runs on the
  listed triggers only. Target ≪1% CPU, measured target ~10 ms per trip-end on the
  Ranger2.
- No on-device training, no downloads beyond corridor tiles.
- No replacement of odometer photos as the legal mileage source — estimation fills
  gaps and validates, photos win.
