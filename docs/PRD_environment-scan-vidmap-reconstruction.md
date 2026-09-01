# PRD — GCC Environment Scans → VidMap-Powered World Reconstruction

> **⚠ SUPERSEDED (2026-08-19) — do not build from this document.**
> The P0 spikes ran and the verdict inverted this PRD's core proposal:
> VidMap is **NO-GO on iOS footage** (29–31 m RMS, row-house aliasing) while
> **ARKit sidecar poses are canonical** (GPS fit scale 0.973, 3.4 m median
> residual). VidMap is reserved for non-ARKit footage (Ray-Ban, third-party)
> only. Current truth:
> `docs/plans/2026-08-19-scan-to-world-pipeline-and-ios-release-plan.md` §3.1.

Status: SUPERSEDED (was: DRAFT for Blake review) · 2026-08-10 · Author: Claude (bg session)
Scope: GCC iOS app + DGX recon services. This is the CIVIC lane (scans, block
cleanings, before/after documentation) — deliberately separate from the video-game
world flow (chicago-twin worldclaw pipeline) and the 3D environment recreation
tools, though all three share the recon backbone.

## Problem

GCC volunteers already record long environment-capture walks and block-cleaning
sessions (iPhone, Ray-Ban glasses). Today those videos become worldgen source
bundles with APPROXIMATE camera priors (`prior_camera.json`), which caps
reconstruction quality (z-drift, misregistration — see chicago-twin tile-stitch
VERDICT: 2.2× relief mismatch between independently generated tiles). There is
no in-app scan capture flow, no automatic pose recovery, and no metric anchor
between a walk video and the GCC map.

## Proposal

Use **VidMap** (arXiv:2607.27194, Apache 2.0, staged at `~/opensource/vidmap`;
full dossier: KB `external-tech-research/10-vidmap-deep-dive.md`) as the pose
backbone: every scan video → metric camera poses + intrinsics + sparse map
(COLMAP format) → feeds WorldMirror splats / Phase-3 3DGS cells / mesh recon
with REAL cameras instead of guesses.

```
iOS scan capture (new app feature)
  │  video + GPS track + heading + capture metadata
  ▼
Scan intake (backend, existing route-queue rails)
  │  upload → job row → DGX pull
  ▼
VidMap run (DGX, GPU-serial lane)          ← the new engine
  │  poses + intrinsics + sparse map, GPS-anchored via track
  ▼
Recon stage (existing): WorldMirror --prior_cam_path / 3DGS cell
  ▼
QA gates (existing runtime_approvals pattern) → GCC map + before/after views
```

## iOS scan feature (app-side spec)

1. **Scan mode** in the GCC app (KMP `composeApp` + `iosApp`): guided capture
   with live protocol coaching derived from VidMap's failure modes:
   - prompt side-to-side pans (forward-only motion is its weakest case)
   - "close the loop" — end where you started on block walks
   - steady-motion meter (blur kills tracks); no cuts within one scan
   - warn on night/heavy rain (OOD for learned depth/calibration priors)
2. Record GPS track + compass heading at 1Hz alongside video (anchors the
   metric model to world coordinates; VidMap output is metric but gauge-free).
3. Attach context: block face / route id (reuse route-queue entities from
   `PRD_route-queue-and-scans.md`), scan intent (baseline / post-cleaning /
   damage report), notes.
4. Upload via existing route-queue/scan rails; offline-queue friendly.

## DGX service lane

- `vidmap` env on GB10 (aarch64 build: COLMAP 4.1 + PyCOLMAP from source,
  xFormers source build, PYTORCH_JIT=0 if kernels JIT-fail; ~9GB checkpoints).
- Job runner in the scans queue: strictly GPU-serial with other CDX GPU work.
- Output contract per scan: `rec/` COLMAP model + `poses_gps.json` (similarity
  transform fitted from the GPS track: COLMAP frame → EPSG:4326/ECEF) +
  quality report (track lengths, loop closures found, keyframe count).
- Handoff: WorldMirror jobs consume images + recovered `prior_cam_path`;
  3DGS cells consume the COLMAP model directly.

## Deliverables for civic use (NOT game content)

- Before/after 3D block views (cleaning impact documentation for grants/311).
- Metrically consistent block tiles on the GCC map (wallet-ledger/impact pages
  can link a cleaning event to its 3D evidence).
- Scan library per block face with QA status.

## Phasing

- **P0 spike (1 day, DGX)**: install VidMap; run on ONE existing walk bundle
  (NOT rayban_wwgo3126_douglass_park — privacy-quarantined 2026-08-10,
  t_96a47f07: all 24 frames were indoor residential, bundle retired; pick a
  verified-outdoor route_* walk bundle instead) + one block-cleaning video;
  compare recovered poses vs current prior_camera.json; run WorldMirror both
  ways and eyeball splat quality delta. GO/NO-GO gate.
- **P1**: backend intake job + GPS anchor fit + WorldMirror handoff (no app
  changes — process existing footage).
- **P2**: iOS scan mode with capture coaching + upload.
- **P3**: before/after diffing + map integration.

## Pros / cons vs alternatives

| Option | Pros | Cons |
|---|---|---|
| **VidMap (proposed)** | metric, uncalibrated OK, long-video native, loop-closure robust, Apache 2.0, feeds ALL our recon stages, benchmark-dominant (LaMAR 88.5 vs 76.6 next) | ~9GB models; aarch64 build friction (xFormers/COLMAP source); sparse output only (needs recon stage after); forward-motion + row-house aliasing failure modes need capture protocol |
| Status quo (approx priors) | zero new infra | caps quality; z-drift; no metric anchor; manual |
| DROID/DPV-SLAM | online, dense-ish | needs calibration for best results; weaker on long outdoor sequences (see ETH3D/LaMAR tables) |
| QuerySplat pipeline | end-to-end splats | FAIR-noncommercial vendored deps → internal-only trap; less control |
| ARKit-only (on-device) | easy capture | poses drift outdoors, no global optimization, iPhone-only (excludes Ray-Ban footage) |

## Recommendation

Adopt VidMap as the pose backbone for ALL walk-derived reconstruction (GCC
civic + game lanes share the DGX service; only intake and deliverables differ).
Run the P0 spike before any app work. License check on third_party
(RoMaV2 / DA3 / MegaLoc) before anything client-facing.

## Open questions for Blake

1. P0 spike now, or after the NL worldgen batch settles? (GPU-serial either way)
2. Scan mode ship target: next GGC iOS TestFlight or the one after?
3. Keep civic scans + game captures in one scan library with a `purpose` flag,
   or separate stores?
