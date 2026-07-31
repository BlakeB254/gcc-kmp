"""Deterministic validation views and CPU-pure reconstruction metrics.

The public helpers in this module intentionally depend only on NumPy and
Pillow.  GPU-owning imports live inside :func:`main`, which is admitted and
launched by the reconstruction worker rather than during module import.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
from numbers import Integral, Real
from pathlib import Path
from typing import Any, Mapping, Sequence

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
        interpolated.append(InterpolatedView(start, end, float(alpha)))

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
    rows: Sequence[Mapping[str, Any]], output_path: str | Path
) -> tuple[str, ...]:
    """Write labeled ground-truth/render/error rows and return their labels."""
    if not rows:
        raise ValueError("contact sheet requires at least one row")
    prepared: list[tuple[np.ndarray, np.ndarray, np.ndarray, str]] = []
    dimensions: tuple[int, int] | None = None
    row_labels: list[str] = []
    for row in rows:
        ground_truth = _uint8_rgb(row["ground_truth"])
        rendered = _uint8_rgb(row["balanced_render"])
        if ground_truth.shape != rendered.shape:
            raise ValueError("contact sheet ground truth and render shapes must match")
        height, width = ground_truth.shape[:2]
        if dimensions is None:
            dimensions = (width, height)
        elif dimensions != (width, height):
            raise ValueError("all contact sheet rows must have the same dimensions")
        error = np.abs(ground_truth.astype(np.int16) - rendered.astype(np.int16)).astype(np.uint8)
        error_strength = error.max(axis=2)
        heatmap = np.stack(
            (error_strength, error_strength // 4, np.zeros_like(error_strength)), axis=2
        )
        label = (
            f"{row['frame_id']} | PSNR {float(row['psnr']):.2f} dB | "
            f"SSIM {float(row['ssim']):.4f}"
        )
        row_labels.append(label)
        prepared.append((ground_truth, rendered, heatmap, label))

    assert dimensions is not None
    width, height = dimensions
    header_height, label_height = 22, 18
    canvas = Image.new(
        "RGB",
        (width * 3, header_height + len(prepared) * (height + label_height)),
        "white",
    )
    draw = ImageDraw.Draw(canvas)
    column_labels = ("Ground truth", "Balanced render", "Absolute error")
    for column, label in enumerate(column_labels):
        draw.text((column * width + 3, 4), label, fill="black")
    for row_index, (ground_truth, rendered, heatmap, label) in enumerate(prepared):
        top = header_height + row_index * (height + label_height)
        canvas.paste(Image.fromarray(ground_truth), (0, top))
        canvas.paste(Image.fromarray(rendered), (width, top))
        canvas.paste(Image.fromarray(heatmap), (width * 2, top))
        draw.text((3, top + height + 2), label, fill="black")
    output = Path(output_path).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output, quality=92)
    return column_labels + tuple(row_labels)


_METRICS_REPORT_PROVENANCE = object()


@dataclass(frozen=True)
class _CapturedMetricEvidence:
    frame_id: str
    kind: str
    frame_index: int
    mse: float
    ssim: float


class ValidationMetricsReport:
    """Sealed evidence produced only from captured pixel comparisons."""

    __slots__ = (
        "_captured",
        "_evidence_sha256",
        "_expected_captured_count",
        "_interpolated",
        "_provenance",
        "_view_spec_json",
    )

    def __init__(self, *_args, **_kwargs):
        raise TypeError("use build_validation_metrics_report")


def _metrics_report_evidence_sha256(report: ValidationMetricsReport) -> str:
    payload = {
        "expectedCapturedCount": report._expected_captured_count,
        "captured": [
            {
                "frameId": item.frame_id,
                "kind": item.kind,
                "frameIndex": item.frame_index,
                "mse": item.mse,
                "ssim": item.ssim,
            }
            for item in report._captured
        ],
        "interpolated": [dict(item) for item in report._interpolated],
        "viewSpec": report._view_spec_json,
    }
    encoded = json.dumps(
        payload, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _finite_metric_mapping(value: Any, *, name: str) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be a metric mapping")
    copied: dict[str, Any] = {}
    for key, item in value.items():
        if not isinstance(key, str):
            raise ValueError(f"{name} metric keys must be strings")
        if isinstance(item, bool) or not isinstance(item, Real):
            raise ValueError(f"{name} metric {key} must be numeric")
        numeric = float(item)
        if not math.isfinite(numeric):
            raise ValueError(f"{name} metric {key} must be finite")
        if key in ("startIndex", "endIndex"):
            if not isinstance(item, Integral):
                raise ValueError(f"{name} metric {key} must be an integer")
            copied[key] = int(item)
        else:
            copied[key] = numeric
    return copied


def build_validation_metrics_report(
    captured_rows: Sequence[Mapping[str, Any]],
    *,
    interpolated_metrics: Sequence[Mapping[str, Any]] = (),
    view_spec: ValidationViewSpec | None = None,
) -> ValidationMetricsReport:
    """Build sealed metrics evidence by comparing the supplied captured pixels."""
    if not captured_rows:
        raise ValueError("validation metrics require at least one captured comparison")
    captured: list[_CapturedMetricEvidence] = []
    frame_ids: set[str] = set()
    for row in captured_rows:
        if not isinstance(row, Mapping):
            raise ValueError("captured comparison must be a mapping")
        frame_id = row.get("frame_id")
        kind = row.get("kind")
        frame_index = row.get("frame_index")
        if not isinstance(frame_id, str) or not frame_id or frame_id in frame_ids:
            raise ValueError("captured comparison frame IDs must be unique")
        if not isinstance(kind, str) or not kind:
            raise ValueError("captured comparison kind is invalid")
        if isinstance(frame_index, bool) or not isinstance(frame_index, Integral):
            raise ValueError("captured comparison frame index is invalid")
        frame_ids.add(frame_id)
        metrics = captured_image_metrics(
            row.get("ground_truth"), row.get("balanced_render")
        )
        captured.append(_CapturedMetricEvidence(
            frame_id=frame_id,
            kind=kind,
            frame_index=int(frame_index),
            mse=float(metrics["mse"]),
            ssim=float(metrics["ssim"]),
        ))

    report = object.__new__(ValidationMetricsReport)
    report._captured = tuple(captured)
    report._expected_captured_count = len(captured)
    report._interpolated = tuple(
        tuple(sorted(_finite_metric_mapping(metrics, name="interpolated").items()))
        for metrics in interpolated_metrics
    )
    report._view_spec_json = (
        json.dumps(
            view_spec.to_dict(), sort_keys=True, separators=(",", ":"), allow_nan=False
        )
        if view_spec is not None
        else None
    )
    report._provenance = _METRICS_REPORT_PROVENANCE
    report._evidence_sha256 = _metrics_report_evidence_sha256(report)
    return report


def _validation_metrics_document(report: ValidationMetricsReport) -> dict[str, Any]:
    if (
        type(report) is not ValidationMetricsReport
        or report._provenance is not _METRICS_REPORT_PROVENANCE
    ):
        raise ValueError("write_validation_metrics requires a validated metrics report")
    if report._evidence_sha256 != _metrics_report_evidence_sha256(report):
        raise ValueError("validated metrics report evidence integrity check failed")
    if (
        report._expected_captured_count < 1
        or len(report._captured) != report._expected_captured_count
    ):
        raise ValueError("validated metrics report frame count is inconsistent")

    serialized_captured: list[dict[str, Any]] = []
    mses: list[float] = []
    similarities: list[float] = []
    for metrics in report._captured:
        mse = float(metrics.mse)
        ssim = float(metrics.ssim)
        if not math.isfinite(mse) or mse < 0.0 or not math.isfinite(ssim):
            raise ValueError("validated metrics report contains invalid captured metrics")
        psnr: float | str = "Infinity" if mse == 0.0 else -10.0 * math.log10(mse)
        serialized_captured.append({
            "frameId": metrics.frame_id,
            "kind": metrics.kind,
            "frameIndex": metrics.frame_index,
            "mse": mse,
            "psnr": psnr,
            "ssim": ssim,
        })
        mses.append(mse)
        similarities.append(ssim)

    mean_mse = float(np.mean(mses))
    aggregates: dict[str, float | str] = {
        "meanMse": mean_mse,
        "meanPsnr": "Infinity" if mean_mse == 0.0 else -10.0 * math.log10(mean_mse),
        "meanSsim": float(np.mean(similarities)),
    }
    if report._interpolated:
        interpolation_documents = [dict(item) for item in report._interpolated]
        color = [item["color_flicker"] for item in interpolation_documents]
        depth = [item["depth_flicker"] for item in interpolation_documents]
        aggregates["meanColorFlicker"] = float(np.mean(color))
        aggregates["meanDepthFlicker"] = float(np.mean(depth))
    else:
        interpolation_documents = []
    document: dict[str, Any] = {
        "expectedCapturedCount": report._expected_captured_count,
        "captured": serialized_captured,
        "interpolated": interpolation_documents,
        "aggregates": aggregates,
    }
    if report._view_spec_json is not None:
        document["viewSpec"] = json.loads(report._view_spec_json)
    return document


def write_validation_metrics(
    report: ValidationMetricsReport, output_path: str | Path
) -> None:
    """Serialize only sealed, internally recomputed validation evidence."""
    document = _validation_metrics_document(report)
    output = Path(output_path).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as stream:
        json.dump(
            document,
            stream,
            sort_keys=True,
            indent=2,
            allow_nan=False,
        )
        stream.write("\n")


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

    first_frame = Image.open(data_dir / "frames" / "0000.jpg")
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

    captured_rows: list[dict[str, Any]] = []
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
    for kind, indices in captured_groups:
        for index in indices:
            color, _depth = render_pose(poses[index], intrinsics[index])
            ground_truth = np.asarray(Image.open(data_dir / "frames" / f"{index:04d}.jpg"))
            metrics = captured_image_metrics(ground_truth, color)
            frame_id = f"{kind}-{index:04d}"
            Image.fromarray(_uint8_rgb(color)).save(output_dir / f"{frame_id}.png")
            captured_rows.append({
                "frame_id": frame_id,
                "kind": kind,
                "frame_index": index,
                "ground_truth": ground_truth,
                "balanced_render": color,
                **metrics,
            })

    interpolation_metrics: list[dict[str, Any]] = []
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
                Image.fromarray(_uint8_rgb(color)).save(
                    output_dir
                    / f"interpolated-{view.start_index:04d}-{view.end_index:04d}.png"
                )
        metrics = interpolation_flicker_metrics(colors_path, depths_path)
        interpolation_metrics.append({
            "startIndex": view.start_index,
            "endIndex": view.end_index,
            "alpha": view.alpha,
            **metrics,
        })

    metrics_report = build_validation_metrics_report(
        captured_rows,
        interpolated_metrics=interpolation_metrics,
        view_spec=spec,
    )
    write_validation_metrics(metrics_report, output_dir / "validation-metrics.json")
    build_contact_sheet(captured_rows, output_dir / "validation-contact-sheet.jpg")


if __name__ == "__main__":
    main()
