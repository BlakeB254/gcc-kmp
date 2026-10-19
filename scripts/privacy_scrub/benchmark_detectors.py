"""Old-vs-new privacy detector benchmark on RAW capture chunks.

Privacy: frames are decoded in memory on this machine only, never written to
disk, never uploaded; nothing but box statistics is printed.

Reference = the Ultralytics pair (YOLO11m COCO + face_yolov8m, the
SCRUB_DETECTOR=yolo path). Ultralytics is AGPL-3.0 and lives only in
requirements-yolo.txt; this benchmark needs BOTH backends, so run it from a
scratch venv with requirements-yolo.txt and requirements-rfdetr.txt
installed — never from the cloud image, which carries rfdetr only.
Candidates = detector.PrivacyDetector variants (RF-DETR + YuNet).

For each candidate:
  missed     reference boxes with IoU < 0.3 against every candidate box
  uncovered  reference boxes less than 50% covered by the union of candidate
             boxes (the privacy-relevant miss: blur is applied per pixel)
  new_only   candidate boxes with IoU < 0.3 against every reference box
  blur_area  mean fraction of the frame inside a (padded) box — must not be
             materially below the reference's
  ms/frame   detection wall time per frame (batch of --batch)

  python benchmark_detectors.py --key raw/... --key raw/... --frames 200 \
      --candidate large --candidate large@1024 --candidate medium
"""
from __future__ import annotations

import argparse
import io
import json
import os
import sys
import time
from pathlib import Path

import av
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "reconstruction"))
import r2_store  # noqa: E402
from gpu_admission import GpuReservation  # noqa: E402
from scrub_video import Detection, pad_box  # noqa: E402

PAD_FRAC = 0.08


class LazyFrames:
    """Frames kept JPEG-encoded (q95) in memory and decoded on access: ~40 MB
    for 200 frames instead of ~740 MB, which matters on a shared GB10."""

    def __init__(self):
        self._jpegs: list[bytes] = []

    def append(self, frame: np.ndarray) -> None:
        import cv2  # noqa: PLC0415

        self._jpegs.append(cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 95])[1].tobytes())

    def __len__(self):
        return len(self._jpegs)

    def __getitem__(self, i):
        import cv2  # noqa: PLC0415

        if isinstance(i, slice):
            return [self[j] for j in range(*i.indices(len(self)))]
        return cv2.imdecode(np.frombuffer(self._jpegs[i], np.uint8), cv2.IMREAD_COLOR)

    def __iter__(self):
        return (self[i] for i in range(len(self)))


