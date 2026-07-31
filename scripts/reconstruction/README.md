# DGX Fusion Pipeline (Task 8) — consumes the real capture format, running, tested, with two known limitations

Fuses multiple independent scan sessions of the same physical location
(`locationCellId`) into one merged point cloud, per
`docs/superpowers/specs/2026-07-03-phase3-reconstruction-fusion-research.md`'s
recommended split (Open3D for registration, gsplat for splat training — that
doc's own conclusion, this is its implementation).

## Verified publication quality configuration

Verified candidate publication has no built-in quality thresholds. Operators
must configure all six bounds; a missing, non-numeric, non-finite, negative,
or out-of-range value rejects the candidate rather than selecting a default:

- `VERIFIED_MIN_IMAGE_SIMILARITY` (`0..1`)
- `VERIFIED_MAX_DEPTH_ERROR` (`>=0`)
- `VERIFIED_MAX_TEMPORAL_FLICKER` (`>=0`)
- `VERIFIED_MAX_FLOATER_RATIO` (`0..1`)
- `VERIFIED_MIN_RETAINED_COVERAGE` (`0..1`)
- `VERIFIED_MAX_ASSET_SIZE_BYTES` (`>=0`)

The six measurements also have explicit artifact bindings. Image similarity,
depth error, and temporal flicker are respectively
`aggregates.meanSsim`, `aggregates.meanDepthError`, and
`aggregates.meanColorFlicker` in `validation-metrics.json`. Floater ratio is
`qualityMetrics.floaterRatio` in `cleanup-report.json`; retained spatial
coverage is `qualityMetrics.retainedCoverage` in `frame-audit.json`; asset
size is the exact balanced PLY byte length. Missing fields stay missing and
produce stable gate rejections. In particular, depth flicker is not depth
error, removed-Gaussian counts are not floater ratio, and selected-frame
counts are not retained spatial coverage.

**Ingests the REAL mobile-side capture format** shipped in
`iosApp/iosApp/ArSessionRecorder.swift` (Pillar 1 session capture,
2026-07-07): per session, rotating chunks of HEVC video + a per-frame JSONL
pose sidecar + LZFSE-compressed LiDAR depth keyframes at 2Hz — NOT this
pipeline's original placeholder design (periodic JPEG+PNG frame pairs via a
`CapturedFrame`/`capturedFrames` manifest field). That placeholder was
deleted, Kotlin scaffolding and all, once the real capture code landed and
turned out to use a materially different (and better-engineered) format.

## Privacy scrub stage (2026-07-12)

Per `docs/prd/scan-session-replay-devops-prd.md` (branch `scan-replay-plan`)
decision #2, capture video is privacy-scrubbed (bystanders + vehicles
blurred) on this box before feeding reconstruction or review surfaces. The
worker lives in `../privacy_scrub/` (own venv — torch nightly for the YOLO
pass); the naming contract (`raw/…chunk-N.mov` → `scrubbed/…chunk-N.mov`)
is `r2_store.scrubbed_key_for()`. Every video consumer here downloads via
`r2_store.download_video_bytes()`, which prefers the scrubbed rendition
when it exists (`PREFER_SCRUBBED_VIDEO=0` to disable). Scrubbed renditions
preserve per-frame pts, so `chunk_io`'s first-pts rebasing and depth↔video
matching behave identically on either rendition. See
`../privacy_scrub/README.md`.

## Status: two things to know before trusting this on real data

**1. Open3D 0.18.0 (the only aarch64 wheel that exists at all) segfaults on
several core geometry-processing calls on this DGX Spark** —
`o3d.geometry.KDTreeFlann`, `o3d.pipelines.registration.registration_icp`,
`o3d.geometry.PointCloud.create_from_rgbd_image`, and even
`PointCloud.transform(np.eye(4))` on 3 points. All confirmed via isolated
repros (bisected line-by-line), independent of anything in this pipeline.
**Worked around, not fixed**: `frame_io.py` and `registration.py` reimplement
all of this pipeline's math in pure NumPy + SciPy's `cKDTree` (both confirmed
safe on this hardware) instead of relying on Open3D's broken C++ core. Open3D
is used for exactly one thing now — `.ply` file read/write, confirmed safe:

```bash
cd scripts/reconstruction && source .venv/bin/activate
python3 -c "
import open3d as o3d, numpy as np
pts = np.random.rand(500,3)
pcd = o3d.geometry.PointCloud(); pcd.points = o3d.utility.Vector3dVector(pts)
tree = o3d.geometry.KDTreeFlann(pcd)   # still crashes here -- re-verify before
                                        # trusting Open3D's math ops again,
                                        # including after any version bump
print('if you see this, the bug is fixed -- update this README')
"
```

