# Privacy scrub pass (DGX ingest)

Implements the binding privacy decision in
`docs/prd/scan-session-replay-devops-prd.md` (branch `scan-replay-plan`,
Blake 2026-07-12, decision #2): **bystanders (people/faces) and vehicles
(incl. license plates) are detected and blurred in scan capture-chunk video
on the DGX, before that imagery feeds reconstruction or any review
surface.** Until a chunk has a scrubbed rendition, admin-only access to the
raw artifact is the mitigation.

**Where it runs.** The Spark is the intelligence engine (Blake, 2026-10-18):
its memory belongs to the resident model. The scrub's intended home is the
**cloud recon container**, running ahead of reconstruction (see "Running in
the cloud recon container"). The Spark timer is only a memory-gated
fallback, and on a busy Spark it mostly defers.

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

### Detector switch: `SCRUB_DETECTOR=yolo|rfdetr` (default `yolo`)

Two detector backends sit behind one interface (`detect_frames`,
`qc_counts`, `describe`):

| value | modules | licence | status |
|---|---|---|---|
| `yolo` (**default**) | `detector_yolo.py`: YOLO11m + face_yolov8m | Ultralytics **AGPL-3.0**, internal use only | the current live behaviour. Merging this change alters nothing live. |
| `rfdetr` | `detector.py`: RF-DETR + YuNet | Apache-2.0 / MIT | the target. Off until the rotated-face sweep passes. |

**Do not flip the switch until the sweep below shows RF-DETR Large plus the
chosen face setting leaving no more than one reference face uncovered.** Until
then the Spark timer keeps running YOLO.

To flip it:

1. Set `Environment=SCRUB_DETECTOR=rfdetr` in the unit (or the container env).
2. Re-run a few sessions with `--force` and compare the `detector` blocks in
   their scrub reports.
3. In a follow-up, delete `detector_yolo.py` and `requirements-yolo.txt`.
   That is when the AGPL dependency leaves.

The dependencies are split so that the rfdetr install never pulls AGPL code:

| file | contents |
|---|---|
| `requirements-common.txt` | shared |
| `requirements-yolo.txt` | ultralytics, AGPL-3.0, internal Spark fallback only |
| `requirements-rfdetr.txt` | no AGPL |

`setup.sh` installs only the file for `SCRUB_DETECTOR`.

Each pass below runs per frame, in batches of 16.

- **Object pass**: RF-DETR (`rfdetr`, Apache-2.0; Large COCO weights by
  default, `SCRUB_RFDETR_VARIANT=medium` for Medium; XL/2XL are under PML
  and are refused) for person, bicycle, car, motorcycle, bus and truck at
  conf 0.25, at the variant's square inference resolution.
- **Face pass**: OpenCV `FaceDetectorYN` (Apache-2.0) with
  `face_detection_yunet_2023mar.onnx` (opencv_zoo, MIT) at score 0.3. It runs
  upright and at both quarter turns, because chunks decode sideways (the
  phone's rotation flag is not applied) and YuNet is not rotation-invariant.
  It catches partially visible people that the person class misses.

### Benchmark (2026-10-18, Spark, 198 frames from 3 RAW chunks, in memory)

The reference is the retired YOLO11m + face_yolov8m pair, at conf 0.25 and
imgsz 1280: 43 boxes (33 person/vehicle, 10 face), 1.95% of frame area
blurred, 149 ms/frame. "Uncovered" means a reference box less than 50%
covered by the candidate's padded boxes.

| candidate (faces: YuNet 0.5, upright only) | uncovered | IoU<0.3 missed | candidate-only boxes | faces found | blurred area | ms/frame |
|---|---|---|---|---|---|---|
| RF-DETR Large @704  | 10 (1 car, 9 face) | 12 | 82 | 8 | 3.17% | 139 |
| RF-DETR Large @960  | 10 (1 car, 9 face) | 12 | 78 | 8 | 3.30% | 159 |
| RF-DETR Medium @576 | 12 (3 car, 9 face) | 14 | 87 | 8 | 3.57% | 117 |

- **Person/vehicle boxes:** Large leaves 1 of 33 reference boxes uncovered
  (a car; the uncovered reference confidences are all 0.28–0.45). It finds
  82 boxes the reference did not and blurs about 60% more area.
- **Faces:** upright YuNet at 0.5 left 9 of the 10 reference faces
  uncovered. That is a material regression. The defaults were therefore moved
  to score 0.3 plus 0° / 90° / 270° passes (rotation mapping is unit-tested).
  **The sweep that measures those settings has not run yet:** the Spark's
  headroom fell to 3–8 GB once the resident model restarted. Run it before
  installing:

```bash
SCRUB_MODELS_DIR=… .venv-with-ultralytics/bin/python benchmark_detectors.py --lock-wait-s 3600 \
  --min-available-gb 5 --frames 200 --batch 1 --key raw/… --key raw/… --key raw/… \
  --candidate large --face 0.5/1/0 --face 0.5 --face 0.3 --face 0.3/2 --face 0.2/2
```

Accept when Large plus the chosen face setting leaves no more than one
reference face uncovered.

`benchmark_detectors.py` compares a candidate detector with the retired YOLO
pair on RAW chunks, in memory only. It reports missed and uncovered reference
boxes, candidate-only boxes, faces, blurred area and ms/frame. Re-run it
before changing the variant or the thresholds; it needs Ultralytics in a
scratch venv, never this one.

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
./setup.sh            # SCRUB_DETECTOR=yolo (default): uv venv (py3.12) + torch nightly cu130 + requirements-yolo.txt + weights
SCRUB_DETECTOR=rfdetr ./setup.sh   # requirements-rfdetr.txt + RF-DETR/YuNet weights (no AGPL)
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

`/home/codex450/models/specialty/detection/` (override with
`SCRUB_MODELS_DIR`). setup.sh downloads, for the chosen backend:

- `rfdetr`, each checked against a pinned hash:
  - `rf-detr-large-2026.pth` (md5 `5cb72153…`, the hash rfdetr pins)
  - `rf-detr-medium.pth` (md5 `7223f764…`)
  - `face_detection_yunet_2023mar.onnx` (sha256 `8f2383e4…`)
- `yolo`: `yolo11m.pt`. The face model, `~/models/ultralytics/bbox/face_yolov8m.pt`, is pre-existing and read-only.

## Running in the cloud recon container

Blake has decided that GPU vision work other than the resident model moves to
cloud GPUs, and the Spark stays the intelligence engine. The scrub must run
**before** reconstruction. It therefore belongs in the same image as the
cloud recon worker that lane-recon is building (`deploy/cloud/recon/`), not in
a separate service.

The scrub needs nothing host-specific; every setting comes from the
environment:

- **Code and dependencies:** copy `scripts/privacy_scrub/` and
  `scripts/reconstruction/r2_store.py` into the image, then run
  `pip install -r requirements-rfdetr.txt` on the image's CUDA torch.
  **Install only the rfdetr file**: the image must never carry ultralytics
  (AGPL).
- **Weights:** fetch them at build time with
  `SCRUB_DETECTOR=rfdetr SCRUB_SKIP_VENV=1 SCRUB_MODELS_DIR=/models/detection ./setup.sh`.
- **Env:**
  - `R2_*` and `DATABASE_URL`, the same set the recon worker uses
  - `SCRUB_MODELS_DIR`
  - `SCRUB_DETECTOR=rfdetr`. The container cannot run the yolo path, so it
    goes live together with the switch flip, after the face sweep passes.
    Until then, the Spark fallback scrubs.
  - `SCRUB_MEMGATE=0`. A dedicated GPU box has no shared model to protect,
    so this turns the memory gate off.
- **Order:** run `python scrub_worker.py --sweep` (or `--cell <id>` for the
  job's cell) before draining `reconstruction_jobs`. `recon_worker`'s
  `REQUIRE_SCRUBBED_VIDEO` gate already defers any job whose cell still has
  unscrubbed chunks, so a container that runs scrub, then recon, then scrub
  again on each tick stays correct even if a step is skipped.
- **The Spark timer stays as a fallback,** with the memory gate on. Both can
  run at once safely: the sweep is idempotent and skips chunks that already
  have a rendition.

## Memory budget (Spark fallback)

With the resident model loaded, the Spark idles at about 3–8 GB
MemAvailable, and earlyoom fires near 3 GB, killing shared services.
`memgate.py` budgets for that. All values are in GB and can be tuned through
env:

| setting | value | default yolo | default rfdetr |
|---|---|---|---|
| abort floor | `SCRUB_ABORT_AVAIL_GB`, else `SCRUB_EARLYOOM_GB` (3) + `SCRUB_MARGIN_GB` (1.5) | 4.5 | 4.5 |
| start | abort + weights (`SCRUB_WEIGHTS_GB`: yolo 1.5, rfdetr 2.5) + decode (`SCRUB_DECODE_GB` 1.0); `SCRUB_MIN_AVAIL_GB` raises it | 7.0 | 8.0 |
| loaded | abort + decode | 5.5 | 5.5 |

1. **Before any weights load:** if `start` is not available, the sweep logs
   `deferred: low memory` and exits 0. No weights are ever loaded on a
   deferral.
2. **Right after the load:** if `loaded` is not available, the sweep drops
   the detector, frees CUDA memory, logs `deferred: low memory after loading
   weights` and exits 0.
3. **While working:** the abort floor is checked between every 16-frame
   batch, in both the detect pass and the encode pass, and before and during
   QC. Decoding is bounded the whole time:
   - the scrub keeps one batch of decoded frames plus boxes;
   - QC seeks to its 8 samples instead of decoding the whole chunk.

   Below the abort floor, the sweep stops its own work. The interrupted chunk
   is neither uploaded nor marked; the next sweep redoes it, and finished
   chunks still get their report.

On a dedicated host, set `SCRUB_MEMGATE=0` to turn the gate off.

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
