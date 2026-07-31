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
from evidence_score import EvidenceInputs, EvidenceResult, score_gaussians
import gsplat_runner
from fuse_cell import FusionResult
from verified_evidence import (
    candidate_source_checksums,
    load_verified_evidence_artifact,
    ply_fingerprints,
    write_verified_evidence_artifact,
)


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


def _evidence(source: Path) -> EvidenceResult:
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
        source_vertex_sha256=ply_fingerprints(source)[1],
    )


def _artifact_inputs(source: Path, staging: Path) -> EvidenceInputs:
    vertices = PlyData.read(source)["vertex"].data
    positions = np.stack([vertices[name] for name in ("x", "y", "z")], axis=1)
    return EvidenceInputs(
        positions=positions,
        scales=np.exp(
            np.stack([vertices[f"scale_{index}"] for index in range(3)], axis=1)
        ),
        opacities=np.asarray(vertices["opacity"]),
        camera_poses=np.load(staging / "poses.npy"),
        intrinsics=np.load(staging / "intrinsics.npy"),
        lidar_points=positions.copy(),
        lidar_confidence=np.ones(len(positions)),
        source_vertex_sha256=ply_fingerprints(source)[1],
    )


def _valid_candidate_staging(tmp_path: Path) -> Path:
    staging = tmp_path / "durable"
    manifest = staging / "candidate-manifest"
    manifest.mkdir(parents=True)
    source = staging / "splat_gsplat.ply"
    _write_splat(source)
    poses = np.repeat(np.eye(4, dtype=np.float64)[None], 2, axis=0)
    poses[:, 0, 3] = [9.0, 11.0]
    np.save(staging / "poses.npy", poses)
    np.save(staging / "intrinsics.npy", np.repeat(np.eye(3)[None], 2, axis=0))
    source_vertices = PlyData.read(source)["vertex"].data
    np.save(
        staging / "init_points.npy",
        np.stack([source_vertices[name] for name in ("x", "y", "z")], axis=1),
    )
    (staging / "frame-audit.json").write_text('{"version":1}')
    (staging / "training-state.json").write_text('{"status":"completed"}')
    (staging / "checkpoint_latest.pt").write_bytes(b"checkpoint")
    for name in (
        "poses.npy",
        "frame-audit.json",
        "training-state.json",
        "checkpoint_latest.pt",
    ):
        (manifest / name).write_bytes((staging / name).read_bytes())
    inputs = _artifact_inputs(source, staging)
    artifact = write_verified_evidence_artifact(
        staging / "verified-evidence.npz",
        inputs,
        score_gaussians(inputs),
        raw_ply_path=source,
        source_checksums=candidate_source_checksums(staging),
        provenance={"producer": "captured-observation-projector", "captureIds": ["scan-a"]},
    )
    clean_variants(
        source,
        artifact.result,
        staging,
        points_path=staging / "init_points.npy",
        poses_path=staging / "poses.npy",
        verified_evidence=artifact,
    )
    return staging


def test_variants_are_nested_and_source_is_unchanged(tmp_path: Path):
    """Catches policy reversal or cleanup mutating the archival raw PLY."""
    source = tmp_path / "raw.ply"
    output_dir = tmp_path / "candidate"
    _write_splat(source)
    original_sha256 = _sha256(source)

    report = clean_variants(source, _evidence(source), output_dir)

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

    report = clean_variants(source, _evidence(source), output_dir)
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
    clean_variants(source, _evidence(source), output_dir)

    changed = _evidence(source)
    changed = EvidenceResult(
        total=changed.total,
        components=changed.components,
        conservative_mask=changed.conservative_mask,
        balanced_mask=changed.conservative_mask,
        diagnostic_mask=changed.diagnostic_mask,
        source_vertex_sha256=changed.source_vertex_sha256,
    )
    with pytest.raises(FileExistsError, match="immutable cleanup candidate"):
        clean_variants(source, changed, output_dir)

    raw_named_like_output = tmp_path / "splat_balanced.ply"
    _write_splat(raw_named_like_output)
    with pytest.raises(ValueError, match="source PLY"):
        clean_variants(raw_named_like_output, _evidence(raw_named_like_output), tmp_path)


