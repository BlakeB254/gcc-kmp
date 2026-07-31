"""Deterministic validation views and CPU-pure reconstruction metrics.

The public helpers in this module intentionally depend only on NumPy and
Pillow.  GPU-owning imports live inside :func:`main`, which is admitted and
launched by the reconstruction worker rather than during module import.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass, field
import hashlib
import io
import json
import math
import os
from numbers import Integral, Real
from pathlib import Path, PurePosixPath
import stat
import tempfile
from typing import Any, Iterable, Iterator, Mapping, Sequence
import weakref

import numpy as np
from PIL import Image, ImageDraw


STANDARDIZED_CATEGORIES = (
    "sidewalk",
    "facade",
    "vegetation",
    "vehicle",
    "ground",
)
MIN_HOLDOUT_ELIGIBLE_VIEWS = 5
POSE_TOLERANCE = 1e-6
ROTATION_TOLERANCE = 1e-4

_CATEGORY_TAGS = {
    "sidewalk": frozenset(("sidewalk", "curb", "pavement")),
    "facade": frozenset(("facade", "building", "building-facade")),
    "vegetation": frozenset(("vegetation", "tree", "grass", "plant")),
    "vehicle": frozenset(("vehicle", "car", "truck", "bus")),
    "ground": frozenset(("ground", "ground-facing")),
}
_VALID_SEMANTIC_TAGS = frozenset().union(*_CATEGORY_TAGS.values())


@dataclass(frozen=True)
class InterpolatedView:
    start_index: int
    end_index: int
    alpha: float


@dataclass(frozen=True)
class StandardizedView:
    category: str
    status: str
    frame_index: int | None


@dataclass(frozen=True)
class ValidationViewSpec:
    """Complete deterministic view selection for one frozen camera path."""

    recorded: tuple[int, ...]
    withheld: tuple[int, ...]
    interpolated: tuple[InterpolatedView, ...]
    standardized: tuple[StandardizedView, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "recorded": list(self.recorded),
            "withheld": list(self.withheld),
            "interpolated": [
                {
                    "startIndex": view.start_index,
                    "endIndex": view.end_index,
                    "alpha": view.alpha,
                }
                for view in self.interpolated
            ],
            "standardized": [
                {
                    "category": view.category,
                    "status": view.status,
                    "frameIndex": view.frame_index,
                }
                for view in self.standardized
            ],
        }


def _validated_poses(camera_poses: Any) -> np.ndarray:
    poses = np.asarray(camera_poses, dtype=np.float64)
    if poses.ndim != 3 or poses.shape[1:] != (4, 4) or len(poses) == 0:
        raise ValueError("camera_poses must be a non-empty Nx4x4 array")
    if not np.isfinite(poses).all():
        raise ValueError("camera_poses must contain only finite values")
    homogeneous_row = np.array([0.0, 0.0, 0.0, 1.0])
    if not np.allclose(poses[:, 3, :], homogeneous_row, atol=POSE_TOLERANCE, rtol=0.0):
        raise ValueError("camera poses must have homogeneous row [0, 0, 0, 1]")
    rotations = poses[:, :3, :3]
    identities = rotations @ np.transpose(rotations, (0, 2, 1))
    if not np.allclose(identities, np.eye(3), atol=ROTATION_TOLERANCE, rtol=0.0):
        raise ValueError("camera pose rotations must be orthonormal")
    if not np.allclose(
        np.linalg.det(rotations), 1.0, atol=ROTATION_TOLERANCE, rtol=0.0
    ):
        raise ValueError("camera pose rotations must be proper rotations")
    return poses


def _arc_length_indices(poses: np.ndarray, count: int) -> tuple[int, ...]:
    count = min(count, len(poses))
    if count == len(poses):
        return tuple(range(len(poses)))
    translations = poses[:, :3, 3]
    arc = np.concatenate(([0.0], np.cumsum(np.linalg.norm(np.diff(translations, axis=0), axis=1))))
    if arc[-1] <= 1e-12:
        return tuple(np.linspace(0, len(poses) - 1, count).round().astype(int))

    chosen: list[int] = []
    for target in np.linspace(0.0, arc[-1], count):
        distances = np.abs(arc - target)
        for index in np.argsort(distances, kind="stable"):
            candidate = int(index)
            if candidate not in chosen:
                chosen.append(candidate)
                break
    return tuple(sorted(chosen))


def _rotation_degrees(left: np.ndarray, right: np.ndarray) -> float:
    relative = left.T @ right
    cosine = float(np.clip((np.trace(relative) - 1.0) * 0.5, -1.0, 1.0))
    return math.degrees(math.acos(cosine))


def _coerce_tags(value: Any) -> frozenset[str]:
    if value is None:
        return frozenset()
    if isinstance(value, Mapping):
        value = value.get("semanticTags", value.get("tags", ()))
    if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
        raise ValueError("semantic categories must be a sequence of category names")
    normalized: list[str] = []
    for tag in value:
        if not isinstance(tag, str) or not tag.strip():
            raise ValueError(f"invalid semantic category: {tag!r}")
        category = tag.strip().lower().replace("_", "-").replace(" ", "-")
        if category not in _VALID_SEMANTIC_TAGS:
            raise ValueError(f"invalid semantic category: {tag!r}")
        if category in normalized:
            raise ValueError(f"duplicate semantic category: {category}")
        normalized.append(category)
    return frozenset(normalized)


def _semantic_frame_index(value: Any) -> int:
    if isinstance(value, bool):
        raise ValueError(f"invalid semantic frame index: {value!r}")
    if isinstance(value, Integral):
        return int(value)
    if isinstance(value, Real):
        raise ValueError(f"invalid semantic frame index: {value!r}")
    if isinstance(value, str) and value.isascii() and value.isdigit():
        return int(value)
    raise ValueError(f"invalid semantic frame index: {value!r}")


def _normalized_frame_tags(frame_tags: Any, pose_count: int) -> dict[int, frozenset[str]]:
    if frame_tags is None:
        return {}
    if isinstance(frame_tags, Mapping) and "frames" in frame_tags:
        frame_tags = frame_tags["frames"]
    normalized: dict[int, frozenset[str]] = {}
    if isinstance(frame_tags, Mapping):
        entries = frame_tags.items()
    else:
        entries = enumerate(frame_tags)
    for fallback_index, value in entries:
        index_value = fallback_index
        if isinstance(value, Mapping):
            index_value = value.get(
                "frameIndex", value.get("index", value.get("frame_id", fallback_index))
            )
        index = _semantic_frame_index(index_value)
        if index < 0 or index >= pose_count:
            raise ValueError(f"semantic frame index out of range: {index}")
        if index in normalized:
            raise ValueError(f"duplicate semantic frame index: {index}")
        normalized[index] = _coerce_tags(value)
    return normalized


def _standardized_views(
    frame_tags: Any, pose_count: int, seed: int
) -> tuple[StandardizedView, ...]:
    tags_by_frame = _normalized_frame_tags(frame_tags, pose_count)
    views: list[StandardizedView] = []
    for category in STANDARDIZED_CATEGORIES:
        candidates = sorted(
            index
            for index, tags in tags_by_frame.items()
            if tags.intersection(_CATEGORY_TAGS[category])
        )
        if not candidates:
            views.append(StandardizedView(category, "not_observed", None))
            continue
        digest = hashlib.sha256(f"{seed}:{category}".encode("utf-8")).digest()
        selected = candidates[int.from_bytes(digest[:8], "big") % len(candidates)]
        views.append(StandardizedView(category, "observed", selected))
    return tuple(views)


def build_view_spec(
    camera_poses: Any,
    *,
    seed: int = 0,
    frame_tags: Any = None,
    recorded_count: int = 12,
    interpolation_alpha: float = 0.5,
    max_translation_m: float = 1.5,
    max_rotation_degrees: float = 20.0,
) -> ValidationViewSpec:
    """Select reproducible captured, withheld, interpolated and semantic views.

    Candidate recorded frames are spaced by cumulative camera-path distance.
    Every fifth candidate is withheld and therefore never appears in
    ``recorded``.  Interpolation is limited to consecutive captured poses and
    uses strict spatial and angular thresholds.
    """
    poses = _validated_poses(camera_poses)
    if isinstance(recorded_count, bool) or recorded_count < 1:
        raise ValueError("recorded_count must be positive")
    if not 0.0 < float(interpolation_alpha) < 1.0:
        raise ValueError("interpolation_alpha must be strictly between zero and one")
    if max_translation_m <= 0.0 or max_rotation_degrees <= 0.0:
        raise ValueError("interpolation thresholds must be positive")

    eligible = _arc_length_indices(poses, int(recorded_count))
    if len(eligible) < MIN_HOLDOUT_ELIGIBLE_VIEWS:
        raise ValueError(
            "validation requires at least 5 eligible views for a nonempty holdout"
        )
    withheld = tuple(index for position, index in enumerate(eligible, 1) if position % 5 == 0)
    recorded = tuple(index for position, index in enumerate(eligible, 1) if position % 5 != 0)

    interpolated: list[InterpolatedView] = []
    for start_index in range(len(poses) - 1):
        end_index = start_index + 1
        translation = float(
            np.linalg.norm(poses[end_index, :3, 3] - poses[start_index, :3, 3])
        )
        rotation = _rotation_degrees(
            poses[start_index, :3, :3], poses[end_index, :3, :3]
        )
        if translation < max_translation_m and rotation < max_rotation_degrees:
            interpolated.append(
                InterpolatedView(start_index, end_index, float(interpolation_alpha))
            )

    return ValidationViewSpec(
        recorded=recorded,
        withheld=withheld,
        interpolated=tuple(interpolated),
        standardized=_standardized_views(frame_tags, len(poses), int(seed)),
    )


def _strict_index_list(value: Any, *, name: str, frame_count: int) -> tuple[int, ...]:
    if not isinstance(value, list):
        raise ValueError(f"validation {name} must be an index list")
    result: list[int] = []
    for raw_index in value:
        if isinstance(raw_index, bool) or not isinstance(raw_index, Integral):
            raise ValueError(f"validation {name} contains an invalid index")
        index = int(raw_index)
        if index < 0 or index >= frame_count:
            raise ValueError(f"validation {name} index out of range: {index}")
        if index in result:
            raise ValueError(f"validation {name} contains duplicate index: {index}")
        result.append(index)
    return tuple(result)


def validation_view_spec_from_dict(
    document: Mapping[str, Any], *, frame_count: int
) -> ValidationViewSpec:
    """Validate and restore a persisted pre-training view partition."""
    if not isinstance(document, Mapping) or frame_count < 1:
        raise ValueError("invalid validation view specification")
    if frame_count < MIN_HOLDOUT_ELIGIBLE_VIEWS:
        raise ValueError(
            "validation requires at least 5 eligible views for a nonempty withheld partition"
        )
    recorded = _strict_index_list(
        document.get("recorded"), name="recorded", frame_count=frame_count
    )
    withheld = _strict_index_list(
        document.get("withheld"), name="withheld", frame_count=frame_count
    )
    if not withheld:
        raise ValueError("validation requires a nonempty withheld partition")
    if set(recorded).intersection(withheld) or set(recorded).union(withheld) != set(
        range(frame_count)
    ):
        raise ValueError("recorded and withheld must be a disjoint complete partition")

    interpolated: list[InterpolatedView] = []
    seen_interpolated: set[tuple[int, int, float]] = set()
    raw_interpolated = document.get("interpolated", [])
    if not isinstance(raw_interpolated, list):
        raise ValueError("validation interpolated must be a list")
    for raw_view in raw_interpolated:
        if not isinstance(raw_view, Mapping):
            raise ValueError("invalid interpolated validation view")
        start = _semantic_frame_index(raw_view.get("startIndex"))
        end = _semantic_frame_index(raw_view.get("endIndex"))
        alpha = raw_view.get("alpha")
        if (
            start < 0
            or end >= frame_count
            or end != start + 1
            or isinstance(alpha, bool)
            or not isinstance(alpha, Real)
            or not math.isfinite(float(alpha))
            or not 0.0 < float(alpha) < 1.0
        ):
            raise ValueError("invalid interpolated validation view")
        identity = (start, end, float(alpha))
        if identity in seen_interpolated:
            raise ValueError("validation contains duplicate interpolated view")
        seen_interpolated.add(identity)
        interpolated.append(InterpolatedView(*identity))

    standardized: list[StandardizedView] = []
    raw_standardized = document.get("standardized", [])
    if not isinstance(raw_standardized, list):
        raise ValueError("validation standardized must be a list")
    seen_categories: set[str] = set()
    for raw_view in raw_standardized:
        if not isinstance(raw_view, Mapping):
            raise ValueError("invalid standardized validation view")
        category = raw_view.get("category")
        status = raw_view.get("status")
        frame_index = raw_view.get("frameIndex")
        if category not in STANDARDIZED_CATEGORIES or category in seen_categories:
            raise ValueError("invalid standardized validation category")
        seen_categories.add(category)
        if status == "not_observed" and frame_index is None:
            standardized.append(StandardizedView(category, status, None))
        elif status == "observed":
            index = _semantic_frame_index(frame_index)
            if index < 0 or index >= frame_count:
                raise ValueError("standardized validation frame index out of range")
            standardized.append(StandardizedView(category, status, index))
        else:
            raise ValueError("invalid standardized validation status")
    return ValidationViewSpec(
        recorded=recorded,
        withheld=withheld,
        interpolated=tuple(interpolated),
        standardized=tuple(standardized),
    )


def write_validation_view_spec(
    camera_poses: Any,
    output_path: str | Path,
    *,
    seed: int = 0,
    frame_tags: Any = None,
) -> ValidationViewSpec:
    """Create and persist the complete split before candidate training."""
    poses = _validated_poses(camera_poses)
    spec = build_view_spec(
        poses,
        seed=seed,
        frame_tags=frame_tags,
        recorded_count=len(poses),
    )
    output = Path(output_path).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as stream:
        json.dump(spec.to_dict(), stream, sort_keys=True, indent=2, allow_nan=False)
        stream.write("\n")
    return spec


def _normalized_image(image: Any, *, name: str) -> np.ndarray:
    array = np.asarray(image)
    if array.ndim not in (2, 3) or (array.ndim == 3 and array.shape[2] not in (1, 3, 4)):
        raise ValueError(f"{name} must be an HxW or HxWxC image")
    if array.size == 0 or not np.issubdtype(array.dtype, np.number):
        raise ValueError(f"{name} must contain numeric pixels")
    normalized = array.astype(np.float64)
    if not np.isfinite(normalized).all():
        raise ValueError(f"{name} must contain only finite pixels")
    if np.issubdtype(array.dtype, np.integer):
        if normalized.min() < 0.0:
            raise ValueError(f"integer {name} pixels must be non-negative")
        storage_max = float(np.iinfo(array.dtype).max)
        pixel_max = 255.0 if storage_max > 255.0 and normalized.max() <= 255.0 else storage_max
        normalized /= pixel_max
    elif normalized.size and (normalized.min() < 0.0 or normalized.max() > 1.0):
        raise ValueError(f"floating-point {name} pixels must be in [0, 1]")
    return normalized


def captured_image_metrics(ground_truth: Any, rendered: Any) -> dict[str, float]:
    """Compute unit-range PSNR and SSIM for a captured reference view."""
    reference = _normalized_image(ground_truth, name="ground_truth")
    candidate = _normalized_image(rendered, name="rendered")
    if reference.shape != candidate.shape:
        raise ValueError("ground_truth and rendered images must have the same shape")
    mse = float(np.mean(np.square(reference - candidate)))
    psnr = float("inf") if mse == 0.0 else -10.0 * math.log10(mse)
    if reference.ndim == 2:
        reference = reference[:, :, None]
        candidate = candidate[:, :, None]
    window = 11
    coordinates = np.arange(window, dtype=np.float64) - window // 2
    gaussian = np.exp(-(coordinates ** 2) / (2.0 * 1.5 ** 2))
    gaussian /= gaussian.sum()

    def gaussian_filter(image: np.ndarray) -> np.ndarray:
        filtered = image
        padding = window // 2
        for axis in (0, 1):
            pads = [(0, 0)] * filtered.ndim
            pads[axis] = (padding, padding)
            padded = np.pad(filtered, pads, mode="constant")
            windows = np.lib.stride_tricks.sliding_window_view(
                padded, window, axis=axis
            )
            filtered = np.tensordot(windows, gaussian, axes=([-1], [0]))
        return filtered

    mu_ref = gaussian_filter(reference)
    mu_candidate = gaussian_filter(candidate)
    variance_ref = gaussian_filter(reference * reference) - mu_ref * mu_ref
    variance_candidate = (
        gaussian_filter(candidate * candidate) - mu_candidate * mu_candidate
    )
    covariance = gaussian_filter(reference * candidate) - mu_ref * mu_candidate
    c1, c2 = 0.01 ** 2, 0.03 ** 2
    ssim_channels = (
        (2.0 * mu_ref * mu_candidate + c1) * (2.0 * covariance + c2)
        / ((mu_ref * mu_ref + mu_candidate * mu_candidate + c1)
           * (variance_ref + variance_candidate + c2))
    )
    ssim = float(np.clip(np.mean(ssim_channels), -1.0, 1.0))
    if mse == 0.0:
        ssim = 1.0
    return {"mse": mse, "psnr": psnr, "ssim": ssim}


def captured_depth_metrics(
    captured_depth: Any,
    balanced_depth: Any,
    confidence: Any,
) -> dict[str, float | int] | None:
    """Compute withheld-view metric depth error on qualified LiDAR pixels.

    ``relative_error`` is the canonical ``meanDepthError`` input (AbsRel).
    Captured depth must be positive and ARKit confidence must be medium/high
    (>=1). A missing qualified pixel set returns ``None`` rather than a
    fabricated zero error.
    """
    reference = np.asarray(captured_depth)
    candidate = np.asarray(balanced_depth)
    confidence_values = np.asarray(confidence)
    if (
        reference.ndim != 2
        or reference.size == 0
        or reference.shape != candidate.shape
        or reference.shape != confidence_values.shape
        or not np.issubdtype(reference.dtype, np.number)
        or not np.issubdtype(candidate.dtype, np.number)
        or not np.issubdtype(confidence_values.dtype, np.number)
    ):
        raise ValueError("captured, balanced, and confidence depth must align as numeric HxW arrays")
    reference = np.asarray(reference, dtype=np.float64)
    candidate = np.asarray(candidate, dtype=np.float64)
    confidence_values = np.asarray(confidence_values, dtype=np.float64)
    if (
        not np.isfinite(reference).all()
        or not np.isfinite(candidate).all()
        or not np.isfinite(confidence_values).all()
        or np.any(confidence_values < 0.0)
        or np.any(confidence_values > 2.0)
        or np.any(confidence_values != np.floor(confidence_values))
    ):
        raise ValueError("captured depth evidence contains invalid values")
    qualified = (confidence_values >= 1.0) & (reference > 0.0)
    qualified_pixels = int(qualified.sum())
    if qualified_pixels == 0:
        return None
    absolute = np.abs(candidate[qualified] - reference[qualified])
    relative = absolute / reference[qualified]
    return {
        "absolute_error_meters": float(np.mean(absolute)),
        "relative_error": float(np.mean(relative)),
        "qualified_pixels": qualified_pixels,
    }


def load_captured_depth_evidence(
    path: str | Path,
    *,
    target_shape: tuple[int, int],
) -> tuple[np.ndarray, np.ndarray] | None:
    """Load exported LiDAR depth/confidence and nearest-resample them together."""
    source = Path(path)
    if not source.is_file():
        return None
    try:
        with np.load(source, allow_pickle=False) as archive:
            if set(archive.files) != {"depthMeters", "confidence"}:
                raise ValueError("captured depth evidence fields are invalid")
            depth = np.asarray(archive["depthMeters"], dtype=np.float64).copy()
            confidence = np.asarray(archive["confidence"]).copy()
    except (OSError, TypeError, ValueError) as error:
        raise ValueError("captured depth evidence is unreadable") from error
    if (
        depth.ndim != 2
        or depth.size == 0
        or depth.shape != confidence.shape
        or not np.isfinite(depth).all()
        or not np.issubdtype(confidence.dtype, np.integer)
        or np.any(confidence < 0)
        or np.any(confidence > 2)
        or len(target_shape) != 2
        or any(
            isinstance(value, bool) or not isinstance(value, Integral) or value < 1
            for value in target_shape
        )
    ):
        raise ValueError("captured depth evidence arrays are invalid")
    target_height, target_width = (int(value) for value in target_shape)
    source_height, source_width = depth.shape
    rows = np.minimum(
        (np.arange(target_height) * source_height // target_height),
        source_height - 1,
    )
    columns = np.minimum(
        (np.arange(target_width) * source_width // target_width),
        source_width - 1,
    )
    return (
        np.ascontiguousarray(depth[rows[:, None], columns[None, :]]),
        np.ascontiguousarray(confidence[rows[:, None], columns[None, :]]),
    )


def aggregate_captured_metrics(
    captured_metrics: Sequence[Mapping[str, Any]],
) -> dict[str, float]:
    """Aggregate PSNR through MSE so mixed perfect views remain finite."""
    if not captured_metrics:
        raise ValueError("captured metrics must not be empty")
    mean_squared_errors: list[float] = []
    similarities: list[float] = []
    for metrics in captured_metrics:
        mse = metrics.get("mse")
        psnr = metrics.get("psnr")
        ssim = metrics.get("ssim")
        if (
            isinstance(mse, bool)
            or not isinstance(mse, Real)
            or not math.isfinite(float(mse))
            or float(mse) < 0.0
            or isinstance(ssim, bool)
            or not isinstance(ssim, Real)
            or not math.isfinite(float(ssim))
        ):
            raise ValueError("captured metric is invalid")
        expected_psnr = float("inf") if float(mse) == 0.0 else -10.0 * math.log10(float(mse))
        if (
            isinstance(psnr, bool)
            or not isinstance(psnr, Real)
            or (
                math.isfinite(expected_psnr)
                and (
                    not math.isfinite(float(psnr))
                    or not math.isclose(float(psnr), expected_psnr, abs_tol=1e-9)
                )
            )
            or (math.isinf(expected_psnr) and float(psnr) != expected_psnr)
        ):
            raise ValueError("captured PSNR is invalid")
        mean_squared_errors.append(float(mse))
        similarities.append(float(ssim))
    aggregate_mse = float(np.mean(mean_squared_errors))
    aggregate_psnr = (
        float("inf") if aggregate_mse == 0.0 else -10.0 * math.log10(aggregate_mse)
    )
    return {
        "meanMse": aggregate_mse,
        "meanPsnr": aggregate_psnr,
        "meanSsim": float(np.mean(similarities)),
    }


def interpolation_flicker_metrics(
    color_frames: Sequence[Any], depth_frames: Sequence[Any]
) -> dict[str, float]:
    """Return mean adjacent-frame absolute RGB and metric-depth changes."""
    if len(color_frames) != len(depth_frames) or len(color_frames) < 2:
        raise ValueError("color_frames and depth_frames require the same length of at least two")
    colors = [_normalized_image(frame, name="color frame") for frame in color_frames]
    depths = [np.asarray(frame, dtype=np.float64) for frame in depth_frames]
    color_shape = colors[0].shape
    depth_shape = depths[0].shape
    if any(frame.shape != color_shape for frame in colors):
        raise ValueError("all color frames must have the same shape")
    if any(frame.shape != depth_shape for frame in depths):
        raise ValueError("all depth frames must have the same shape")
    if any(frame.ndim not in (2, 3) or not np.isfinite(frame).all() for frame in depths):
        raise ValueError("depth frames must be finite HxW arrays")
    color_changes = [
        float(np.mean(np.abs(right - left))) for left, right in zip(colors, colors[1:])
    ]
    depth_changes = [
        float(np.mean(np.abs(right - left))) for left, right in zip(depths, depths[1:])
    ]
    return {
        "color_flicker": float(np.mean(color_changes)),
        "depth_flicker": float(np.mean(depth_changes)),
    }


def _uint8_rgb(image: Any) -> np.ndarray:
    normalized = _normalized_image(image, name="contact sheet image")
    if normalized.ndim == 2:
        normalized = np.repeat(normalized[:, :, None], 3, axis=2)
    if normalized.shape[2] == 1:
        normalized = np.repeat(normalized, 3, axis=2)
    if normalized.shape[2] == 4:
        normalized = normalized[:, :, :3]
    return np.round(np.clip(normalized, 0.0, 1.0) * 255.0).astype(np.uint8)


def build_contact_sheet(
    rows: Iterable[Mapping[str, Any]],
    output_path: str | Path,
    *,
    workspace: str | Path | None = None,
) -> tuple[str, ...]:
    """Spool labeled rows and assemble a disk-backed contact sheet."""
    if isinstance(rows, (str, bytes, Mapping)):
        raise ValueError("contact sheet rows must be an iterable of mappings")
    if workspace is None:
        with tempfile.TemporaryDirectory(prefix="validation-contact-sheet-") as temporary:
            return _build_contact_sheet_in_workspace(
                rows, output_path, workspace=Path(temporary)
            )
    spool = Path(workspace).expanduser().resolve()
    spool.mkdir(mode=0o700, parents=True, exist_ok=False)
    return _build_contact_sheet_in_workspace(rows, output_path, workspace=spool)


def _atomic_save_jpeg(image: Image.Image, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".jpg", dir=output.parent
    )
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        image.save(temporary, format="JPEG", quality=92)
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def _build_contact_sheet_in_workspace(
    rows: Iterable[Mapping[str, Any]],
    output_path: str | Path,
    *,
    workspace: Path,
) -> tuple[str, ...]:
    prepared: list[tuple[Path, str]] = []
    dimensions: tuple[int, int] | None = None
    row_labels: list[str] = []
    header_height, label_height = 22, 18
    column_labels = ("Ground truth", "Balanced render", "Absolute error")
    canvas_path = workspace / "contact-sheet-canvas.rgb"
    try:
        row_index = 0
        for row in rows:
            if not isinstance(row, Mapping):
                raise ValueError("contact sheet row must be a mapping")
            ground_truth = _uint8_rgb(row["ground_truth"])
            rendered = _uint8_rgb(row["balanced_render"])
            if ground_truth.shape != rendered.shape:
                raise ValueError(
                    "contact sheet ground truth and render shapes must match"
                )
            height, width = ground_truth.shape[:2]
            if dimensions is None:
                dimensions = (width, height)
            elif dimensions != (width, height):
                raise ValueError("all contact sheet rows must have the same dimensions")
            error = np.abs(
                ground_truth.astype(np.int16) - rendered.astype(np.int16)
            ).astype(np.uint8)
            error_strength = error.max(axis=2)
            heatmap = np.stack(
                (
                    error_strength,
                    error_strength // 4,
                    np.zeros_like(error_strength),
                ),
                axis=2,
            )
            label = (
                f"{row['frame_id']} | PSNR {float(row['psnr']):.2f} dB | "
                f"SSIM {float(row['ssim']):.4f}"
            )
            row_labels.append(label)
            row_image = Image.new("RGB", (width * 3, height + label_height), "white")
            row_image.paste(Image.fromarray(ground_truth), (0, 0))
            row_image.paste(Image.fromarray(rendered), (width, 0))
            row_image.paste(Image.fromarray(heatmap), (width * 2, 0))
            ImageDraw.Draw(row_image).text((3, height + 2), label, fill="black")
            row_path = workspace / f"row-{row_index:06d}.png"
            row_image.save(row_path, format="PNG")
            prepared.append((row_path, label))
            row_image.close()
            del row, ground_truth, rendered, error, error_strength, heatmap, row_image
            row_index += 1

        if dimensions is None:
            raise ValueError("contact sheet requires at least one row")
        width, height = dimensions
        canvas_height = header_height + len(prepared) * (height + label_height)
        canvas = np.memmap(
            canvas_path,
            mode="w+",
            dtype=np.uint8,
            shape=(canvas_height, width * 3, 3),
        )
        header = Image.new("RGB", (width * 3, header_height), "white")
        header_draw = ImageDraw.Draw(header)
        for column, label in enumerate(column_labels):
            header_draw.text((column * width + 3, 4), label, fill="black")
        canvas[:header_height] = np.asarray(header)
        header.close()
        del header, header_draw

        for row_index, (row_path, _label) in enumerate(prepared):
            with Image.open(row_path) as row_image:
                row_pixels = np.asarray(row_image.convert("RGB"))
                top = header_height + row_index * (height + label_height)
                canvas[top : top + height + label_height] = row_pixels
                del row_pixels
            row_path.unlink()
        canvas.flush()
        canvas_image = Image.frombuffer(
            "RGB",
            (width * 3, canvas_height),
            canvas,
            "raw",
            "RGB",
            0,
            1,
        )
        _atomic_save_jpeg(canvas_image, Path(output_path).expanduser())
        canvas_image.close()
        del canvas_image, canvas
        canvas_path.unlink(missing_ok=True)
        return column_labels + tuple(row_labels)
    finally:
        for row_path, _label in prepared:
            row_path.unlink(missing_ok=True)
        canvas_path.unlink(missing_ok=True)


@dataclass(frozen=True)
class _ArraySnapshot:
    relative_path: str
    shape: tuple[int, ...]
    dtype: str
    byte_length: int
    sha256: str


@dataclass(frozen=True)
class _CapturedComparisonSnapshot:
    frame_id: str
    kind: str
    frame_index: int
    ground_truth: _ArraySnapshot
    balanced_render: _ArraySnapshot
    captured_depth: _ArraySnapshot | None = None
    balanced_depth: _ArraySnapshot | None = None
    depth_confidence: _ArraySnapshot | None = None


@dataclass(frozen=True)
class _InterpolatedComparisonSnapshot:
    start_index: int
    end_index: int
    alpha: float
    color_frames: tuple[_ArraySnapshot, ...]
    depth_frames: tuple[_ArraySnapshot, ...]


@dataclass(frozen=True)
class ValidationMetricSnapshots:
    """Small immutable manifest for disk-backed pixel/depth evidence."""

    workspace: Path
    manifest_relative_path: str
    manifest_byte_length: int
    manifest_sha256: str
    include_interpolation: bool
    captured: tuple[_CapturedComparisonSnapshot, ...]
    interpolated: tuple[_InterpolatedComparisonSnapshot, ...]


@dataclass
class ValidationEvidenceInstrumentation:
    """Records and enforces the one-comparison decode bound."""

    live_decoded_evidence: int = 0
    max_live_decoded_evidence: int = 0
    decoded_captured_comparisons: int = 0
    decoded_interpolation_tuples: int = 0
    _active_array_references: tuple[Any, ...] = field(
        default=(), init=False, repr=False, compare=False
    )


_CAPTURED_COMPARISON_FIELDS = frozenset(
    ("frame_id", "kind", "frame_index", "ground_truth", "balanced_render")
)
_CAPTURED_DEPTH_FIELDS = frozenset(
    ("captured_depth", "balanced_depth", "depth_confidence")
)
_INTERPOLATED_COMPARISON_FIELDS = frozenset(
    ("start_index", "end_index", "alpha", "color_frames", "depth_frames")
)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_array_snapshot(
    workspace: Path,
    relative_path: str,
    array: np.ndarray,
) -> _ArraySnapshot:
    portable = np.ascontiguousarray(array, dtype=np.dtype("<f8"))
    destination = workspace / relative_path
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("xb") as stream:
        np.lib.format.write_array(
            stream,
            portable,
            version=(2, 0),
            allow_pickle=False,
        )
    byte_length = destination.stat().st_size
    return _ArraySnapshot(
        relative_path=relative_path,
        shape=tuple(int(dimension) for dimension in portable.shape),
        dtype=portable.dtype.str,
        byte_length=byte_length,
        sha256=_sha256_file(destination),
    )


def _snapshot_image(
    value: Any,
    *,
    workspace: Path,
    relative_path: str,
    name: str,
) -> _ArraySnapshot:
    normalized = _normalized_image(value, name=name)
    return _write_array_snapshot(workspace, relative_path, normalized)


def _snapshot_depth(
    value: Any,
    *,
    workspace: Path,
    relative_path: str,
    name: str,
) -> _ArraySnapshot:
    array = np.asarray(value)
    if (
        array.ndim != 2
        or array.size == 0
        or not np.issubdtype(array.dtype, np.number)
    ):
        raise ValueError(f"{name} must be a non-empty numeric HxW depth array")
    copied = np.ascontiguousarray(array, dtype=np.dtype("<f8"))
    if not np.isfinite(copied).all():
        raise ValueError(f"{name} must contain only finite depth values")
    return _write_array_snapshot(workspace, relative_path, copied)


def _strict_comparison_mapping(
    value: Any, *, fields: frozenset[str], name: str
) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be a mapping")
    copied = dict(value)
    if set(copied) != fields:
        raise ValueError(f"{name} fields must be exactly {sorted(fields)}")
    return copied


def snapshot_validation_comparisons(
    captured_comparisons: Iterable[Mapping[str, Any]],
    *,
    workspace: str | Path,
    interpolated_comparisons: Iterable[Mapping[str, Any]] | None = None,
) -> ValidationMetricSnapshots:
    """Stream raw comparisons into a caller-owned, hash-bound evidence spool."""
    if isinstance(captured_comparisons, (str, bytes, Mapping)):
        raise ValueError("captured comparisons must be an iterable")
    try:
        captured_iterator = iter(captured_comparisons)
    except TypeError as error:
        raise ValueError("captured comparisons must be an iterable") from error
    evidence_workspace = Path(workspace).expanduser().resolve()
    evidence_workspace.mkdir(mode=0o700, parents=True, exist_ok=False)

    captured: list[_CapturedComparisonSnapshot] = []
    ordinal = 0
    for value in captured_iterator:
        if not isinstance(value, Mapping) or set(value) not in (
            _CAPTURED_COMPARISON_FIELDS,
            _CAPTURED_COMPARISON_FIELDS | _CAPTURED_DEPTH_FIELDS,
        ):
            raise ValueError(
                "captured comparison fields must be the image fields or the complete depth-evidence fields"
            )
        row = dict(value)
        frame_id = row["frame_id"]
        kind = row["kind"]
        frame_index = row["frame_index"]
        if not isinstance(frame_id, str) or not frame_id:
            raise ValueError("captured comparison frame ID is invalid")
        if not isinstance(kind, str) or not kind:
            raise ValueError("captured comparison kind is invalid")
        if isinstance(frame_index, bool) or not isinstance(frame_index, Integral):
            raise ValueError("captured comparison frame index is invalid")
        ground_truth = _snapshot_image(
            row["ground_truth"],
            workspace=evidence_workspace,
            relative_path=f"captured/{ordinal:06d}-ground-truth.npy",
            name="captured ground_truth",
        )
        balanced_render = _snapshot_image(
            row["balanced_render"],
            workspace=evidence_workspace,
            relative_path=f"captured/{ordinal:06d}-balanced-render.npy",
            name="captured balanced_render",
        )
        if ground_truth.shape != balanced_render.shape:
            raise ValueError(
                "captured ground_truth and balanced_render must have the same shape"
            )
        captured_depth = balanced_depth = depth_confidence = None
        if _CAPTURED_DEPTH_FIELDS <= set(row):
            if kind != "withheld":
                raise ValueError("captured depth evidence is allowed only for withheld views")
            captured_depth = _snapshot_depth(
                row["captured_depth"],
                workspace=evidence_workspace,
                relative_path=f"captured/{ordinal:06d}-captured-depth.npy",
                name="captured depth",
            )
            balanced_depth = _snapshot_depth(
                row["balanced_depth"],
                workspace=evidence_workspace,
                relative_path=f"captured/{ordinal:06d}-balanced-depth.npy",
                name="balanced depth",
            )
            depth_confidence = _snapshot_depth(
                row["depth_confidence"],
                workspace=evidence_workspace,
                relative_path=f"captured/{ordinal:06d}-depth-confidence.npy",
                name="depth confidence",
            )
            if not (
                captured_depth.shape
                == balanced_depth.shape
                == depth_confidence.shape
            ):
                raise ValueError("captured depth evidence arrays must have the same shape")
        captured.append(
            _CapturedComparisonSnapshot(
                frame_id=frame_id,
                kind=kind,
                frame_index=int(frame_index),
                ground_truth=ground_truth,
                balanced_render=balanced_render,
                captured_depth=captured_depth,
                balanced_depth=balanced_depth,
                depth_confidence=depth_confidence,
            )
        )
        del row, value, ground_truth, balanced_render
        ordinal += 1

    include_interpolation = interpolated_comparisons is not None
    if interpolated_comparisons is None:
        interpolated_iterator: Iterator[Mapping[str, Any]] = iter(())
    else:
        if isinstance(interpolated_comparisons, (str, bytes, Mapping)):
            raise ValueError("interpolated comparisons must be an iterable")
        try:
            interpolated_iterator = iter(interpolated_comparisons)
        except TypeError as error:
            raise ValueError("interpolated comparisons must be an iterable") from error
    interpolated: list[_InterpolatedComparisonSnapshot] = []
    ordinal = 0
    for value in interpolated_iterator:
        row = _strict_comparison_mapping(
            value,
            fields=_INTERPOLATED_COMPARISON_FIELDS,
            name="interpolated comparison",
        )
        start_index = row["start_index"]
        end_index = row["end_index"]
        alpha = row["alpha"]
        if (
            isinstance(start_index, bool)
            or not isinstance(start_index, Integral)
            or isinstance(end_index, bool)
            or not isinstance(end_index, Integral)
            or isinstance(alpha, bool)
            or not isinstance(alpha, Real)
            or not math.isfinite(float(alpha))
        ):
            raise ValueError("interpolated comparison identity is invalid")
        color_values = row["color_frames"]
        depth_values = row["depth_frames"]
        if (
            isinstance(color_values, (str, bytes))
            or not isinstance(color_values, Sequence)
            or isinstance(depth_values, (str, bytes))
            or not isinstance(depth_values, Sequence)
            or len(color_values) != 3
            or len(depth_values) != 3
        ):
            raise ValueError(
                "interpolated comparison requires three color and depth frames"
            )
        colors = tuple(
            _snapshot_image(
                frame,
                workspace=evidence_workspace,
                relative_path=f"interpolated/{ordinal:06d}-color-{position}.npy",
                name="interpolated color frame",
            )
            for position, frame in enumerate(color_values)
        )
        depths = tuple(
            _snapshot_depth(
                frame,
                workspace=evidence_workspace,
                relative_path=f"interpolated/{ordinal:06d}-depth-{position}.npy",
                name="interpolated depth frame",
            )
            for position, frame in enumerate(depth_values)
        )
        if len({frame.shape for frame in colors}) != 1:
            raise ValueError("interpolated color frames must have the same shape")
        if len({frame.shape for frame in depths}) != 1:
            raise ValueError("interpolated depth frames must have the same shape")
        if any(color.shape[:2] != depth.shape for color, depth in zip(colors, depths)):
            raise ValueError("interpolated color and depth frame shapes must align")
        interpolated.append(
            _InterpolatedComparisonSnapshot(
                start_index=int(start_index),
                end_index=int(end_index),
                alpha=float(alpha),
                color_frames=colors,
                depth_frames=depths,
            )
        )
        del row, value, color_values, depth_values, colors, depths
        ordinal += 1

    manifest_document = _metric_snapshots_manifest_document(
        include_interpolation=include_interpolation,
        captured=tuple(captured),
        interpolated=tuple(interpolated),
    )
    manifest_payload = (
        json.dumps(
            manifest_document,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")
    manifest_relative_path = "validation-evidence-manifest.json"
    manifest_path = evidence_workspace / manifest_relative_path
    with manifest_path.open("xb") as stream:
        stream.write(manifest_payload)
    return ValidationMetricSnapshots(
        workspace=evidence_workspace,
        manifest_relative_path=manifest_relative_path,
        manifest_byte_length=len(manifest_payload),
        manifest_sha256=hashlib.sha256(manifest_payload).hexdigest(),
        include_interpolation=include_interpolation,
        captured=tuple(captured),
        interpolated=tuple(interpolated),
    )


def _array_snapshot_document(value: _ArraySnapshot) -> dict[str, Any]:
    return {
        "relativePath": value.relative_path,
        "shape": list(value.shape),
        "dtype": value.dtype,
        "byteLength": value.byte_length,
        "sha256": value.sha256,
    }


def _metric_snapshots_manifest_document(
    *,
    include_interpolation: bool,
    captured: tuple[_CapturedComparisonSnapshot, ...],
    interpolated: tuple[_InterpolatedComparisonSnapshot, ...],
) -> dict[str, Any]:
    return {
        "formatVersion": 1,
        "arrayFormat": "numpy-npy-v2-little-endian-float64",
        "includeInterpolation": include_interpolation,
        "captured": [
            {
                "frameId": item.frame_id,
                "kind": item.kind,
                "frameIndex": item.frame_index,
                "groundTruth": _array_snapshot_document(item.ground_truth),
                "balancedRender": _array_snapshot_document(item.balanced_render),
                "capturedDepth": (
                    _array_snapshot_document(item.captured_depth)
                    if item.captured_depth is not None
                    else None
                ),
                "balancedDepth": (
                    _array_snapshot_document(item.balanced_depth)
                    if item.balanced_depth is not None
                    else None
                ),
                "depthConfidence": (
                    _array_snapshot_document(item.depth_confidence)
                    if item.depth_confidence is not None
                    else None
                ),
            }
            for item in captured
        ],
        "interpolated": [
            {
                "startIndex": item.start_index,
                "endIndex": item.end_index,
                "alpha": item.alpha,
                "colorFrames": [
                    _array_snapshot_document(frame) for frame in item.color_frames
                ],
                "depthFrames": [
                    _array_snapshot_document(frame) for frame in item.depth_frames
                ],
            }
            for item in interpolated
        ],
    }


def _copy_array_snapshot(value: Any, *, name: str) -> _ArraySnapshot:
    if not isinstance(value, _ArraySnapshot):
        raise ValueError(f"{name} must be an immutable array descriptor")
    relative_path = value.relative_path
    shape = tuple(value.shape)
    if (
        not isinstance(relative_path, str)
        or not relative_path
        or PurePosixPath(relative_path).is_absolute()
        or ".." in PurePosixPath(relative_path).parts
        or not shape
        or any(
            isinstance(dimension, bool)
            or not isinstance(dimension, Integral)
            or int(dimension) < 1
            for dimension in shape
        )
        or value.dtype != "<f8"
        or isinstance(value.byte_length, bool)
        or not isinstance(value.byte_length, Integral)
        or int(value.byte_length) <= math.prod(int(item) for item in shape) * 8
        or int(value.byte_length)
        > math.prod(int(item) for item in shape) * 8 + 65536
        or not isinstance(value.sha256, str)
        or len(value.sha256) != 64
        or any(character not in "0123456789abcdef" for character in value.sha256)
    ):
        raise ValueError(f"{name} descriptor is invalid")
    return _ArraySnapshot(
        relative_path=relative_path,
        shape=tuple(int(dimension) for dimension in shape),
        dtype="<f8",
        byte_length=int(value.byte_length),
        sha256=value.sha256,
    )


def _copy_metric_snapshots(value: Any) -> ValidationMetricSnapshots:
    """Take a writer-owned copy of the small descriptor manifest."""
    if not isinstance(value, ValidationMetricSnapshots):
        raise ValueError(
            "write_validation_metrics requires immutable pixel/depth snapshots"
        )
    try:
        workspace = value.workspace
        manifest_relative_path = value.manifest_relative_path
        manifest_byte_length = value.manifest_byte_length
        manifest_sha256 = value.manifest_sha256
        include_interpolation = value.include_interpolation
        captured_values = tuple(value.captured)
        interpolated_values = tuple(value.interpolated)
    except (AttributeError, TypeError) as error:
        raise ValueError("validation metric snapshots are invalid") from error

    if (
        not isinstance(workspace, Path)
        or not isinstance(manifest_relative_path, str)
        or manifest_relative_path != "validation-evidence-manifest.json"
        or isinstance(manifest_byte_length, bool)
        or not isinstance(manifest_byte_length, Integral)
        or int(manifest_byte_length) < 2
        or not isinstance(manifest_sha256, str)
        or len(manifest_sha256) != 64
        or any(character not in "0123456789abcdef" for character in manifest_sha256)
        or not isinstance(include_interpolation, bool)
    ):
        raise ValueError("validation metric snapshot manifest is invalid")

    captured: list[_CapturedComparisonSnapshot] = []
    for item in captured_values:
        if not isinstance(item, _CapturedComparisonSnapshot):
            raise ValueError("captured metric snapshot is invalid")
        captured.append(
            _CapturedComparisonSnapshot(
                frame_id=item.frame_id,
                kind=item.kind,
                frame_index=item.frame_index,
                ground_truth=_copy_array_snapshot(
                    item.ground_truth, name="captured ground_truth"
                ),
                balanced_render=_copy_array_snapshot(
                    item.balanced_render, name="captured balanced_render"
                ),
                captured_depth=(
                    _copy_array_snapshot(item.captured_depth, name="captured depth")
                    if item.captured_depth is not None
                    else None
                ),
                balanced_depth=(
                    _copy_array_snapshot(item.balanced_depth, name="balanced depth")
                    if item.balanced_depth is not None
                    else None
                ),
                depth_confidence=(
                    _copy_array_snapshot(item.depth_confidence, name="depth confidence")
                    if item.depth_confidence is not None
                    else None
                ),
            )
        )

    interpolated: list[_InterpolatedComparisonSnapshot] = []
    for item in interpolated_values:
        if not isinstance(item, _InterpolatedComparisonSnapshot):
            raise ValueError("interpolated metric snapshot is invalid")
        interpolated.append(
            _InterpolatedComparisonSnapshot(
                start_index=item.start_index,
                end_index=item.end_index,
                alpha=item.alpha,
                color_frames=tuple(
                    _copy_array_snapshot(frame, name="interpolated color frame")
                    for frame in tuple(item.color_frames)
                ),
                depth_frames=tuple(
                    _copy_array_snapshot(frame, name="interpolated depth frame")
                    for frame in tuple(item.depth_frames)
                ),
            )
        )
    if not include_interpolation and interpolated:
        raise ValueError("omitted interpolation evidence must be empty")
    return ValidationMetricSnapshots(
        workspace=workspace.expanduser().resolve(),
        manifest_relative_path=manifest_relative_path,
        manifest_byte_length=int(manifest_byte_length),
        manifest_sha256=manifest_sha256,
        include_interpolation=include_interpolation,
        captured=tuple(captured),
        interpolated=tuple(interpolated),
    )


def _verified_payload(
    workspace: Path,
    relative_path: str,
    *,
    byte_length: int,
    sha256: str,
    name: str,
) -> bytes:
    candidate = workspace / relative_path
    if candidate.is_symlink():
        raise ValueError(f"{name} must not be a symbolic link")
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(workspace)
        file_status = resolved.stat()
    except (FileNotFoundError, OSError, ValueError) as error:
        raise ValueError(f"{name} path is invalid") from error
    if not stat.S_ISREG(file_status.st_mode):
        raise ValueError(f"{name} must be a regular file")
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(resolved, flags)
    except OSError as error:
        raise ValueError(f"{name} could not be opened safely") from error
    try:
        opened_status = os.fstat(descriptor)
        if not stat.S_ISREG(opened_status.st_mode) or opened_status.st_size != byte_length:
            raise ValueError(f"{name} byte length does not match its descriptor")
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            payload = stream.read()
    finally:
        os.close(descriptor)
    if len(payload) != byte_length:
        raise ValueError(f"{name} byte length does not match its descriptor")
    if hashlib.sha256(payload).hexdigest() != sha256:
        raise ValueError(f"{name} SHA-256 does not match its descriptor")
    return payload


def _array_snapshot_from_document(value: Any, *, name: str) -> _ArraySnapshot:
    row = _strict_comparison_mapping(
        value,
        fields=frozenset(("relativePath", "shape", "dtype", "byteLength", "sha256")),
        name=f"{name} descriptor",
    )
    shape = row["shape"]
    if not isinstance(shape, list):
        raise ValueError(f"{name} descriptor shape is invalid")
    return _copy_array_snapshot(
        _ArraySnapshot(
            relative_path=row["relativePath"],
            shape=tuple(shape),
            dtype=row["dtype"],
            byte_length=row["byteLength"],
            sha256=row["sha256"],
        ),
        name=name,
    )


def _snapshots_from_manifest_document(
    document: Any,
    *,
    evidence: ValidationMetricSnapshots,
) -> ValidationMetricSnapshots:
    row = _strict_comparison_mapping(
        document,
        fields=frozenset(
            (
                "formatVersion",
                "arrayFormat",
                "includeInterpolation",
                "captured",
                "interpolated",
            )
        ),
        name="validation evidence manifest",
    )
    if (
        row["formatVersion"] != 1
        or row["arrayFormat"] != "numpy-npy-v2-little-endian-float64"
        or not isinstance(row["includeInterpolation"], bool)
        or not isinstance(row["captured"], list)
        or not isinstance(row["interpolated"], list)
    ):
        raise ValueError("validation evidence manifest format is invalid")
    captured: list[_CapturedComparisonSnapshot] = []
    for raw_item in row["captured"]:
        item = _strict_comparison_mapping(
            raw_item,
            fields=frozenset(
                (
                    "frameId",
                    "kind",
                    "frameIndex",
                    "groundTruth",
                    "balancedRender",
                    "capturedDepth",
                    "balancedDepth",
                    "depthConfidence",
                )
            ),
            name="captured evidence manifest item",
        )
        captured.append(
            _CapturedComparisonSnapshot(
                frame_id=item["frameId"],
                kind=item["kind"],
                frame_index=item["frameIndex"],
                ground_truth=_array_snapshot_from_document(
                    item["groundTruth"], name="captured ground_truth"
                ),
                balanced_render=_array_snapshot_from_document(
                    item["balancedRender"], name="captured balanced_render"
                ),
                captured_depth=(
                    _array_snapshot_from_document(
                        item["capturedDepth"], name="captured depth"
                    )
                    if item["capturedDepth"] is not None
                    else None
                ),
                balanced_depth=(
                    _array_snapshot_from_document(
                        item["balancedDepth"], name="balanced depth"
                    )
                    if item["balancedDepth"] is not None
                    else None
                ),
                depth_confidence=(
                    _array_snapshot_from_document(
                        item["depthConfidence"], name="depth confidence"
                    )
                    if item["depthConfidence"] is not None
                    else None
                ),
            )
        )
    interpolated: list[_InterpolatedComparisonSnapshot] = []
    for raw_item in row["interpolated"]:
        item = _strict_comparison_mapping(
            raw_item,
            fields=frozenset(
                ("startIndex", "endIndex", "alpha", "colorFrames", "depthFrames")
            ),
            name="interpolated evidence manifest item",
        )
        if not isinstance(item["colorFrames"], list) or not isinstance(
            item["depthFrames"], list
        ):
            raise ValueError("interpolated evidence manifest arrays are invalid")
        interpolated.append(
            _InterpolatedComparisonSnapshot(
                start_index=item["startIndex"],
                end_index=item["endIndex"],
                alpha=item["alpha"],
                color_frames=tuple(
                    _array_snapshot_from_document(
                        frame, name="interpolated color frame"
                    )
                    for frame in item["colorFrames"]
                ),
                depth_frames=tuple(
                    _array_snapshot_from_document(
                        frame, name="interpolated depth frame"
                    )
                    for frame in item["depthFrames"]
                ),
            )
        )
    parsed = ValidationMetricSnapshots(
        workspace=evidence.workspace,
        manifest_relative_path=evidence.manifest_relative_path,
        manifest_byte_length=evidence.manifest_byte_length,
        manifest_sha256=evidence.manifest_sha256,
        include_interpolation=row["includeInterpolation"],
        captured=tuple(captured),
        interpolated=tuple(interpolated),
    )
    return _copy_metric_snapshots(parsed)


def _load_evidence_manifest(value: Any) -> ValidationMetricSnapshots:
    evidence = _copy_metric_snapshots(value)
    payload = _verified_payload(
        evidence.workspace,
        evidence.manifest_relative_path,
        byte_length=evidence.manifest_byte_length,
        sha256=evidence.manifest_sha256,
        name="validation evidence manifest",
    )
    try:
        document = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("validation evidence manifest is invalid JSON") from error
    parsed = _snapshots_from_manifest_document(document, evidence=evidence)
    if (
        parsed.include_interpolation != evidence.include_interpolation
        or parsed.captured != evidence.captured
        or parsed.interpolated != evidence.interpolated
    ):
        raise ValueError("validation evidence descriptors do not match their manifest")
    return parsed


def _array_from_snapshot(
    workspace: Path,
    snapshot: _ArraySnapshot,
    *,
    name: str,
    depth: bool = False,
) -> np.ndarray:
    payload = _verified_payload(
        workspace,
        snapshot.relative_path,
        byte_length=snapshot.byte_length,
        sha256=snapshot.sha256,
        name=name,
    )
    stream = io.BytesIO(payload)
    try:
        array = np.load(stream, allow_pickle=False)
    except (OSError, TypeError, ValueError) as error:
        raise ValueError(f"{name} is not a valid NumPy snapshot") from error
    if stream.tell() != len(payload):
        raise ValueError(f"{name} snapshot contains trailing bytes")
    if (
        array.shape != snapshot.shape
        or array.dtype.str != snapshot.dtype
        or not array.flags.c_contiguous
        or array.nbytes != math.prod(snapshot.shape) * np.dtype(snapshot.dtype).itemsize
    ):
        raise ValueError(f"{name} snapshot does not match its descriptor")
    if not np.isfinite(array).all():
        raise ValueError(f"{name} snapshot must contain only finite values")
    if depth:
        if array.ndim != 2:
            raise ValueError(f"{name} snapshot must be an HxW depth array")
    elif array.ndim not in (2, 3) or (
        array.ndim == 3 and array.shape[2] not in (1, 3, 4)
    ):
        raise ValueError(f"{name} snapshot must be an HxW or HxWxC image")
    if not depth and (array.min() < 0.0 or array.max() > 1.0):
        raise ValueError(f"{name} snapshot pixels must be in [0, 1]")
    array.setflags(write=False)
    return array


def _validated_metrics_view_spec(value: Any) -> ValidationViewSpec:
    if isinstance(value, ValidationViewSpec):
        document = value.to_dict()
    elif isinstance(value, Mapping):
        document = dict(value)
    else:
        raise ValueError("validation metrics require the persisted validation view spec")
    try:
        persisted = json.loads(json.dumps(document, allow_nan=False))
    except (TypeError, ValueError) as error:
        raise ValueError("persisted validation view spec is invalid") from error
    recorded = persisted.get("recorded")
    withheld = persisted.get("withheld")
    if not isinstance(recorded, list) or not isinstance(withheld, list):
        raise ValueError("persisted validation view spec is invalid")
    frame_count = len(recorded) + len(withheld)
    return validation_view_spec_from_dict(persisted, frame_count=frame_count)


def _validate_evidence_coverage(
    evidence: ValidationMetricSnapshots,
    spec: ValidationViewSpec,
) -> int:
    expected_captured = {
        *(("recorded", index) for index in spec.recorded),
        *(("withheld", index) for index in spec.withheld),
    }
    actual_captured: list[tuple[str, int]] = []
    frame_ids: set[str] = set()
    for item in evidence.captured:
        if (
            not isinstance(item.frame_id, str)
            or not item.frame_id
            or item.frame_id in frame_ids
            or not isinstance(item.kind, str)
            or isinstance(item.frame_index, bool)
            or not isinstance(item.frame_index, Integral)
        ):
            raise ValueError("validation metrics require exact captured coverage")
        frame_ids.add(item.frame_id)
        actual_captured.append((item.kind, int(item.frame_index)))
        if item.ground_truth.shape != item.balanced_render.shape:
            raise ValueError("captured comparison images must have the same shape")
        depth_descriptors = (
            item.captured_depth,
            item.balanced_depth,
            item.depth_confidence,
        )
        present_depth_descriptors = sum(
            descriptor is not None for descriptor in depth_descriptors
        )
        if present_depth_descriptors not in (0, 3):
            raise ValueError("captured depth evidence must be complete")
        if present_depth_descriptors == 3 and (
            item.kind != "withheld"
            or len({descriptor.shape for descriptor in depth_descriptors}) != 1
        ):
            raise ValueError("captured depth evidence must align with a withheld view")
    if (
        len(actual_captured) != len(expected_captured)
        or len(set(actual_captured)) != len(actual_captured)
        or set(actual_captured) != expected_captured
    ):
        raise ValueError("validation metrics require exact captured coverage")

    if evidence.include_interpolation:
        expected_interpolated = {
            (view.start_index, view.end_index, view.alpha)
            for view in spec.interpolated
        }
        actual_interpolated: list[tuple[int, int, float]] = []
        for item in evidence.interpolated:
            if (
                isinstance(item.start_index, bool)
                or not isinstance(item.start_index, Integral)
                or isinstance(item.end_index, bool)
                or not isinstance(item.end_index, Integral)
                or isinstance(item.alpha, bool)
                or not isinstance(item.alpha, Real)
                or not math.isfinite(float(item.alpha))
            ):
                raise ValueError("validation metrics require exact interpolated coverage")
            actual_interpolated.append(
                (int(item.start_index), int(item.end_index), float(item.alpha))
            )
            if len(item.color_frames) != 3 or len(item.depth_frames) != 3:
                raise ValueError(
                    "interpolated comparison requires three color and depth frames"
                )
            if len({frame.shape for frame in item.color_frames}) != 1:
                raise ValueError("interpolated color frames must have the same shape")
            if len({frame.shape for frame in item.depth_frames}) != 1:
                raise ValueError("interpolated depth frames must have the same shape")
            if any(
                color.shape[:2] != depth.shape
                for color, depth in zip(item.color_frames, item.depth_frames)
            ):
                raise ValueError("interpolated color and depth frame shapes must align")
        if (
            len(actual_interpolated) != len(expected_interpolated)
            or len(set(actual_interpolated)) != len(actual_interpolated)
            or set(actual_interpolated) != expected_interpolated
        ):
            raise ValueError("validation metrics require exact interpolated coverage")
    elif evidence.interpolated:
        raise ValueError("omitted interpolation evidence must be empty")

    descriptor_paths = [
        descriptor.relative_path
        for item in evidence.captured
        for descriptor in (item.ground_truth, item.balanced_render)
    ]
    descriptor_paths.extend(
        descriptor.relative_path
        for item in evidence.captured
        for descriptor in (
            item.captured_depth,
            item.balanced_depth,
            item.depth_confidence,
        )
        if descriptor is not None
    )
    descriptor_paths.extend(
        descriptor.relative_path
        for item in evidence.interpolated
        for descriptor in (*item.color_frames, *item.depth_frames)
    )
    if len(descriptor_paths) != len(set(descriptor_paths)):
        raise ValueError("validation evidence descriptor paths must be unique")
    return len(expected_captured)


def _validated_instrumentation(
    value: ValidationEvidenceInstrumentation | None,
) -> ValidationEvidenceInstrumentation:
    if value is None:
        return ValidationEvidenceInstrumentation()
    if not isinstance(value, ValidationEvidenceInstrumentation):
        raise ValueError("validation evidence instrumentation is invalid")
    if value.live_decoded_evidence != 0 or any(
        reference() is not None for reference in value._active_array_references
    ):
        raise ValueError("validation evidence instrumentation is already active")
    value._active_array_references = ()
    return value


@contextmanager
def _decoded_evidence_slot(
    instrumentation: ValidationEvidenceInstrumentation,
    *,
    kind: str,
) -> Iterator[None]:
    if instrumentation.live_decoded_evidence != 0 or any(
        reference() is not None
        for reference in instrumentation._active_array_references
    ):
        raise RuntimeError("validation decoded more than one evidence comparison")
    instrumentation._active_array_references = ()
    instrumentation.live_decoded_evidence += 1
    instrumentation.max_live_decoded_evidence = max(
        instrumentation.max_live_decoded_evidence,
        instrumentation.live_decoded_evidence,
    )
    if kind == "captured":
        instrumentation.decoded_captured_comparisons += 1
    elif kind == "interpolated":
        instrumentation.decoded_interpolation_tuples += 1
    else:
        raise RuntimeError("invalid validation evidence kind")
    failed = False
    try:
        yield
    except BaseException:
        failed = True
        raise
    finally:
        retained = tuple(
            reference
            for reference in instrumentation._active_array_references
            if reference() is not None
        )
        instrumentation._active_array_references = retained
        instrumentation.live_decoded_evidence = 1 if retained else 0
        if retained and not failed:
            raise RuntimeError(
                "validation retained decoded evidence after comparison processing"
            )


def _register_decoded_arrays(
    instrumentation: ValidationEvidenceInstrumentation,
    arrays: Iterable[np.ndarray],
) -> None:
    if instrumentation.live_decoded_evidence != 1:
        raise RuntimeError("validation decoded arrays outside an evidence slot")
    references = tuple(weakref.ref(array) for array in arrays)
    if not references:
        raise RuntimeError("validation evidence slot decoded no arrays")
    instrumentation._active_array_references = references


def _validation_metrics_document(
    snapshots: ValidationMetricSnapshots,
    view_spec: Any,
    *,
    instrumentation: ValidationEvidenceInstrumentation | None = None,
) -> dict[str, Any]:
    evidence = _load_evidence_manifest(snapshots)
    spec = _validated_metrics_view_spec(view_spec)
    expected_captured_count = _validate_evidence_coverage(evidence, spec)
    decode_stats = _validated_instrumentation(instrumentation)

    serialized_captured: list[dict[str, Any]] = []
    mse_sum = 0.0
    similarity_sum = 0.0
    captured_count = 0
    depth_absolute_sum = 0.0
    depth_relative_sum = 0.0
    depth_qualified_pixels = 0
    for item in evidence.captured:
        depth_metrics = None
        with _decoded_evidence_slot(decode_stats, kind="captured"):
            ground_truth = _array_from_snapshot(
                evidence.workspace,
                item.ground_truth,
                name="captured ground_truth",
            )
            balanced_render = _array_from_snapshot(
                evidence.workspace,
                item.balanced_render,
                name="captured balanced_render",
            )
            decoded_arrays = [ground_truth, balanced_render]
            if item.captured_depth is not None:
                captured_depth = _array_from_snapshot(
                    evidence.workspace,
                    item.captured_depth,
                    name="captured depth",
                    depth=True,
                )
                balanced_depth = _array_from_snapshot(
                    evidence.workspace,
                    item.balanced_depth,
                    name="balanced depth",
                    depth=True,
                )
                depth_confidence = _array_from_snapshot(
                    evidence.workspace,
                    item.depth_confidence,
                    name="depth confidence",
                    depth=True,
                )
                decoded_arrays.extend(
                    (captured_depth, balanced_depth, depth_confidence)
                )
                depth_metrics = captured_depth_metrics(
                    captured_depth, balanced_depth, depth_confidence
                )
            _register_decoded_arrays(
                decode_stats,
                decoded_arrays,
            )
            metrics = captured_image_metrics(ground_truth, balanced_render)
            mse = float(metrics["mse"])
            ssim = float(metrics["ssim"])
            del ground_truth, balanced_render, decoded_arrays, metrics
            if item.captured_depth is not None:
                del captured_depth, balanced_depth, depth_confidence
        psnr: float | str = "Infinity" if mse == 0.0 else -10.0 * math.log10(mse)
        captured_document = {
            "frameId": item.frame_id,
            "kind": item.kind,
            "frameIndex": int(item.frame_index),
            "mse": mse,
            "psnr": psnr,
            "ssim": ssim,
        }
        if depth_metrics is not None:
            qualified = int(depth_metrics["qualified_pixels"])
            captured_document.update(
                {
                    "depthAbsoluteErrorMeters": float(
                        depth_metrics["absolute_error_meters"]
                    ),
                    "depthError": float(depth_metrics["relative_error"]),
                    "depthQualifiedPixels": qualified,
                }
            )
            depth_absolute_sum += (
                float(depth_metrics["absolute_error_meters"]) * qualified
            )
            depth_relative_sum += float(depth_metrics["relative_error"]) * qualified
            depth_qualified_pixels += qualified
        serialized_captured.append(captured_document)
        mse_sum += mse
        similarity_sum += ssim
        captured_count += 1

    mean_mse = mse_sum / captured_count
    aggregates: dict[str, float | str] = {
        "meanMse": mean_mse,
        "meanPsnr": "Infinity" if mean_mse == 0.0 else -10.0 * math.log10(mean_mse),
        "meanSsim": similarity_sum / captured_count,
    }
    if any(item.captured_depth is not None for item in evidence.captured):
        aggregates["depthQualifiedPixels"] = depth_qualified_pixels
    if depth_qualified_pixels:
        aggregates["meanDepthAbsoluteErrorMeters"] = (
            depth_absolute_sum / depth_qualified_pixels
        )
        aggregates["meanDepthError"] = depth_relative_sum / depth_qualified_pixels
    if evidence.include_interpolation:
        interpolation_documents: list[dict[str, Any]] = []
        color_flicker_sum = 0.0
        depth_flicker_sum = 0.0
        interpolation_count = 0
        for item in evidence.interpolated:
            with _decoded_evidence_slot(decode_stats, kind="interpolated"):
                colors = tuple(
                    _array_from_snapshot(
                        evidence.workspace,
                        frame,
                        name="interpolated color frame",
                    )
                    for frame in item.color_frames
                )
                depths = tuple(
                    _array_from_snapshot(
                        evidence.workspace,
                        frame,
                        name="interpolated depth frame",
                        depth=True,
                    )
                    for frame in item.depth_frames
                )
                _register_decoded_arrays(decode_stats, (*colors, *depths))
                metrics = interpolation_flicker_metrics(colors, depths)
                del colors, depths
            interpolation_documents.append(
                {
                    "startIndex": int(item.start_index),
                    "endIndex": int(item.end_index),
                    "alpha": float(item.alpha),
                    **metrics,
                }
            )
            color_flicker_sum += metrics["color_flicker"]
            depth_flicker_sum += metrics["depth_flicker"]
            interpolation_count += 1
            del metrics
        if interpolation_count:
            aggregates["meanColorFlicker"] = (
                color_flicker_sum / interpolation_count
            )
            aggregates["meanDepthFlicker"] = (
                depth_flicker_sum / interpolation_count
            )
    else:
        interpolation_documents = []
    document: dict[str, Any] = {
        "expectedCapturedCount": expected_captured_count,
        "captured": serialized_captured,
        "interpolated": interpolation_documents,
        "aggregates": aggregates,
        "viewSpec": spec.to_dict(),
    }
    return document


def write_validation_metrics(
    snapshots: ValidationMetricSnapshots,
    output_path: str | Path,
    *,
    view_spec: ValidationViewSpec | Mapping[str, Any],
    instrumentation: ValidationEvidenceInstrumentation | None = None,
) -> dict[str, Any]:
    """Validate descriptors first, then stream pixel-recomputed metrics."""
    document = _validation_metrics_document(
        snapshots,
        view_spec,
        instrumentation=instrumentation,
    )
    output = Path(output_path).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.", suffix=".json", dir=output.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(
                document,
                stream,
                sort_keys=True,
                indent=2,
                allow_nan=False,
            )
            stream.write("\n")
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return document


def build_contact_sheet_from_snapshots(
    snapshots: ValidationMetricSnapshots,
    metrics_document: Mapping[str, Any],
    output_path: str | Path,
    *,
    workspace: str | Path,
    instrumentation: ValidationEvidenceInstrumentation | None = None,
) -> tuple[str, ...]:
    """Verify and decode one captured comparison per spooled contact-sheet row."""
    if not isinstance(metrics_document, Mapping):
        raise ValueError("contact sheet metrics document is invalid")
    evidence = _load_evidence_manifest(snapshots)
    spec = _validated_metrics_view_spec(metrics_document.get("viewSpec"))
    _validate_evidence_coverage(evidence, spec)
    raw_metrics = metrics_document.get("captured")
    if not isinstance(raw_metrics, list):
        raise ValueError("contact sheet captured metrics are invalid")
    by_identity: dict[tuple[str, int], Mapping[str, Any]] = {}
    for raw_item in raw_metrics:
        if not isinstance(raw_item, Mapping):
            raise ValueError("contact sheet captured metric is invalid")
        kind = raw_item.get("kind")
        frame_index = raw_item.get("frameIndex")
        if (
            not isinstance(kind, str)
            or isinstance(frame_index, bool)
            or not isinstance(frame_index, Integral)
            or (kind, int(frame_index)) in by_identity
        ):
            raise ValueError("contact sheet captured metric identity is invalid")
        by_identity[(kind, int(frame_index))] = raw_item
    expected_identities = {
        (item.kind, int(item.frame_index)) for item in evidence.captured
    }
    if set(by_identity) != expected_identities:
        raise ValueError("contact sheet metrics require exact captured coverage")
    decode_stats = _validated_instrumentation(instrumentation)

    def rows() -> Iterator[Mapping[str, Any]]:
        for item in evidence.captured:
            metrics = by_identity[(item.kind, int(item.frame_index))]
            if metrics.get("frameId") != item.frame_id:
                raise ValueError("contact sheet frame ID does not match evidence")
            raw_psnr = metrics.get("psnr")
            raw_ssim = metrics.get("ssim")
            if raw_psnr != "Infinity" and (
                isinstance(raw_psnr, bool)
                or not isinstance(raw_psnr, Real)
                or not math.isfinite(float(raw_psnr))
            ):
                raise ValueError("contact sheet PSNR is invalid")
            if (
                isinstance(raw_ssim, bool)
                or not isinstance(raw_ssim, Real)
                or not math.isfinite(float(raw_ssim))
            ):
                raise ValueError("contact sheet SSIM is invalid")
            with _decoded_evidence_slot(decode_stats, kind="captured"):
                ground_truth = _array_from_snapshot(
                    evidence.workspace,
                    item.ground_truth,
                    name="contact sheet ground_truth",
                )
                balanced_render = _array_from_snapshot(
                    evidence.workspace,
                    item.balanced_render,
                    name="contact sheet balanced_render",
                )
                _register_decoded_arrays(
                    decode_stats,
                    (ground_truth, balanced_render),
                )
                recomputed = captured_image_metrics(
                    ground_truth,
                    balanced_render,
                )
                expected_psnr = float(recomputed["psnr"])
                expected_ssim = float(recomputed["ssim"])
                psnr_matches = (
                    raw_psnr == "Infinity" and math.isinf(expected_psnr)
                ) or (
                    raw_psnr != "Infinity"
                    and math.isfinite(expected_psnr)
                    and math.isclose(
                        float(raw_psnr),
                        expected_psnr,
                        rel_tol=0.0,
                        abs_tol=1e-12,
                    )
                )
                if not psnr_matches or not math.isclose(
                    float(raw_ssim),
                    expected_ssim,
                    rel_tol=0.0,
                    abs_tol=1e-12,
                ):
                    raise ValueError(
                        "contact sheet metric labels do not match pixel evidence"
                    )
                row = {
                    "frame_id": item.frame_id,
                    "ground_truth": ground_truth,
                    "balanced_render": balanced_render,
                    "psnr": expected_psnr,
                    "ssim": expected_ssim,
                }
                try:
                    yield row
                finally:
                    del row, ground_truth, balanced_render, recomputed

    contact_rows = rows()
    try:
        return build_contact_sheet(
            contact_rows,
            output_path,
            workspace=workspace,
        )
    finally:
        contact_rows.close()


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ply", required=True, help="balanced candidate PLY")
    parser.add_argument("--data", required=True, help="exported gsplat dataset")
    parser.add_argument(
        "--manifest", required=True, help="scan manifest containing frame semantic tags"
    )
    parser.add_argument("--out", required=True, help="validation artifact directory")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--recorded-count", type=int, default=12)
    return parser.parse_args(argv)


def _load_manifest_tags(path: str | Path) -> Any:
    with Path(path).expanduser().open(encoding="utf-8") as stream:
        manifest = json.load(stream)
    return manifest.get("frameSemanticTags", manifest.get("frames", ()))


def main(argv: Sequence[str] | None = None) -> None:
    """Render validation artifacts after the worker has admitted this GPU job."""
    args = parse_args(argv)

    # GPU-owning dependencies must stay below the admitted process entrypoint.
    import torch
    from gsplat import rasterization
    from plyfile import PlyData

    # The renderer implementation remains deliberately local to the admitted
    # process.  Pure selection/metric tests never execute or import this path.
    data_dir = Path(args.data).expanduser()
    output_dir = Path(args.out).expanduser()
    output_dir.mkdir(parents=True, exist_ok=True)
    poses = np.load(data_dir / "poses.npy")
    intrinsics = np.load(data_dir / "intrinsics.npy")
    with (data_dir / "validation-view-spec.json").open(encoding="utf-8") as stream:
        persisted_spec = validation_view_spec_from_dict(
            json.load(stream), frame_count=len(poses)
        )
    semantic_spec = build_view_spec(
        poses,
        seed=args.seed,
        frame_tags=_load_manifest_tags(args.manifest),
        recorded_count=len(poses),
    )
    spec = ValidationViewSpec(
        recorded=persisted_spec.recorded,
        withheld=persisted_spec.withheld,
        interpolated=persisted_spec.interpolated,
        standardized=semantic_spec.standardized,
    )

    vertex = PlyData.read(str(Path(args.ply).expanduser()))["vertex"].data
    count = len(vertex)
    means = torch.tensor(
        np.stack([vertex[name] for name in ("x", "y", "z")], 1),
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
        [vertex[f"f_rest_{i}"] for i in range(rest_count)], 1
    ).reshape(count, 3, rest_count // 3).transpose(0, 2, 1)
    colors = torch.tensor(np.concatenate([sh0, rest], 1), dtype=torch.float32, device="cuda")
    sh_degree = int(np.sqrt(rest_count // 3 + 1)) - 1

    with Image.open(data_dir / "frames" / "0000.jpg") as first_frame:
        width, height = first_frame.size

    def render_pose(pose: np.ndarray, intrinsic: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        with torch.no_grad():
            rendered, _alpha, _meta = rasterization(
                means=means,
                quats=torch.nn.functional.normalize(quats, dim=-1),
                scales=scales,
                opacities=opacities,
                colors=colors,
                viewmats=torch.tensor(
                    np.linalg.inv(pose), dtype=torch.float32, device="cuda"
                )[None],
                Ks=torch.tensor(intrinsic, dtype=torch.float32, device="cuda")[None],
                width=width,
                height=height,
                sh_degree=sh_degree,
                packed=False,
                render_mode="RGB+ED",
            )
        values = rendered[0].detach().cpu().numpy()
        return np.clip(values[..., :3], 0.0, 1.0), values[..., 3]

    captured_groups: list[tuple[str, tuple[int, ...]]] = [
        ("recorded", spec.recorded),
        ("withheld", spec.withheld),
    ]
    already_captured = set(spec.recorded).union(spec.withheld)
    for view in spec.standardized:
        if (
            view.status == "observed"
            and view.frame_index is not None
            and view.frame_index not in already_captured
        ):
            captured_groups.append(
                (f"standardized-{view.category}", (view.frame_index,))
            )
            already_captured.add(view.frame_index)

    def captured_comparisons() -> Iterator[Mapping[str, Any]]:
        for kind, indices in captured_groups:
            for index in indices:
                color, rendered_depth = render_pose(poses[index], intrinsics[index])
                with Image.open(
                    data_dir / "frames" / f"{index:04d}.jpg"
                ) as source_image:
                    ground_truth = np.asarray(source_image).copy()
                frame_id = f"{kind}-{index:04d}"
                rendered_image = Image.fromarray(_uint8_rgb(color))
                rendered_image.save(output_dir / f"{frame_id}.png")
                rendered_image.close()
                comparison = {
                    "frame_id": frame_id,
                    "kind": kind,
                    "frame_index": index,
                    "ground_truth": ground_truth,
                    "balanced_render": color,
                }
                depth_evidence = None
                if kind == "withheld":
                    depth_evidence = load_captured_depth_evidence(
                        data_dir / "depth-evidence" / f"{index:04d}.npz",
                        target_shape=rendered_depth.shape,
                    )
                    if depth_evidence is not None:
                        captured_depth, depth_confidence = depth_evidence
                        comparison.update(
                            {
                                "captured_depth": captured_depth,
                                "balanced_depth": rendered_depth,
                                "depth_confidence": depth_confidence,
                            }
                        )
                try:
                    yield comparison
                finally:
                    del comparison, color, rendered_depth, ground_truth, rendered_image
                    if depth_evidence is not None:
                        del captured_depth, depth_confidence
                    del depth_evidence

    def interpolated_comparisons() -> Iterator[Mapping[str, Any]]:
        for view in spec.interpolated:
            colors_path: list[np.ndarray] = []
            depths_path: list[np.ndarray] = []
            for alpha in (0.0, view.alpha, 1.0):
                pose = poses[view.start_index].copy()
                pose[:3, 3] = (
                    (1.0 - alpha) * poses[view.start_index, :3, 3]
                    + alpha * poses[view.end_index, :3, 3]
                )
                # Nearby rotations are projected back to SO(3) after linear blend.
                rotation = (
                    (1.0 - alpha) * poses[view.start_index, :3, :3]
                    + alpha * poses[view.end_index, :3, :3]
                )
                left, _, right = np.linalg.svd(rotation)
                pose[:3, :3] = left @ right
                intrinsic = (
                    (1.0 - alpha) * intrinsics[view.start_index]
                    + alpha * intrinsics[view.end_index]
                )
                color, depth = render_pose(pose, intrinsic)
                colors_path.append(color)
                depths_path.append(depth)
                if alpha == view.alpha:
                    rendered_image = Image.fromarray(_uint8_rgb(color))
                    rendered_image.save(
                        output_dir
                        / f"interpolated-{view.start_index:04d}-{view.end_index:04d}.png"
                    )
                    rendered_image.close()
            comparison = {
                "start_index": view.start_index,
                "end_index": view.end_index,
                "alpha": view.alpha,
                "color_frames": colors_path,
                "depth_frames": depths_path,
            }
            try:
                yield comparison
            finally:
                del comparison, colors_path, depths_path

    with tempfile.TemporaryDirectory(
        prefix=".validation-evidence-", dir=output_dir
    ) as temporary_workspace:
        temporary_root = Path(temporary_workspace)
        metric_snapshots = snapshot_validation_comparisons(
            captured_comparisons(),
            interpolated_comparisons=interpolated_comparisons(),
            workspace=temporary_root / "snapshots",
        )
        metrics_document = write_validation_metrics(
            metric_snapshots,
            output_dir / "validation-metrics.json",
            view_spec=spec,
        )
        build_contact_sheet_from_snapshots(
            metric_snapshots,
            metrics_document,
            output_dir / "validation-contact-sheet.jpg",
            workspace=temporary_root / "contact-sheet",
        )


if __name__ == "__main__":
    main()
