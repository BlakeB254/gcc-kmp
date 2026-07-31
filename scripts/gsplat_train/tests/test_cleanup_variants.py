from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image
from plyfile import PlyData, PlyElement
import pytest


GSPLAT_DIR = Path(__file__).resolve().parents[1]
RECONSTRUCTION_DIR = GSPLAT_DIR.parent / "reconstruction"
sys.path.insert(0, str(GSPLAT_DIR))
sys.path.insert(0, str(RECONSTRUCTION_DIR))

from clean_splat import clean, clean_variants
from evidence_score import EvidenceResult
import gsplat_runner
from fuse_cell import FusionResult


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_splat(path: Path) -> None:
    vertices = np.zeros(
        4,
        dtype=[
            ("x", "f4"),
            ("y", "f4"),
            ("z", "f4"),
            ("opacity", "f4"),
            ("scale_0", "f4"),
            ("scale_1", "f4"),
            ("scale_2", "f4"),
        ],
    )
    vertices["x"] = [10.0, 20.0, 30.0, 40.0]
    vertices["opacity"] = [2.0, 1.0, 0.0, -1.0]
    vertices["scale_0"] = np.log(0.05)
    vertices["scale_1"] = np.log(0.05)
    vertices["scale_2"] = np.log(0.05)
    PlyData([PlyElement.describe(vertices, "vertex")], comments=["raw capture"]).write(path)


def _evidence() -> EvidenceResult:
    def frozen(values, *, dtype=float):
        result = np.asarray(values, dtype=dtype)
        result.setflags(write=False)
        return result

    return EvidenceResult(
        total=frozen([0.95, 0.70, 0.50, 0.10]),
        components={
            "lidar_proximity": frozen([1.0, 0.8, 0.6, 0.0]),
            "view_support": frozen([1.0, 1.0, 0.0, 0.0]),
            "component_size": frozen([1.0, 0.7, 0.4, 0.0]),
        },
        conservative_mask=frozen([True, True, True, False], dtype=bool),
        balanced_mask=frozen([True, True, False, False], dtype=bool),
        diagnostic_mask=frozen([True, False, False, False], dtype=bool),
    )


def test_variants_are_nested_and_source_is_unchanged(tmp_path: Path):
    """Catches policy reversal or cleanup mutating the archival raw PLY."""
    source = tmp_path / "raw.ply"
    output_dir = tmp_path / "candidate"
    _write_splat(source)
    original_sha256 = _sha256(source)

    report = clean_variants(source, _evidence(), output_dir)

    assert report.counts == {
        "source": 4,
        "conservative": 3,
        "balanced": 2,
        "diagnostic": 1,
    }
    assert _sha256(source) == original_sha256
    assert [
        len(PlyData.read(output_dir / name)["vertex"].data)
        for name in (
            "splat_diagnostic.ply",
            "splat_balanced.ply",
            "splat_conservative.ply",
        )
    ] == [1, 2, 3]
    assert PlyData.read(output_dir / "splat_balanced.ply")["vertex"].data["x"].tolist() == [
        10.0,
        20.0,
    ]


def test_cleanup_report_records_checksums_thresholds_histograms_and_reasons(tmp_path: Path):
    """Catches a candidate becoming unauditable after cleanup."""
    source = tmp_path / "raw.ply"
    output_dir = tmp_path / "candidate"
    _write_splat(source)

    report = clean_variants(source, _evidence(), output_dir)
    serialized = json.loads((output_dir / "cleanup-report.json").read_text())

    assert serialized["source"] == {
        "path": str(source.resolve()),
        "sha256": _sha256(source),
        "count": 4,
    }
    assert set(serialized["policyThresholds"]) == {
        "conservative",
        "balanced",
        "diagnostic",
    }
    assert serialized["policyThresholds"]["balanced"]["totalScore"] == 0.6
    assert serialized["counts"] == report.counts
    assert set(serialized["componentScoreHistograms"]) == {
        "lidar_proximity",
        "view_support",
        "component_size",
    }
    assert sum(serialized["componentScoreHistograms"]["view_support"]["counts"]) == 4
    assert serialized["removedReasonCounts"]["balanced"]["policy-mask-rejected"] == 2
    assert serialized["removedReasonCounts"]["balanced"]["below-total-score"] == 2
    assert serialized["removedReasonCounts"]["balanced"]["insufficient-direct-support"] == 1
    for policy in ("conservative", "balanced", "diagnostic"):
        output = output_dir / f"splat_{policy}.ply"
        assert serialized["outputs"][policy] == {
            "path": str(output.resolve()),
            "sha256": _sha256(output),
            "count": report.counts[policy],
            "publishable": policy == "balanced",
        }


def test_cleanup_refuses_to_overwrite_raw_or_existing_different_candidate(tmp_path: Path):
    """Catches reuse of an immutable output name for different evidence."""
    source = tmp_path / "raw.ply"
    output_dir = tmp_path / "candidate"
    _write_splat(source)
    clean_variants(source, _evidence(), output_dir)

    changed = _evidence()
    changed = EvidenceResult(
        total=changed.total,
        components=changed.components,
        conservative_mask=changed.conservative_mask,
        balanced_mask=changed.conservative_mask,
        diagnostic_mask=changed.diagnostic_mask,
    )
    with pytest.raises(FileExistsError, match="immutable cleanup candidate"):
        clean_variants(source, changed, output_dir)

    raw_named_like_output = tmp_path / "splat_balanced.ply"
    _write_splat(raw_named_like_output)
    with pytest.raises(ValueError, match="source PLY"):
        clean_variants(raw_named_like_output, _evidence(), tmp_path)


