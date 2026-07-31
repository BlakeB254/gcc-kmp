"""Render a trained 3DGS PLY beside captured ground-truth frames.

Importing this legacy entrypoint is CPU-pure.  Torch, gsplat and the PLY
decoder are loaded only by ``main()``, after the reconstruction worker has
admitted the renderer process to the GPU.
"""
from __future__ import annotations

import argparse
import os
from typing import Sequence

import numpy as np
from PIL import Image


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ply", required=True)
    parser.add_argument("--data", required=True)
    parser.add_argument("--frames", default="10,80,150")
    parser.add_argument("--out", required=True)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> None:
    args = parse_args(argv)

    # GPU-owning dependencies stay below the admitted process entrypoint.
    import torch
    from gsplat import rasterization
    from plyfile import PlyData

    vertex = PlyData.read(os.path.expanduser(args.ply))["vertex"].data
    count = len(vertex)
    means = torch.tensor(
        np.stack([vertex["x"], vertex["y"], vertex["z"]], 1),
        dtype=torch.float32,
        device="cuda",
    )
    scales = torch.exp(torch.tensor(
        np.stack([vertex[f"scale_{i}"] for i in range(3)], 1),
        dtype=torch.float32,
        device="cuda",
    ))
    quats = torch.tensor(
        np.stack([vertex[f"rot_{i}"] for i in range(4)], 1),
        dtype=torch.float32,
        device="cuda",
    )
    opacities = torch.sigmoid(torch.tensor(
        np.asarray(vertex["opacity"]), dtype=torch.float32, device="cuda"
    ))
    sh0 = np.stack([vertex[f"f_dc_{i}"] for i in range(3)], 1)[:, None, :]
    rest_count = len([name for name in vertex.dtype.names if name.startswith("f_rest_")])
    rest = np.stack(
        [vertex[f"f_rest_{index}"] for index in range(rest_count)], 1
    ).reshape(count, 3, rest_count // 3).transpose(0, 2, 1)
    colors = torch.tensor(
        np.concatenate([sh0, rest], 1), dtype=torch.float32, device="cuda"
    )
    sh_degree = int(np.sqrt(rest_count // 3 + 1)) - 1
    print(f"{count:,} gaussians, SH degree {sh_degree}")

    data = os.path.expanduser(args.data)
    poses = np.load(os.path.join(data, "poses.npy"))
    intrinsics = np.load(os.path.join(data, "intrinsics.npy"))
    indices = [int(index) for index in args.frames.split(",")]
    first_ground_truth = Image.open(os.path.join(data, "frames", f"{indices[0]:04d}.jpg"))
    width, height = first_ground_truth.width, first_ground_truth.height

    rows = []
    for index in indices:
        viewmat = torch.tensor(
            np.linalg.inv(poses[index]), dtype=torch.float32, device="cuda"
        )[None]
        intrinsic = torch.tensor(
            intrinsics[index], dtype=torch.float32, device="cuda"
        )[None]
        with torch.no_grad():
            rendered, _, _ = rasterization(
                means=means,
                quats=torch.nn.functional.normalize(quats, dim=-1),
                scales=scales,
                opacities=opacities,
                colors=colors,
                viewmats=viewmat,
                Ks=intrinsic,
                width=width,
                height=height,
                sh_degree=sh_degree,
                packed=False,
            )
        image = (rendered[0].clamp(0, 1).cpu().numpy() * 255).astype(np.uint8)
        ground_truth = np.asarray(
            Image.open(os.path.join(data, "frames", f"{index:04d}.jpg"))
        )
        rows.append(np.concatenate([ground_truth, image], 1))
    grid = np.concatenate(rows, 0)
    Image.fromarray(grid).resize((grid.shape[1] // 2, grid.shape[0] // 2)).save(
        os.path.expanduser(args.out), quality=90
    )
    print("wrote", args.out)


if __name__ == "__main__":
    main()
