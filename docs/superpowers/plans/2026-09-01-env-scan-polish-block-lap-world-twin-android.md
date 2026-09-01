# Env-Scan Polish + Block 2-Lap → World Twin + Android Bring-Up — Master Plan

> **For agentic workers:** this is a PROGRAM plan (multiple subsystems). Execute
> one phase-workstream at a time; the code-heavy workstreams (1.3, 2.1–2.3, 4.T2)
> each get their own detailed task plan (superpowers:writing-plans) at execution
> time, argued from this document plus the canonical PRDs. Steps use checkbox
> syntax for tracking.

**Date:** 2026-09-01 · **Owner:** Blake (product) · **Author:** Claude (bg session, 4-agent system survey)

**Goal:** (a) polish the iOS environment scanner to the RadianceFields-VPS look
(stable ground grid, radial scan-start reveal, ground-arrow navigation), (b) make
the block-assignment 2-lap flow real end-to-end — AR in both laps, trash
registered to the map layer with exclusion zones active, and every completed scan
becoming a visual world recreation in World Twin — and (c) have the Android build
ready when the Samsung Galaxy arrives.

**Architecture:** the scan→twin lane already exists and is LIVE:
`iOS capture → R2 raw/{cell}/{session} → Ktor ScanIndexer → reconstruction_jobs
(Neon) → gcc-recon-worker.timer (Spark) → fuse_cell/gsplat → reconstruction_assets
+ R2 reconstructed/ → cdx-world-twin :7047 /api/scans/manifest?route_to=twin-v4 →
chicago-twin viewer-v4 :7099`. This plan unblocks it (privacy scrub is down),
polishes the app on top of it, and instruments it — it does NOT build a new lane.

**Tech stack:** Compose Multiplatform (KMP) + RealityKit/ARKit (iosApp Swift) +
Next.js `site/` API (canonical mobile backend) + Ktor `backend/` scan
control-plane + Python recon workers (gsplat 1.5.3, GB10) + cdx-world-twin
(FastAPI, ms-a2) + viewer-v4 (Three.js, Spark).

**Specs this plan argues from:**
- `docs/prd/env-scan-prd.md` (CANONICAL)
- `docs/prd/block-assignment-2-lap-prd.md` (CANONICAL)
- `docs/plans/2026-08-19-scan-to-world-pipeline-and-ios-release-plan.md` (freshest recon verdicts + work order)
- `docs/governance/scan-splat-registration-contract.md`, `docs/governance/scan-and-coverage-model.md`
- `docs/superpowers/plans/2026-07-03-ar-scan-android-parity.md` (Android AR port, never executed)
- `docs/superpowers/specs/2026-07-10-world-model-layer-design.md` (world-model API seam)
- `~/cdx-platform/_shared/registry/workflow_catalog.yaml` → `app.environment_scan_to_twin` (six hops, `status: designed`)
- X-research shortlist: `~/cdx-platform/docs/research/2026-08-28-x-bookmarks/kb-docs/radiance-vps-gcc-video.md` (+ `3d-spatial-reconstruction.md`, `04-spatial-3d.md`)

---

## Global constraints (binding — every task inherits these)

- **PRDs win over older specs.** Deprecated-feature tables in both PRDs are
  hard bans (no AR fog, no coverage quads, no camera-anchored guidance bar).
- **One AR stack.** The block-lap AR experience IS the env scanner
  parameterized (foundation principle). Nothing in this plan may fork it.
- **ARKit poses are the canonical geometry backbone** (2026-08-19 verdict:
  scale 0.973, 3.4 m median GPS residual). VidMap is external-footage-only
  (Ray-Ban / third-party). Do not re-litigate.
- **Cell-id arithmetic is byte-exact** between `shared/.../geo/GridCell.kt`,
  `site/src/lib/world-observations.ts`, and `cdx-world-twin gcc_assets.py`
  (fixed 111000/82300 m-per-deg, NO cos(lat) correction). Never "fix" it.