def test_cleanup_rejects_evidence_for_reordered_same_length_vertices(tmp_path: Path):
    """Catches masks silently selecting a different same-sized vertex sequence."""
    source = tmp_path / "raw.ply"
    _write_splat(source)
    evidence = _evidence(source)
    ply = PlyData.read(source)
    reordered = ply["vertex"].data[[1, 0, 2, 3]]
    PlyData([PlyElement.describe(reordered, "vertex")]).write(source)

    with pytest.raises(ValueError, match="vertex order fingerprint"):
        clean_variants(source, evidence, tmp_path / "candidate")


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


def test_clean_compatibility_wrapper_cannot_overwrite_cleanup_report(tmp_path: Path):
    """Catches the balanced legacy copy replacing JSON report bytes with a PLY."""
    source = tmp_path / "raw.ply"
    points = tmp_path / "points.npy"
    _write_splat(source)
    np.save(
        points,
        np.column_stack(([10.0, 20.0, 30.0, 40.0], np.zeros(4), np.zeros(4))),
    )

    with pytest.raises(ValueError, match="cleanup report"):
        clean(source, points, tmp_path / "cleanup-report.json")


def test_report_records_spatial_inputs_and_named_crop_reasons(tmp_path: Path):
    """Catches opaque crop removal counts that cannot explain missing geometry."""
    source = tmp_path / "raw.ply"
    _write_splat(source)
    ply = PlyData.read(source)
    vertices = ply["vertex"].data.copy()
    vertices["x"] = [0.0, 0.0, 100.0, 1.0]
    vertices["y"] = [-1.0, 10.0, 1.0, 1.0]
    PlyData([PlyElement.describe(vertices, "vertex")]).write(source)
    poses = np.eye(4, dtype=np.float64)[None]
    poses[0, 1, 3] = 1.5
    poses_path = tmp_path / "poses.npy"
    np.save(poses_path, poses)
    evidence = _evidence(source)
    all_keep = np.ones(4, dtype=bool)
    evidence = EvidenceResult(
        total=evidence.total,
        components=evidence.components,
        conservative_mask=all_keep,
        balanced_mask=all_keep,
        diagnostic_mask=all_keep,
        source_vertex_sha256=evidence.source_vertex_sha256,
    )

    report = clean_variants(
        source,
        evidence,
        tmp_path / "candidate",
        poses_path=poses_path,
        cam_height=1.5,
        below_margin=0.3,
        above_cap=3.0,
        corridor=15.0,
    )
    serialized = json.loads(report.path.read_text())

    assert report.counts["balanced"] == 1
    assert {
        "camHeight": 1.5,
        "belowMargin": 0.3,
        "aboveCap": 3.0,
        "corridor": 15.0,
    }.items() <= serialized["policyThresholds"]["balanced"].items()
    assert {
        "below-ground": 1,
        "above-cap": 1,
        "outside-corridor": 1,
    }.items() <= serialized["removedReasonCounts"]["balanced"].items()


def test_verified_candidate_names_all_durable_artifacts_and_only_balanced_is_publishable(
    tmp_path: Path,
):
    """Catches anonymous bytes losing the audit trail or publishing another policy."""
    staging = _valid_candidate_staging(tmp_path)
    manifest = staging / "candidate-manifest"

    candidate = gsplat_runner.VerifiedCandidate.from_staging(staging)

    assert not isinstance(candidate, bytes)
    assert candidate.balanced_bytes == candidate.balanced.read_bytes()
    assert candidate.raw == staging / "splat_gsplat.ply"
    assert candidate.conservative == staging / "splat_conservative.ply"
    assert candidate.balanced == staging / "splat_balanced.ply"
    assert candidate.diagnostic == staging / "splat_diagnostic.ply"
    assert candidate.cleanup_report == staging / "cleanup-report.json"
    assert candidate.evidence_artifact == staging / "verified-evidence.npz"
    assert candidate.poses == manifest / "poses.npy"
    assert candidate.frame_audit == manifest / "frame-audit.json"
    assert candidate.training_state == manifest / "training-state.json"
    assert candidate.checkpoint == manifest / "checkpoint_latest.pt"
    assert candidate.publishable == candidate.balanced
    assert candidate.publishable_policy == "balanced"
    with pytest.raises(TypeError):
        gsplat_runner.VerifiedCandidate()


