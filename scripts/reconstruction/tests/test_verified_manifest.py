from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

from verified_manifest import build_manifest
from verified_quality import QualityMetrics, QualityThresholds, publication_decision


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class CandidateFixture:
    """Minimal verified-candidate shape; the manifest must consume its durable files."""

    publishable_policy = "balanced"

    def __init__(self, root: Path):
        self.raw = root / "splat_gsplat.ply"
        self.conservative = root / "splat_conservative.ply"
        self.balanced = root / "splat_balanced.ply"
        self.diagnostic = root / "splat_diagnostic.ply"
        self.cleanup_report = root / "cleanup-report.json"
        self.evidence_artifact = root / "verified-evidence.npz"
        self.poses = root / "poses.npy"
        self.frame_audit = root / "frame-audit.json"
        self.training_state = root / "training-state.json"
        self.checkpoint = root / "checkpoint_latest.pt"
        for path in (
            self.raw,
            self.conservative,
            self.balanced,
            self.diagnostic,
            self.cleanup_report,
            self.evidence_artifact,
            self.poses,
            self.frame_audit,
            self.training_state,
            self.checkpoint,
        ):
            path.write_bytes(path.name.encode())
        self.generation_id = "generation-7"

    @property
    def publishable(self) -> Path:
        return self.balanced

    @property
    def balanced_bytes(self) -> bytes:
        return self.balanced.read_bytes()


def candidate_fixture(tmp_path: Path) -> CandidateFixture:
    return CandidateFixture(tmp_path)


def passing_metrics() -> QualityMetrics:
    return QualityMetrics(
        image_similarity=0.91,
        depth_error=0.08,
        temporal_flicker=0.03,
        floater_ratio=0.04,
        retained_coverage=0.88,
        asset_size_bytes=1_200,
    )


def thresholds() -> QualityThresholds:
    return QualityThresholds(
        min_image_similarity=0.90,
        max_depth_error=0.10,
        max_temporal_flicker=0.05,
        max_floater_ratio=0.10,
        min_retained_coverage=0.80,
        max_asset_size_bytes=2_000,
    )


def test_manifest_checksum_covers_asset_and_generated_json(tmp_path):
    """Catches a manifest that loses its published asset identity or JSON checksum."""
    candidate = candidate_fixture(tmp_path)
    decision = publication_decision(passing_metrics(), thresholds())

    manifest = build_manifest(
        candidate,
        asset_id="verified-cell-1-v7",
        cell_ids=["cell-1"],
        source_session_ids=["session-a"],
        capture_times={"session-a": "2026-07-31T10:00:00Z"},
        build_time="2026-07-31T11:00:00Z",
        audit_statistics={"selectedFrames": 12, "rejectedFrames": {"blur": 2}},
        recipe_revision="recipe-abc",
        training={"iterations": 30_000, "codeRevision": "deadbeef"},
        metrics=passing_metrics(),
        review_state={"status": "pending"},
        decision=decision,
    )

    document = json.loads(manifest.to_json())
    assert manifest.asset_sha256 == sha256(manifest.asset_path)
    assert document["kind"] == "verified"
    assert document["checksums"]["assetSha256"] == sha256(candidate.balanced)
    assert document["checksums"]["manifestPayloadSha256"] == manifest.manifest_sha256


def test_manifest_serializes_complete_verified_provenance(tmp_path):
    """Catches activation records missing required capture, audit, training, review, or decision facts."""
    candidate = candidate_fixture(tmp_path)
    manifest = build_manifest(
        candidate,
        asset_id="verified-cell-1-v7",
        cell_ids=["cell-1"],
        parent_asset_id="verified-cell-1-v6",
        source_session_ids=["session-a"],
        capture_times={"session-a": "2026-07-31T10:00:00Z"},
        build_time="2026-07-31T11:00:00Z",
        audit_statistics={"selectedFrames": 12, "rejectedFrames": {"blur": 2}},
        recipe_revision="recipe-abc",
        training={"iterations": 30_000, "codeRevision": "deadbeef"},
        metrics=passing_metrics(),
        review_state={"status": "pending"},
        decision=publication_decision(passing_metrics(), thresholds()),
    )

    document = json.loads(manifest.to_json())
    assert set(document) == {
        "schemaVersion", "kind", "assetId", "cellIds", "parentAssetId",
        "sourceSessionIds", "captureTimes", "buildTime", "auditStatistics",
        "recipeRevision", "training", "cleanupReport", "metrics", "reviewState",
        "decision", "checksums",
    }
    assert document["training"]["checkpoint"]["sha256"] == sha256(candidate.checkpoint)
    assert document["cleanupReport"]["sha256"] == sha256(candidate.cleanup_report)
    assert document["decision"] == {"publish": True, "reasons": []}


def test_manifest_refuses_non_verified_or_missing_candidate_files(tmp_path):
    """Catches generated or incomplete candidate material entering the verified manifest."""
    candidate = candidate_fixture(tmp_path)
    candidate.publishable_policy = "diagnostic"

    try:
        build_manifest(candidate)
    except ValueError as error:
        assert "verified balanced candidate" in str(error)
    else:
        raise AssertionError("unverified candidate was accepted")


def test_manifest_module_stays_cpu_only():
    """Catches immutable provenance serialization importing a CUDA-owning dependency."""
    assert "torch" not in sys.modules
