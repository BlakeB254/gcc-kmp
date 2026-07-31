"""Create a reversible, viewer-friendly 3DGS derivative.

The source PLY remains the archival, north-aligned reconstruction.  This
derivative rebases the estimated ground to Y=0, removes extreme anisotropic
streaks, and writes a deterministic walking-height camera preset.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from plyfile import PlyData, PlyElement


def normalize_for_human_view(
    source_path: str | Path,
    output_path: str | Path,
    *,
    poses_path: str | Path,
    metadata_path: str | Path | None = None,
    eye_height: float = 1.65,
    max_anisotropy: float = 8.0,
) -> dict:
    source_path, output_path = Path(source_path), Path(output_path)
    if source_path.resolve() == output_path.resolve():
        raise ValueError("human-view output must not overwrite the source reconstruction")

    poses = np.load(poses_path)
    if poses.ndim != 3 or poses.shape[1:] != (4, 4) or len(poses) == 0:
        raise ValueError("poses must contain one or more 4x4 camera-to-world matrices")

    ply = PlyData.read(source_path)
    vertex = ply["vertex"].data
    data = vertex.copy()
    scales = np.exp(
        np.stack([data[f"scale_{i}"] for i in range(3)], axis=1).astype(np.float64)
    )
    anisotropy = scales.max(axis=1) / np.maximum(scales.min(axis=1), 1e-9)
    keep = np.isfinite(anisotropy) & (anisotropy <= max_anisotropy)

    camera_positions = poses[:, :3, 3].astype(np.float64)
    ground_y = float(np.median(camera_positions[:, 1]) - eye_height)
    data["y"] = (data["y"].astype(np.float64) - ground_y).astype(data["y"].dtype)
    cleaned = data[keep]
    PlyData(
        [PlyElement.describe(cleaned, "vertex")],
        text=ply.text,
        byte_order=ply.byte_order,
    ).write(output_path)

    start = camera_positions[0].copy()
    start[1] = ground_y + eye_height
    horizontal = camera_positions[:, [0, 2]]
    distances = np.linalg.norm(horizontal - horizontal[0], axis=1)
    target = camera_positions[int(np.argmax(distances))].copy()
    if float(distances.max()) < 0.25:
        target = start + np.array([0.0, 0.0, -2.0])
    start[1] = target[1] = eye_height
    start[1] -= ground_y
    # start was expressed in source Y above; after rebasing it is eye_height.
    start[1] = eye_height

    result = {
        "coordinateSystem": "metric-y-up-north-aligned",
        "groundOffsetY": ground_y,
        "sourceGaussians": int(len(data)),
        "outputGaussians": int(len(cleaned)),
        "removed": {"anisotropy": int((~keep).sum())},
        "viewer": {
            "position": [round(float(x), 6) for x in start],
            "target": [
                round(float(target[0]), 6),
                eye_height,
                round(float(target[2]), 6),
            ],
            "up": [0.0, 1.0, 0.0],
            "fovDegrees": 60.0,
        },
    }
    if metadata_path is not None:
        Path(metadata_path).write_text(json.dumps(result, indent=2) + "\n")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ply", required=True)
    parser.add_argument("--poses", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--metadata")
    parser.add_argument("--eye-height", type=float, default=1.65)
    parser.add_argument("--max-anisotropy", type=float, default=8.0)
    args = parser.parse_args()
    result = normalize_for_human_view(
        args.ply,
        args.out,
        poses_path=args.poses,
        metadata_path=args.metadata,
        eye_height=args.eye_height,
        max_anisotropy=args.max_anisotropy,
    )
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
