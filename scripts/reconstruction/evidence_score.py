"""Deterministic, CPU-only evidence scoring for verified Gaussian cleanup.

This module scores only captured evidence. It never renders a view or infers
visibility: ``visibility``, ``observed_colors``, and ``observed_pixels`` are
observations produced by the capture/audit path. It only projects a known
Gaussian position through supplied ARKit calibration to validate a captured
pixel observation.
That makes an absent observation absence of support, rather than a reason to
fill a hole.  RGB may stand in for unavailable LiDAR only after two captured
views see a point from sufficiently separated camera centres with consistent
colour.

``scales`` are physical (linear) Gaussian standard deviations, rather than
the logarithmic PLY ``scale_0..2`` values.  Callers reading a standard 3DGS PLY
must exponentiate its stored scale values before constructing ``EvidenceInputs``.
All coordinates share the frozen ARKit/world coordinate frame.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


_MIN_VIEW_SEPARATION_DEGREES = 5.0
_MAX_RGB_DIFFERENCE = 0.15
_MAX_REPROJECTION_ERROR_PX = 3.0
_LIDAR_NEAR_DISTANCE_METERS = 0.5
_COMPONENT_RADIUS_FLOOR_METERS = 0.15


@dataclass(frozen=True)
class EvidenceInputs:
    """Captured observations and Gaussian attributes used for cleanup scoring.

    ``camera_poses`` are camera-to-world transforms.  ``visibility`` has shape
    ``(gaussian_count, view_count)`` and must come from captured RGB-derived
    observations.  ``observed_colors`` has the same first two dimensions and
    holds normalized RGB values. ``observed_pixels`` holds the captured pixel
    centre for those observations; this module derives its error from the
    position, ARKit pose, and calibration rather than trusting caller-supplied
    reprojection errors. Invisible entries may be NaN. All optional
    observations are deliberately treated as unavailable evidence when absent.
    """

    positions: np.ndarray
    scales: np.ndarray
    opacities: np.ndarray
    camera_poses: np.ndarray
    intrinsics: np.ndarray
    lidar_points: np.ndarray
    lidar_confidence: np.ndarray | None = None
    visibility: np.ndarray | None = None
    observed_colors: np.ndarray | None = None
    observed_pixels: np.ndarray | None = None
    component_sizes: np.ndarray | None = None
    corridor_points: np.ndarray | None = None
    source_vertex_sha256: str | None = None


@dataclass(frozen=True)
class EvidenceResult:
    total: np.ndarray
    components: dict[str, np.ndarray]
    conservative_mask: np.ndarray
    balanced_mask: np.ndarray
    diagnostic_mask: np.ndarray
    source_vertex_sha256: str | None = None


def _as_float(name: str, value: np.ndarray, shape_tail: tuple[int, ...]) -> np.ndarray:
    array = np.asarray(value, dtype=np.float64)
    if array.ndim != len(shape_tail) + 1 or array.shape[1:] != shape_tail:
        raise ValueError(f"{name} must have shape (N, {', '.join(map(str, shape_tail))})")
    if not np.isfinite(array).all():
        raise ValueError(f"{name} contains non-finite values")
    return array


def _unit_interval(values: np.ndarray) -> np.ndarray:
    return np.clip(np.asarray(values, dtype=np.float64), 0.0, 1.0)


def _readonly(values: np.ndarray) -> np.ndarray:
    result = np.asarray(values).copy()
    result.setflags(write=False)
    return result


def _opacity_score(opacities: np.ndarray) -> np.ndarray:
    # PLY exports usually provide an opacity logit, while audited callers may
    # already provide alpha.  Support both forms without normalizing by batch
    # extrema (which would violate permutation/locality expectations).
    if np.all((0.0 <= opacities) & (opacities <= 1.0)):
        alpha = opacities
    else:
        clipped = np.clip(opacities, -60.0, 60.0)
        alpha = 1.0 / (1.0 + np.exp(-clipped))
    return _unit_interval((alpha - 0.05) / 0.85)


def _component_sizes(positions: np.ndarray, scales: np.ndarray) -> np.ndarray:
    """Find deterministic local connected-component sizes without scipy."""
    count = len(positions)
    if count == 0:
        return np.empty(0, dtype=np.float64)
    radius = max(_COMPONENT_RADIUS_FLOOR_METERS, 4.0 * float(np.max(scales)))
    parent = np.arange(count)

    def find(index: int) -> int:
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = int(parent[index])
        return index

    def union(left: int, right: int) -> None:
        left_root, right_root = find(left), find(right)
        if left_root != right_root:
            # Selecting the smaller root makes the output stable independently
            # of which equivalent neighbouring point was visited first.
            parent[max(left_root, right_root)] = min(left_root, right_root)

    cells = np.floor(positions / radius).astype(np.int64)
    buckets: dict[tuple[int, int, int], list[int]] = {}
    radius_squared = radius**2
    for index, cell in enumerate(cells):
        cell_key = tuple(int(value) for value in cell)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    neighbour = (cell_key[0] + dx, cell_key[1] + dy, cell_key[2] + dz)
                    for other in buckets.get(neighbour, ()):
                        delta = positions[index] - positions[other]
                        if float(delta @ delta) <= radius_squared:
                            union(index, other)
        buckets.setdefault(cell_key, []).append(index)

    roots = np.fromiter((find(index) for index in range(count)), dtype=np.int64, count=count)
    _, inverse, sizes = np.unique(roots, return_inverse=True, return_counts=True)
    return sizes[inverse].astype(np.float64)


def _view_components(
    positions: np.ndarray,
    camera_poses: np.ndarray,
    intrinsics: np.ndarray,
    visibility: np.ndarray | None,
    observed_colors: np.ndarray | None,
    observed_pixels: np.ndarray | None,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    count = len(positions)
    zeros = np.zeros(count, dtype=np.float64)
    if visibility is None:
        return zeros, zeros, zeros, zeros

    visibility = np.asarray(visibility, dtype=bool)
    views = len(camera_poses)
    if visibility.shape != (count, views):
        raise ValueError("visibility must match gaussian and camera counts")
    view_count = _unit_interval(visibility.sum(axis=1) / 2.0)
    diversity = zeros.copy()
    color_consistency = zeros.copy()
    reprojection_consistency = zeros.copy()
    camera_centres = camera_poses[:, :3, 3]
    for index in range(count):
        selected = np.flatnonzero(visibility[index])
        if len(selected) < 2:
            continue
        rays = positions[index] - camera_centres[selected]
        norms = np.linalg.norm(rays, axis=1)
        valid_rays = norms > 1e-12
        if valid_rays.sum() >= 2:
            directions = rays[valid_rays] / norms[valid_rays, None]
            cosine = np.clip(directions @ directions.T, -1.0, 1.0)
            maximum_angle = float(np.degrees(np.arccos(cosine)).max())
            diversity[index] = _unit_interval(maximum_angle / _MIN_VIEW_SEPARATION_DEGREES)

    if observed_colors is None or observed_pixels is None:
        # Visibility cannot stand in for either captured colour or a pixel
        # comparison against calibration. Missing evidence is no RGB support.
        return view_count, diversity, reprojection_consistency, zeros

    colors = np.asarray(observed_colors, dtype=np.float64)
    pixels = np.asarray(observed_pixels, dtype=np.float64)
    if colors.shape != (count, views, 3) or pixels.shape != (count, views, 2):
        raise ValueError("observed_colors and observed_pixels must match gaussian and camera counts")
    visible_colors, visible_pixels = colors[visibility], pixels[visibility]
    if not np.isfinite(visible_colors).all() or not np.isfinite(visible_pixels).all():
        raise ValueError("visible RGB observations and pixels must be finite")
    if visible_colors.size and np.max(np.abs(visible_colors)) > 1.0:
        colors = colors / 255.0
    calibrations = (
        np.broadcast_to(intrinsics, (views, 3, 3)) if intrinsics.shape == (3, 3) else intrinsics
    )
    if np.any(np.abs(np.linalg.det(calibrations)) < 1e-12):
        raise ValueError("intrinsics must be non-singular")
    camera_points = np.einsum(
        "nvj,vjk->nvk",
        positions[:, None, :] - camera_centres[None, :, :],
        camera_poses[:, :3, :3],
    )
    homogeneous = np.einsum("nvj,vkj->nvk", camera_points, calibrations)
    denominator = homogeneous[:, :, 2]
    projectable = (camera_points[:, :, 2] > 1e-12) & (np.abs(denominator) > 1e-12)
    projected_pixels = np.divide(
        homogeneous[:, :, :2],
        denominator[:, :, None],
        out=np.zeros((count, views, 2)),
        where=projectable[:, :, None],
    )
    pixel_errors = np.full((count, views), np.inf, dtype=np.float64)
    pixel_errors[projectable] = np.linalg.norm(
        projected_pixels[projectable] - pixels[projectable], axis=1
    )
    for index in range(count):
        selected = np.flatnonzero(visibility[index])
        if len(selected) == 0:
            continue
        errors = pixel_errors[index, selected]
        reprojection_consistency[index] = _unit_interval(
            1.0 - float(np.mean(np.maximum(errors, 0.0))) / _MAX_REPROJECTION_ERROR_PX
        )
        if len(selected) < 2:
            continue
        selected_colors = colors[index, selected]
        pairwise = np.linalg.norm(selected_colors[:, None, :] - selected_colors[None, :, :], axis=2)
        color_consistency[index] = _unit_interval(1.0 - float(pairwise.max()) / _MAX_RGB_DIFFERENCE)

    view_support = (
        (view_count >= 1.0)
        & (diversity >= 1.0)
        & (color_consistency >= 0.8)
        & (reprojection_consistency >= 0.7)
    ).astype(np.float64)
    return view_count, diversity, reprojection_consistency, view_support


def _proximity_score(positions: np.ndarray, points: np.ndarray, confidence: np.ndarray) -> np.ndarray:
    if len(points) == 0:
        return np.zeros(len(positions), dtype=np.float64)
    distances = np.linalg.norm(positions[:, None, :] - points[None, :, :], axis=2)
    distance_score = _unit_interval(1.0 - distances / _LIDAR_NEAR_DISTANCE_METERS)
    return np.max(distance_score * confidence[None, :], axis=1)


def score_gaussians(inputs: EvidenceInputs) -> EvidenceResult:
    """Score cleanup evidence without generating observations or using a GPU.

    The three masks are *keep* masks.  A Gaussian must have direct confident
    LiDAR proximity or verified two-view RGB support for every policy, so
    unsupported regions remain holes.  Their thresholds intentionally satisfy
    ``diagnostic <= balanced <= conservative`` element-wise.
    """
    positions = _as_float("positions", inputs.positions, (3,))
    count = len(positions)
    scales = _as_float("scales", inputs.scales, (3,))
    if len(scales) != count or np.any(scales < 0.0):
        raise ValueError("scales must be non-negative and match positions")
    opacities = np.asarray(inputs.opacities, dtype=np.float64)
    if opacities.shape != (count,) or not np.isfinite(opacities).all():
        raise ValueError("opacities must be a finite (N,) array")
    camera_poses = _as_float("camera_poses", inputs.camera_poses, (4, 4))
    intrinsics = np.asarray(inputs.intrinsics, dtype=np.float64)
    if intrinsics.shape not in {(3, 3), (len(camera_poses), 3, 3)} or not np.isfinite(intrinsics).all():
        raise ValueError("intrinsics must be finite 3x3 or (view_count, 3, 3)")
    lidar_points = _as_float("lidar_points", inputs.lidar_points, (3,))
    if inputs.lidar_confidence is None:
        # A raw depth point without its captured confidence is not verified
        # range evidence.  Retain it in the diagnostics input, but never let
        # it keep a Gaussian under any cleanup policy.
        lidar_confidence = np.zeros(len(lidar_points), dtype=np.float64)
    else:
        lidar_confidence = np.asarray(inputs.lidar_confidence, dtype=np.float64)
        if lidar_confidence.shape != (len(lidar_points),) or not np.isfinite(lidar_confidence).all():
            raise ValueError("lidar_confidence must be a finite array matching lidar_points")
        lidar_confidence = _unit_interval(lidar_confidence)

    opacity = _opacity_score(opacities)
    mean_scale = scales.mean(axis=1)
    scale = _unit_interval(1.0 - mean_scale / 0.5)
    maximum_scale = scales.max(axis=1)
    anisotropy = np.divide(scales.min(axis=1), maximum_scale, out=np.ones(count), where=maximum_scale > 1e-12)
    view_count, diversity, reprojection, view_support = _view_components(
        positions,
        camera_poses,
        intrinsics,
        inputs.visibility,
        inputs.observed_colors,
        inputs.observed_pixels,
    )
    lidar_proximity = _proximity_score(positions, lidar_points, lidar_confidence)
    if inputs.component_sizes is None:
        component_sizes = _component_sizes(positions, scales)
    else:
        component_sizes = np.asarray(inputs.component_sizes, dtype=np.float64)
        if component_sizes.shape != (count,) or not np.isfinite(component_sizes).all() or np.any(component_sizes < 0.0):
            raise ValueError("component_sizes must be a non-negative finite (N,) array")
    component_size = _unit_interval(component_sizes / 3.0)
    if inputs.corridor_points is None:
        corridor_distance = np.ones(count, dtype=np.float64)
    else:
        corridor_points = _as_float("corridor_points", inputs.corridor_points, (3,))
        corridor_distance = _unit_interval(
            1.0 - np.min(np.linalg.norm(positions[:, None, :] - corridor_points[None, :, :], axis=2), axis=1) / 3.0
        ) if len(corridor_points) else np.zeros(count, dtype=np.float64)

    components = {
        "opacity": opacity,
        "scale": scale,
        "anisotropy": anisotropy,
        "view_count": view_count,
        "view_angle_diversity": diversity,
        "reprojection_consistency": reprojection,
        "lidar_proximity": lidar_proximity,
        "component_size": component_size,
        "corridor_distance": corridor_distance,
        "view_support": view_support,
    }
    total = _unit_interval(
        0.10 * opacity + 0.08 * scale + 0.08 * anisotropy + 0.08 * component_size
        + 0.06 * corridor_distance + 0.30 * view_support + 0.18 * lidar_proximity
        + 0.06 * reprojection + 0.06 * view_count
    )
    direct_support = (view_support >= 1.0) | (lidar_proximity >= 0.5)
    conservative = direct_support & (total >= 0.45)
    balanced = direct_support & (total >= 0.60)
    diagnostic = direct_support & (total >= 0.75) & ((view_support >= 1.0) | (lidar_proximity >= 0.8))

    frozen_components = {name: _readonly(value) for name, value in components.items()}
    return EvidenceResult(
        total=_readonly(total),
        components=frozen_components,
        conservative_mask=_readonly(np.asarray(conservative, dtype=bool)),
        balanced_mask=_readonly(np.asarray(balanced, dtype=bool)),
        diagnostic_mask=_readonly(np.asarray(diagnostic, dtype=bool)),
        source_vertex_sha256=inputs.source_vertex_sha256,
    )
