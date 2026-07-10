"""Pinhole RGBD projection + point transform + voxel downsampling, in pure
NumPy -- deliberately NOT using Open3D's `create_from_rgbd_image`/
`PointCloud.transform`, both of which segfault on this DGX Spark (see
README's "Status" section). These operations are simple enough that
reimplementing them removes the dependency on Open3D's broken C++ core
entirely; Open3D is used ONLY for `.ply` file I/O elsewhere in this
pipeline, which is confirmed safe.

Depth is always in METERS here (a plain float array), matching what ARKit's
`sceneDepth`/`smoothedSceneDepth` actually produces and what `chunk_io.py`
decodes from the real capture format's LZFSE depth records -- there's no
mm/PNG scale-factor convention to think about, because nothing in this
pipeline stores depth as a scaled 16-bit PNG anymore (an earlier, since-
retired placeholder capture path did; this module no longer needs to know
about it).
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from PIL import Image

# Depth values beyond this are almost certainly ARKit noise/miss, not a real
# far surface — a scanned block/yard is realistically within this range.
DEPTH_TRUNC_METERS = 8.0


@dataclass
class Intrinsics:
    fx: float
    fy: float
    cx: float
    cy: float
    width: int
    height: int


@dataclass
class PointCloudArrays:
    """Plain-array point cloud: Nx3 float64 positions, Nx3 float64 colors in
    [0,1]. Deliberately not an o3d.geometry.PointCloud -- callers convert to
    one only at the final .ply-write step (see fuse_cell.py), the one
    Open3D code path confirmed safe on this hardware."""
    points: np.ndarray
    colors: np.ndarray


def apply_transform(points: np.ndarray, transform_4x4: np.ndarray) -> np.ndarray:
    """Nx3 points -> Nx3 points, via a 4x4 homogeneous transform. Pure NumPy
    replacement for o3d.geometry.PointCloud.transform, which segfaults here
    even on 3 points with an identity matrix -- confirmed not a size/data
    issue, a broken C++ binding."""
    if points.shape[0] == 0:
        return points
    homogeneous = np.hstack([points, np.ones((points.shape[0], 1))])
    transformed = (transform_4x4 @ homogeneous.T).T
    return transformed[:, :3]


def project_rgbd_to_points(
    color_img: np.ndarray,
    depth_meters: np.ndarray,
    intr: Intrinsics,
) -> PointCloudArrays:
    """Standard pinhole projection: pixel (u,v) with depth d -> camera-space
    (x,y,z) = ((u-cx)*d/fx, (v-cy)*d/fy, d). Uses the conventional computer-
    vision convention (+Z forward, image row increases downward) matching
    the fx/fy/cx/cy convention Intrinsics documents.

    NOT YET VALIDATED against a real ARKit depth buffer's exact axis/origin
    convention (row-major top-left vs. bottom-left, sensor rotation) -- flag
    this the same way registration.py flags its GPS axis-convention
    assumption. Verify by re-projecting a known real capture and checking it
    looks like the actual scanned scene, once real depth frames exist.
    """
    depth_m = depth_meters.astype(np.float64)
    valid = (depth_m > 0) & (depth_m < DEPTH_TRUNC_METERS)

    v_idx, u_idx = np.nonzero(valid)
    d = depth_m[v_idx, u_idx]
    x = (u_idx - intr.cx) * d / intr.fx
    y = (v_idx - intr.cy) * d / intr.fy
    points = np.stack([x, y, d], axis=1)
    colors = color_img[v_idx, u_idx].astype(np.float64) / 255.0
    return PointCloudArrays(points=points, colors=colors)


def build_point_cloud_from_frame(
    color_img: np.ndarray,
    depth_meters: np.ndarray,
    camera_transform: np.ndarray,
    intr: Intrinsics,
) -> PointCloudArrays:
    """color_img (HxWx3 uint8), depth_meters (HxW float, may be a different
    resolution than color -- ARKit's depth is typically lower-res than the
    video feed), camera_transform (4x4, camera-to-world, already assembled
    e.g. by chunk_io.SidecarFrame from the pose sidecar's column-major
    floats). Resizes color DOWN to depth's resolution when they differ, so
    every pixel pairing stays 1:1 real data rather than guessing an
    upscale."""
    if color_img.shape[:2] != depth_meters.shape:
        height, width = depth_meters.shape
        color_img = np.asarray(Image.fromarray(color_img).resize((width, height), Image.BILINEAR))

    camera_points = project_rgbd_to_points(color_img, depth_meters, intr)
    if camera_points.points.shape[0] == 0:
        return camera_points

    # ARKit's camera-to-world transform places these points in the session's
    # own local coordinate frame -- consistent across frames of ONE session
    # (ARKit maintains that internally), but arbitrary relative to any other
    # session's frame. Cross-session alignment is registration.py's job, not
    # this function's.
    world_points = apply_transform(camera_points.points, camera_transform)
    return PointCloudArrays(points=world_points, colors=camera_points.colors)


def voxel_down_sample(cloud: PointCloudArrays, voxel_size: float) -> PointCloudArrays:
    """Pure-NumPy replacement for o3d.geometry.PointCloud.voxel_down_sample:
    bucket points into a voxel grid, average position+color per occupied
    voxel. Not performance-tuned (a dict keyed by voxel index) -- fine at the
    per-session point counts this pipeline deals with; revisit if profiling
    ever shows this is the bottleneck."""
    if cloud.points.shape[0] == 0:
        return cloud
    voxel_indices = np.floor(cloud.points / voxel_size).astype(np.int64)
    _, inverse, counts = np.unique(voxel_indices, axis=0, return_inverse=True, return_counts=True)
    inverse = inverse.reshape(-1)

    n_voxels = counts.shape[0]
    summed_points = np.zeros((n_voxels, 3), dtype=np.float64)
    summed_colors = np.zeros((n_voxels, 3), dtype=np.float64)
    np.add.at(summed_points, inverse, cloud.points)
    np.add.at(summed_colors, inverse, cloud.colors)

    return PointCloudArrays(
        points=summed_points / counts[:, None],
        colors=summed_colors / counts[:, None],
    )


def merge_session_point_clouds(clouds: list[PointCloudArrays], voxel_size: float = 0.02) -> PointCloudArrays:
    """Frames within one session are already consistently posed by ARKit, so
    this is a plain concatenate + downsample, not a registration problem."""
    non_empty = [c for c in clouds if c.points.shape[0] > 0]
    if not non_empty:
        return PointCloudArrays(points=np.zeros((0, 3)), colors=np.zeros((0, 3)))
    merged = PointCloudArrays(
        points=np.concatenate([c.points for c in non_empty], axis=0),
        colors=np.concatenate([c.colors for c in non_empty], axis=0),
    )
    return voxel_down_sample(merged, voxel_size)
