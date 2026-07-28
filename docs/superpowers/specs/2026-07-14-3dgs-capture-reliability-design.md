# 3DGS Capture Reliability — Design

**Date:** 2026-07-14
**Status:** Approved (Blake, 2026-07-14 session)
**Scope:** Track 1 of the env-scan product push. Track 2 (per-object trash
detection, weight estimation, AR tracked-trash feedback) gets its own spec.

## Problem

The world-twin system builds 3D Gaussian Splat environments from env-scan
captures. A capture is splat-usable only when video, pose, depth, GPS, and
heading all survive to R2 together. Today they don't, and when they don't,
nothing tells anyone:

- **Silent video loss.** Field evidence: session
  `env-1783970967094-3wyhss` (2026-07-13, cell `464641:-721827`) uploaded
  "successfully" — 0 upload failures — but chunk-0 (297 s of walk, 3,332
  well-tracked sidecar frames, 20 MB of depth) has no `.mov`: it was never
  created, never listed in the manifest, never attempted. The only video
  that survived is chunk-1's 4-second end-of-scan tail (all LIMITED
  tracking). gsplat had nothing to color-train on; the cell got a
  pointcloud and **no splat**, and the only trace was a stderr line on the
  DGX. Root cause (all silent paths corroborate it): `start()` resets chunk
  state but not `writer/writerInput/adaptor`; a stale AVAssetWriter left by
  a prior teardown (the async `finishSilently` path can be interrupted
  before its finalize runs) makes chunk-0's `appendVideo` skip
  `createWriter`, and a non-`.writing` stale writer drops every frame
  without ever tripping `failCapture` — until the first rotation nils it
  and chunk-1 records normally. The July 12 sessions (build 151) uploaded
  video for every chunk. Separately, the recorder's thermal policy skips
  video appends at `.serious` with no event, no UI, and no manifest
  record — a second, latent way to lose video silently (ruled out for this
  session: depth appends share the `skipHeavy` gate and depth is present).
- **No explicit compass.** The pose sidecar records per-frame
  `transform/intrinsics/trackingState/thermalState`; heading exists only
  implicitly via ARKit's `gravityAndHeading` convention plus geoRefEvents.
  The world twin wants a directly measured compass track.
- **Reconstruction is ad-hoc.** `recon_worker.py` has no service unit; jobs
  sit queued until someone runs it by hand on the DGX. Job 60 (the July 13
  cell) waited ~16 h. The privacy scrub, by contrast, is a proper systemd
  user timer and runs every 5 minutes.
- **Splat skips are invisible.** `fuse_cell` prints "no session produced a
  usable splat" to stderr and moves on; the job row reads `done` with no
  error, indistinguishable from a full success.

## Goal

Every completed outdoor env scan delivers splat-usable inputs to R2, any
degradation is loud (in-app, in the flight recorder, and in the scan
index), and reconstruction runs unattended.

## Design

### 0. Writer lifecycle fix (client, iOS) — the chunk-0 regression

`ArSessionRecorder.swift`, defense in depth on the serial queue:

- `start()` hard-resets the writer stack: if `writer != nil`, cancel it,
  nil `writer/writerInput/adaptor/videoURL`, and log a
  `GCC-DIAG ERR [capture] stale writer at start` line — a stale writer at
  session start is always a bug worth seeing.
- `startChunk` asserts the same invariant (a chunk boundary owns the writer
  lifecycle): any surviving writer is cancelled and nil'd before the new
  chunk opens.
- `appendVideo` treats a writer whose status is neither `.writing` nor
  `.unknown` as dead: emit one `capture-degraded(reason: writer-error)`
  flight-recorder event, discard it, and let the next frame recreate it —
  today that state silently drops every frame for the rest of the chunk.

Unit-testable without a device: inject a finished/cancelled writer, feed a
frame, assert a fresh writer is created and the degraded event fires.

### 1. Thermal ladder: degrade, then warn (client, iOS)

`ArSessionRecorder.swift`. Decision: **degrade instead of drop** at
`.serious`; only `.critical` stops video.

