"""Pure, fail-closed quality decisions for verified reconstruction assets.

This module deliberately consumes recorded validation metrics only.  It does
not run validation, inspect trainer exit codes, or import GPU dependencies.
"""
from __future__ import annotations

from dataclasses import dataclass
import math
from numbers import Real
from typing import Any, Mapping


@dataclass(frozen=True)
class QualityMetrics:
    """Recorded metrics from the deterministic validation run."""

    image_similarity: float | None = None
    depth_error: float | None = None
    temporal_flicker: float | None = None
    floater_ratio: float | None = None
    retained_coverage: float | None = None
    asset_size_bytes: int | float | None = None

    def to_dict(self) -> dict[str, float | int | None]:
        """Return the canonical camelCase schema used in persisted manifests."""
        return {
            "imageSimilarity": self.image_similarity,
            "depthError": self.depth_error,
            "temporalFlicker": self.temporal_flicker,
            "floaterRatio": self.floater_ratio,
            "retainedCoverage": self.retained_coverage,
            "assetSizeBytes": self.asset_size_bytes,
        }


@dataclass(frozen=True)
class QualityThresholds:
    """Explicit bounds required before an asset can be published."""

    min_image_similarity: float
    max_depth_error: float
    max_temporal_flicker: float
    max_floater_ratio: float
    min_retained_coverage: float
    max_asset_size_bytes: int | float


@dataclass(frozen=True)
class PublicationDecision:
    """The complete, inspectable outcome of an automated quality gate."""

    publish: bool
    reasons: list[str]

    def to_dict(self) -> dict[str, bool | list[str]]:
        return {"publish": self.publish, "reasons": list(self.reasons)}


_METRIC_RULES = (
    ("image_similarity", "imageSimilarity", "image-similarity", "min_image_similarity", "min", 0.0, 1.0),
    ("depth_error", "depthError", "depth-error", "max_depth_error", "max", 0.0, None),
    ("temporal_flicker", "temporalFlicker", "temporal-flicker", "max_temporal_flicker", "max", 0.0, None),
    ("floater_ratio", "floaterRatio", "floater-ratio", "max_floater_ratio", "max", 0.0, 1.0),
    ("retained_coverage", "retainedCoverage", "retained-coverage", "min_retained_coverage", "min", 0.0, 1.0),
    ("asset_size_bytes", "assetSizeBytes", "asset-size", "max_asset_size_bytes", "max", 0.0, None),
)


def _attribute_or_key(values: Any, name: str) -> Any:
    if isinstance(values, Mapping):
        return values.get(name)
    return getattr(values, name, None)


def _metric_value(metrics: Any, attribute_name: str, json_name: str) -> Any:
    if isinstance(metrics, Mapping):
        return metrics.get(json_name)
    return getattr(metrics, attribute_name, None)


def _valid_number(value: Any, *, minimum: float, maximum: float | None) -> bool:
    if isinstance(value, bool) or not isinstance(value, Real):
        return False
    value = float(value)
    return math.isfinite(value) and value >= minimum and (
        maximum is None or value <= maximum
    )


def _validated_thresholds(thresholds: Any) -> dict[str, float]:
    normalized: dict[str, float] = {}
    for _metric_name, _json_name, _reason, threshold_name, _direction, minimum, maximum in _METRIC_RULES:
        value = _attribute_or_key(thresholds, threshold_name)
        if not _valid_number(value, minimum=minimum, maximum=maximum):
            raise ValueError(f"invalid quality threshold: {threshold_name}")
        normalized[threshold_name] = float(value)
    return normalized


def publication_decision(metrics: QualityMetrics | Mapping[str, Any] | Any,
                         thresholds: QualityThresholds | Mapping[str, Any] | Any) -> PublicationDecision:
    """Return all failed quality checks, without consulting process status.

    Missing and malformed measurements are explicit rejection reasons rather
    than values that can accidentally pass a comparison.  Threshold mistakes
    are configuration errors and are rejected before examining candidate data.
    """
    validated_thresholds = _validated_thresholds(thresholds)
    reasons: list[str] = []
    for metric_name, json_name, reason, threshold_name, direction, minimum, maximum in _METRIC_RULES:
        value = _metric_value(metrics, metric_name, json_name)
        if value is None:
            reasons.append(f"{reason}-missing")
            continue
        if not _valid_number(value, minimum=minimum, maximum=maximum):
            reasons.append(f"{reason}-invalid")
            continue
        numeric_value = float(value)
        threshold = validated_thresholds[threshold_name]
        if (direction == "min" and numeric_value < threshold) or (
            direction == "max" and numeric_value > threshold
        ):
            reasons.append(reason)
    return PublicationDecision(publish=not reasons, reasons=reasons)
