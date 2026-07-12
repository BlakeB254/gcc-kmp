# Privacy Scrub Pass (DGX ingest) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect and blur bystanders (people/faces) and vehicles (incl. license plates) in scan capture-chunk video on the DGX, writing scrubbed renditions to R2 alongside the originals under a deterministic naming convention that reconstruction and the future review UI prefer.

**Architecture:** A new standalone `scripts/privacy_scrub/` worker (own venv, torch-nightly cu130 for GB10 sm_121, mirroring `scripts/gsplat_train/`'s pattern) downloads `raw/.../capture/chunk-N.mov` chunks named by the scan index (Neon `capture_chunks`), runs a two-model YOLO pass (COCO person+vehicle detector plus the existing local face model), pixelates+blurs detections with temporal dilation, re-encodes H.264 preserving per-frame pts, and uploads to `scrubbed/<same suffix>`. A small `r2_store.py` extension gives every existing video consumer (fusion, splat train, gsplat export, block reports) a single `download_video_bytes()` that prefers the scrubbed rendition when it exists.

**Tech Stack:** Python 3.12 venv (uv), torch nightly cu130, ultralytics YOLO, PyAV, OpenCV (headless), boto3, psycopg.

**Source PRD:** `docs/prd/scan-session-replay-devops-prd.md` (branch `scan-replay-plan`), binding decision #2: *"bystanders AND vehicles (cars/license plates …) should be scrubbed/blurred … Implementation home: the DGX ingest side (detection + blur pass over capture chunks before they feed reconstruction or the review UI); the review UI must prefer scrubbed renditions when they exist."*

## Global Constraints

- GB10/Blackwell sm_121: stable torch wheels lack kernels — install `--pre torch torchvision --index-url https://download.pytorch.org/whl/nightly/cu130` (same recipe as `scripts/gsplat_train/setup.sh`).
- Model weights live under `/home/codex450/models/ultralytics/` (never inside the repo). Existing: `bbox/face_yolov8m.pt`. New COCO det model downloads there once.
- R2 namespace contract: the mobile app owns `raw/`; this pipeline writes ONLY under `scrubbed/` (extends the existing "pipeline writes only `reconstructed/`" rule in `r2_store.py`'s docstring).
- **Naming convention (the contract the review UI relies on):** scrubbed rendition key = original key with leading `raw/` replaced by `scrubbed/`, filename unchanged. Per-session machine-readable report at `scrubbed/{cell}/{sessionId}/capture/scrub-report.json`.
- **Timing contract:** `chunk_io.extract_video_frames_at` rebases pts by the stream's first pts. Scrubbed renditions must preserve each source frame's pts (same `time_base`, same pts values) so depth↔video matching is bit-identical for reconstruction.
- Privacy errs toward over-blurring: low confidence threshold (0.25), padded boxes, temporal dilation. Whole-person and whole-vehicle boxes are blurred (covers faces and plates by construction).
- Env vars: same `R2_*` + `DATABASE_URL` as `recon_worker.py` (source `backend/deploy/.env`). Consumers' preference is gated by `PREFER_SCRUBBED_VIDEO` (default `1`).
- No DB schema change in this pass (deterministic key mapping + HEAD check instead). `capture_chunks.scrubbed_r2_key` is an explicit follow-up once the review UI lands.

## File Structure

- Create `scripts/privacy_scrub/setup.sh` — venv bootstrap (uv + torch nightly + requirements) + one-time COCO weight download into `~/models/ultralytics/`.
- Create `scripts/privacy_scrub/requirements.txt` — non-torch deps.
- Create `scripts/privacy_scrub/scrub_video.py` — pure scrub core: box dilation, pixelation, two-pass decode/detect → blur/encode with pts preservation. No network, detector injected as a callable (testable without GPU).
- Create `scripts/privacy_scrub/detector.py` — YOLO wrapper (COCO person/vehicle classes + face model), returns per-frame boxes.
- Create `scripts/privacy_scrub/scrub_worker.py` — CLI: `--session`/`--cell`/`--sweep`, `--force`, `--dry-run`; Neon sweep query; R2 down/upload; scrub-report.json; post-scrub QC re-detection.
- Create `scripts/privacy_scrub/tests/test_scrub_video.py` — synthetic-video unit tests.
- Create `scripts/privacy_scrub/README.md` — format, convention, ops runbook.
- Modify `scripts/reconstruction/r2_store.py` — add `scrubbed_key_for()`, `object_exists()`, `download_video_bytes()`.
- Modify `scripts/reconstruction/fuse_cell.py:397`, `scripts/reconstruction/splat_train.py:57`, `scripts/reconstruction/block_report.py:73`, `scripts/gsplat_train/export_gsplat_dataset.py:75` — switch `download_bytes(kinds["mov"])` → `download_video_bytes(kinds["mov"])`.
- Create `scripts/reconstruction/tests/test_scrubbed_preference.py` — key-mapping + preference tests.
- Modify `scripts/reconstruction/README.md` — document the scrub stage.

---

### Task 1: Scrubbed-key convention + preference download in `r2_store`

**Files:**
- Modify: `scripts/reconstruction/r2_store.py`
- Create: `scripts/reconstruction/tests/test_scrubbed_preference.py`
- Modify: `scripts/reconstruction/fuse_cell.py:397`, `scripts/reconstruction/splat_train.py:57`, `scripts/reconstruction/block_report.py:73`, `scripts/gsplat_train/export_gsplat_dataset.py:75`

**Interfaces:**
- Produces: `r2_store.scrubbed_key_for(raw_key: str) -> str` (raises `ValueError` on keys not under `raw/`); `r2_store.object_exists(key: str) -> bool`; `r2_store.download_video_bytes(raw_key: str) -> bytes` (prefers scrubbed rendition when `PREFER_SCRUBBED_VIDEO != '0'`, R2 configured, and the scrubbed object exists; always falls back to the raw key).

- [ ] **Step 1: Write the failing tests**

```python
# scripts/reconstruction/tests/test_scrubbed_preference.py
"""The scrubbed-rendition naming convention (privacy scrub pass, PRD
docs/prd/scan-session-replay-devops-prd.md decision #2): scrubbed key =
raw key with the leading 'raw/' swapped for 'scrubbed/'. Consumers prefer
the scrubbed rendition when it exists."""
import pytest

import r2_store


RAW = "raw/464650:-721828/env-1783640498732-0cgku8/capture/chunk-0.mov"
SCRUBBED = "scrubbed/464650:-721828/env-1783640498732-0cgku8/capture/chunk-0.mov"


def test_scrubbed_key_swaps_only_the_leading_prefix():
    assert r2_store.scrubbed_key_for(RAW) == SCRUBBED


def test_scrubbed_key_rejects_non_raw_keys():
    with pytest.raises(ValueError):
        r2_store.scrubbed_key_for("reconstructed/464650:-721828/1/splat.ply")
    with pytest.raises(ValueError):
        # 'raw/' must be a whole path segment at the start, not a substring
        r2_store.scrubbed_key_for("rawish/x/chunk-0.mov")


def test_download_prefers_scrubbed_when_it_exists(monkeypatch):
    monkeypatch.setenv("PREFER_SCRUBBED_VIDEO", "1")
    monkeypatch.setattr(r2_store, "is_configured", lambda: True)
    monkeypatch.setattr(r2_store, "object_exists", lambda key: key == SCRUBBED)
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: key.encode())
    assert r2_store.download_video_bytes(RAW) == SCRUBBED.encode()


def test_download_falls_back_to_raw_when_no_scrubbed_rendition(monkeypatch):
    monkeypatch.setenv("PREFER_SCRUBBED_VIDEO", "1")
    monkeypatch.setattr(r2_store, "is_configured", lambda: True)
    monkeypatch.setattr(r2_store, "object_exists", lambda key: False)
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: key.encode())
    assert r2_store.download_video_bytes(RAW) == RAW.encode()


def test_download_kill_switch(monkeypatch):
    monkeypatch.setenv("PREFER_SCRUBBED_VIDEO", "0")
    monkeypatch.setattr(r2_store, "is_configured", lambda: True)
    monkeypatch.setattr(r2_store, "object_exists", lambda key: (_ for _ in ()).throw(AssertionError("must not HEAD when disabled")))
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: key.encode())
    assert r2_store.download_video_bytes(RAW) == RAW.encode()


def test_download_skips_head_when_r2_unconfigured(monkeypatch):
    """Unit tests of consumers monkeypatch download_bytes without configuring
    R2 — the preference probe must not raise R2NotConfiguredError there."""
    monkeypatch.delenv("PREFER_SCRUBBED_VIDEO", raising=False)
    monkeypatch.setattr(r2_store, "is_configured", lambda: False)
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: key.encode())
    assert r2_store.download_video_bytes(RAW) == RAW.encode()
```

- [ ] **Step 2: Run to verify failure** — `cd scripts/reconstruction && source .venv/bin/activate && python -m pytest tests/test_scrubbed_preference.py -v` → FAIL (`AttributeError: scrubbed_key_for`).

- [ ] **Step 3: Implement in `r2_store.py`** (append; also extend the module docstring's namespace note to mention `scrubbed/`):

```python
SCRUBBED_PREFIX = "scrubbed/"
_RAW_PREFIX = "raw/"


def scrubbed_key_for(raw_key: str) -> str:
    """Privacy-scrubbed rendition key for a raw capture artifact: same key
    with the leading 'raw/' segment swapped for 'scrubbed/'. This is THE
    naming contract the review UI and this pipeline share — change it in
    lockstep with scripts/privacy_scrub/ and any UI preference logic."""
    if not raw_key.startswith(_RAW_PREFIX):
        raise ValueError(f"not a raw/ capture key: {raw_key!r}")
    return SCRUBBED_PREFIX + raw_key[len(_RAW_PREFIX):]


def object_exists(key: str) -> bool:
    c = client()
    try:
        c.head_object(Bucket=bucket_name(), Key=key)
        return True
    except c.exceptions.ClientError:
        return False


def download_video_bytes(raw_key: str) -> bytes:
    """Download a capture video chunk, preferring its privacy-scrubbed
    rendition when one exists (PRD scan-session-replay decision #2: scrubbed
    imagery feeds reconstruction and review; raw stays admin-only).
    PREFER_SCRUBBED_VIDEO=0 is the kill switch."""
    if os.environ.get("PREFER_SCRUBBED_VIDEO", "1") != "0" and is_configured():
        try:
            candidate = scrubbed_key_for(raw_key)
        except ValueError:
            candidate = None
        if candidate and object_exists(candidate):
            return download_bytes(candidate)
    return download_bytes(raw_key)
```

- [ ] **Step 4: Run tests** — same command → 6 PASS.

- [ ] **Step 5: Switch the four consumers.** In each file replace exactly `r2_store.download_bytes(kinds["mov"])` with `r2_store.download_video_bytes(kinds["mov"])` (fuse_cell.py:397, splat_train.py:57, block_report.py:73, gsplat_train/export_gsplat_dataset.py:75).

- [ ] **Step 6: Run the full reconstruction suite** — `python -m pytest tests/ -v` → all pass (existing fuse tests monkeypatch `download_bytes` with R2 unconfigured; the `is_configured()` guard keeps them green). If any test monkeypatches per-key download maps, confirm mov keys still resolve via fallback.

- [ ] **Step 7: Commit** — `git add -A && git commit -m "feat(scrub): scrubbed-rendition naming contract + preferred download in r2_store"`.

---

### Task 2: `scripts/privacy_scrub/` scaffold + venv

**Files:**
- Create: `scripts/privacy_scrub/setup.sh`, `scripts/privacy_scrub/requirements.txt`, `scripts/privacy_scrub/README.md` (skeleton; filled in Task 6)

**Interfaces:**
- Produces: a working `.venv` with CUDA torch on sm_121, ultralytics, av, cv2, boto3, psycopg; COCO weights at `/home/codex450/models/ultralytics/yolo11m.pt`.

- [ ] **Step 1: Write `requirements.txt`**

```
ultralytics>=8.3
opencv-python-headless>=4.10
av>=13
boto3>=1.34
psycopg[binary]>=3.1
numpy
```

- [ ] **Step 2: Write `setup.sh`** (mirrors `scripts/gsplat_train/setup.sh`)

```bash
#!/usr/bin/env bash
# GB10 (sm_121): stable torch wheels lack kernels; nightly cu130 carries them
# (same recipe as ../gsplat_train/setup.sh — see the cdx-dgx-spark skill).
set -euo pipefail
cd "$(dirname "$0")"
uv venv .venv
VIRTUAL_ENV=$PWD/.venv uv pip install --pre torch torchvision --index-url https://download.pytorch.org/whl/nightly/cu130
VIRTUAL_ENV=$PWD/.venv uv pip install -r requirements.txt
# Weights live in the central model repo, never in the project tree.
MODELS_DIR=/home/codex450/models/ultralytics
mkdir -p "$MODELS_DIR"
if [ ! -f "$MODELS_DIR/yolo11m.pt" ]; then
  (cd "$MODELS_DIR" && "$OLDPWD/.venv/bin/python" -c "from ultralytics import YOLO; YOLO('yolo11m.pt')")
fi
.venv/bin/python - <<'EOF'
import torch
assert torch.cuda.is_available(), "CUDA not available — wrong torch wheel?"
print("torch", torch.__version__, "cap", torch.cuda.get_device_capability(0))
EOF
```

- [ ] **Step 3: Run it** — `bash scripts/privacy_scrub/setup.sh` → torch prints `cap (12, 1)`; `yolo11m.pt` present in `~/models/ultralytics/`.

- [ ] **Step 4: Commit** — `git add scripts/privacy_scrub && git commit -m "chore(scrub): privacy_scrub venv scaffold (torch nightly cu130 for sm_121)"`.

---

### Task 3: Scrub core (`scrub_video.py`) — dilation, pixelation, pts-preserving re-encode

**Files:**
- Create: `scripts/privacy_scrub/scrub_video.py`
- Test: `scripts/privacy_scrub/tests/test_scrub_video.py`

**Interfaces:**
- Produces:
  - `Box = tuple[int, int, int, int]` (x1, y1, x2, y2 pixel coords) with a `label: str` and `conf: float` via `Detection(box, label, conf)` dataclass.
  - `dilate_detections(per_frame: list[list[Detection]], radius: int) -> list[list[Detection]]` — each frame's effective set is the union of detections within ±`radius` frames.
  - `pad_box(box: Box, frac: float, w: int, h: int) -> Box` — grow each side by `frac` of the box's own size, clamped to the image.
  - `pixelate_regions(frame_bgr: np.ndarray, boxes: list[Box], cell_px: int = 14) -> np.ndarray` — mosaic + Gaussian inside boxes, untouched outside.
  - `scrub_video_bytes(video_bytes: bytes, detect_frames: Callable[[list[np.ndarray]], list[list[Detection]]], *, dilation_radius: int = 6, pad_frac: float = 0.08, detect_batch: int = 16) -> ScrubOutput` where `ScrubOutput` has `.video_bytes: bytes`, `.frame_count: int`, `.frames_with_detections: int`, `.detections_by_label: dict[str, int]`. Two passes over the input: (1) decode → batched `detect_frames`; (2) decode again → blur with dilated+padded boxes → encode H.264 (`libx264`, `yuv420p`, mov container) copying each source frame's `pts` and the source stream's `time_base`, `width`, `height`, and `PIX_FMT` conversion via frame.to_ndarray(format="bgr24") / from_ndarray.
- Consumes: nothing from other tasks (detector injected).

- [ ] **Step 1: Write the failing tests** (synthetic 48-frame 320×240 video via PyAV; fake detector returns a fixed box on frames 10–20)

```python
# scripts/privacy_scrub/tests/test_scrub_video.py
import io

import av
import numpy as np
import pytest

import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from scrub_video import Detection, dilate_detections, pad_box, pixelate_regions, scrub_video_bytes


def _make_video(n_frames=48, w=320, h=240, fps=24) -> bytes:
    buf = io.BytesIO()
    container = av.open(buf, "w", format="mov")
    stream = container.add_stream("libx264", rate=fps)
    stream.width, stream.height, stream.pix_fmt = w, h, "yuv420p"
    for i in range(n_frames):
        img = np.full((h, w, 3), 32, dtype=np.uint8)
        # high-frequency texture so blur is measurable
        img[:, ::4] = 255
        img[10:40, 10:60] = (i * 5) % 255
        frame = av.VideoFrame.from_ndarray(img, format="bgr24")
        for f in frame.reformat(format="yuv420p"),:
            container.mux(stream.encode(f))
    container.mux(stream.encode())
    container.close()
    return buf.getvalue()


def _decode_frames(video_bytes):
    with av.open(io.BytesIO(video_bytes)) as c:
        return [f.to_ndarray(format="bgr24") for f in c.decode(video=0)]


def _decode_rebased_pts(video_bytes):
    with av.open(io.BytesIO(video_bytes)) as c:
        stream = c.streams.video[0]
        pts = [float(f.pts * stream.time_base) for f in c.decode(video=0)]
    return [p - pts[0] for p in pts]


BOX = (100, 60, 220, 180)


def _fake_detector(frames_seen):
    def detect(batch):
        out = []
        for _ in batch:
            idx = len(frames_seen)
            frames_seen.append(idx)
            out.append([Detection(BOX, "person", 0.9)] if 10 <= idx <= 20 else [])
        return out
    return detect


def test_pad_box_grows_and_clamps():
    assert pad_box((10, 10, 20, 20), 0.5, 100, 100) == (5, 5, 25, 25)
    assert pad_box((0, 0, 100, 100), 0.5, 100, 100) == (0, 0, 100, 100)


def test_dilate_detections_bridges_gaps():
    per_frame = [[] for _ in range(10)]
    per_frame[5] = [Detection(BOX, "car", 0.8)]
    dilated = dilate_detections(per_frame, radius=2)
    assert [len(d) for d in dilated] == [0, 0, 0, 1, 1, 1, 1, 1, 0, 0]


def test_pixelate_regions_only_touches_boxes():
    img = (np.random.default_rng(0).integers(0, 255, (240, 320, 3))).astype(np.uint8)
    out = pixelate_regions(img.copy(), [BOX])
    x1, y1, x2, y2 = BOX
    inside_before = img[y1:y2, x1:x2].astype(int)
    inside_after = out[y1:y2, x1:x2].astype(int)
    assert np.abs(inside_before - inside_after).mean() > 5      # visibly changed
    assert inside_after.std() < inside_before.std() * 0.6       # detail destroyed
    mask = np.ones(img.shape[:2], bool); mask[y1:y2, x1:x2] = False
    assert np.array_equal(out[mask], img[mask])                 # untouched outside


def test_scrub_video_blurs_detected_frames_and_preserves_timing():
    src = _make_video()
    result = scrub_video_bytes(src, _fake_detector([]), dilation_radius=2, pad_frac=0.0)
    src_frames, out_frames = _decode_frames(src), _decode_frames(result.video_bytes)
    assert len(out_frames) == len(src_frames) == result.frame_count
    assert _decode_rebased_pts(result.video_bytes) == pytest.approx(_decode_rebased_pts(src), abs=1e-6)
    x1, y1, x2, y2 = BOX
    def roi_std(f): return f[y1:y2, x1:x2].std()
    # dilated window 8..22 blurred; frame 0 and last untouched-ish (lossy re-encode: compare detail, not equality)
    assert roi_std(out_frames[15]) < roi_std(src_frames[15]) * 0.6
    assert roi_std(out_frames[0]) > roi_std(src_frames[0]) * 0.8
    assert result.frames_with_detections == 11
    assert result.detections_by_label == {"person": 11}


def test_scrub_video_no_detections_still_reencodes():
    src = _make_video(n_frames=12)
    result = scrub_video_bytes(src, lambda batch: [[] for _ in batch])
    assert result.frame_count == 12 and result.frames_with_detections == 0
    assert len(_decode_frames(result.video_bytes)) == 12
```

- [ ] **Step 2: Run to verify failure** — `cd scripts/privacy_scrub && .venv/bin/python -m pytest tests/ -v` → FAIL (no module `scrub_video`).

- [ ] **Step 3: Implement `scrub_video.py`**

```python
"""Privacy scrub core: pixelate+blur detected regions in a capture-chunk
video, preserving frame timing exactly (chunk_io.extract_video_frames_at
rebases pts by the stream's first pts — scrubbed renditions must carry the
same pts/time_base so depth↔video matching is unchanged for reconstruction).

Detector-agnostic: detection is an injected callable so this module needs
no GPU/network and is fully unit-testable. Two passes over the (in-memory)
input keep peak memory at one frame + boxes instead of the whole clip.
"""
from __future__ import annotations

import io
from dataclasses import dataclass, field
from typing import Callable

import av
import cv2
import numpy as np

Box = tuple[int, int, int, int]


@dataclass(frozen=True)
class Detection:
    box: Box
    label: str
    conf: float


@dataclass
class ScrubOutput:
    video_bytes: bytes
    frame_count: int
    frames_with_detections: int
    detections_by_label: dict[str, int] = field(default_factory=dict)


def pad_box(box: Box, frac: float, w: int, h: int) -> Box:
    x1, y1, x2, y2 = box
    dx, dy = int((x2 - x1) * frac), int((y2 - y1) * frac)
    return (max(0, x1 - dx), max(0, y1 - dy), min(w, x2 + dx), min(h, y2 + dy))


def dilate_detections(per_frame: list[list[Detection]], radius: int) -> list[list[Detection]]:
    """Frame i's effective detections = union of raw detections on frames
    i-radius..i+radius. Bridges detector flicker; privacy errs to over-blur."""
    n = len(per_frame)
    out: list[list[Detection]] = []
    for i in range(n):
        merged: list[Detection] = []
        for j in range(max(0, i - radius), min(n, i + radius + 1)):
            merged.extend(per_frame[j])
        out.append(merged)
    return out


def pixelate_regions(frame_bgr: np.ndarray, boxes: list[Box], cell_px: int = 14) -> np.ndarray:
    h, w = frame_bgr.shape[:2]
    for x1, y1, x2, y2 in boxes:
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(w, x2), min(h, y2)
        if x2 - x1 < 2 or y2 - y1 < 2:
            continue
        roi = frame_bgr[y1:y2, x1:x2]
        small = cv2.resize(roi, (max(1, (x2 - x1) // cell_px), max(1, (y2 - y1) // cell_px)),
                           interpolation=cv2.INTER_LINEAR)
        mosaic = cv2.resize(small, (x2 - x1, y2 - y1), interpolation=cv2.INTER_NEAREST)
        frame_bgr[y1:y2, x1:x2] = cv2.GaussianBlur(mosaic, (0, 0), sigmaX=3)
    return frame_bgr


def scrub_video_bytes(
    video_bytes: bytes,
    detect_frames: Callable[[list[np.ndarray]], list[list[Detection]]],
    *,
    dilation_radius: int = 6,
    pad_frac: float = 0.08,
    detect_batch: int = 16,
) -> ScrubOutput:
    # Pass 1: decode + detect (batched), keep only boxes.
    per_frame: list[list[Detection]] = []
    with av.open(io.BytesIO(video_bytes)) as container:
        batch: list[np.ndarray] = []
        for frame in container.decode(video=0):
            batch.append(frame.to_ndarray(format="bgr24"))
            if len(batch) == detect_batch:
                per_frame.extend(detect_frames(batch))
                batch = []
        if batch:
            per_frame.extend(detect_frames(batch))

    effective = dilate_detections(per_frame, dilation_radius)
    by_label: dict[str, int] = {}
    for dets in per_frame:
        for d in dets:
            by_label[d.label] = by_label.get(d.label, 0) + 1

    # Pass 2: decode again, blur, re-encode preserving pts/time_base.
    out_buf = io.BytesIO()
    with av.open(io.BytesIO(video_bytes)) as src, av.open(out_buf, "w", format="mov") as dst:
        in_stream = src.streams.video[0]
        out_stream = dst.add_stream("libx264")
        out_stream.width = in_stream.codec_context.width
        out_stream.height = in_stream.codec_context.height
        out_stream.pix_fmt = "yuv420p"
        out_stream.time_base = in_stream.time_base
        out_stream.codec_context.time_base = in_stream.time_base
        out_stream.options = {"crf": "23", "preset": "medium"}
        w, h = out_stream.width, out_stream.height
        for i, frame in enumerate(src.decode(video=0)):
            img = frame.to_ndarray(format="bgr24")
            dets = effective[i] if i < len(effective) else []
            if dets:
                boxes = [pad_box(d.box, pad_frac, w, h) for d in dets]
                img = pixelate_regions(img, boxes)
            out = av.VideoFrame.from_ndarray(img, format="bgr24")
            out.pts = frame.pts
            out.time_base = frame.time_base
            dst.mux(out_stream.encode(out))
        dst.mux(out_stream.encode())

    return ScrubOutput(
        video_bytes=out_buf.getvalue(),
        frame_count=len(per_frame),
        frames_with_detections=sum(1 for d in per_frame if d),
        detections_by_label=by_label,
    )
```

- [ ] **Step 4: Run tests** — `.venv/bin/python -m pytest tests/ -v` → 5 PASS. If the encoder rejects raw pts pass-through (B-frame reordering), pin `out_stream.options["bf"] = "0"` and `g` (GOP) to 30 — re-run until the pts-equivalence test passes.

- [ ] **Step 5: Commit** — `git add scripts/privacy_scrub && git commit -m "feat(scrub): detector-agnostic pixelate+blur core with pts-preserving re-encode"`.

---

### Task 4: YOLO detector wrapper (`detector.py`)

**Files:**
- Create: `scripts/privacy_scrub/detector.py`

**Interfaces:**
- Consumes: `Detection` from `scrub_video`.
- Produces: `PrivacyDetector(coco_weights=Path('~/models/ultralytics/yolo11m.pt'), face_weights=Path('~/models/ultralytics/bbox/face_yolov8m.pt'), conf=0.25, imgsz=1280)` with `.detect_frames(frames: list[np.ndarray]) -> list[list[Detection]]` (BGR in). COCO classes kept: person(0), bicycle(1), car(2), motorcycle(3), bus(5), truck(7); labels come from the model's names map, faces labeled `face`. Also `.qc_counts(frames) -> dict[str, int]` for post-scrub QC at conf 0.4.

- [ ] **Step 1: Implement**

```python
"""YOLO-based privacy detector for scan chunks: one COCO pass (person +
vehicle classes) + one face pass (belt-and-braces for partially-visible
people). Weights live in the central model repo (~/models/ultralytics),
never in the project tree. GB10: torch nightly venv (see setup.sh)."""
from __future__ import annotations

from pathlib import Path

import numpy as np
from ultralytics import YOLO

from scrub_video import Detection

MODELS_DIR = Path("/home/codex450/models/ultralytics")
PRIVACY_COCO_CLASSES = [0, 1, 2, 3, 5, 7]  # person bicycle car motorcycle bus truck


class PrivacyDetector:
    def __init__(self, coco_weights: Path = MODELS_DIR / "yolo11m.pt",
                 face_weights: Path | None = MODELS_DIR / "bbox" / "face_yolov8m.pt",
                 conf: float = 0.25, imgsz: int = 1280, device: str | None = None):
        self.conf, self.imgsz, self.device = conf, imgsz, device
        self.coco = YOLO(str(coco_weights))
        self.face = YOLO(str(face_weights)) if face_weights and Path(face_weights).exists() else None

    def _run(self, model, frames, classes=None, label_override=None):
        results = model.predict(frames, conf=self.conf, imgsz=self.imgsz,
                                classes=classes, device=self.device, verbose=False)
        out = []
        for r in results:
            dets = []
            for b in r.boxes:
                x1, y1, x2, y2 = (int(v) for v in b.xyxy[0].tolist())
                label = label_override or model.names[int(b.cls[0])]
                dets.append(Detection((x1, y1, x2, y2), label, float(b.conf[0])))
            out.append(dets)
        return out

    def detect_frames(self, frames: list[np.ndarray]) -> list[list[Detection]]:
        merged = self._run(self.coco, frames, classes=PRIVACY_COCO_CLASSES)
        if self.face is not None:
            for dets, faces in zip(merged, self._run(self.face, frames, label_override="face")):
                dets.extend(faces)
        return merged

    def qc_counts(self, frames: list[np.ndarray], conf: float = 0.4) -> dict[str, int]:
        counts: dict[str, int] = {}
        for r in self.coco.predict(frames, conf=conf, imgsz=self.imgsz,
                                   classes=PRIVACY_COCO_CLASSES, device=self.device, verbose=False):
            for b in r.boxes:
                name = self.coco.names[int(b.cls[0])]
                counts[name] = counts.get(name, 0) + 1
        return counts
```

- [ ] **Step 2: GPU smoke test on a real frame** (not a pytest — needs GPU + weights):

```bash
cd scripts/privacy_scrub && .venv/bin/python - <<'EOF'
import numpy as np
from detector import PrivacyDetector
det = PrivacyDetector()
dets = det.detect_frames([np.zeros((720, 1280, 3), np.uint8)])
print("empty-frame detections:", dets[0])  # expect []
EOF
```
Expected: `[]`, no CUDA errors.

- [ ] **Step 3: Commit** — `git commit -am "feat(scrub): YOLO privacy detector (COCO person+vehicles, face pass)"`.

---

### Task 5: Worker CLI (`scrub_worker.py`)

**Files:**
- Create: `scripts/privacy_scrub/scrub_worker.py`

**Interfaces:**
- Consumes: `scrub_video.scrub_video_bytes`, `detector.PrivacyDetector`, `r2_store` (imported from `../reconstruction` via `sys.path`).
- Produces: CLI `python scrub_worker.py --session ID [--session ID2 …] | --cell CELL | --sweep [--limit N]` with `--force`, `--dry-run`. Per chunk: skips if scrubbed rendition exists (unless `--force`); per session: uploads `scrub-report.json` (schema below). Sweep needs `DATABASE_URL`.

Report schema (one per session, key `scrubbed/{cell}/{sessionId}/capture/scrub-report.json`):

```json
{
  "schema": 1,
  "sessionId": "…", "locationCellId": "…",
  "scrubbedAt": "2026-07-12T…Z",
  "detector": {"coco": "yolo11m.pt", "face": "face_yolov8m.pt", "conf": 0.25, "imgsz": 1280,
               "dilationRadius": 6, "padFrac": 0.08},
  "chunks": [{"rawKey": "…chunk-0.mov", "scrubbedKey": "…", "frames": 1800,
              "framesWithDetections": 412, "detectionsByLabel": {"person": 512, "car": 1210},
              "qcResidualDetections": {"car": 2}, "bytes": 21034567}]
}
```

- [ ] **Step 1: Implement**

```python
"""Privacy scrub worker — PRD docs/prd/scan-session-replay-devops-prd.md
decision #2 (DGX ingest side). For each raw video capture chunk, produce a
bystander/vehicle-blurred rendition at r2_store.scrubbed_key_for(raw_key)
and a per-session scrub-report.json. Idempotent on rendition existence.

Env: R2_ACCOUNT_ID/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY/R2_BUCKET_NAME
     (+ DATABASE_URL for --sweep/--cell/--session index lookups)
     — same set as recon_worker.py; source backend/deploy/.env.

Usage:
  python scrub_worker.py --session env-…-0cgku8 [--session …] [--force] [--dry-run]
  python scrub_worker.py --cell 464650:-721828
  python scrub_worker.py --sweep --limit 10
"""
from __future__ import annotations

import argparse
import datetime as dt
import io
import json
import os
import sys
from pathlib import Path

import av
import numpy as np
import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "reconstruction"))
import r2_store  # noqa: E402

from detector import PrivacyDetector  # noqa: E402
from scrub_video import scrub_video_bytes  # noqa: E402

MOV_CHUNKS_SQL = """
SELECT r2_key, session_id, location_cell_id FROM capture_chunks
WHERE format = 'mov' {where}
ORDER BY session_id, r2_key
"""


def _index_chunks(where: str, params: tuple) -> list[tuple[str, str, str]]:
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL required to query the scan index")
    with psycopg.connect(dsn) as conn:
        return conn.execute(MOV_CHUNKS_SQL.format(where=where), params).fetchall()


def _sample_frames(video_bytes: bytes, n: int = 8) -> list[np.ndarray]:
    with av.open(io.BytesIO(video_bytes)) as c:
        frames = [f.to_ndarray(format="bgr24") for f in c.decode(video=0)]
    if not frames:
        return []
    step = max(1, len(frames) // n)
    return frames[::step][:n]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--session", action="append", default=[])
    ap.add_argument("--cell")
    ap.add_argument("--sweep", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--dilation-radius", type=int, default=6)
    ap.add_argument("--pad-frac", type=float, default=0.08)
    args = ap.parse_args()

    if args.session:
        rows = _index_chunks("AND session_id = ANY(%s)", (args.session,))
    elif args.cell:
        rows = _index_chunks("AND location_cell_id = %s", (args.cell,))
    elif args.sweep:
        rows = _index_chunks("", ())
    else:
        ap.error("one of --session/--cell/--sweep required")

    todo: list[tuple[str, str, str]] = []
    for raw_key, session_id, cell in rows:
        if not args.force and r2_store.object_exists(r2_store.scrubbed_key_for(raw_key)):
            continue
        todo.append((raw_key, session_id, cell))
        if args.limit and len(todo) >= args.limit:
            break
    print(f"{len(todo)} chunk(s) to scrub ({len(rows)} indexed)")
    if args.dry_run:
        for raw_key, *_ in todo:
            print("would scrub:", raw_key, "->", r2_store.scrubbed_key_for(raw_key))
        return

    det = PrivacyDetector()
    reports: dict[str, dict] = {}
    for raw_key, session_id, cell in todo:
        print(f"[{session_id}] {raw_key}")
        video = r2_store.download_bytes(raw_key)
        result = scrub_video_bytes(video, det.detect_frames,
                                   dilation_radius=args.dilation_radius, pad_frac=args.pad_frac)
        scrub_key = r2_store.scrubbed_key_for(raw_key)
        qc = det.qc_counts(_sample_frames(result.video_bytes))
        r2_store.upload_bytes(scrub_key, result.video_bytes, "video/quicktime")
        print(f"  {result.frame_count} frames, {result.frames_with_detections} with detections "
              f"{result.detections_by_label}; QC residual {qc}; "
              f"{len(result.video_bytes)/1e6:.1f}MB -> {scrub_key}")
        rep = reports.setdefault(session_id, {
            "schema": 1, "sessionId": session_id, "locationCellId": cell,
            "scrubbedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "detector": {"coco": "yolo11m.pt", "face": "face_yolov8m.pt",
                         "conf": det.conf, "imgsz": det.imgsz,
                         "dilationRadius": args.dilation_radius, "padFrac": args.pad_frac},
            "chunks": [],
        })
        rep["chunks"].append({
            "rawKey": raw_key, "scrubbedKey": scrub_key,
            "frames": result.frame_count,
            "framesWithDetections": result.frames_with_detections,
            "detectionsByLabel": result.detections_by_label,
            "qcResidualDetections": qc, "bytes": len(result.video_bytes),
        })

    for session_id, rep in reports.items():
        first_chunk_key = rep["chunks"][0]["scrubbedKey"]
        report_key = first_chunk_key.rsplit("/", 1)[0] + "/scrub-report.json"
        r2_store.upload_bytes(report_key, json.dumps(rep, indent=2).encode(), "application/json")
        print(f"[{session_id}] report -> {report_key}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Dry-run against the real index** — `set -a; source ../../backend/deploy/.env; set +a; .venv/bin/python scrub_worker.py --sweep --dry-run` → lists the 5 indexed mov chunks with their scrubbed keys, no writes.

- [ ] **Step 3: Commit** — `git commit -am "feat(scrub): scrub worker CLI (session/cell/sweep, idempotent, per-session report)"`.

---

### Task 6: Real-data run + verification + docs

**Files:**
- Modify: `scripts/privacy_scrub/README.md`, `scripts/reconstruction/README.md`

- [ ] **Step 1: Scrub the three real sessions with video** (from the scan index: `env-1783640498732-0cgku8` 3 chunks, `env-1783470359293-i3tt1m` 1 chunk, `env-1783540639220-6nfcxr` 1 chunk):

```bash
cd scripts/privacy_scrub && set -a; source ../../backend/deploy/.env; set +a
.venv/bin/python scrub_worker.py \
  --session env-1783640498732-0cgku8 --session env-1783470359293-i3tt1m --session env-1783540639220-6nfcxr
```
Expected: 5 renditions + 3 reports uploaded under `scrubbed/…`; per-chunk detection counts printed; QC residual near-zero.

- [ ] **Step 2: Verify the timing contract on a real pair** — decode original + scrubbed `chunk-0.mov` of `0cgku8` with `chunk_io.extract_video_frames_at`-style rebasing; assert equal frame counts and rebased-pts within 1ms; extract 3 matched frames from each and save side-by-side JPEGs to `$CLAUDE_JOB_DIR/tmp/scrub-qc/` for human review.

- [ ] **Step 3: Verify consumer preference end-to-end** — with env sourced, run a python snippet: `r2_store.download_video_bytes(raw_key)` returns bytes whose size matches the scrubbed object (not the raw one); with `PREFER_SCRUBBED_VIDEO=0` it returns the raw object's size.

- [ ] **Step 4: Idempotence check** — re-run the Step-1 command → `0 chunk(s) to scrub`.

- [ ] **Step 5: Write `scripts/privacy_scrub/README.md`** — the naming contract, report schema, model/threshold rationale, ops runbook (setup.sh, worker invocations, kill switch), and the follow-ups (capture_chunks.scrubbed_r2_key column + indexer/report awareness; photo artifacts scrub; plate-specific detector if whole-vehicle blur is ever relaxed). Add a short "Privacy scrub stage" section to `scripts/reconstruction/README.md` pointing at it.

- [ ] **Step 6: Run both test suites once more** — reconstruction (`python -m pytest tests/ -v` in its venv) and privacy_scrub (`.venv/bin/python -m pytest tests/ -v`) → green.

- [ ] **Step 7: Commit** — `git commit -am "docs(scrub): privacy scrub runbook + reconstruction README stage note"`.

---

### Task 7: Ship

- [ ] Push branch `worktree-privacy-scrub-pass`, open draft PR referencing the PRD (`docs/prd/scan-session-replay-devops-prd.md` on `scan-replay-plan`) decision #2, with the real-session QC numbers in the body.

## Self-Review

- **Spec coverage:** bystanders (person boxes + face pass) ✔; vehicles incl. plates (whole-vehicle blur covers plates) ✔; DGX ingest home ✔; before reconstruction/review (all four video consumers now prefer scrubbed) ✔; alongside originals with a UI-preferable convention (`raw/`→`scrubbed/`, same suffix + HEAD probe; machine-readable report) ✔; real sessions from the scan index (Task 6, 3 sessions / 5 chunks) ✔.
- **Placeholder scan:** none — all steps carry code/commands.
- **Type consistency:** `Detection(box, label, conf)` and `detect_frames(list[np.ndarray]) -> list[list[Detection]]` used identically in Tasks 3–5; `scrubbed_key_for`/`object_exists`/`download_video_bytes` names match between Tasks 1 and 5.
