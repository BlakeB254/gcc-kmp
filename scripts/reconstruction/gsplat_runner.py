"""Trains the cell's Gaussian splat with gsplat from ARKit-POSED frames —
the geo-registered splat producer (replaced world-mirror for this role
2026-07-11; see docs/superpowers/specs/2026-07-10-phase3-... §splat).

Why this beats the world-mirror + Sim3 path it replaces: world-mirror
re-solves cameras from pixels alone, and at street scale its content was
internally inconsistent with its own returned cameras (up to ~78° off), so
no rigid fit could place it. Here the poses ARE ARKit's (gravity-aligned,
metric), mapped through the exact per-session transform fuse_sessions()
computed — so the splat trains natively in the north-aligned cell frame
and is geo-true by construction. Verified on nl-0cgku8: near-field bearing
0.1° vs the 10.6° GPS walk, footprint matching the LiDAR cloud.

The training itself runs in scripts/gsplat_train/.venv (torch nightly
cu130 for the GB10's sm_121) as a subprocess; this module only builds the
dataset and shuttles bytes. Env:
  GSPLAT_ITERS  training iterations (default 15000, ~35 min on GB10)
"""
from __future__ import annotations

import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

import av
import numpy as np
from PIL import Image

import chunk_io
import r2_store
from frame_audit import FrameRecord, audit_frames
from fuse_cell import FusionResult, _group_chunks_by_index

GSPLAT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "gsplat_train"))
GSPLAT_PYTHON = os.path.join(GSPLAT_DIR, ".venv", "bin", "python")
MAX_TOTAL_FRAMES = 160
# ARKit camera convention is GL (-Z forward, +Y up); gsplat wants OpenCV.
GL_TO_CV = np.diag([1.0, -1.0, -1.0, 1.0])
MIN_NOVEL_VIEW_BASELINE_METERS = 2.0
MAX_CELL_POSE_SPAN_METERS = 40.0


def _build_train_command(data_dir: str, iters: str, max_gaussians: str) -> list[str]:
    """Build the explicit verified-publication trainer contract."""
    return [
        GSPLAT_PYTHON,
        os.path.join(GSPLAT_DIR, "train_gsplat.py"),
        "--data", data_dir,
        "--iters", iters,
        "--max-gaussians", max_gaussians,
        "--out", "splat_gsplat.ply",
        "--pose-mode", "frozen",
        "--checkpoint-every", "1000",
    ]


def _stage_verified_training_metadata(
    dataset_dir: str | os.PathLike[str],
    staging_dir: str | os.PathLike[str],
) -> None:
    """Copy immutable trainer evidence into the candidate-manifest staging area."""
    source = Path(dataset_dir)
    destination = Path(staging_dir)
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("poses.npy", "frame-audit.json", "training-state.json"):
        shutil.copy2(source / name, destination / name)


def splat_quality_gate(poses: np.ndarray) -> str | None:
    """Reject camera tracks that cannot form a trustworthy cell splat.

    Map/evidence ingestion happens before this trainer and is unaffected.
    The horizontal bounding-box diagonal is intentionally conservative:
    below 2 m there is not enough parallax for a useful novel view, while
    above 40 m indicates that independent session frames were misregistered
    into one local cell coordinate frame.
    """
    camera_positions = np.asarray(poses, dtype=np.float64)[:, :3, 3]
    horizontal_extent = np.ptp(camera_positions[:, [0, 2]], axis=0)
    span = float(np.linalg.norm(horizontal_extent))
    if span < MIN_NOVEL_VIEW_BASELINE_METERS:
        return "insufficient-parallax"
    if span > MAX_CELL_POSE_SPAN_METERS:
        return "implausible-pose-span"
    return None


def _frame_sharpness(image: np.ndarray) -> float:
    """Resolution-normalized high-frequency energy used only for ranking."""
    gray = np.asarray(image, dtype=np.float32).mean(axis=2) / 255.0
    if min(gray.shape) < 3:
        return 0.0
    laplacian = (
        -4.0 * gray[1:-1, 1:-1]
        + gray[:-2, 1:-1]
        + gray[2:, 1:-1]
        + gray[1:-1, :-2]
        + gray[1:-1, 2:]
    )
    return float(np.var(laplacian))


