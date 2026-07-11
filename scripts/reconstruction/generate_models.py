"""Model generation from existing reconstructions (Blake, 2026-07-11):
for every (cell, version) that has a splat/point cloud but NO mesh asset
yet, derive a triangle mesh and publish it as assetType 'mesh' — the "CDX
3D models" the twin's models tab shows.

Incremental by default: a watermark file records the last run, so "generate
models from splats" only processes what's new since then. Targeted mode:
--bbox w,s,e,n restricts to cells whose center falls in the box (the
map-section generation feature).

Poisson runs in the cdx-world-mirror venv (its Open3D build is proven to
mesh on this box — it produces collision.obj; the reconstruction venv's
Open3D compute core segfaults, see README).

Usage:
  export R2_* DATABASE_URL ...
  python generate_models.py [--bbox W,S,E,N] [--all] [--dry-run]
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import tempfile
import time

import psycopg

import r2_store

WATERMARK = pathlib.Path(__file__).parent / ".generate_models_watermark"
WM_PYTHON = os.path.expanduser("~/cdx-platform/services/cdx-world-mirror/.venv/bin/python")

POISSON_SNIPPET = """
import os, sys
import open3d as o3d
pcd = o3d.io.read_point_cloud(sys.argv[1])
print("points:", len(pcd.points), flush=True)
# PoissonRecon hard-fails (C++ error, EXIT 0!) on multi-million-point
# clouds on this box -- downsample first; detail beyond ~500k points adds
# nothing at mesh depth 8-9 anyway.
voxel = 0.02
while len(pcd.points) > 500000:
    pcd = pcd.voxel_down_sample(voxel)
    voxel *= 1.6
    print("downsampled to:", len(pcd.points), flush=True)
pcd, _ = pcd.remove_statistical_outlier(nb_neighbors=20, std_ratio=2.5)
pcd.estimate_normals(search_param=o3d.geometry.KDTreeSearchParamHybrid(radius=0.1, max_nn=30))
try:
    mesh, dens = o3d.geometry.TriangleMesh.create_from_point_cloud_poisson(pcd, depth=8)
except Exception:
    mesh = o3d.geometry.TriangleMesh()
if len(mesh.triangles) == 0:
    # PoissonRecon "Failed to close loop" bug on fragmented clouds --
    # alpha-shape fallback gives a usable (if chunkier) surface.
    print("poisson empty -- alpha-shape fallback", flush=True)
    pcd = pcd.voxel_down_sample(0.05)
    mesh = o3d.geometry.TriangleMesh.create_from_point_cloud_alpha_shape(pcd, alpha=0.15)
print("raw triangles:", len(mesh.triangles), flush=True)
if len(mesh.triangles) > 150000:
    mesh = mesh.simplify_quadric_decimation(target_number_of_triangles=150000)
    print("decimated to:", len(mesh.triangles), flush=True)
mesh.compute_vertex_normals()
assert len(mesh.triangles) > 0, "empty mesh"
assert o3d.io.write_triangle_mesh(sys.argv[2], mesh), "write failed"
assert os.path.getsize(sys.argv[2]) > 0, "empty file"
print("mesh written:", len(mesh.triangles), "triangles", flush=True)
"""


def _cell_center(cell: str) -> tuple[float, float]:
    lat_i, lng_i = (int(x) for x in cell.split(":"))
    return (lat_i + 0.5) * 10 / 111000, (lng_i + 0.5) * 10 / 82300


def candidates(conn, bbox=None, everything=False):
    """(cell, version, pointcloud_key) needing a mesh."""
    since = 0.0
    if not everything and WATERMARK.exists():
        since = float(WATERMARK.read_text().strip() or 0)
    with conn.cursor() as cur:
        cur.execute(
            """SELECT location_cell_id, version,
                      max(r2_key) FILTER (WHERE asset_type='pointcloud') AS pc,
                      bool_or(asset_type='mesh') AS has_mesh,
                      extract(epoch from max(created_at)) AS newest
               FROM reconstruction_assets
               GROUP BY location_cell_id, version"""
        )
        out = []
        for cell, version, pc, has_mesh, newest in cur.fetchall():
            if has_mesh or not pc:
                continue
            if not everything and newest is not None and float(newest) <= since:
                continue
            if bbox:
                lat, lng = _cell_center(cell)
                w, s, e, n = bbox
                if not (w <= lng <= e and s <= lat <= n):
                    continue
            out.append((cell, version, pc))
        return out


def generate(cell: str, version: int, pc_key: str) -> str:
    with tempfile.TemporaryDirectory(prefix="meshgen_") as tmp:
        src = os.path.join(tmp, "pc.ply")
        dst = os.path.join(tmp, "mesh.obj")
        with open(src, "wb") as f:
            f.write(r2_store.download_bytes(pc_key))
        proc = subprocess.run([WM_PYTHON, "-c", POISSON_SNIPPET, src, dst],
                              check=True, timeout=1800, capture_output=True, text=True)
        if not os.path.exists(dst):
            # PoissonRecon can die internally and still exit 0 (observed:
            # "Failed to close loop" on huge clouds) -- surface it honestly.
            raise RuntimeError(f"poisson produced no mesh: {proc.stdout[-200:]} {proc.stderr[-200:]}")
        key = r2_store.reconstructed_asset_key(cell, version=version, asset_type="mesh", fmt="obj")
        r2_store.upload_bytes(key, open(dst, "rb").read(), content_type="application/octet-stream")
        return key


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bbox", help="W,S,E,N lng/lat — targeted map-section generation")
    parser.add_argument("--all", action="store_true", help="ignore the watermark (full pass)")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    bbox = tuple(float(x) for x in args.bbox.split(",")) if args.bbox else None

    with psycopg.connect(os.environ["DATABASE_URL"]) as conn:
        todo = candidates(conn, bbox=bbox, everything=args.all or bool(bbox))
        print(json.dumps({"candidates": [f"{c}@v{v}" for c, v, _ in todo]}))
        if args.dry_run:
            return
        done = []
        for cell, version, pc_key in todo:
            try:
                key = generate(cell, version, pc_key)
                with conn.cursor() as cur:
                    cur.execute(
                        """INSERT INTO reconstruction_assets
                             (location_cell_id, asset_type, format, r2_key, version,
                              source_scan_coverage_event_ids)
                           VALUES (%s, 'mesh', 'obj', %s, %s, %s::jsonb)
                           ON CONFLICT (r2_key) DO NOTHING""",
                        (cell, key, version, json.dumps({"derivedFrom": pc_key})),
                    )
                conn.commit()
                done.append(key)
                print(f"generated {key}")
            except Exception as e:
                err = getattr(e, "stderr", "") or str(e)
                print(f"FAILED {cell}@v{version}: {err[-300:]}")
        if not bbox:  # watermark only advances on full (untargeted) runs
            WATERMARK.write_text(str(time.time()))
        print(json.dumps({"generated": done}))


if __name__ == "__main__":
    main()
