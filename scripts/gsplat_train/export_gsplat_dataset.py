"""Exports a scan session as a gsplat training dataset — the plan-B splat
path (2026-07-11): train 3DGS directly from ARKit-POSED frames, so the splat
is metric, gravity-aligned, and TRUE-NORTH by construction. No world-mirror,
no Sim3 fitting, nothing to mis-register.

Per session:
  frames/NNNN.jpg          color frames (video, single decode pass per chunk)
  poses.npy    (N,4,4)     cam2world in the NORTH-ALIGNED session frame
                           (ARKit poses pre-rotated by the session's GPS-
                           fitted yaw; +X=east, -Z=north, metric meters)
  intrinsics.npy (N,3,3)   pinhole K per frame (video-resolution scaled)
  init_points.npy/colors   LiDAR point cloud in the SAME frame — gaussian init
  meta.json                session/cell/anchor lat-lng/frame count

Run (uses the reconstruction venv):
  cd ../reconstruction && .venv/bin/python ../gsplat_train/export_gsplat_dataset.py \
      --cell 464650:-721828 --session env-1783640498732-0cgku8 --out ~/models/gcc/gsplat/<name>
"""
from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "reconstruction"))

import numpy as np
from PIL import Image

import chunk_io
import frame_io
import r2_store
from fuse_cell import (MERGE_VOXEL_SIZE_METERS, _fuse_one_chunk, _group_chunks_by_index,
                       _session_fusion_input_from_manifest, _track_gps_pairs)
from gaussian_ply import fit_yaw_to_north

MAX_FRAMES = 160


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cell", required=True)
    parser.add_argument("--session", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--max-frames", type=int, default=MAX_FRAMES)
    args = parser.parse_args()

    manifest = next(m for m in r2_store.list_manifests_for_cell(args.cell)
                    if m.manifest.get("sessionId") == args.session)

    # North yaw for THIS session (ARKit track ↔ GPS track).
    sess = _session_fusion_input_from_manifest(manifest)
    pairs = _track_gps_pairs(sess)
    R_north = np.eye(3)
    if len(pairs) >= 5:
        arkit_xz = np.array([[p[0][0], p[0][2]] for p in pairs])
        enu = np.array([p[1] for p in pairs])
        R_north, rms = fit_yaw_to_north(arkit_xz, enu)
        print(f"north yaw fitted: rms {rms:.2f}m over {len(pairs)} pairs")
    else:
        print("WARNING: too few GPS pairs — exporting in raw ARKit yaw")
    T_north = np.eye(4)
    T_north[:3, :3] = R_north

    out = os.path.expanduser(args.out)
    os.makedirs(os.path.join(out, "frames"), exist_ok=True)

    chunks = _group_chunks_by_index(manifest.manifest.get("captureChunks") or [])
    chunks = {i: k for i, k in sorted(chunks.items()) if "mov" in k and "jsonl" in k}
    per_chunk = max(1, args.max_frames // max(1, len(chunks)))

    poses, intrinsics, n = [], [], 0
    for kinds in chunks.values():
        video = r2_store.download_video_bytes(kinds["mov"])
        _header, frames = chunk_io.parse_sidecar(r2_store.download_bytes(kinds["jsonl"]))
        if not frames:
            continue
        # Contract gate: only NORMAL-tracking frames train the splat.
        usable = [i for i, f in enumerate(frames) if f.tracking == 0]
        if not usable:
            continue
        idxs = [usable[j] for j in np.linspace(0, len(usable) - 1,
                                               min(per_chunk, len(usable)), dtype=int)]
        imgs = chunk_io.extract_video_frames_at(video, [frames[i].t_video for i in idxs])
        for i, img in zip(idxs, imgs):
            if img is None:
                continue
            f = frames[i]
            # ARKit cam2world (GL convention: -Z forward, +Y up) → OpenCV
            # (+Z forward, +Y down), then into the north-aligned frame.
            c2w = T_north @ f.camera_transform
            c2w_cv = c2w @ np.diag([1.0, -1.0, -1.0, 1.0])
            poses.append(c2w_cv)
            K = np.array([[f.intrinsics.fx, 0, f.intrinsics.cx],
                          [0, f.intrinsics.fy, f.intrinsics.cy],
                          [0, 0, 1]], dtype=np.float64)
            intrinsics.append(K)
            Image.fromarray(img).save(os.path.join(out, "frames", f"{n:04d}.jpg"), quality=92)
            n += 1
    print(f"exported {n} posed frames")

    # LiDAR init cloud in the same north-aligned frame.
    pts = (R_north @ sess.points.T).T
    np.save(os.path.join(out, "init_points.npy"), pts.astype(np.float32))
    np.save(os.path.join(out, "init_colors.npy"), sess.colors.astype(np.float32))
    np.save(os.path.join(out, "poses.npy"), np.stack(poses).astype(np.float64))
    np.save(os.path.join(out, "intrinsics.npy"), np.stack(intrinsics).astype(np.float64))

    sample = Image.open(os.path.join(out, "frames", "0000.jpg"))
    with open(os.path.join(out, "meta.json"), "w") as fh:
        json.dump({
            "cell": args.cell, "session": args.session, "frames": n,
            "width": sample.width, "height": sample.height,
            "anchorLat": sess.anchor_lat, "anchorLng": sess.anchor_lng,
            "northAligned": len(pairs) >= 5,
            "initPoints": int(len(pts)),
        }, fh, indent=1)
    print(f"dataset at {out}: {n} frames, {len(pts):,} init points")


if __name__ == "__main__":
    main()
