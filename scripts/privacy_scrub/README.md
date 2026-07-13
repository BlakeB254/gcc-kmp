# Privacy scrub pass (DGX ingest)

Implements the binding privacy decision in
`docs/prd/scan-session-replay-devops-prd.md` (branch `scan-replay-plan`,
Blake 2026-07-12, decision #2): **bystanders (people/faces) and vehicles
(incl. license plates) are detected and blurred in scan capture-chunk video
on the DGX, before that imagery feeds reconstruction or any review
surface.** Until a chunk has a scrubbed rendition, admin-only access to the
raw artifact is the mitigation.

## The naming contract (what the review UI relies on)

For every raw capture video chunk, the scrubbed rendition lives at the SAME
key with the leading `raw/` swapped for `scrubbed/`:

```
raw/{cell}/{sessionId}/capture/chunk-N.mov        # original (mobile-owned namespace)
scrubbed/{cell}/{sessionId}/capture/chunk-N.mov   # blurred rendition (this worker)
scrubbed/{cell}/{sessionId}/capture/scrub-report.json  # per-session machine-readable report
```

The mapping is `r2_store.scrubbed_key_for(raw_key)`
(`scripts/reconstruction/r2_store.py`) — change it in lockstep with any UI
preference logic. A consumer "prefers scrubbed" by computing the mapped key
and HEAD-probing it (or reading the scrub report). All four DGX video
consumers (fusion, splat train, gsplat dataset export, block reports)
already do this via `r2_store.download_video_bytes()`;
`PREFER_SCRUBBED_VIDEO=0` is the kill switch.

Scrubbed renditions are H.264 (`libx264`, yuv420p, B-frames disabled) in a
`.mov` container with **each source frame's pts and time_base preserved**, so
`chunk_io.extract_video_frames_at`'s first-pts rebasing yields identical
frame↔depth↔pose matching on either rendition — and H.264 plays in browsers
that won't decode the HEVC originals, which the future session-replay video
pane can exploit.

## What gets blurred, and why whole boxes

Two YOLO passes per frame (batch of 16):

- **COCO pass** (`yolo11m.pt`): person, bicycle, car, motorcycle, bus, truck
  at conf 0.25 / imgsz 1280 (chunks are 1280-long-edge).
- **Face pass** (`face_yolov8m.pt`, ADetailer weights): catches
  partially-visible people the person class misses.

Whole-person and whole-vehicle boxes are pixelated (INTER_AREA mosaic,
~14px cells + Gaussian) — that covers faces and license plates by
construction, with no plate-specific model to maintain. Detections are
temporally dilated (±6 frames) to bridge detector flicker, and boxes padded
8% per side. Privacy errs toward over-blurring; if whole-vehicle blur is
ever relaxed for review legibility, add a plate detector first.

Post-scrub QC: the COCO detector re-runs on 8 sampled frames of the
scrubbed output at conf 0.4 (`qcResidualDetections` in the report) — high
residual counts mean the blur failed to destroy what the detector keys on.
Note the QC samples ~8 of ~1800 frames per chunk; per-frame residuals in the
report's `detectionsByLabel` vs `framesWithDetections` give the fuller
picture.

## Setup + run

```bash
cd scripts/privacy_scrub
./setup.sh            # uv venv (py3.12) + torch nightly cu130 (GB10 sm_121) + deps + weights
.venv/bin/python -m pytest tests/ -v

set -a; source ../../backend/deploy/.env; set +a   # R2_* + DATABASE_URL
.venv/bin/python scrub_worker.py --sweep --dry-run  # what's unscrubbed (per the scan index)
.venv/bin/python scrub_worker.py --sweep            # scrub everything missing a rendition
.venv/bin/python scrub_worker.py --session env-…    # one session
.venv/bin/python scrub_worker.py --cell 464650:-721828
```

Idempotent: chunks whose scrubbed rendition already exists are skipped
(`--force` re-scrubs, e.g. after a detector/threshold change). `--dry-run`
never loads torch. The worker only ever writes under `scrubbed/`.

## Weights

Central model repo only (never in the project tree):

- `/home/codex450/models/specialty/detection/yolo11m.pt` (setup.sh downloads;
  `~/models/ultralytics/` is root-owned so new weights can't land there)
- `/home/codex450/models/ultralytics/bbox/face_yolov8m.pt` (pre-existing,
  read-only)

## Ordering enforcement (scrub BEFORE reconstruction)

Two mechanisms, shipped together, enforce the PRD's ordering requirement:

1. **`ops/systemd/gcc-privacy-scrub.timer`** runs `scrub_worker.py --sweep`
   every 10 minutes (user scope on the DGX), so new chunks get renditions
   shortly after the index sweep sees them. Install:
   `ln -sf ~/cdx/dev-projects/gcc/ops/systemd/gcc-privacy-scrub.{service,timer} ~/.config/systemd/user/ && systemctl --user daemon-reload && systemctl --user enable --now gcc-privacy-scrub.timer`
2. **`recon_worker`'s scrub gate** (`REQUIRE_SCRUBBED_VIDEO`, default on):
   a claimed job whose cell still has unscrubbed video chunks is *deferred* —
   re-leased 10 minutes into the future with the attempt refunded — so
   reconstruction waits for the scrub instead of falling back to raw, and a
   slow scrub can never starve a job into `failed`. Disable with
   `REQUIRE_SCRUBBED_VIDEO=0` (or the blanket `PREFER_SCRUBBED_VIDEO=0`).

## Follow-ups

- `capture_chunks.scrubbed_r2_key` (+ scrub status) column so the review UI
  and sweep can query Neon instead of HEAD-probing R2 — Drizzle schema
  change (`site/src/db/schema.ts`) + Ktor `Tables.kt` mirror per the
  repo-wide schema policy; deferred to land with the Phase-2 review surface.
- Photo artifacts (`…-photoN.jpg`) aren't scrubbed yet — same convention
  would apply if they ever feed a review surface.
