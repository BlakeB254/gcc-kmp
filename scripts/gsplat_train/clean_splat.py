"""Post-training cleanup for a trained 3DGS ply. Three prunes, all cheap
(numpy + plyfile only — runs in any venv, no torch):

  1. near-transparent gaussians (opacity haze; with depth supervision most
     junk ends up here instead of floating in space)
  2. oversized gaussians (fog sheets)
  3. off-surface floaters: LiDAR-occupancy anchoring — a gaussian survives
     only within `dilate` voxels of the fused cloud. CALIBRATION (real-scan
     tested 2026-07-28): voxel 0.5 / dilate 4 (~2m reach) preserves
     photo-learned geometry beyond sensor range; the first attempt at 0.9m
     reach deleted real content (renders went black).
  4. optional (needs --poses): below-ground crop (ground plane estimated
     from camera height) and horizontal corridor crop. --above-cap is OFF
     by default — it was right for a yard scan at 5m but would amputate
     storefronts/buildings on street cells.

Run:  python clean_splat.py --ply splat.ply --points init_points.npy \
          [--poses poses.npy] --out splat_clean.ply
"""
from __future__ import annotations

import argparse

import numpy as np
from plyfile import PlyData, PlyElement


def clean(ply_path: str, points_path: str, out_path: str, *,
          poses_path: str | None = None, min_opacity: float = 0.05,
          max_scale: float = 0.35, voxel: float = 0.5, dilate: int = 4,
          cam_height: float = 1.5, below_margin: float = 0.3,
          above_cap: float = 0.0, corridor: float = 15.0) -> tuple[int, int]:
    """Returns (kept, total)."""
    ply = PlyData.read(ply_path)
    v = ply["vertex"].data
    n0 = len(v)
    xyz = np.stack([v["x"], v["y"], v["z"]], 1)
    opacity = 1 / (1 + np.exp(-np.asarray(v["opacity"])))
    biggest = np.exp(np.stack([v[f"scale_{i}"] for i in range(3)], 1)).max(1)

    keep = (opacity >= min_opacity) & (biggest <= max_scale)
    print(f"clean_splat: opacity<{min_opacity}: {int((opacity < min_opacity).sum()):,}"
          f"  scale>{max_scale}: {int((biggest > max_scale).sum()):,}")

    pts = np.load(points_path)
    vox = set(map(tuple, np.floor(pts / voxel).astype(np.int64)))
    offs = [(dx, dy, dz) for dx in range(-dilate, dilate + 1)
            for dy in range(-dilate, dilate + 1) for dz in range(-dilate, dilate + 1)]
    dil: set[tuple[int, int, int]] = set()
    for o in offs:
        dil.update((a + o[0], b + o[1], c + o[2]) for a, b, c in vox)
    gvox = np.floor(xyz / voxel).astype(np.int64)
    anchored = np.fromiter((tuple(q) in dil for q in gvox), dtype=bool, count=n0)
    print(f"clean_splat: off-surface floaters: {int((~anchored & keep).sum()):,}")
    keep &= anchored

    if poses_path:
        cams = np.load(poses_path)[:, :3, 3]  # cam2world translations
        up = 1  # dataset frame: +Y up
        ground = np.median(cams[:, up]) - cam_height
        below = xyz[:, up] < (ground - below_margin)
        keep &= ~below
        removed = int(below.sum())
        if above_cap > 0:
            above = xyz[:, up] > (ground + above_cap)
            keep &= ~above
            removed += int(above.sum())
        hor = [a for a in (0, 1, 2) if a != up]
        d2 = np.full(n0, np.inf)
        for c in cams[::4]:
            dd = (xyz[:, hor[0]] - c[hor[0]]) ** 2 + (xyz[:, hor[1]] - c[hor[1]]) ** 2
            np.minimum(d2, dd, out=d2)
        far = d2 > corridor ** 2
        keep &= ~far
        print(f"clean_splat: ground/corridor crops: {removed + int(far.sum()):,}"
              f" (ground y={ground:.2f})")

    out = v[keep]
    PlyData([PlyElement.describe(out, "vertex")]).write(out_path)
    kept = int(keep.sum())
    print(f"clean_splat: {n0:,} -> {kept:,} gaussians ({100 * kept / max(1, n0):.0f}% kept)")
    return kept, n0


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ply", required=True)
    p.add_argument("--points", required=True, help="init_points.npy from the dataset")
    p.add_argument("--poses", help="poses.npy — enables ground/corridor crops")
    p.add_argument("--out", required=True)
    p.add_argument("--min-opacity", type=float, default=0.05)
    p.add_argument("--max-scale", type=float, default=0.35)
    p.add_argument("--voxel", type=float, default=0.5)
    p.add_argument("--dilate", type=int, default=4)
    p.add_argument("--cam-height", type=float, default=1.5)
    p.add_argument("--below-margin", type=float, default=0.3)
    p.add_argument("--above-cap", type=float, default=0.0,
                   help="crop above ground+N meters (0 = off; unsafe near buildings)")
    p.add_argument("--corridor", type=float, default=15.0)
    args = p.parse_args()
    clean(args.ply, args.points, args.out, poses_path=args.poses,
          min_opacity=args.min_opacity, max_scale=args.max_scale,
          voxel=args.voxel, dilate=args.dilate, cam_height=args.cam_height,
          below_margin=args.below_margin, above_cap=args.above_cap,
          corridor=args.corridor)


if __name__ == "__main__":
    main()