def sample_frames(keys: list[str], total: int) -> LazyFrames:
    per_key = max(1, total // len(keys))
    frames = LazyFrames()
    for key in keys:
        data = r2_store.download_bytes(key)
        with av.open(io.BytesIO(data)) as c:
            st = c.streams.video[0]
            n = st.frames or int(float(st.duration * st.time_base) * float(st.average_rate))
            step = max(1, n // per_key)
            got = 0
            for i, f in enumerate(c.decode(video=0)):
                if i % step == 0 and got < per_key:
                    frames.append(f.to_ndarray(format="bgr24"))
                    got += 1
        del data
        print(f"sampled {got} frames from chunk {keys.index(key) + 1}/{len(keys)}", flush=True)
    return frames


def iou(a, b) -> float:
    ix1, iy1, ix2, iy2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, ix2 - ix1) * max(0, iy2 - iy1)
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def coverage(box, others, shape) -> float:
    x1, y1, x2, y2 = box
    if x2 <= x1 or y2 <= y1:
        return 1.0
    mask = np.zeros((y2 - y1, x2 - x1), dtype=bool)
    for o in others:
        ox1, oy1 = max(o[0], x1) - x1, max(o[1], y1) - y1
        ox2, oy2 = min(o[2], x2) - x1, min(o[3], y2) - y1
        if ox2 > ox1 and oy2 > oy1:
            mask[oy1:oy2, ox1:ox2] = True
    return float(mask.mean())


def blur_fraction(dets: list[Detection], shape) -> float:
    h, w = shape[:2]
    mask = np.zeros((h, w), dtype=bool)
    for d in dets:
        x1, y1, x2, y2 = pad_box(d.box, PAD_FRAC, w, h)
        mask[max(0, y1):y2, max(0, x1):x2] = True
    return float(mask.mean())


def run_detector(detect, frames, batch) -> tuple[list[list[Detection]], float]:
    out: list[list[Detection]] = []
    t0 = time.perf_counter()
    for i in range(0, len(frames), batch):
        out.extend(detect(frames[i:i + batch]))
    return out, (time.perf_counter() - t0) * 1000 / max(1, len(frames))


def reference_detector(device):
    from ultralytics import YOLO  # noqa: PLC0415 — AGPL, benchmark only

    coco = YOLO("/home/codex450/models/specialty/detection/yolo11m.pt")
    face = YOLO("/home/codex450/models/ultralytics/bbox/face_yolov8m.pt")

    def run(model, frames, classes=None, label=None):
        res = model.predict(frames, conf=0.25, imgsz=1280, classes=classes, device=device, verbose=False)
        return [[Detection(tuple(int(v) for v in b.xyxy[0].tolist()),
                           label or model.names[int(b.cls[0])], float(b.conf[0])) for b in r.boxes]
                for r in res]

    def detect(frames):
        merged = run(coco, frames, classes=[0, 1, 2, 3, 5, 7])
        for dets, faces in zip(merged, run(face, frames, label="face")):
            dets.extend(faces)
        return merged

    return detect


def compare(ref, cand, frames) -> dict:
    stats = {"ref_boxes": 0, "cand_boxes": 0, "missed": 0, "uncovered": 0, "new_only": 0,
             "ref_faces": 0, "cand_faces": 0, "missed_faces_uncovered": 0,
             "uncovered_by_label": {}, "uncovered_conf": [],
             "ref_blur": 0.0, "cand_blur": 0.0, "frames_ref_has_cand_none": 0}
    for r, c, f in zip(ref, cand, frames):
        rb, cb = [d.box for d in r], [d.box for d in c]
        stats["ref_boxes"] += len(r)
        stats["cand_boxes"] += len(c)
        stats["ref_faces"] += sum(d.label == "face" for d in r)
        stats["cand_faces"] += sum(d.label == "face" for d in c)
        if r and not c:
            stats["frames_ref_has_cand_none"] += 1
        for d in r:
            if not cb or max(iou(d.box, b) for b in cb) < 0.3:
                stats["missed"] += 1
            # Padded candidate boxes = what actually gets blurred.
            padded = [pad_box(b, PAD_FRAC, f.shape[1], f.shape[0]) for b in cb]
            if coverage(d.box, padded, f.shape) < 0.5:
                stats["uncovered"] += 1
                stats["uncovered_by_label"][d.label] = stats["uncovered_by_label"].get(d.label, 0) + 1
                stats["uncovered_conf"].append(round(d.conf, 2))
                if d.label == "face":
                    stats["missed_faces_uncovered"] += 1
        for d in c:
            if not rb or max(iou(d.box, b) for b in rb) < 0.3:
                stats["new_only"] += 1
        stats["ref_blur"] += blur_fraction(r, f.shape)
        stats["cand_blur"] += blur_fraction(c, f.shape)
    n = max(1, len(frames))
    stats["ref_blur"] = round(stats["ref_blur"] / n, 4)
    stats["cand_blur"] = round(stats["cand_blur"] / n, 4)
    stats["uncovered_conf"] = sorted(stats["uncovered_conf"], reverse=True)[:25]
    return stats


def mem_available_gb() -> float:
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / 1024 / 1024
    return float("inf")


def start_memory_watchdog(min_gb: float) -> None:
    """The GB10's memory is unified and shared with the resident model: if
    this benchmark ever pushes the box under min_gb available, exit at once
    rather than become the reason something else is OOM-killed."""
    import threading  # noqa: PLC0415

    def watch():
        while True:
            avail = mem_available_gb()
            if avail < min_gb:
                print(f"ABORT: MemAvailable {avail:.1f} GB < {min_gb} GB", flush=True)
                os._exit(4)
            time.sleep(0.5)

    threading.Thread(target=watch, daemon=True).start()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--min-available-gb", type=float, default=6.0)
    ap.add_argument("--lock-wait-s", type=int, default=0,
                    help="wait this long for the shared GPU lock (default: fail if busy)")
    ap.add_argument("--key", action="append", required=True)
    ap.add_argument("--frames", type=int, default=200)
    ap.add_argument("--batch", type=int, default=4)
    ap.add_argument("--candidate", action="append", default=[],
                    help="VARIANT[@IMGSZ][:CONF], e.g. large, large@1024, medium:0.2")
    ap.add_argument("--face", action="append", default=[],
                    help="face pass setting CONF[/SCALE][/ROTS], e.g. 0.5, 0.3/2, 0.5/1/0 "
                         "(ROTS: digits 0,9=90,2=270); each is scored on the same object boxes")
    ap.add_argument("--device", default=None)
    args = ap.parse_args()

    start_memory_watchdog(args.min_available_gb)
    frames = sample_frames(args.key, args.frames)
    print(f"{len(frames)} frames, {frames[0].shape}", flush=True)
    results = {}
    with GpuReservation(command="privacy-scrub detector benchmark").acquire(
        "scrub-benchmark", timeout_seconds=args.lock_wait_s
    ):
        ref, ref_ms = run_detector(reference_detector(args.device), frames, args.batch)
        results["reference"] = {"ms_per_frame": round(ref_ms, 1),
                                "boxes": sum(map(len, ref))}
        print(f"reference yolo11m+face_yolov8m: {ref_ms:.1f} ms/frame", flush=True)
        import torch  # noqa: PLC0415
        from detector import PrivacyDetector  # noqa: PLC0415

        rot_codes = {"0": 0, "9": 90, "2": 270}
        for spec in args.candidate or ["large"]:
            name, _, conf = spec.partition(":")
            variant, _, imgsz = name.partition("@")
            det = PrivacyDetector(variant=variant, conf=float(conf or 0.25),
                                  imgsz=int(imgsz) if imgsz else None, device=args.device)
            det._objects(frames[:args.batch], det.conf)  # warm-up
            objects, obj_ms = run_detector(lambda b: det._objects(b, det.conf), frames, args.batch)
            for face_spec in args.face or ["0.5"]:
                parts = face_spec.split("/")
                det.face_conf = float(parts[0])
                det.face_scale = float(parts[1]) if len(parts) > 1 else 1.0
                det.face_rotations = tuple(rot_codes[c] for c in parts[2]) if len(parts) > 2 else (0, 90, 270)
                det.face.setScoreThreshold(det.face_conf)
                t0 = time.perf_counter()
                cand = [[*o, *det._faces(f)] for o, f in zip(objects, frames)]
                face_ms = (time.perf_counter() - t0) * 1000 / len(frames)
                res = compare(ref, cand, frames)
                res["ms_per_frame"] = round(obj_ms + face_ms, 1)
                res["detector"] = det.describe()
                key = f"{spec} face={face_spec}"
                results[key] = res
                print(json.dumps({key: res}), flush=True)
            del det
            torch.cuda.empty_cache()
    print("RESULTS " + json.dumps(results))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
