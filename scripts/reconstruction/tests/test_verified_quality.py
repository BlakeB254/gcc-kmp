from __future__ import annotations

import math
import sys

from verified_quality import QualityMetrics, QualityThresholds, publication_decision


def metrics(**overrides):
    values = {
        "image_similarity": 0.91,
        "depth_error": 0.08,
        "temporal_flicker": 0.03,
        "floater_ratio": 0.04,
        "retained_coverage": 0.88,
        "asset_size_bytes": 1_200,
    }
    values.update(overrides)
    return QualityMetrics(**values)


def thresholds(**overrides):
    values = {
        "min_image_similarity": 0.90,
        "max_depth_error": 0.10,
        "max_temporal_flicker": 0.05,
        "max_floater_ratio": 0.10,
        "min_retained_coverage": 0.80,
        "max_asset_size_bytes": 2_000,
    }
    values.update(overrides)
    return QualityThresholds(**values)


def test_failed_candidate_cannot_activate():
    """Catches floater-heavy assets being activated after training exits cleanly."""
    decision = publication_decision(
        metrics(floater_ratio=0.22), thresholds(max_floater_ratio=0.10)
    )

    assert decision.publish is False
    assert decision.reasons == ["floater-ratio"]


def test_gate_reports_every_metric_that_fails():
    """Catches a first-failure return hiding independent publication blockers."""
    decision = publication_decision(
        metrics(image_similarity=0.85, depth_error=0.12, asset_size_bytes=2_001),
        thresholds(),
    )

    assert decision.publish is False
    assert decision.reasons == ["image-similarity", "depth-error", "asset-size"]


def test_missing_and_nonfinite_metrics_are_rejected_without_trainer_status():
    """Catches treating absent or NaN validation as a passing trainer result."""
    decision = publication_decision(
        metrics(image_similarity=None, depth_error=math.nan), thresholds()
    )

    assert decision.publish is False
    assert decision.reasons == ["image-similarity-missing", "depth-error-invalid"]


def test_serialized_metric_mapping_has_the_same_decision_as_typed_metrics():
    """Catches a manifest metric round-trip silently becoming six missing metrics."""
    typed_metrics = metrics(floater_ratio=0.22, retained_coverage=0.70)

    typed_decision = publication_decision(typed_metrics, thresholds())
    serialized_decision = publication_decision(typed_metrics.to_dict(), thresholds())

    assert serialized_decision == typed_decision
    assert serialized_decision.reasons == ["floater-ratio", "retained-coverage"]


def test_canonical_camel_case_mapping_can_publish():
    """Catches persisted metric JSON being rejected despite using the documented schema."""
    decision = publication_decision(metrics().to_dict(), thresholds())

    assert decision.publish is True
    assert decision.reasons == []


def test_snake_case_mapping_is_rejected_as_a_schema_alias():
    """Catches a snake_case alias or unknown key being silently ignored."""
    serialized = metrics().to_dict()
    serialized["depth_error"] = serialized.pop("depthError")
    serialized["unrecordedMetric"] = 1

    decision = publication_decision(serialized, thresholds())

    assert decision.publish is False
    assert decision.reasons == [
        "metric-schema:depth_error",
        "metric-schema:unrecordedMetric",
        "depth-error-missing",
    ]


def test_duplicate_equal_alias_is_rejected_as_an_ambiguous_schema():
    """Catches a duplicate alias looking harmless while making metric ownership ambiguous."""
    serialized = metrics().to_dict()
    serialized["depth_error"] = serialized["depthError"]

    decision = publication_decision(serialized, thresholds())

    assert decision.publish is False
    assert decision.reasons == ["metric-schema:depth_error"]


def test_conflicting_nonfinite_alias_is_rejected_even_when_canonical_metric_passes():
    """Catches an invalid alias being retained while the canonical twin publishes."""
    serialized = metrics().to_dict()
    serialized["depth_error"] = math.nan

    decision = publication_decision(serialized, thresholds())

    assert decision.publish is False
    assert decision.reasons == ["metric-schema:depth_error"]


def test_quality_module_stays_cpu_only():
    """Catches a validation decision importing a CUDA-owning dependency."""
    assert "torch" not in sys.modules