def test_clean_compatibility_wrapper_returns_the_balanced_result(tmp_path: Path):
    """Catches the legacy entrypoint selecting raw or a diagnostic policy."""
    source = tmp_path / "raw.ply"
    points = tmp_path / "points.npy"
    output = tmp_path / "legacy-clean.ply"
    _write_splat(source)
    np.save(
        points,
        np.column_stack(([10.0, 20.0, 30.0, 40.0], np.zeros(4), np.zeros(4))),
    )

    kept, total = clean(source, points, output)

    balanced = tmp_path / "splat_balanced.ply"
    assert (kept, total) == (len(PlyData.read(balanced)["vertex"].data), 4)
    assert output.read_bytes() == balanced.read_bytes()
    assert (tmp_path / "splat_conservative.ply").is_file()
    assert (tmp_path / "splat_diagnostic.ply").is_file()
    assert (tmp_path / "cleanup-report.json").is_file()
    serialized = json.loads((tmp_path / "cleanup-report.json").read_text())
    assert serialized["policyThresholds"]["balanced"]["minOpacity"] == 0.05
    assert serialized["policyThresholds"]["balanced"]["maxScale"] == 0.35


def test_clean_compatibility_wrapper_cannot_replace_a_named_policy(tmp_path: Path):
    """Catches the balanced legacy copy overwriting another immutable policy."""
    source = tmp_path / "raw.ply"
    points = tmp_path / "points.npy"
    _write_splat(source)
    np.save(
        points,
        np.column_stack(([10.0, 20.0, 30.0, 40.0], np.zeros(4), np.zeros(4))),
    )

    with pytest.raises(ValueError, match="named cleanup policy"):
        clean(source, points, tmp_path / "splat_conservative.ply")


def test_verified_candidate_names_all_durable_artifacts_and_only_balanced_is_publishable(
    tmp_path: Path,
):
    """Catches anonymous bytes losing the audit trail or publishing another policy."""
    staging = tmp_path / "durable"
    manifest = staging / "candidate-manifest"
    manifest.mkdir(parents=True)
    artifacts = {
        "raw": staging / "splat_gsplat.ply",
        "conservative": staging / "splat_conservative.ply",
        "balanced": staging / "splat_balanced.ply",
        "diagnostic": staging / "splat_diagnostic.ply",
        "cleanup_report": staging / "cleanup-report.json",
        "poses": manifest / "poses.npy",
        "frame_audit": manifest / "frame-audit.json",
        "training_state": manifest / "training-state.json",
        "checkpoint": manifest / "checkpoint_latest.pt",
    }
    for name, path in artifacts.items():
        path.write_bytes(b"balanced-ply" if name == "balanced" else name.encode())

    candidate = gsplat_runner.VerifiedCandidate.from_staging(staging)

    assert isinstance(candidate, bytes)
    assert candidate == b"balanced-ply"
    for name, path in artifacts.items():
        assert getattr(candidate, name) == path
    assert candidate.publishable == candidate.balanced
    assert candidate.publishable_policy == "balanced"
    with pytest.raises(AttributeError):
        candidate.balanced = candidate.raw


def test_successful_runner_returns_verified_candidate_without_losing_resume_staging(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    """Catches orchestration reverting to anonymous bytes or temporary artifacts."""
    staging = tmp_path / "durable"
    fusion = FusionResult(
        accepted_session_ids=["session"],
        merged_points=np.column_stack(
            ([10.0, 20.0, 30.0, 40.0], np.zeros(4), np.zeros(4))
        ).astype(np.float32),
        merged_colors=np.full((4, 3), 0.5, dtype=np.float32),
        session_transforms={"session": np.eye(4)},
        anchor_lat=1.0,
        anchor_lng=2.0,
        north_aligned=True,
    )
    poses = np.repeat(np.eye(4, dtype=np.float64)[None], 8, axis=0)
    poses[:, 0, 3] = np.arange(8)

    def fake_export(_manifest, _transform, frames_dir, start_index, _budget):
        for index in range(8):
            Image.fromarray(np.full((4, 4, 3), index, dtype=np.uint8)).save(
                Path(frames_dir) / f"{index:04d}.jpg"
            )
        return (
            list(poses),
            [np.eye(3, dtype=np.float64) for _ in range(8)],
            start_index + 8,
            [{"chunk": 0}],
        )

    def fake_subprocess(command, **_kwargs):
        if command[1].endswith("train_gsplat.py"):
            _write_splat(staging / "splat_gsplat.ply")
            (staging / "checkpoint_latest.pt").write_bytes(b"checkpoint")
            (staging / "training-state.json").write_text('{"status":"completed"}')
        else:
            clean(
                command[command.index("--ply") + 1],
                command[command.index("--points") + 1],
                command[command.index("--out") + 1],
                poses_path=command[command.index("--poses") + 1],
            )
        return type("Result", (), {"returncode": 0})()

    monkeypatch.setattr(gsplat_runner, "available", lambda: True)
    monkeypatch.setattr(gsplat_runner, "_export_session_frames", fake_export)
    monkeypatch.setattr(gsplat_runner.subprocess, "run", fake_subprocess)

    candidate, reason = gsplat_runner.train_gsplat_for_cell(
        "cell", fusion, {"session": {}}, staging_dir=staging
    )

    assert reason is None
    assert isinstance(candidate, gsplat_runner.VerifiedCandidate)
    assert candidate.publishable == staging / "splat_balanced.ply"
    assert candidate.raw.read_bytes() == (staging / "splat_gsplat.ply").read_bytes()
    assert candidate.poses == staging / "candidate-manifest" / "poses.npy"
    assert candidate.frame_audit.is_file()
    assert candidate.training_state.is_file()
    assert candidate.checkpoint.is_file()