- **viewer-v4 consumes `ply`/`splat`/`ksplat`, not `spz`; `file_url` must be a
  base-relative path** (raw string concat in viewer-v4/index.html ~4602).
- **GPU jobs strictly serial** (`gpu_admission.GpuReservation`); never restart
  `comfyui.service` or recon under a non-empty queue.
- **Host placement:** GPU stages (gsplat, WorldMirror, frame detection) on
  Spark; app/data-plane (Ktor control-plane, Next.js, cdx-world-twin, fusion
  candidates) on ms-a2. Android APK assembly on ms-a2 (x86-64 — Spark has no
  ARM64 `aapt2`); iOS builds on the MacBook via `cdx-mobile`.
- **Privacy:** `rayban_wwgo3126_douglass_park` bundles stay quarantined;
  `~/gcc-scan-imports/` is raw private capture — never commit/publish; the
  indoor-purge and scrub gates are non-negotiable.
- **Schema rule:** any `site/src/db/schema.ts` change updates `Tables.kt`
  mirror + shared `@Serializable` DTOs in the same PR. Deploy via
  `npm run db:push` (drizzle-kit generate is untrusted — see repo CLAUDE.md).
- **Every mobile `expect` added must land with BOTH `actual`s** (iOS real,
  Android at minimum a compiling stub) so `:composeApp:compileDebugKotlinAndroid`
  stays green — Android bring-up (Phase 4) depends on this.

---

## Phase 0 — Unblock & stabilize (no app changes; do this first)

Nothing new reconstructs until 0.1 lands: `recon_worker`'s
`REQUIRE_SCRUBBED_VIDEO=1` defers every video-bearing session until scrub
renditions exist, and the scrub service has failed continuously since
2026-08-10 (~720 failures/wk).

### Task 0.1 — Fix `gcc-privacy-scrub.service`
**Files:** `scripts/privacy_scrub/` (runner), unit `~/.config/systemd/user/gcc-privacy-scrub.{service,timer}`
- [ ] Reproduce: `journalctl --user -u gcc-privacy-scrub -n 50`. Known error:
  ultralytics weight auto-download raises `FileExistsError: /home/codex450/models/specialty`
  (the path became a symlink to the CodexSSD4 cold archive on 2026-08-13 —
  a second, separate breakage on top of the original 08-10 failure).
- [ ] Fix by **pointing ultralytics at a real, writable weights dir** (e.g.
  `YOLO_CONFIG_DIR`/explicit weight path into `~/models/` proper or a
  scrub-local dir) — do NOT delete or retarget the `models/specialty` symlink
  (central model repo; other consumers may resolve through it).
- [ ] Diagnose the ORIGINAL 08-10 failure beneath it once weights load; fix.
- [ ] Verify: run the service once by hand; confirm scrub renditions appear for
  a queued session; confirm `systemctl --user status` stays green over 3 timer
  fires. While broken, consider `systemctl --user stop gcc-privacy-scrub.timer`
  to stop the failure loop (restart-loop hygiene).

### Task 0.2 — Prove the lane end-to-end on real data
- [ ] Pick 1–2 of the 26 R2 sessions passing the quality bar (≥20 s, ≥10 GPS
  fixes, .mov present; 158 total in `gcc-scan-storage/raw/`).
- [ ] Drain: `recon_worker.py --drain` (via the timer or by hand, GPU-serial).
- [ ] Verify each hop with receipts: `reconstruction_jobs` → `done`;
  `reconstruction_assets` row (`processing_status`, `registration_residual_m`);
  `reconstructed/{cell}/{v}/splat.ply` in R2;
  `curl 'http://127.0.0.1:7047/api/scans/manifest?route_to=twin-v4'` contains the
  cell; open viewer-v4 `:7099` and see it render at the right anchor.
- [ ] Record wall-clock per hop — this becomes the baseline for Task 3.1 tracing.

### Task 0.3 — WorldMirror A/B on an iOS-sourced cell (work order #1b, unblocked)
- [ ] `systemctl --user start cdx-world-mirror` (`:7027`, installed + inactive;
  weights present at `~/models/hunyuan/HunyuanWorld-2.0/HY-WorldMirror-2.0/`).