- `.nominal` / `.fair` — unchanged: HEVC, `averageBitRate = 3_000_000`,
  native AR camera dimensions, 60 s chunk rotation.
- `.serious` — rotate the chunk and continue at **half resolution and
  ~1.2 Mbps**. AVAssetWriter settings are fixed per file, so a quality
  change is a chunk boundary; the rotation machinery already exists. The
  chunk header JSON records the active quality tier so the decoder side
  never has to guess.
- `.critical` — stop video+depth appends (existing auto-pause), auto-resume
  below `.serious` (existing behavior, kept).
- Depth appends: resume at `.serious` (today `skipHeavy` drops depth along
  with video there; depth is cheap relative to encode and the fusion
  pipeline is depth-keyframe-driven, so keeping it flowing is most of the
  point of "degrade instead of drop").

**Telemetry (the "warn" half):**

- Every thermal-tier transition emits a flight-recorder event
  (`{"type":"thermal","state":"serious","videoTier":"reduced"}`), via the
  existing `SessionEventLog` seam.
- The first skipped/degraded video append per chunk emits a
  `capture-degraded` event with a reason (`thermal-serious`,
  `thermal-critical`, `writer-error`). This also settles definitively what
  dropped chunk-0's video on July 13 — the class's failure policy can
  discard video on writer errors too, and today the two are
  indistinguishable after the fact.
- On-screen chip: **"Phone hot — 3D capture reduced"** while the tier is
  degraded, same honest-state pattern as the route REC⏸ chip (only shown
  when the recorder actually degraded, cleared on recovery).

### 2. Explicit compass in GeoRefEvents (shared + client)

`GeoRefEvent` (shared `@Serializable` DTO — already the AR↔GPS pairing
record with both clocks and both frames) gains:

- `headingDegrees: Double?` — CLHeading `trueHeading` at the fix
- `headingAccuracyDegrees: Double?` — CLHeading `headingAccuracy`

Recorded in `updateGeoReference` alongside the existing lat/lng/arXYZ
pairing on all three `ArEnvScanOverlay`/`ArRouteOverlay` actuals (iOS
provides real values; other platforms pass null). Manifest `captureSchema`
bumps (2 → 3). kotlinx `ignoreUnknownKeys` keeps the Ktor indexer and every
existing parser safe on old manifests; the registration contract doc gains
the two fields as optional.

`locationProvider.headingDegrees: StateFlow<Float?>` already exists and is
collected on SpotScreen — the plumbing cost is passing it through to where
geoRefEvents are recorded, not new sensor work.

### 3. Capture-health metadata, end to end (shared + backend)

The manifest gains a `captureHealth` block, written at session finalize
from recorder counters:

```json
{
  "sessionSeconds": 311.2,
  "videoCoveredSeconds": 44.7,
  "maxThermalState": "serious",
  "degradedChunkCount": 1,
  "droppedVideoChunkCount": 1,
  "writerErrorCount": 0
}
```