@pytest.mark.parametrize(
    "mutation",
    (
        "source-checksum",
        "output-checksum",
        "output-path",
        "output-count",
        "publishable",
        "missing-section",
    ),
)
def test_verified_candidate_rejects_incoherent_cleanup_report(
    tmp_path: Path, mutation: str
):
    """Catches candidate construction trusting report claims without verification."""
    staging = _valid_candidate_staging(tmp_path)
    report_path = staging / "cleanup-report.json"
    report = json.loads(report_path.read_text())
    if mutation == "source-checksum":
        report["source"]["sha256"] = "0" * 64
    elif mutation == "output-checksum":
        report["outputs"]["balanced"]["sha256"] = "0" * 64
    elif mutation == "output-path":
        report["outputs"]["balanced"]["path"] = str(staging / "splat_diagnostic.ply")
    elif mutation == "output-count":
        report["outputs"]["balanced"]["count"] += 1
    elif mutation == "publishable":
        report["outputs"]["diagnostic"]["publishable"] = True
    else:
        del report["componentScoreHistograms"]
    report_path.write_text(json.dumps(report))

    with pytest.raises(ValueError, match="cleanup report"):
        gsplat_runner.VerifiedCandidate.from_staging(staging)


def test_verified_candidate_balanced_accessor_rechecks_bytes(tmp_path: Path):
    """Catches post-construction bytes drifting from the validated checksum."""
    candidate = gsplat_runner.VerifiedCandidate.from_staging(
        _valid_candidate_staging(tmp_path)
    )
    candidate.balanced.write_bytes(b"tampered")

    with pytest.raises(ValueError, match="balanced checksum"):
        _ = candidate.balanced_bytes


def test_verified_candidate_paths_survive_cwd_change(tmp_path: Path, monkeypatch):
    """Catches validated artifacts becoming relative to a later worker cwd."""
    _valid_candidate_staging(tmp_path)
    other = tmp_path / "other"
    other.mkdir()
    monkeypatch.chdir(tmp_path)
    candidate = gsplat_runner.VerifiedCandidate.from_staging(Path("durable"))
    monkeypatch.chdir(other)

    assert candidate.raw.is_absolute()
    assert candidate.balanced_bytes == candidate.balanced.read_bytes()


def test_incomplete_cleanup_artifacts_fail_closed_instead_of_aliasing_variants(
    tmp_path: Path,
):
    """Catches legacy single-output cleanup being returned as a successful candidate."""
    staging = _valid_candidate_staging(tmp_path)
    (staging / "splat_conservative.ply").unlink()
    (staging / "cleanup-report.json").unlink()

    candidate, reason = gsplat_runner.verified_candidate_result(staging)

    assert candidate is None
    assert reason == "cleanup-incomplete"


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
            inputs = _artifact_inputs(staging / "splat_gsplat.ply", staging)
            write_verified_evidence_artifact(
                staging / "verified-evidence.npz",
                inputs,
                score_gaussians(inputs),
                raw_ply_path=staging / "splat_gsplat.ply",
                source_checksums=candidate_source_checksums(staging),
                provenance={"producer": "captured-observation-projector"},
            )
        else:
            artifact = load_verified_evidence_artifact(
                command[command.index("--evidence-artifact") + 1],
                raw_ply_path=command[command.index("--ply") + 1],
                expected_source_checksums=candidate_source_checksums(staging),
            )
            clean_variants(
                command[command.index("--ply") + 1],
                artifact.result,
                command[command.index("--out-dir") + 1],
                points_path=command[command.index("--points") + 1],
                poses_path=command[command.index("--poses") + 1],
                verified_evidence=artifact,
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
    assert not isinstance(candidate, bytes)
    assert candidate.balanced_bytes == candidate.balanced.read_bytes()
    assert candidate.publishable == staging / "splat_balanced.ply"
    assert candidate.raw.read_bytes() == (staging / "splat_gsplat.ply").read_bytes()
    assert candidate.poses == staging / "candidate-manifest" / "poses.npy"
    assert candidate.frame_audit.is_file()
    assert candidate.training_state.is_file()
    assert candidate.checkpoint.is_file()
