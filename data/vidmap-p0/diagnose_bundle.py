#!/usr/bin/env python3
"""P0 spike diagnostics: VidMap COLMAP solve vs Mapillary GPS for one bundle.

Reports (same metric set used on the Ogden West bundle, 2026-08-19):
  - poses_gps.json Sim3 rms_m / scale (runner output, echoed)
  - path lengths: COLMAP units vs GPS metres
  - collinearity: mean perpendicular deviation from the PCA ground line,
    as % of path length ("off-axis")
  - consecutive-frame distance ratio median (metric scale consistency)
  - 2D similarity shape-fit (scale+rotation+translation in the ground
    plane): fitted scale, per-frame residual median / max (m)

Usage: diagnose_bundle.py <bundle_dir>
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, "/home/codex450/cdx/dev-projects/gcc/scripts/reconstruction")
from vidmap_runner import (  # noqa: E402
    camera_center_w2c,
    load_colmap_model,
    load_gps_track,
    colmap_dir_for,
)


def enu_meters(gps) -> np.ndarray:
    """GPS samples → local ENU metres relative to first sample."""
    lat0, lon0 = gps[0].lat, gps[0].lon
    out = []
    for s in gps:
        e = (s.lon - lon0) * 111_320.0 * math.cos(math.radians(lat0))
        n = (s.lat - lat0) * 111_320.0
        out.append([e, n, s.alt_m - gps[0].alt_m])
    return np.array(out)


def path_len(p: np.ndarray) -> float:
    return float(np.linalg.norm(np.diff(p, axis=0), axis=1).sum())


def off_axis_pct(p2: np.ndarray) -> float:
    """Mean perpendicular deviation from PCA line, % of path length (2D)."""
    c = p2 - p2.mean(axis=0)
    _, _, vt = np.linalg.svd(c, full_matrices=False)
    dir_v = vt[0]
    perp = c - np.outer(c @ dir_v, dir_v)
    dev = float(np.linalg.norm(perp, axis=1).mean())
    return 100.0 * dev / max(path_len(p2), 1e-9)


def sim2d_fit(src: np.ndarray, dst: np.ndarray):
    """2D similarity (scale, rotation, translation) src→dst, least squares."""
    cs, cd = src - src.mean(axis=0), dst - dst.mean(axis=0)
    h = cs.T @ cd
    u, _, vt = np.linalg.svd(h)
    d = np.sign(np.linalg.det(vt.T @ u.T))
    r = vt.T @ np.diag([1.0, d]) @ u.T
    scale = float((cs ** 2).sum() and ((np.diag(vt.T @ np.diag([1.0, d]) @ u.T @ h)).sum() / (cs ** 2).sum()))
    t = dst.mean(axis=0) - scale * r @ src.mean(axis=0)
    fitted = (scale * (r @ src.T).T) + t
    resid = np.linalg.norm(fitted - dst, axis=1)
    return scale, r, t, resid


def main(bundle: str) -> None:
    b = Path(bundle)
    model = load_colmap_model(colmap_dir_for(b))
    if model is None or model.empty:
        print(json.dumps({"error": "no_colmap_model", "bundle": str(b)}))
        sys.exit(2)
    gps = load_gps_track(b / "gps.jsonl")

    # order COLMAP images by frame number and pair GPS by frame name (the
    # registered subset may skip frames, so positional pairing is wrong)
    gps_by_stem = {}
    for s in gps:
        if s.name:
            gps_by_stem[Path(str(s.name)).stem] = s
    images = sorted(model.images, key=lambda im: Path(im.name).stem)
    pairs = [(im, gps_by_stem[Path(im.name).stem]) for im in images
             if Path(im.name).stem in gps_by_stem]
    if len(pairs) < 3:
        print(json.dumps({"error": "too_few_gps_pairs", "pairs": len(pairs)}))
        sys.exit(2)
    centers = np.stack([camera_center_w2c(im.qvec, im.tvec) for im, _ in pairs])
    geo = enu_meters([s for _, s in pairs])

    # consecutive distances
    c_steps = np.linalg.norm(np.diff(centers, axis=0), axis=1)
    g_steps = np.linalg.norm(np.diff(geo, axis=0), axis=1)
    mask = c_steps > 1e-9
    ratio = (g_steps[mask] / c_steps[mask])

    # 2D shape fit: COLMAP plane = two largest-variance axes; GPS = east/north
    var = centers.var(axis=0)
    plane = np.argsort(var)[-2:]
    c2 = centers[:, plane]
    g2 = geo[:, :2]
    scale2, _, _, resid = sim2d_fit(c2, g2)

    pg_path = b / "rec" / "vidmap" / "poses_gps.json"
    pg = json.loads(pg_path.read_text()) if pg_path.is_file() else {}
    tf = pg.get("transform") or {}

    out = {
        "bundle": b.name,
        "registered_images": len(images),
        "sparse_points": len(model.points),
        "poses_gps_rms_m": tf.get("rms_m"),
        "poses_gps_scale": tf.get("scale"),
        "poses_gps_reason": pg.get("reason"),
        "colmap_path_units": round(path_len(centers), 2),
        "gps_path_m": round(path_len(geo), 2),
        "offaxis_pct": {"gps": round(off_axis_pct(g2), 1), "colmap": round(off_axis_pct(c2), 1)},
        "consec_distance_ratio_median_gps_per_colmap": round(float(np.median(ratio)), 3),
        "shape2d_fit": {
            "scale_m_per_unit": round(scale2, 3),
            "residual_median_m": round(float(np.median(resid)), 1),
            "residual_max_m": round(float(resid.max()), 1),
        },
    }
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main(sys.argv[1])