The Ktor indexer (`ScanIndexer`/`ScanIndexRepository`) extracts it into new
`scan_sessions` columns (nullable, COALESCE-upsert like the event-log
metrics from PR #168):

- `video_coverage_pct` (real)
- `max_thermal_state` (varchar 16)
- `dropped_video_chunks` (int)

Migration at the next free number (0039 at time of writing; 0038 = wallet,
applied to prod 2026-07-13), mirrored in Drizzle `schema.ts`
(authoritative) per the established pattern. A splat-degraded session is
queryable the moment it's indexed. The admin scan-sessions surface shows
video coverage % (small change; it already renders index columns).

### 4. Recon worker becomes a service (DGX ops)

`ops/systemd/gcc-recon-worker.{service,timer}`, exactly the
`gcc-privacy-scrub` pattern that already works on the DGX:

- User-scope oneshot service running `recon_worker.py --sweep` (process all
  claimable jobs, then exit); timer every 10 minutes.
- `EnvironmentFile=backend/deploy/.env` (same creds file the scrub and
  backend container use).
- `MemoryMax` + generous `TimeoutStartSec` (gsplat training runs up to 3 h;
  the lease already outlasts it — the timer's next fire simply finds no
  claimable job while one is leased).
- The existing `REQUIRE_SCRUBBED_VIDEO` defer gate keeps
  scrub-before-reconstruction ordering intact (#176).
- Install instructions in the unit header comment, like the scrub units.

This kills the measured ~16 h ad-hoc latency (job created 2026-07-13
20:00Z, processed 2026-07-14 12:29Z when a session happened to run it).

### 5. Splat skips become visible (DGX pipeline)

When fusion succeeds but no splat is produced, record why instead of
today's stderr-only line:

- `fuse_cell` returns a `splat_skip_reason` on `FusionResult`
  (`no-video-frames`, `too-few-frames`, `training-failed`, `disabled`).
- `recon_worker.complete_cell` writes it to a new
  `reconstruction_jobs.splat_skip_reason` column (nullable varchar; same
  migration as §3) and includes it in the provenance JSON.
- A job that produced a pointcloud but skipped the splat is then
  distinguishable from a full success in one query, and the scan index +
  capture-health columns say whether the cause was capture-side.

## Data flow (after)

```
ARFrame → ArSessionRecorder (thermal ladder, quality tiers, counters)
  → chunks + sidecars + captureHealth → EnvScanSessionUploader → R2
  → Ktor indexer → scan_sessions (+ video_coverage_pct, max_thermal_state)
  → reconstruction_jobs ← gcc-recon-worker.timer (10 min, after scrub gate)
  → fuse_cell/gsplat → splat | splat_skip_reason → reconstruction_assets
  → world twin (worldEligible cells only)
```

## Error handling

- Recorder failure policy unchanged: no error ever escapes to the scan
  path; errors now additionally emit `capture-degraded` events.
- Old manifests (schema ≤ 2) index exactly as today: new columns stay NULL;
  no backfill required.
- The service timer must not double-run jobs: the existing
  `SELECT … FOR UPDATE SKIP LOCKED` lease already guarantees this.

## Testing

- **commonTest:** GeoRefEvent + manifest round-trip with and without the
  new fields; captureHealth serialization; schema-2 manifests still parse.
- **Swift unit tests:** (a) writer lifecycle — inject a stale
  finished/cancelled writer, feed a frame, assert a fresh writer is created
  and one `capture-degraded` event fires (the chunk-0 regression as a
  failing test first); (b) thermal ladder via an injected thermal-state
  provider (real thermal can't be forced in the simulator): tier
  transitions rotate chunks, counters accumulate, `.critical` pauses and
  auto-resumes.
- **Backend (jvmTest):** indexer extracts captureHealth into the new
  columns; COALESCE-upsert semantics; old manifests leave them NULL.
- **Pipeline (pytest):** `splat_skip_reason` set for a no-video fixture
  cell; unset on a video-backed fixture.
- **Acceptance gate (field):** one real outdoor scan must produce a splat +
  `world` registration with **zero manual DGX intervention**, and its
  `video_coverage_pct` must be visible in the scan index.

## Out of scope

- Per-object trash detection, weight estimation, and AR tracked-trash
  feedback (Track 2 spec). Blake's field-confirmed requirements for it
  (2026-07-13 walk, session `env-1783970967094-3wyhss`): trash gets an AR
  reticle with a completion bar that fills while scanned, flips to a
  scan-complete icon for ~1.5 s, then the item lands on the map and the
  live session minimap; stopping close-up on an object treats it as
  probable trash; tapping the screen forces evaluation. Field data: that
  session logged 11 detections — all fence/bush landmarks, zero litter
  (whole-frame classifier, issue #173) — and `tap_count = 0`, so the tap
  path also needs a device-verified investigation in Track 2.
- Android capture parity (recorder is iOS-only today).
- Raw-scan lifecycle/deletion after reconstruction (existing Phase 3
  backlog).
- Named Cloudflare tunnel / backend exposure changes.