- [ ] Run one cell both ways (`SPLAT_ENGINE=gsplat` vs `world-mirror` with ARKit
  `prior_cam_path`), eyeball + `verified_quality` compare.
- [ ] **Decision gate for Blake:** keep gsplat default, or promote WorldMirror
  for exteriors. Stop WorldMirror after (it does not auto-stop; GPU seat).

### Task 0.4 — Doc hygiene (stale canon breeds rework)
- [ ] `docs/PRD_environment-scan-vidmap-reconstruction.md`: add SUPERSEDED
  banner → Aug-19 §3.1 verdict (done in this branch alongside this plan).
- [ ] Fix wrong status headers: `2026-07-09-env-scan-minimap-and-reach-design.md`
  ("NOT approved" but shipped), `2026-07-01-unified-scan-ecosystem…`,
  `2026-07-02-scan-guidance-world-model…` (both "pending approval" but shipped),
  and tick the shipped checkboxes in `2026-07-21-route-lap-tap-to-confirm-parity.md` (PR #210).
- [ ] Fix the stale KDoc in `composeApp/src/iosMain/.../ArRouteOverlay.kt`
  (claims SceneKit-not-RealityKit; RealityKit migration is done).

---

## Phase 1 — iOS AR polish (the RadianceFields look)

Blake's directives (2026-09-01, from x.com/RadianceFields/status/2093008579825414510):
grid expands outward on scan start; grid lines rock-solid on the ground;
ground arrows guide the user to any selected map target so navigation
passively harvests scan coverage.

### Task 1.1 — Ground-grid stability audit + fix ("lines don't move")
**Files:** `iosApp/iosApp/GccArNative.swift` (`PlaneGridEntity`, `snapToLattice`,
`setGridPhaseOrigin`), `composeApp/src/iosMain/.../ArEnvScan.kt`
- [ ] Instrument first: log grid-entity transform deltas per frame for 60 s of
  walking; classify swim sources — (a) plane-anchor re-estimation, (b) lattice
  re-phase on every geo-ref re-pair, (c) entity re-parenting.
- [ ] Fix candidates, in order: anchor grid tiles to persistent ARKit plane
  anchors (not camera-relative), damp/slew `setGridPhaseOrigin` updates on
  geo-ref re-pairs instead of snapping (re-pair is the known discontinuity per
  the splat-registration contract), and hysteresis on plane extent growth.
- [ ] Acceptance: grid visually static against pavement in a 60 s handheld walk
  video; lattice still byte-agrees with `CoverageTracker` cells (grid = cells
  is canon P2 — stability must not decouple the lattice).

### Task 1.2 — Radial scan-start reveal
**Files:** `GccArNative.swift` (`makeGridOpacityMask` already exists)
- [ ] Animate the grid opacity mask radius from 0 → full over ~1.2 s from the
  user's feet on session start (and on lap start), easing out; reuse the same
  mask path for the Task 1.5 coverage tint so it's one mechanism.
- [ ] No new AR stack, no fog — this is an opacity animation on the existing grid.

### Task 1.3 — Navigate mode: ground arrows to any map target  ← NEW FEATURE
**Files:** new `composeApp/src/commonMain/.../scan/NavigateMode.kt` (target
selection + bearing state), `GccArNative.swift` (arrow entities),
`MapFullScreen.kt` + `ScoutScreen.kt`/`SpotScreen.kt` (entry points),
`ArNativeSurface.kt` (+ Android stub in the same PR)
- [ ] PRD amendment first (Blake approves text): add "Navigate mode" to
  `env-scan-prd.md` — ground-projected chevron trail to a selected target
  (block assignment, hotspot to verify/clean, or free-explore landmark). Note
  explicitly this is NOT the deprecated camera-anchored green bar: arrows are
  world-anchored on detected ground, depth-occluded, same stack as the grid.
- [ ] Target sources: (a) tap a map feature/observation on `MapFullScreen` →
  "Guide me there"; (b) lap-2 problem areas (feeds Task 2.2); (c) claimed block
  corridor entry point.
- [ ] Render: chevron discs raycast-dropped to ground every ~2 m along the
  great-circle bearing (recompute on GPS fix; ≤15 m lookahead), pooled like
  reticles; terminal ring = existing guidance beacon (`setGuidanceTarget`).
- [ ] Passive harvest: Navigate mode runs the full scanner session (coverage
  credit, outdoor gate, capture per policy) — navigation IS a scan session;
  the session manifest gains `"context": "navigate"`.
- [ ] Detailed writing-plans pass required before implementation (new expect/actual surface).

### Task 1.4 — Reticle progress/success states (PRD §5 open item)
- [ ] Design + implement the P3 states beyond pill + lock sparkle: per-reticle
  radial progress, capture-success burst, "move closer" hint state. All within
  the existing pooled-reticle path (`setScanReticle(state:)`).

### Task 1.5 — Grid-tint-by-coverage (sanctioned in-camera coverage feedback)
- [ ] Tint grid cells by SEEN/GRAZED/CLOSE tier via the grid texture/mask —
  the PRD-sanctioned replacement for deleted fog/quads. Ship behind
  `/api/scan-config` remote flag; validate legibility in daylight.

### Task 1.6 — Litter classifier: field-validate what's now bundled
- [ ] The `.mlmodelc` shipped in `ca6cb97b` but was never field-tested.
  TestFlight cycle: verify auto-reticles fire on real litter, measure
  precision on the confirm/reject telemetry, keep tap-to-confirm authoritative.
- [ ] **Blake decision (blocking further training):** YOLO26 (AGPL) vs RF-DETR
  (Apache-2.0) for the on-device detector (Aug-19 work order #5).

### Task 1.7 — Capture coaching v2 (Aug-19 §6.1)
- [ ] Perimeter+lattice pattern guidance, live loop-closure prompt, turn
  prompts on long straights, HUD coverage mini-map — all computable from GPS +
  ARKit poses already on device. Improves recon quality (ARKit tracking), which
  is now the canonical geometry source.

**Phase 1 release vehicle:** iOS v1.0.9 TestFlight ("scan-quality +
identification + navigate" release), fastlane via `cdx-mobile build ios`.

---

## Phase 2 — Block 2-lap flow completion

The data path (hotspot → `world_observations` → map layer) is wired and
server-enforced. The gaps are AR presence and one schema hole.

### Task 2.1 — Lap 1 opens AR-first
**Files:** `composeApp/src/commonMain/.../ui/screens/ScoutScreen.kt` (map-first
today; AR behind a "Begin Route" button gated on `DeviceType.AR`)
- [ ] On entering SCOUTING with an AR-capable device, open straight into the AR
  scanner (same as env scan opens), map demoted to the minimap; keep the
  full-map screen reachable. Non-AR devices keep today's map flow.
- [ ] This closes the PRD foundation-principle violation ("the block-assignment
  AR experience IS the standalone environment scanner").

### Task 2.2 — Lap 2 gets AR guidance (PRD requires it; today CleanScreen has zero AR)
**Files:** `RouteScanScreen.kt:77-98` (branch only routes AR in SCOUTING),
`CleanScreen.kt`, `RouteScanViewModel.kt` (`clusterProblemAreas()` exists)
- [ ] Route `ScanPhase.CLEANING` into the AR surface with Navigate mode
  (Task 1.3) targeting problem areas largest-first: minimap target ring +
  ground chevrons + terminal beacon; after-photo capture flow unchanged.
- [ ] Fresh recorder identity per lap (`newLapSessionRecorder("clean")` already
  exists) — lap 2 uploads its own manifest as today.
- [ ] Corridor-aware fallback when problem areas are empty (PRD open item):
  steer along un-scouted corridor cells instead of a generic prompt.

### Task 2.3 — `route_scans.block_id` + exclusion zones
**Files:** `site/src/db/schema.ts:1566-1592` (+ migration via db:push),
`server`-mirror `RouteTables.kt`, shared DTO, hotspots handler
`site/src/app/api/scans/[id]/hotspots/route.ts`
- [ ] Add `block_id` FK to `route_scans`, populated at claim time from the
  block assignment; backfill where derivable from `route_id`.
- [ ] Remove the "exclusion-zone checks return false for route-scan
  observations until block linkage is added" carve-out — private-property
  exclusion zones must apply to block-lap trash registrations.
- [ ] Same-PR schema-mirror rule applies (Tables.kt + DTO).

### Task 2.4 — Parity + polish open items (PRD §6)
- [ ] `GroundCandidateTracker` drive loop on route laps (env-only today).
- [ ] Surface lap-2 hotspot verification score to the worker (recorded
  silently today).
- [ ] Payout status visibility after the completion report.
- [ ] Rejected route taps into the manifest rejected list (telemetry-only today).

**Acceptance for Phase 2 (the "working block 2 lap flow"):** one worker claims a
block, laps 1+2 fully in AR with arrows/beacons, hotspots registered with
exclusion zones active, both lap manifests upload, job completes with report +
payout state visible — and (given Phase 0) the block's scan reconstructs and
appears in World Twin without manual steps.

---

## Phase 3 — World recreations: instrument, expose, and enrich the lane

### Task 3.1 — Trace the six hops (`app.environment_scan_to_twin`)
**Files:** `backend/.../ScanIndexer.kt`, `scripts/reconstruction/recon_worker.py`,
`cdx-platform/services/cdx-world-twin/app/gcc_assets.py`,
`_shared/registry/workflow_catalog.yaml`
- [ ] First fix the catalog: hops 2–3 route through Media DAM, which the real
  pipeline does not (R2 + ScanIndexer). Rewrite the hops to match reality —
  do NOT invent a DAM ingest (DAM has no 3D/scan asset type; R2+Neon is the
  store of record).
- [ ] Emit the spans (`app.scan.captured` … `worldtwin.scene.served`) from the
  three touchpoints; "how long does a scan take" gets an answer.

### Task 3.2 — World-model API layer (retire the twin's direct-DB reach)
**Spec exists:** `docs/superpowers/specs/2026-07-10-world-model-layer-design.md`;
`gcc_assets.py`'s docstring reserves a URL mode for exactly this.
- [ ] Add `GET /api/admin/reconstruction-assets` (+ public read shape as the
  spec defines) to `site/` — today the endpoint is POST-only; nothing reads
  assets back.
- [ ] Switch cdx-world-twin `gcc_assets.py` to the API URL mode; keep direct-DB
  as fallback until burn-in.

### Task 3.3 — Civic deliverables on the twin
- [ ] Before/after cleaning overlays per cell (pairs already exist as bundles) —
  promote to twin overlays keyed by `activated_at`/version so the twin shows
  cleaning deltas over time (Aug-19 §5.3).
- [ ] Per-cell `litter_density` + `freshness` rasters from observations →
  Neon + versioned JSON in R2 (Aug-19 §5.1); render as a twin/GCC-map layer.
- [ ] Admin: embed viewer-v4 (`EMBED.md` pattern) on a `site/` admin page for
  scan-session review — today there is no 3D view in admin at all.

### Task 3.4 — Bookmark-derived upgrades (staged, GPU-serial, in this order)
1. [ ] **SAM3 track dedupe** (work order #10): track litter candidates across
   chunk frames → dedupe observations before twin writes
   (`~/cdx-platform/scripts/sam3_track.py`; SAM license = internal-only, fine
   for this server-side lane).
2. [ ] **Server-side open-vocab frame detection** (work order #8):
   Grounding DINO 1.0 (Apache) or LocateAnything-3B over extracted frames for
   classes the crop model misses (bulk dumping, graffiti); human-verify via
   existing admin flow before twin writes.
3. [ ] **Litter delta layer** (work order #9): scan-over-scan change detection
   per cell — start as raster diff on 3.3's density layers (SuperMap-style
   memory is the north star, raster diff is v1).
4. [ ] **CoMe splat→mesh** (clone `r4dl/CoMe`): confidence-based mesh
   extraction from trained cells → collision/game-usable geometry (the
   "Niantic ships a collision mesh" gap both research docs flag).
5. [ ] **Tiled splat training + stitch** (Train-Splitter pattern): overlapping
   sub-tiles per cell to cut VRAM and address the 2.2× tile-relief mismatch.
6. [ ] **depth-anything.cpp bench** (work order #2) — server-side depth for
   non-LiDAR frames (old devices, Ray-Ban).
7. [ ] **VidMap env build — ONLY for the Ray-Ban/external-footage lane**
   (NO-GO on iOS stands): build `~/opensource/vidmap/.venv` + ~9 GB
   checkpoints when Ray-Ban walks matter; `attach_vidmap_priors()` and
   `_export_vidmap_frames()` are already written and waiting.
- [ ] **VPS end-state (the full RadianceFields effect):** once ≥N adjacent
  cells hold approved splats, spike phone localization against the cell splat
  (research task; candidate court: on-device relocalization vs server VPS).
  The viewer pairing ("click map → jump into world, live tracking") lands on
  the twin page first — it's pure viewer-v4 work.

### Task 3.5 — Host rebalance (ms-a2 is available; use it)
- [ ] Move the Ktor scan control-plane container (`gcc-backend`, :8080) to
  ms-a2 per app-plane doctrine (deploy dir + registry housing + tunnel check —
  follow the five-place service-registry pattern; housing follows the unit).
- [ ] Evaluate moving `fuse_cell` CPU fusion to ms-a2 (pure NumPy/SciPy by
  design — Open3D segfaults on GB10 anyway); gsplat/WorldMirror/detection stay
  on Spark. Only worth it if Spark GPU contention actually bites — measure
  after 3.1 tracing, don't pre-optimize.

---

## Phase 4 — Android bring-up (Samsung Galaxy, arriving in days)

Reality check: ~81% of the app (all 46 screens, networking, models, offline
cache, maps) is shared KMP code that compiles for Android today; the AR scanner
is iOS-native Swift with explicit Android no-op stubs; no ARCore dependency
exists yet. Plan: usable app in days, AR scanner parity as its own project.

### Tier 0 — an APK waiting before the phone arrives (do NOW)
- [ ] `./gradlew :composeApp:compileDebugKotlinAndroid` on the DGX (pure
  Kotlin, no aapt2) — fix any drift; Android Kotlin hasn't compiled in months.
- [ ] Produce the APK off-DGX, either: fire the existing GitHub Actions
  `android-debug` job (`workflow_dispatch`, uploads `gcc-android-debug`
  artifact), or set up ms-a2 as the Android build host (x86-64: install
  cmdline-tools + SDK 36 + build-tools 35, then `:composeApp:assembleDebug`
  runs natively — no MacBook needed for Android).
- [ ] Phone day 1: Developer Options → USB debugging → `adb install -r`
  (USB-C to either host, or `adb pair`/`connect` over Wi-Fi; `adb` is
  aarch64-native on the Spark). Debug keystore already exists.

### Tier 1 — usable on the Galaxy (days → 2 weeks)
- [ ] **Fix the CAMERA runtime-permission bug** in
  `composeApp/src/androidMain/.../PermissionManager.android.kt` — never
  implemented; existing spot-capture `CameraPreview` binds with no check.
  Blocking for any capture.
- [ ] Implement `HapticFeedback` + `ScreenWake` actuals (trivial, very visible).
- [ ] FCM: Firebase project + `google-services.json` → `PushNotificationManager`.
- [ ] Bump Android `versionCode`/`versionName` onto the release line (stuck at
  2 / 1.0.1 vs iOS 1.1.0 b184).
- [ ] Visual QA pass: 65k lines of Compose never eyeballed on a real Android
  phone — insets/gesture-nav/theming (`Theme.Material.Light.NoActionBar` is a
  placeholder); replace with a proper theme.

### Tier 2 — AR scanner parity (the real project, 3–6 weeks)
- [ ] Execute `docs/superpowers/plans/2026-07-03-ar-scan-android-parity.md`
  (already task-by-task TDD): `com.google.ar:core` + SceneView
  (`io.github.sceneview:arsceneview`) + ML Kit object-detection/image-labeling
  + rotation-vector compass alignment (no Geospatial API / no GCP key).
  Re-verify dependency versions (plan is from July). `ArRouteScreen.kt` needs
  zero changes — the expect contracts hold.
- [ ] Task 1 of that plan first: extract `TrashDetectionRules` to commonMain so
  thresholds can't drift between platforms.
- [ ] Sequence AFTER Phase 1/2 stabilize the iOS AR contract, and port the NEW
  surfaces too (Navigate-mode arrows, radial reveal, grid tint) — every new
  `expect` lands with an Android stub during Phases 1–2, and Tier 2 fills the
  stubs with ARCore implementations.
- [ ] Known permanent gaps on Android: RealityKit Object Capture (no ARCore
  equivalent — `objectCaptureSupported()` stays false, UI already degrades),
  and LiDAR depth (ARCore Depth API is a software substitute — volume
  estimates get a confidence downgrade; confirm the Galaxy model against
  ARCore's supported-devices + depth list on arrival).
- [ ] Release gotcha logged now: adding `composeApp/signing.properties`
  enables R8/minify for the first time — budget a keep-rules crash pass.

---

## Phase 5 — Code health: dead code, perf, best practices (2026-09-01 sweep)

Findings from a read-only repo sweep, verified with evidence; prod-DB claims
re-checked live against Neon the same day. Premise corrections first: the
`server/` SOURCE is already deleted (commit `9f4d5fab`), every env-scan-PRD
deprecated feature is already fully removed (zero grep hits), and only 2 of
the 214 site API routes are true orphans — the codebase is cleaner than
assumed. What remains:

### Quick wins (safe now)
- [ ] **CI never runs the 88 composeApp tests.** `.github/workflows/ci.yml:105-114`
  compiles desktop but never tests — the entire scan/coverage math suite
  (CoverageTracker/GridLatticeRegistration/WorldObservationWireCompat…) is
  decorative. Add `./gradlew :composeApp:desktopTest`. Also: 19
  `scripts/reconstruction/tests/test_*.py` never run in CI. **S, highest value.**
- [ ] `rm -rf server/` — 185 MB of untracked, gitignored build artifacts for a
  module whose source was deleted. Then rename the stale CI job name
  (`ci.yml:76`, after confirming no branch protection pins it). **S.**
- [ ] Delete orphan route `site/src/app/api/public/contributions/route.ts`
  (zero callers). **S.**
- [ ] **Rewrite CLAUDE.md's schema-mirror section** — it mandates updating
  `server/.../Tables.kt` files that no longer exist. New rule: Drizzle
  authoritative → `shared/` DTO mirror only. Also correct the "duplicate
  migration numbers resolved 2026-05-23" note — three NEW duplicate pairs
  exist (0013, 0032, 0033); 45 .sql files vs 31 journal entries vs 15
  snapshots. **S, highest doc leverage.**
- [ ] Rename `RouteFogClassification.kt` → `StaleCellClassification.kt`
  (+test) — live code, dead-feature name (its own docstring says so). **S.**

### Perf (mobile-facing hot paths; verified against prod)
- [ ] **`world_observations` has NO indexes beyond its PK** (verified live:
  1 index). It's bbox-queried by 8 routes. Add
  `(is_current, lat, lng)` + `(location_cell_id)` via guarded
  `CREATE INDEX IF NOT EXISTS` + ledger insert. **S.**
- [ ] **`/api/map/coverage` fetches up to 5000 unfiltered rows then bbox-filters
  in JS** (`route.ts:215-218` vs correctly-scoped siblings at :226/:240); the
  5000 cap silently truncates dense areas → wrong coverage. Persist lat/lng on
  `cell_coverage_summary` or derive a cell-id range predicate. **M.**
- [ ] Same file: three independent serial Neon round-trips → `Promise.all`. **S.**
- [ ] `/api/map/observations` does `SELECT *` including the large `depth_data`
  + `ar_anchor_data` TEXT blobs it never returns → explicit projection. **S.**
- [ ] Dashboard fetches up to 2000 observations with no bbox and no `since`
  cursor (`DashboardViewModel.kt:157`); the delta cursor is used at only 1 of
  5 call sites. Pass bbox + adopt `since` on repeat fetches. **S.**
- [ ] `fuse_cell.py:483-489`: 4 serial R2 downloads per chunk, download and
  fusion fully serialized → thread the per-chunk fetches, prefetch chunk N+1
  while fusing N. Real wall-clock on 30-min recon jobs. **M.**

### Best practices
- [ ] Promote the scan range tiers (CLOSE 4 m / GRAZE 10 m / SEEN 55 m,
  `CoverageTracker.kt:107-131`) into `/api/scan-config` — its docstring
  promises exactly this tunability; keep `cellSizeMeters` compiled (byte-equal
  gridCell contract) and say so in the route doc. **M.**
- [ ] `site/src/lib/stripe/client.ts:4` falls back to `'sk_test_placeholder'`
  — throw at load in production instead (matches the env.ts fail-fast rule). **S.**
- [ ] Sponsor checkout funnel is half-migrated: `MediaSection.tsx:183` links to
  `/sponsor/checkout` which does not exist (404), and
  `api/sponsors/checkout/route.ts` (195 lines) has zero callers while live
  flows use `/api/contribute/checkout` + `/api/sponsor-packages/checkout`.
  **Blake call:** fix the link and delete the orphan, or finish the migration. **S/M.**
- [ ] Minimap ring caching is already correct (marching-squares is memoized);
  residual cost is 8 O(n) list-equality `remember` keys re-checked at frame
  rate — swap for a coverage-generation counter key. Measure first. **M, optional.**

## Sequencing & ownership

| Order | Item | Why first | Host |
|---|---|---|---|
| 1 | 0.1 privacy scrub fix | everything downstream is deferred until it passes | Spark |
| 2 | 4.T0 Android APK via CI/ms-a2 + compile check | phone arrives in days; zero coupling to other work | ms-a2 / CI |
| 3 | 0.2 lane proof + 0.4 doc hygiene | receipts + clean canon before building on it | Spark |
| 4 | 1.1–1.3 grid stability, reveal, Navigate mode | Blake's explicit UX directives; 1.3 is a dependency of 2.2 | iOS (MacBook builds) |
| 5 | 2.1–2.3 lap AR + block_id | "working block 2-lap flow" | iOS + site/ |
| 6 | 0.3 WorldMirror A/B → 3.1–3.3 | recreation quality + visibility | Spark + ms-a2 |
| 7 | 4.T1 Galaxy usability → 4.T2 AR parity | parity ports the stabilized contract | ms-a2 + device |
| 8 | 3.4 bookmark upgrades (staged) | enrichment, each independently shippable | Spark |
| — | Phase 5 quick wins (CI tests on, CLAUDE.md fix, map-route perf) | cheap, independent — interleave anytime | site/ + CI |

## Decisions Blake owns (decision-shaped)

1. **Detector license:** YOLO26 (AGPL — viral for a closed-source app) vs
   RF-DETR (Apache-2.0). Recommendation: **RF-DETR** unless benchmark gap is
   large; AGPL in a shipped binary is a real exposure. (Blocks classifier v2.)
2. **Splat engine default** after the 0.3 A/B: gsplat (current) vs WorldMirror.
3. **Navigate-mode PRD amendment** text (Task 1.3) — approve before build.
4. **Scan library purpose flag:** one library with `purpose: civic|game` flag
   (recommended — one pipeline, two deliverable lanes) vs separate stores.
5. **TestFlight cadence:** ship 1.1+1.2+1.6 as a fast v1.0.9, holding 1.3
   Navigate for v1.1.0 — or one bigger release. Recommendation: fast v1.0.9
   (grid feel + classifier validation get field data sooner).
