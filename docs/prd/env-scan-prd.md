# PRD — Environment Scan (Standalone AR Flow)

**Status:** CANONICAL — this document supersedes every prior spec/plan for the
environment scanner. When an older doc (specs, plans, punchlists) contradicts
this PRD, this PRD wins. Update this file first; code second.

**Owner:** Blake (product). Last canonized 2026-07-12 from the field-test
rounds through TestFlight 149.

**Why this exists:** the scanner has been through 100+ iterations with heavy
feature churn. This PRD freezes what the flow IS, and — just as important —
what it is NOT anymore, so deprecated concepts stop leaking back in. The
standalone env scan is the app's most important flow and the foundation the
block-assignment flow builds on (see `block-assignment-2-lap-prd.md`).

---

## 1. Core flow (the four pillars)

### P1 — Open scanner: camera + live minimap
- Opening the environment scanner shows the AR camera feed full-screen with a
  minimap (bottom-end) over real map tiles (OpenFreeMap Liberty light style).
- The minimap tracks the user's location **smoothly**: center animates via
  meter-offsets from a session anchor (never animate absolute lat/lng through
  Compose — Float quantization causes half-meter shake), no-bounce spring so
  retargets don't kink.
- The user dot is a single centered dot (white ring + green core over a dark
  pad). A **pronounced heading cone** (radial-gradient fill, heavy edge)
  rotates with the compass via shortest-path unwrap.
- Screen stays awake for the whole session (`isIdleTimerDisabled`).

### P2 — Ground grid: AR ↔ map registration
- A neon-green emissive grid renders on detected ground (sidewalks, lots,
  streets): 1 m minor / 3 m major lattice, where 3 m = the coverage cell size,
  so grid tiles ARE map cells.
- The grid is phase-locked to the coverage lattice (`CoverageTracker`
  lattice origin, re-derived on every geo-ref re-pair) — the AR grid, the
  minimap coverage paint, and the server cells must all agree on one lattice.
- The grid is depth-occluded (LiDAR scene understanding) so it never glows
  through fences/walls; emissive so it reads in daylight.

### P3 — Litter identification: reticles, labels, progress
- **Detection surface:** pointing the camera at litter should raise an AR
  reticle over the item with (a) a label (type or "Unknown litter"),
  (b) user instruction (e.g. "move closer to identify"), and (c) visible
  scan progress, ending in a success animation when the item is captured.
- **Multiple concurrent reticles** are required: several items in frame each
  get their own reticle and their own progress/completion state.
- **Tap authority (Option A, binding):** a screen tap ALWAYS opens a
  provisional candidate if it raycasts — reticle + bottom confirm/reject
  pill — even when the classifier names nothing ("Unknown litter"). Nothing
  persists without explicit Confirm. A raycast miss is never silent
  ("Hold steady and tap the item again").
- **Provenance (binding):** incident reports file ONLY on user-confirmed
  litter (`user_confirmed`). Auto-detections record as `auto_detected`
  observations; rejects record as negative examples and never paint the map.
- **Ranges:** auto-promotion and tap-identify share the 4 m envelope;
  landmarks (tree/bush/fence/pole/…) register silently out to 12 m and never
  read as trash in UI (no toasts, no trash glyphs, counted only as
  "features mapped").
- Confirmed items drop a persistent AR marker disc and increment the live
  session HUD (incident count + summed estimated weight, top-of-screen chip).
- **Known gap (accepted, next up):** the current whole-frame scene classifier
  cannot label small litter at distance; reticles at range require a
  per-object detector. Until then, tap-to-confirm is the guaranteed path.

### P4 — Live minimap data + world model
- As the user moves, the **scanned-area blob grows in real time** on the
  minimap: merged marching-squares outlines (no graph-paper cells), tiers
  SEEN (faint) → GRAZED (light) → CLOSE (vivid), punched out of the
  fog-of-war veil (unscanned world stays veiled — the veil is a KEPT feature;
  do not confuse it with the deprecated in-camera AR fog).