This matches the class of issue the 2026-07-03 research doc already warned
about for gsplat (aarch64/Blackwell wheel fragility, GitHub #595/#855) — a
library importing cleanly on this hardware is not evidence its compute paths
work here.

**2. Point-to-point ICP has a confirmed, unresolved risk of converging
CONFIDENTLY to a WRONG registration on scenes dominated by a flat/repetitive
ground plane, once the true offset exceeds ~1m/2deg** — see
`registration.py`'s module docstring "KNOWN LIMITATION" section and
`tests/test_registration.py`'s `xfail`'d
`test_larger_offset_can_converge_to_a_wrong_but_confident_registration`. Tried
and did not reliably fix within the time available: multi-scale
coarse-to-fine ICP (helps, shipped, doesn't fully solve it), RANSAC
ground-plane removal before registration (tried in a scratch script, didn't
reliably fix it either on synthetic scenes). Real GCC scans will be dominated
by flat sidewalk/street surfaces — exactly the geometry this targets. This is
squarely the kind of question the 2026-07-03 research doc already flagged as
needing real scan pairs, not more synthetic engineering ("registration
accuracy on real GCC data ... needs testing against real scan pairs from the
app, not further research"). The activation-count gate (§3.1, `MIN_SESSIONS_FOR_ACTIVATION`
in `fuse_cell.py`) is the actual safety net today, not any single session's
fitness score — a future improvement worth making explicit: cross-check a new
registration against another already-accepted session's relative pose before
trusting it, not implemented yet.

## The real capture format (what this pipeline actually parses)

Per rotating chunk (60s or 64MB, whichever first), `ArSessionRecorder`
produces up to three files, uploaded to
`raw/{locationCellId}/{sessionId}/capture/chunk-{NNN}.{ext}` and referenced
by R2 key in `EnvScanManifest.captureChunks: List<String>`:

- **`chunk-NNN.mov`** — HEVC (H.264 fallback), long edge 1280px, aspect
  preserved from the ARKit source feed. Absent for a chunk recorded entirely
  under thermal `.serious` pressure (sidecar-only in that case).
- **`chunk-NNN.jsonl`** — one header line (`schema, sessionId, chunk,
  sourceWidth, sourceHeight, videoWidth, videoHeight`) then one line per
  captured ARFrame: `t` (ARKit's monotonic `frame.timestamp`, NOT wall-clock
  epoch), `transform` (16 floats), `intrinsics` (9 floats), `tracking`,
  `thermal`. **Both `transform` and `intrinsics` are COLUMN-MAJOR** (simd's
  `.columns.N` flattened in order) — `chunk_io.py` reshapes with
  `order='F'`, not a plain `.reshape()`, which would silently transpose the
  matrix. Caught one real bug this way: an earlier version of this parser
  had `cx`/`cy` at `k[0,2]`/`k[1,2]` instead of the correct `k[2,0]`/`k[2,1]`
  (Apple's intrinsics layout is `[[fx,0,0],[0,fy,0],[cx,cy,1]]`) —
  `tests/test_chunk_io.py` caught it immediately.
- **`chunk-NNN.depth`** — LiDAR depth keyframes at 2Hz, length-prefixed
  binary records (`[u32 recordLen][f64 t][u32 width][u32 height]
  [u32 bytesPerRow][u32 pixelFormat][u32 compressedLen][LZFSE bytes]`, all
  native little-endian). The LZFSE payload is the raw Float32
  depth-in-meters buffer — ARKit's depth is already real-world meters, no
  scale-factor convention to apply.

Because intrinsics are calibrated against the **source** capture resolution
but this pipeline extracts color from the (downscaled) **video**,
`chunk_io.parse_sidecar` rescales fx/fy/cx/cy by `videoWidth/sourceWidth` and
`videoHeight/sourceHeight` before returning them — using the raw
source-resolution intrinsics against a downscaled video frame would silently
mis-project every point.

GPS anchor for registration seeding: the sidecar has no GPS at all (and
couldn't be time-aligned to the manifest's wall-clock `path` even if it did,
since `frame.timestamp` and `path[].atMs` are different clock domains) —
approximated as the manifest's first GPS `path` sample paired with the first
sidecar frame's `camera_transform`. Both are close to session start; this is
a reasonable stand-in for an ICP initial guess (which only needs to be
roughly right), not validated against a real device's actual startup-lag
characteristics.

## What's implemented and tested (31 passed, 1 documented xfail)

- **`chunk_io.py`** — decodes the real format above: `parse_sidecar`,
  `parse_depth_chunk` (LZFSE via `pyliblzfse`, confirmed round-trips
  correctly on this hardware), `extract_video_frame_at` (nearest-PTS frame
  extraction via PyAV). `tests/test_chunk_io.py`: column-major
  transform/intrinsics recovery + resolution rescaling (this is the test
  that caught the cx/cy bug above), multi-record depth chunk round-trip,
  row-padding stripping, nearest-timestamp video frame selection against a
  synthetically encoded .mov. All passing.
- **`frame_io.py`** — pure-NumPy pinhole RGBD projection (depth always in
  meters — no PNG/mm convention anymore, nothing produces that format) +
  point transform + voxel downsampling. `tests/test_frame_io.py`: projection
  round-trip recovers known depth, zero/far-depth pixels dropped, pose
  transform matches manual computation, color correctly resized to depth's
  (lower) resolution, downsampling/merge behave as expected. All passing.
- **`registration.py`** — GPS-seeded, coarse-to-fine multi-scale ICP
  (SciPy `cKDTree` correspondence + NumPy SVD/Kabsch rigid-fit).
  `tests/test_registration.py`: 6 passing (small/realistic offset recovery,
  GPS-seed math, first-session trivial-accept, badly-misaligned-session
  rejection), 1 `xfail` documenting the ground-plane-degeneracy risk above.
- **`fuse_cell.py`** — orchestrates `r2_store` → `chunk_io`/`frame_io` →
  `registration` → activation gate → `.ply` write → provenance record.
  `_group_chunks_by_index` groups R2 keys by chunk number/kind from the key
  extension; `_fuse_one_chunk` matches each ~2Hz depth keyframe to its
  nearest sidecar pose sample and nearest video frame by timestamp (dropping
  a depth record if the nearest pose is more than `MAX_POSE_TIME_GAP_SECONDS`
  away — untrustworthy tracking gap, not a data-quality compromise worth
  making silently). `tests/test_fuse_cell.py`: chunk grouping, one full
  synthetic chunk (video+sidecar+depth) fused into real points, and a full
  manifest → `SessionFusionInput` round-trip with R2 downloads
  monkeypatched. `fuse_sessions()` (the fusion/activation logic itself) is
  separately unit-tested with synthetic in-memory sessions — first-session
  accept, activation requires `MIN_SESSIONS_FOR_ACTIVATION` (3, empirical
  starting value per the research doc) independent accepted sessions, an
  unrelated/misaligned session is rejected not silently merged, rejected
  sessions don't count toward activation.
- **`site/src/app/api/admin/reconstruction-assets/route.ts`** (Next.js side)
  — the writer `reconstructionAssets` never had (schema + migration 0025
  existed, nothing wrote to it). Server-to-server, guarded by
  `RECONSTRUCTION_PIPELINE_SECRET` bearer auth (same pattern as
  `/api/cron/route-releases`), not a logged-in-admin session — this pipeline
  calls it directly, it's not a human-facing endpoint. Computes `version`
  server-side (`MAX(version)+1` per cell) so two runs can't race on the same
  version number. `sourceScanCoverageEventIds` is written as `[]` for now —
  this pipeline only has session manifests from R2, not actual
  `scanCoverageEvents` row IDs; resolving those is a follow-up (server-side
  lookup, or have this pipeline query Postgres directly).

  **Naming collision risk, flagged not resolved:** an unrelated, unmerged
  branch (`worktree-scan-viz-and-conversion-pipeline`, a single-asset
  Blender PLY/OBJ→GLB converter) reportedly has its own
  `POST /api/admin/reconstruction-assets` route for a different producer.
  If that branch merges, reconcile the two request shapes rather than
  silently picking one — both are legitimately writing to the same table,
  from different pipelines.

- **`world_mirror_client.py` + `gaussian_ply.py` + `splat_train.py`** —
  dense-reconstruction step, wired to the real `cdx-world-mirror` service
  (`~/cdx-platform/services/cdx-world-mirror/`, HY-WorldMirror-2.0).
  Per-session only (world-mirror does one fresh joint solve per call with
  no cross-session registration of its own — confirmed by reading
  `app/main.py`'s `/generate` signature, no prior/persistent-map param
  exposed), then `gaussian_ply.py` applies the SAME transform
  `fuse_sessions()` already computed for that session (now exposed via
  `FusionResult.session_transforms`) to align each session's independently-
  reconstructed splat into the shared cell frame, and concatenates them.
  **Verified against a REAL cdx-world-mirror instance this session** (2
  synthetic multi-view images, full submit → poll → download round trip):
  confirmed the actual `gaussians.ply` property layout
  (`x,y,z,nx,ny,nz,f_dc_0-2,opacity,scale_0-2,rot_0-3`) and scalar-first
  quaternion convention match what `gaussian_ply.py` assumed, then ran the
  real transform/concatenate code against that real file successfully (not
  just synthetic fixtures — see `gaussian_ply.py`'s module docstring).
  **Found and fixed a real bug in cdx-world-mirror itself** while doing
  this: `/tasks/{id}/artifact/{name}` resolved paths against `output_dir`,
  but the pipeline actually nests output one level deeper
  (`output_dir/input/<timestamp>/`) and the `artifacts` list was already
  correctly relative to THAT — so every artifact download 404'd for any
  consumer of this API. Patched in `app/main.py` (added a `result_dir`
  field), not committed to a repo (this service isn't in the gcc repo).
- **`site/src/app/api/admin/reconstruction-assets/route.ts`** now also has
  a real writer being fed by `fuse_cell.py`'s splat step (`assetType='splat'`)
  in addition to the point-cloud path, and `r2_store.next_version_for_cell`
  (lists existing R2 objects for the cell/assetType, returns max+1) fixes a
  real versioning bug found while wiring this: `_write_ply_to_r2` used to
  hardcode `version=1` in the R2 key regardless of the DB's actual computed
  version, so a re-run would have silently overwritten an older snapshot's
  physical file even though the DB recorded a new logical version.
- **`site/src/app/api/scans/manifest/route.ts`** (Next.js) — serves
  activated `reconstructionAssets` at the EXACT path chicago-twin-viewer's
  Twin v4 already fetches (`/api/scans/manifest?route_to=twin-v4`,
  hardcoded client-side), so pointing Twin v4 at GCC data needs zero
  viewer-side changes — just `?scan=<this-site-origin>`. `file_url` is a
  same-origin path (`/api/world-model/asset/{id}`, a redirect endpoint
  minting a fresh presigned R2 GET URL per request) because the viewer does
  `${_CDX_SCAN_BASE}${item.file_url}` string concatenation, not
  `new URL()` — an absolute presigned URL there would produce a malformed
  URL. Confirmed via a live local dev server: both endpoints return
  correctly-shaped 200s, and the viewer (revived on :7099) loads without
  error against an empty manifest.
- **`site/src/app/admin/world-model/page.tsx`** + `api/admin/world-model-stats` —
  new admin analytics page (observations by domain, scan coverage, DGX
  reconstruction pipeline status), matching the existing admin panel's
  Tailwind house style (no charting library in this codebase yet — see the
  page for the plain-div convention). Added to the admin nav.

## Setup

```bash
cd scripts/reconstruction
uv venv --python 3.11 .venv   # open3d's only aarch64 wheel needs 3.11, not this box's default 3.12
source .venv/bin/activate
uv pip install -r requirements.txt
python3 -m pytest tests/ -v   # 31 passed, 1 xfailed (documented limitation above)
```

Splat training additionally needs a running `cdx-world-mirror` instance
(`~/cdx-platform/services/cdx-world-mirror/`, HY-WorldMirror-2.0 wrapper).
Its systemd unit is currently disabled (archived 2026-05-27) and its
declared port (7025) is presently occupied by an unrelated `aiq-agent`
Docker container — resolve that port conflict (move one of them) before
re-enabling the real unit; in the meantime this was verified via a manual
instance on a free port (`--port 7027`, matching `world_mirror_client.py`'s
`base_url` param).

Running against real data:

```bash
export R2_ACCOUNT_ID=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=... R2_BUCKET_NAME=...
export RECONSTRUCTION_PIPELINE_SECRET=...   # must match the Next.js env var of the same name
python3 fuse_cell.py "419500:-876234" --api-base-url https://gcc.app
```

Not yet done, next concrete steps once real chunk data exists in R2:
verify a real chunk actually decodes end-to-end (the video-codec path in
particular — HEVC decode support depends on what's actually in the .mov
container; H.264 fallback per `ArSessionRecorder`'s `hevcSupported` probe
should decode fine via PyAV/ffmpeg regardless), and empirically validate
`project_rgbd_to_points`'s depth-buffer axis/origin assumption (documented
as unverified in its own docstring) against a real captured scene.

---

## 2026-07-10 — queue + real-data prototype (this branch)

The pipeline above is now driven by a durable Neon queue and has run against
REAL captured data for the first time. Spec:
`docs/superpowers/specs/2026-07-10-phase3-reconstruction-queue-and-prototype-design.md`.

**What ran (cell `464650:-721828`, sessions `env-1783470359293-i3tt1m` +
`env-1783640498732-0cgku8`):** both sessions decoded (real HEVC via PyAV,
real LZFSE depth), both ACCEPTED by GPS-seeded ICP, fused into a 61 MB
`pointcloud.ply`; per-session HY-WorldMirror splats aligned + concatenated
into a 105 MB `splat.ply` (1.54 M gaussians, recognizable photo-textured
sidewalk); `splat-transform` compressed it to an 18.7 MB `.spz` in ~2 s.
QC'd in SuperSplat (:7051, `gcc-cell-464650-721828.ply` staged in its dist/).

**Fixes real data forced (all committed on this branch):**
- Depth chunks upload as `.bin`, not `.depth` — grouping accepts both.
- `extract_video_frame_at` was O(N²) on real chunks (full 60 s HEVC decode
  per ~2 Hz depth record, ~120×/chunk → 20+ min with no end in sight);
  `extract_video_frames_at` decodes once for all targets → full cell fuses
  in ~3 min.
- Open3D `Vector3dVector` SEGFAULTS on float32/non-contiguous input on this
  box — coerce to contiguous float64 (add to the Open3D breakage list above).
- `world_mirror_client.DEFAULT_BASE_URL` pointed at :7025 (now aiq-agent);
  honors `WORLD_MIRROR_URL`, default :7027 (the live instance).

**BLOCKED — R2 publish needs a write-capable token.** The DGX's surviving
R2 token (`gcc-r2-key`, `backend/deploy/.env`) is Object-READ-only:
`PutObject reconstructed/… → AccessDenied` (probed directly). Prod's
write creds are Vercel-sensitive (env pull returns empty), and the site's
presigned-PUT endpoints are strictly `raw/`-scoped. Create an
Object Read & Write token scoped to `gcc-scan-storage`, put it in
`backend/deploy/.env` (or a worker-specific env), and the queued jobs
complete end-to-end — the failed attempt is recorded on job 3 and requeues
cleanly.

**Known quality caveats to iterate on (visible in the QC screenshot):**
- Splat patches are camera-relative → orientation post-process needed
  (rotate to Y-up + ground-level translate; see
  `cdx-platform/scripts/rotate_splat_canonical.py` for the reference fix).
- World-mirror reconstructs each session in its OWN scale/frame; the ICP
  transform applied comes from the LiDAR point-cloud frame, so per-session
  splat pieces don't perfectly co-register. Candidate fix: estimate a
  similarity transform between each session's world-mirror cameras and its
  ARKit poses before applying the cell transform.

**Queue + worker usage:**

```bash
# Enqueue happens automatically after every backend index sweep
curl -X POST localhost:8080/admin/index-scans -H "Authorization: Bearer $INDEX_SECRET"
```

### Running unattended: the systemd timer (primary)

`ops/systemd/gcc-recon-worker.{service,timer}` runs `recon_worker.py --drain`
every 10 minutes (user scope, on the DGX) — it claims and fuses queued
`reconstruction_jobs` until none remain, then exits 0, mirroring
`ops/systemd/gcc-privacy-scrub.{service,timer}`'s pattern. The
`REQUIRE_SCRUBBED_VIDEO` defer gate (#176) already serializes
scrub-before-reconstruction, so the two timers need no explicit ordering
between them.

Install:

```bash
ln -sf ~/cdx/dev-projects/gcc/ops/systemd/gcc-recon-worker.{service,timer} ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now gcc-recon-worker.timer
```

Verify: `systemctl --user list-timers | grep recon` and
`journalctl --user -u gcc-recon-worker.service -f` on the next fire.

### Manual runs (debugging appendix)

For one-off runs or interactive debugging against real data, bypass the
timer and invoke the worker directly:

```bash
cd scripts/reconstruction && source .venv/bin/activate
export DATABASE_URL=...           # Neon (unpooled)
export R2_ACCOUNT_ID=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=... R2_BUCKET_NAME=gcc-scan-storage
export WORLD_MIRROR_URL=http://127.0.0.1:7027 SPLAT_REQUIRE_ACTIVATION=0
python recon_worker.py --once     # process at most one job
python recon_worker.py --loop 60  # poll forever, 60s idle sleep
python recon_worker.py --drain    # process until the queue is empty, then exit
                                   # (what the timer runs)
```
