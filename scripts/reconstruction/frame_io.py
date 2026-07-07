"""Turns one CapturedFrame (color JPEG + depth PNG + pose + intrinsics, as
defined by shared/src/commonMain/kotlin/com/gcc/shared/model/EnvScanSession.kt)
into an Open3D point cloud in the frame's own session-local coordinates.

Depth convention: 16-bit grayscale PNG, millimeters — matches
CapturedFrame.DEPTH_PNG_SCALE_MM_PER_UNIT on the Kotlin side and the common
RGBD-dataset convention (TUM RGB-D and similar), so this reads correctly with
zero GCC-specific tooling if someone opens it in another pipeline.
"""
from __future__ import annotations

import io
from dataclasses import dataclass

import numpy as np
import open3d as o3d
from PIL import Image

DEPTH_SCALE_MM_PER_UNIT = 1000.0
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
class CapturedFrameData:
    """Python-side mirror of the Kotlin CapturedFrame, plus the actual
    decoded image bytes (the caller downloads color_bytes/depth_bytes from R2
    using the frame's colorKey/depthKey — this module doesn't fetch)."""
    color_bytes: bytes
    depth_bytes: bytes | None
    camera_transform: list[float] | None  # row-major 4x4, 16 floats
    intrinsics: Intrinsics | None


def _camera_transform_to_matrix(values: list[float]) -> np.ndarray:
    if len(values) != 16:
        raise ValueError(f"cameraTransform must have 16 floats, got {len(values)}")
    return np.array(values, dtype=np.float64).reshape(4, 4)


def build_point_cloud(frame: CapturedFrameData) -> o3d.geometry.PointCloud | None:
    """None when the frame lacks depth or pose/intrinsics -- a color-only
    frame is still valid raw input for classical SfM, just not this
    depth-fused path. Caller decides what to do with a color-only frame."""
    if frame.depth_bytes is None or frame.camera_transform is None or frame.intrinsics is None:
        return None

    color_img = np.asarray(Image.open(io.BytesIO(frame.color_bytes)).convert("RGB"))
    depth_img = np.asarray(Image.open(io.BytesIO(frame.depth_bytes)))
    if depth_img.dtype != np.uint16:
        raise ValueError(f"expected 16-bit depth PNG, got dtype {depth_img.dtype}")

    intr = frame.intrinsics
    if depth_img.shape[:2] != (intr.height, intr.width):
        # Depth is commonly captured at a lower resolution than color on
        # ARKit devices -- resample color down to match rather than guessing
        # an upscale for depth, so every pixel pairing stays 1:1 real data.
        color_pil = Image.fromarray(color_img).resize((intr.width, intr.height), Image.BILINEAR)
        color_img = np.asarray(color_pil)

    color_o3d = o3d.geometry.Image(np.ascontiguousarray(color_img))
    depth_o3d = o3d.geometry.Image(np.ascontiguousarray(depth_img))
    rgbd = o3d.geometry.RGBDImage.create_from_color_and_depth(
        color_o3d,
        depth_o3d,
        depth_scale=DEPTH_SCALE_MM_PER_UNIT,
        depth_trunc=DEPTH_TRUNC_METERS,
        convert_rgb_to_intensity=False,
    )
    intrinsic = o3d.camera.PinholeCameraIntrinsic(intr.width, intr.height, intr.fx, intr.fy, intr.cx, intr.cy)
    pcd = o3d.geometry.PointCloud.create_from_rgbd_image(rgbd, intrinsic)

    # ARKit's camera-to-world transform places these points in the session's
    # own local coordinate frame -- consistent across frames of ONE session
    # (ARKit maintains that internally), but arbitrary relative to any other
    # session's frame. Cross-session alignment is registration.py's job, not
    # this function's.
    cam_to_world = _camera_transform_to_matrix(frame.camera_transform)
    pcd.transform(cam_to_world)
    return pcd


def merge_session_point_clouds(clouds: list[o3d.geometry.PointCloud], voxel_size: float = 0.02) -> o3d.geometry.PointCloud:
    """Frames within one session are already consistently posed by ARKit, so
    this is a plain concatenate + downsample, not a registration problem."""
    merged = o3d.geometry.PointCloud()
    for cloud in clouds:
        merged += cloud
    return merged.voxel_down_sample(voxel_size)