- Litter appears on the minimap live: per-type glyphs at item positions plus
  merged orange **litter-pattern zones** (item positions quantized to the
  10 m grid, marching-squares traced) so clusters read as shapes.
- Session data flows into the shared world model: coverage cells (with
  quality tiers) to the coverage ledger, litter/landmarks to
  `world_observations` — and from there to the main map for every user
  (fresh/stale/problem-area layers).
- Coverage credit is measured (raycast-fan hits), never assumed frustum.

## 2. Session integrity

- **Outdoor gate:** deny-by-default tri-state (OUTDOOR/UNCERTAIN/INDOOR).
  Capture, credit, and persistence only while OUTDOOR. Signals: GPS accuracy,
  camera scene votes, ceiling-plane detection, mapped-building containment.
  **The user's explicit "I'm outside" pins the session OUTDOOR; only a
  detected ceiling plane may clear the pin — map containment may not**
  (footprint data is provably wrong in the field; 2012 import incident).
  INDOOR flips purge the trailing 10 s (privacy) — purge applies to captures,
  path, credit; it does not kill the session.
- **Capture pipeline:** sessions record video/depth/pose chunks + a manifest
  (path, gate events, geo-ref events, observed items, diagnostics tail) to R2
  via presigned PUTs; manifests upload at completion, chunks by policy
  (Wi-Fi/charging/opt-in). GeoRef events pair AR and GPS clocks/frames per the
  splat-registration contract (`docs/governance/scan-splat-registration-contract.md`)
  for the DGX reconstruction pipeline.
- **Quality:** an informational quality badge (tracking + GPS + detection
  confidence). It never gates capture or Complete Scan.
- **Completion:** Complete Scan is always tappable; clean (no-litter) sessions
  archive without filing a report; the summary card shows the swept blob,
  stats, and grade (absolute-area effort scoring).

## 3. Deprecated — never reintroduce

| Feature | Status |
|---|---|
| In-camera **AR fog** (fog cells over unscanned ground, route or env) | REMOVED — "we no longer do anything with fog". The minimap veil is a different, kept feature. |
| Neon **coverage quads** in AR | REMOVED (in-camera coverage feedback = the ground grid only) |
| **Green guidance bar/arrow** (camera-anchored box) | REMOVED (replaced by minimap guidance + pooled AR beacon ring ≤15 m) |
| **Spark bursts** on landmark registration | REMOVED (landmarks register silently) |
| Landmark **white squares** on the minimap | REMOVED (landmarks never render on the minimap) |
| **"Is it still there?"** per-tick proximity spam | REPLACED by `NearbyDirtyPromptGate` (one card, cooldowns, cluster-resolve) |
| Frustum-swept coverage credit | REPLACED by measured raycast-fan credit |
| `capturedFrames` per-frame upload pipeline (`AreaCaptureFrameUploader`) | REMOVED (superseded by capture chunks) |

## 4. Rebuild doctrine

Blake, 2026-07-12: for highly convoluted subsystems there is **no shame in a
parallel rebuild** — stand up a new implementation next to the old one,
parameter by parameter, verifying each of P1→P4 with a test per step, then
cut over and delete the old path. Efficiency and testability beat preserving
accumulated context. Any rebuild must satisfy this PRD exactly; anything the
PRD doesn't require is a candidate for omission.

## 5. Open items (wanted, not yet built)

- Per-object litter detector (replaces whole-frame classification as the
  auto-reticle source; enables P3 reticles at range).
- Reticle progress/success animation states (P3) beyond the current
  pill + lock sparkle.
- VIO-fused minimap dot (AR-odometry-smoothed position between GPS fixes).
- Grid-tint-by-coverage (in-camera "where have I scanned" via grid tint —
  the sanctioned replacement for the deleted fog/quads, if ever needed).