def _frame_sharpness_by_sidecar_index(
    video_bytes: bytes,
    sidecar: list[chunk_io.SidecarFrame],
) -> dict[int, float]:
    """Map every pose sample to its nearest decoded RGB frame's sharpness.

    A chunk is decoded once and retains only timestamp/metric pairs, rather
    than one RGB array per sidecar sample. This lets the audit cover the full
    captured path without multiplying decoder work or holding the video in
    memory, while final image extraction remains bounded by the frame budget.
    """
    if not sidecar:
        return {}
    container = av.open(io.BytesIO(video_bytes))
    try:
        stream = next((stream for stream in container.streams if stream.type == "video"), None)
        if stream is None:
            return {}
        video_times: list[float] = []
        sharpness: list[float] = []
        pts0: float | None = None
        for frame in container.decode(stream):
            if frame.pts is None:
                continue
            timestamp = float(frame.pts * stream.time_base)
            if pts0 is None:
                pts0 = timestamp
            video_times.append(timestamp - pts0)
            sharpness.append(_frame_sharpness(frame.to_ndarray(format="rgb24")))
    finally:
        container.close()
    if not video_times:
        return {}

    decoded_times = np.asarray(video_times)
    targets = np.asarray([frame.t_video for frame in sidecar])
    right = np.searchsorted(decoded_times, targets, side="left")
    right = np.clip(right, 0, len(decoded_times) - 1)
    left = np.clip(right - 1, 0, len(decoded_times) - 1)
    nearest = np.where(
        np.abs(decoded_times[left] - targets) <= np.abs(decoded_times[right] - targets),
        left,
        right,
    )
    return {
        index: sharpness[int(decoded_index)]
        for index, decoded_index in enumerate(nearest)
    }


def select_training_frame_indices(images: list[np.ndarray], budget: int) -> list[int]:
    """Drop the blurriest fifth, then retain uniform temporal coverage.

    This is deliberately relative within a capture: concrete, grass, and
    brick have very different absolute texture energy, so a global threshold
    would reject legitimate low-texture scenes. Short captures stay intact.
    """
    if budget <= 0 or not images:
        return []
    if len(images) <= budget:
        return list(range(len(images)))

    scores = np.asarray([_frame_sharpness(image) for image in images])
    keep_count = max(budget, int(np.ceil(len(images) * 0.8)))
    sharpest = np.argsort(scores, kind="stable")[-keep_count:]
    candidates = np.sort(sharpest)
    if len(candidates) <= budget:
        return candidates.tolist()
    picks = np.linspace(0, len(candidates) - 1, budget, dtype=int)
    return candidates[picks].tolist()


def available() -> bool:
    return os.path.exists(GSPLAT_PYTHON)


