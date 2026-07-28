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

import json
import os
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

import chunk_io
import r2_store
from fuse_cell import FusionResult, _group_chunks_by_index

GSPLAT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "gsplat_train"))
GSPLAT_PYTHON = os.path.join(GSPLAT_DIR, ".venv", "bin", "python")
MAX_TOTAL_FRAMES = 160
# ARKit camera convention is GL (-Z forward, +Y up); gsplat wants OpenCV.
GL_TO_CV = np.diag([1.0, -1.0, -1.0, 1.0])


def available() -> bool:
    return os.path.exists(GSPLAT_PYTHON)


def _export_session_frames(manifest: dict, T_cell: np.ndarray, frames_dir: str,
                           start_index: int, budget: int) -> tuple[list, list, int]:
    """Sample up to `budget` posed frames from one session's chunks, writing
    jpgs into frames_dir and returning (poses_cv, intrinsics, next_index)."""
    chunks = _group_chunks_by_index(manifest.get("captureChunks") or [])
    chunks = {i: k for i, k in sorted(chunks.items()) if "mov" in k and "jsonl" in k}
    if not chunks:
        return [], [], start_index
    per_chunk = max(1, budget // len(chunks))

    poses, intrinsics, n = [], [], start_index
    for kinds in chunks.values():
        if n - start_index >= budget:
            break
        video = r2_store.download_bytes(kinds["mov"])
        _header, sidecar = chunk_io.parse_sidecar(r2_store.download_bytes(kinds["jsonl"]))
        if not sidecar:
            continue
        # Contract gate: only NORMAL-tracking frames may train the splat --
        # LIMITED spans (fast turns, low light) carry mis-posed cameras.
        usable = [i for i, f in enumerate(sidecar) if f.tracking == 0]
        if not usable:
            continue
        idxs = [usable[j] for j in np.linspace(0, len(usable) - 1,
                                               min(per_chunk, len(usable)), dtype=int)]
        imgs = chunk_io.extract_video_frames_at(video, [sidecar[i].t_video for i in idxs])
        for i, img in zip(idxs, imgs):
            if img is None:
                continue
            f = sidecar[i]
            poses.append(T_cell @ f.camera_transform @ GL_TO_CV)
            intrinsics.append(np.array([[f.intrinsics.fx, 0, f.intrinsics.cx],
                                        [0, f.intrinsics.fy, f.intrinsics.cy],
                                        [0, 0, 1]], dtype=np.float64))
            Image.fromarray(img).save(os.path.join(frames_dir, f"{n:04d}.jpg"), quality=92)
            n += 1
    return poses, intrinsics, n


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
        poses, intrinsics, n = [], [], 0
        for sid in fusion_result.accepted_session_ids:
            manifest = session_manifests_by_id.get(sid)
            if manifest is None:
                continue
            p, K, n = _export_session_frames(
                manifest, fusion_result.session_transforms[sid], frames_dir, n, budget)
            poses.extend(p)
            intrinsics.extend(K)
        if n == 0:
            print(f"gsplat_runner: cell {cell_id}: no posed frames")
            return None, "no-video-frames"
        if n < 8:
            print(f"gsplat_runner: cell {cell_id}: only {n} posed frames (need 8+)")
            return None, "too-few-frames"

        sample = Image.open(os.path.join(frames_dir, "0000.jpg"))
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

        # 30k default since the quality-recipe upgrade (antialiased + pose
        # refinement + exposure comp + LiDAR depth loss live in the trainer):
        # ~2x the old 15k wall-clock (~95min/cell on the GB10). Set
        # GSPLAT_ITERS=15000 to trade quality for turnaround.
        iters = os.environ.get("GSPLAT_ITERS", "30000")
        print(f"gsplat_runner: cell {cell_id}: training on {n} frames, {iters} iters...")
        env = dict(os.environ, TORCH_CUDA_ARCH_LIST="12.0;12.1")
        proc = subprocess.run(
            [GSPLAT_PYTHON, os.path.join(GSPLAT_DIR, "train_gsplat.py"),
             "--data", tmp, "--iters", iters, "--out", "splat_gsplat.ply"],
            env=env, timeout=3 * 3600,
        )
        out_ply = os.path.join(tmp, "splat_gsplat.ply")
        if proc.returncode != 0 or not os.path.exists(out_ply):
            print(f"gsplat_runner: cell {cell_id}: training failed (rc={proc.returncode})", file=sys.stderr)
            return None, "training-failed"

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