def _export_session_frames(manifest: dict, T_cell: np.ndarray, frames_dir: str,
                           start_index: int, budget: int) -> tuple[list, list, int, list[dict]]:
    """Sample up to `budget` posed frames from one session's chunks, writing
    jpgs into frames_dir and returning (poses_cv, intrinsics, next_index,
    serialized_frame_audits)."""
    chunks = _group_chunks_by_index(manifest.get("captureChunks") or [])
    chunks = {i: k for i, k in sorted(chunks.items()) if "mov" in k and "jsonl" in k}
    if not chunks:
        return [], [], start_index, []
    per_chunk = max(1, budget // len(chunks))

    poses, intrinsics, n = [], [], start_index
    audits: list[dict] = []
    for chunk_index, kinds in chunks.items():
        if n - start_index >= budget:
            break
        if r2_store.is_configured() and r2_store.object_exists(
            r2_store.unreadable_marker_key_for(kinds["mov"])
        ):
            print(f"gsplat_runner: skipping permanently unreadable video {kinds['mov']}")
            continue
        video = r2_store.download_bytes(kinds["mov"])
        _header, sidecar = chunk_io.parse_sidecar(r2_store.download_bytes(kinds["jsonl"]))
        if not sidecar:
            continue
        confidence_records = (
            chunk_io.parse_confidence_chunk(r2_store.download_bytes(kinds["confidence"]))
            if "confidence" in kinds
            else []
        )
        sharpness_by_index = _frame_sharpness_by_sidecar_index(video, sidecar)
        records: list[FrameRecord] = []
        for i, frame in enumerate(sidecar):
            depth_confidence_fraction = None
            if confidence_records:
                confidence_record = min(
                    confidence_records, key=lambda record: abs(record.t - frame.t)
                )
                if abs(confidence_record.t - frame.t) <= 0.2:
                    depth_confidence_fraction = float(
                        np.mean(confidence_record.confidence >= 1)
                    )
            exposure_ev = None
            if (
                frame.exposure_duration_seconds is not None
                and frame.iso is not None
                and frame.exposure_duration_seconds > 0
                and frame.iso > 0
            ):
                exposure_ev = float(
                    np.log2(100.0 / (frame.exposure_duration_seconds * frame.iso))
                )
            records.append(FrameRecord(
                index=i,
                position=frame.camera_transform[:3, 3],
                tracking=frame.tracking,
                sharpness=sharpness_by_index.get(i, float("nan")),
                exposure_ev=exposure_ev,
                depth_confidence_fraction=depth_confidence_fraction,
            ))
        audit = audit_frames(records, budget=per_chunk)
        audits.append({
            "chunk": chunk_index,
            "selectedIndices": audit.selected_indices,
            "rejections": {str(index): reasons for index, reasons in audit.rejections.items()},
            "baselineMeters": audit.baseline_meters,
            "normalTrackingFraction": audit.normal_tracking_fraction,
            "depthCoverageFraction": audit.depth_coverage_fraction,
        })
        selected = set(audit.selected_indices)
        selected_indices = sorted(selected)
        imgs = chunk_io.extract_video_frames_at(
            video, [sidecar[i].t_video for i in selected_indices]
        )
        for i, img in zip(selected_indices, imgs):
            if img is None:
                continue
            f = sidecar[i]
            poses.append(T_cell @ f.camera_transform @ GL_TO_CV)
            intrinsics.append(np.array([[f.intrinsics.fx, 0, f.intrinsics.cx],
                                        [0, f.intrinsics.fy, f.intrinsics.cy],
                                        [0, 0, 1]], dtype=np.float64))
            Image.fromarray(img).save(os.path.join(frames_dir, f"{n:04d}.jpg"), quality=92)
            n += 1
    return poses, intrinsics, n, audits


def train_gsplat_for_cell(
    cell_id: str,
    fusion_result: FusionResult,
    session_manifests_by_id: dict[str, dict],
) -> tuple[bytes | None, str | None]:
    """Train one splat for the whole cell: every accepted session's frames,
    posed through its fusion transform into the shared cell frame, with the
    already-merged cell point cloud as gaussian init. Returns
    (bytes, None) on success, or (None, reason) when gsplat isn't set up or
    training fails -- the point-cloud asset is unaffected either way. reason
    is one of splat_outcome.py's vocabulary: "no-video-frames",
    "too-few-frames", or "training-failed"."""
    if not available():
        print(f"gsplat_runner: no venv at {GSPLAT_PYTHON} -- run gsplat_train/setup.sh", file=sys.stderr)
        return None, "training-failed"
    if fusion_result.merged_points.shape[0] == 0:
        return None, "training-failed"

    with tempfile.TemporaryDirectory(prefix="gsplat_") as tmp:
        frames_dir = os.path.join(tmp, "frames")
        os.makedirs(frames_dir)
        budget = max(1, MAX_TOTAL_FRAMES // len(fusion_result.accepted_session_ids))
        poses, intrinsics, n, frame_audits = [], [], 0, []
        for sid in fusion_result.accepted_session_ids:
            manifest = session_manifests_by_id.get(sid)
            if manifest is None:
                continue
            p, K, n, audits = _export_session_frames(
                manifest, fusion_result.session_transforms[sid], frames_dir, n, budget)
            poses.extend(p)
            intrinsics.extend(K)
            frame_audits.extend(audits)
        if n == 0:
            print(f"gsplat_runner: cell {cell_id}: no posed frames")
            return None, "no-video-frames"
        if n < 8:
            print(f"gsplat_runner: cell {cell_id}: only {n} posed frames (need 8+)")
            return None, "too-few-frames"
        quality_reason = splat_quality_gate(np.stack(poses))
        if quality_reason is not None:
            print(
                f"gsplat_runner: cell {cell_id}: splat publication held: "
                f"{quality_reason}"
            )
            return None, quality_reason

        sample = Image.open(os.path.join(frames_dir, "0000.jpg"))
        with open(os.path.join(tmp, "frame-audit.json"), "w") as fh:
            json.dump({"version": 1, "audits": frame_audits}, fh, indent=2)
        np.save(os.path.join(tmp, "poses.npy"), np.stack(poses).astype(np.float64))
        np.save(os.path.join(tmp, "intrinsics.npy"), np.stack(intrinsics).astype(np.float64))
        np.save(os.path.join(tmp, "init_points.npy"), fusion_result.merged_points.astype(np.float32))
        np.save(os.path.join(tmp, "init_colors.npy"), fusion_result.merged_colors.astype(np.float32))
        with open(os.path.join(tmp, "meta.json"), "w") as fh:
            json.dump({"cell": cell_id, "frames": n,
                       "width": sample.width, "height": sample.height,
                       "anchorLat": fusion_result.anchor_lat,
                       "anchorLng": fusion_result.anchor_lng,
                       "northAligned": fusion_result.north_aligned,
                       "initPoints": int(fusion_result.merged_points.shape[0])}, fh)

        # 30k default since the quality-recipe upgrade (antialiased rendering,
        # frozen ARKit poses, exposure comp, and LiDAR depth loss in trainer):
        # ~2x the old 15k wall-clock (~95min/cell on the GB10). Set
        # GSPLAT_ITERS=15000 to trade quality for turnaround.
        iters = os.environ.get("GSPLAT_ITERS", "30000")
        max_gaussians = os.environ.get("GSPLAT_MAX_GAUSSIANS", "5000000")
        print(
            f"gsplat_runner: cell {cell_id}: training on {n} frames, "
            f"{iters} iters, cap {max_gaussians} gaussians..."
        )
        env = dict(os.environ, TORCH_CUDA_ARCH_LIST="12.0;12.1")
        proc = subprocess.run(
            _build_train_command(tmp, iters, max_gaussians),
            env=env, timeout=3 * 3600,
        )
        out_ply = os.path.join(tmp, "splat_gsplat.ply")
        if proc.returncode != 0 or not os.path.exists(out_ply):
            print(f"gsplat_runner: cell {cell_id}: training failed (rc={proc.returncode})", file=sys.stderr)
            return None, "training-failed"
        _stage_verified_training_metadata(
            tmp, os.path.join(tmp, "candidate-manifest")
        )

        # Post-training cleanup: haze / fog-sheet / off-surface-floater
        # prune + ground and corridor crops (clean_splat.py, numpy-only).
        # A cleanup failure must never cost a finished training run — fall
        # back to the raw splat.
        clean_ply = os.path.join(tmp, "splat_clean.ply")
        cproc = subprocess.run(
            [GSPLAT_PYTHON, os.path.join(GSPLAT_DIR, "clean_splat.py"),
             "--ply", out_ply, "--points", os.path.join(tmp, "init_points.npy"),
             "--poses", os.path.join(tmp, "poses.npy"), "--out", clean_ply],
            env=env, timeout=1800,
        )
        if cproc.returncode == 0 and os.path.exists(clean_ply):
            out_ply = clean_ply
        else:
            print(f"gsplat_runner: cell {cell_id}: cleanup failed (rc={cproc.returncode}),"
                  " publishing uncleaned splat", file=sys.stderr)
        with open(out_ply, "rb") as f:
            return f.read(), None
