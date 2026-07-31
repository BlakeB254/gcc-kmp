from __future__ import annotations

from dataclasses import replace
import hashlib
import os
from pathlib import Path

import numpy as np
from plyfile import PlyData, PlyElement
import pytest

import verified_evidence
from evidence_score import EvidenceInputs, score_gaussians
from verified_evidence import (
    EvidenceArtifactError,
    candidate_source_checksums,
    load_verified_evidence_artifact,
    ply_fingerprints,
    write_verified_evidence_artifact,
)


def _write_ply(path: Path, order: tuple[int, ...] = (0, 1)) -> None:
    vertices = np.zeros(
        2,
        dtype=[
            ("x", "f4"), ("y", "f4"), ("z", "f4"),
            ("opacity", "f4"),
            ("scale_0", "f4"), ("scale_1", "f4"), ("scale_2", "f4"),
        ],
    )
    vertices["x"] = [0.0, 0.1]
    vertices["z"] = 5.0
    vertices["opacity"] = 2.0
    vertices["scale_0"] = np.log(0.05)
    vertices["scale_1"] = np.log(0.05)
    vertices["scale_2"] = np.log(0.05)
    PlyData([PlyElement.describe(vertices[list(order)], "vertex")]).write(path)


def _inputs(source: Path, *, with_confidence: bool = True) -> EvidenceInputs:
    vertices = PlyData.read(source)["vertex"].data
    positions = np.stack([vertices[name] for name in ("x", "y", "z")], axis=1)
    scales = np.exp(
        np.stack([vertices[f"scale_{index}"] for index in range(3)], axis=1)
    )
    poses = np.repeat(np.eye(4, dtype=np.float64)[None], 2, axis=0)
    poses[:, 0, 3] = [-2.0, 2.0]
    visibility = np.ones((2, 2), dtype=bool)
    observed_colors = np.full((2, 2, 3), 0.5, dtype=np.float64)
    observed_pixels = np.empty((2, 2, 2), dtype=np.float64)
    for view, pose in enumerate(poses):
        camera_points = positions - pose[:3, 3]
        observed_pixels[:, view] = camera_points[:, :2] / camera_points[:, 2:3]
    _raw_sha, vertex_sha = ply_fingerprints(source)
    return EvidenceInputs(
        positions=positions,
        scales=scales,
        opacities=np.asarray(vertices["opacity"]),
        camera_poses=poses,
        intrinsics=np.eye(3, dtype=np.float64),
        lidar_points=positions.copy(),
        lidar_confidence=np.ones(2) if with_confidence else None,
        visibility=visibility,
        observed_colors=observed_colors,
        observed_pixels=observed_pixels,
        source_vertex_sha256=vertex_sha,
    )


def _staging(tmp_path: Path) -> tuple[Path, Path]:
    staging = tmp_path / "staging"
    staging.mkdir()
    raw = staging / "splat_gsplat.ply"
    _write_ply(raw)
    poses = np.repeat(np.eye(4)[None], 2, axis=0)
    poses[:, 0, 3] = [-2.0, 2.0]
    np.save(staging / "poses.npy", poses)
    np.save(staging / "intrinsics.npy", np.repeat(np.eye(3)[None], 2, axis=0))
    vertices = PlyData.read(raw)["vertex"].data
    np.save(
        staging / "init_points.npy",
        np.stack([vertices[name] for name in ("x", "y", "z")], axis=1),
    )
    (staging / "frame-audit.json").write_text('{"version":1}')
    (staging / "training-state.json").write_text('{"status":"completed"}')
    (staging / "checkpoint_latest.pt").write_bytes(b"checkpoint-a")
    return staging, raw


def _corrupt_npz_member_crc(path: Path, member_name: str) -> None:
    """Change one central-directory CRC without changing member bytes."""
    data = bytearray(path.read_bytes())
    signature = b"PK\x01\x02"
    offset = 0
    while (offset := data.find(signature, offset)) >= 0:
        name_length = int.from_bytes(data[offset + 28 : offset + 30], "little")
        extra_length = int.from_bytes(data[offset + 30 : offset + 32], "little")
        comment_length = int.from_bytes(data[offset + 32 : offset + 34], "little")
        name_start = offset + 46
        name = bytes(data[name_start : name_start + name_length]).decode("utf-8")
        if name == member_name:
            data[offset + 16] ^= 0xFF
            path.write_bytes(data)
            return
        offset = name_start + name_length + extra_length + comment_length
    raise AssertionError(f"NPZ member not found: {member_name}")


def _rewrite_npz_member_zip_header(
    path: Path,
    member_name: str,
    *,
    compression: int | None = None,
    encrypted: bool = False,
) -> None:
    """Rewrite matching local/central ZIP fields without changing NPZ data."""
    data = bytearray(path.read_bytes())
    signature = b"PK\x01\x02"
    offset = 0
    while (offset := data.find(signature, offset)) >= 0:
        name_length = int.from_bytes(data[offset + 28 : offset + 30], "little")
        extra_length = int.from_bytes(data[offset + 30 : offset + 32], "little")
        comment_length = int.from_bytes(data[offset + 32 : offset + 34], "little")
        name_start = offset + 46
        name = bytes(data[name_start : name_start + name_length]).decode("utf-8")
        if name == member_name:
            local_offset = int.from_bytes(data[offset + 42 : offset + 46], "little")
            assert data[local_offset : local_offset + 4] == b"PK\x03\x04"
            if compression is not None:
                encoded = compression.to_bytes(2, "little")
                data[local_offset + 8 : local_offset + 10] = encoded
                data[offset + 10 : offset + 12] = encoded
            if encrypted:
                local_flags = int.from_bytes(
                    data[local_offset + 6 : local_offset + 8], "little"
                ) | 1
                central_flags = int.from_bytes(data[offset + 8 : offset + 10], "little") | 1
                data[local_offset + 6 : local_offset + 8] = local_flags.to_bytes(2, "little")
                data[offset + 8 : offset + 10] = central_flags.to_bytes(2, "little")
            path.write_bytes(data)
            return
        offset = name_start + name_length + extra_length + comment_length
    raise AssertionError(f"NPZ member not found: {member_name}")


def test_versioned_artifact_round_trips_inputs_results_and_generation(tmp_path: Path):
    """Catches cleanup accepting scores detached from their captured inputs."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    result = score_gaussians(inputs)
    source_checksums = candidate_source_checksums(staging)
    artifact_path = staging / "verified-evidence.npz"

    written = write_verified_evidence_artifact(
        artifact_path,
        inputs,
        result,
        raw_ply_path=raw,
        source_checksums=source_checksums,
        provenance={"producer": "captured-observation-projector", "captureIds": ["scan-a"]},
    )
    loaded = load_verified_evidence_artifact(
        artifact_path,
        raw_ply_path=raw,
        expected_source_checksums=source_checksums,
    )

    assert written.generation_id == loaded.generation_id
    assert loaded.schema_version == 1
    assert loaded.result.source_vertex_sha256 == ply_fingerprints(raw)[1]
    assert np.array_equal(loaded.result.balanced_mask, result.balanced_mask)
    assert loaded.source_checksums == source_checksums
    assert loaded.provenance["captureIds"] == ["scan-a"]


def test_artifact_rejects_missing_lidar_confidence(tmp_path: Path):
    """Catches raw LiDAR points being promoted to verified range evidence."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw, with_confidence=False)

    with pytest.raises(EvidenceArtifactError) as error:
        write_verified_evidence_artifact(
            staging / "verified-evidence.npz",
            inputs,
            score_gaussians(inputs),
            raw_ply_path=raw,
            source_checksums=candidate_source_checksums(staging),
            provenance={"producer": "test"},
        )

    assert error.value.reason == "evidence-incomplete"


def test_artifact_rejects_reordered_same_length_raw_ply(tmp_path: Path):
    """Catches same-sized evidence masks being applied to different vertex order."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    artifact_path = staging / "verified-evidence.npz"
    write_verified_evidence_artifact(
        artifact_path,
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=candidate_source_checksums(staging),
        provenance={"producer": "test"},
    )
    _write_ply(raw, order=(1, 0))

    with pytest.raises(EvidenceArtifactError) as error:
        load_verified_evidence_artifact(
            artifact_path,
            raw_ply_path=raw,
            expected_source_checksums=candidate_source_checksums(staging),
        )

    assert error.value.reason == "evidence-source-mismatch"


def test_artifact_rejects_reordered_inputs_with_claimed_source_fingerprint(
    tmp_path: Path,
):
    """Catches a producer labeling differently ordered arrays with the raw fingerprint."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    order = np.array([1, 0])
    reordered = replace(
        inputs,
        positions=inputs.positions[order],
        scales=inputs.scales[order],
        opacities=inputs.opacities[order],
        visibility=inputs.visibility[order],
        observed_colors=inputs.observed_colors[order],
        observed_pixels=inputs.observed_pixels[order],
    )

    with pytest.raises(EvidenceArtifactError) as error:
        write_verified_evidence_artifact(
            staging / "verified-evidence.npz",
            reordered,
            score_gaussians(reordered),
            raw_ply_path=raw,
            source_checksums=candidate_source_checksums(staging),
            provenance={"producer": "test"},
        )

    assert error.value.reason == "evidence-source-mismatch"


def test_artifact_rejects_result_not_derived_from_serialized_inputs(tmp_path: Path):
    """Catches a producer serializing permissive masks that Task 3 did not score."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    result = score_gaussians(inputs)
    forged = replace(result, balanced_mask=np.zeros(2, dtype=bool))

    with pytest.raises(EvidenceArtifactError) as error:
        write_verified_evidence_artifact(
            staging / "verified-evidence.npz",
            inputs,
            forged,
            raw_ply_path=raw,
            source_checksums=candidate_source_checksums(staging),
            provenance={"producer": "test"},
        )

    assert error.value.reason == "evidence-malformed"


def test_loaded_result_remains_immutable(tmp_path: Path):
    """Catches artifact loading weakening Task 3's immutable evidence contract."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    artifact = write_verified_evidence_artifact(
        staging / "verified-evidence.npz",
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=candidate_source_checksums(staging),
        provenance={"producer": "test"},
    )

    with pytest.raises(ValueError):
        artifact.result.balanced_mask[0] = False
    with pytest.raises(ValueError):
        artifact.result.components["view_support"][0] = 0.0


def test_artifact_rejects_malformed_and_other_generation_files(tmp_path: Path):
    """Catches unreadable or stale artifacts crossing the cleanup boundary."""
    staging, raw = _staging(tmp_path)
    malformed = staging / "malformed.npz"
    malformed.write_bytes(b"not-an-npz")
    with pytest.raises(EvidenceArtifactError) as malformed_error:
        load_verified_evidence_artifact(
            malformed,
            raw_ply_path=raw,
            expected_source_checksums=candidate_source_checksums(staging),
        )
    assert malformed_error.value.reason == "evidence-malformed"

    inputs = _inputs(raw)
    artifact_path = staging / "verified-evidence.npz"
    write_verified_evidence_artifact(
        artifact_path,
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=candidate_source_checksums(staging),
        provenance={"producer": "test"},
    )
    (staging / "training-state.json").write_text('{"status":"different-generation"}')
    with pytest.raises(EvidenceArtifactError) as generation_error:
        load_verified_evidence_artifact(
            artifact_path,
            raw_ply_path=raw,
            expected_source_checksums=candidate_source_checksums(staging),
        )
    assert generation_error.value.reason == "evidence-generation-mismatch"


def test_artifact_normalizes_non_object_json_metadata_to_malformed(tmp_path: Path):
    """Catches JSON arrays escaping the evidence error boundary via AttributeError."""
    staging, raw = _staging(tmp_path)
    artifact_path = staging / "array-metadata.npz"
    np.savez(artifact_path, metadata_json=np.asarray("[]"))

    with pytest.raises(EvidenceArtifactError) as error:
        load_verified_evidence_artifact(
            artifact_path,
            raw_ply_path=raw,
            expected_source_checksums=candidate_source_checksums(staging),
        )

    assert error.value.reason == "evidence-malformed"


def test_artifact_normalizes_npz_member_crc_failure_to_malformed(tmp_path: Path):
    """Catches deferred zip CRC errors escaping the evidence error contract."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    artifact_path = staging / "verified-evidence.npz"
    source_checksums = candidate_source_checksums(staging)
    write_verified_evidence_artifact(
        artifact_path,
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=source_checksums,
        provenance={"producer": "test"},
    )
    _corrupt_npz_member_crc(artifact_path, "metadata_json.npy")

    with pytest.raises(EvidenceArtifactError) as error:
        load_verified_evidence_artifact(
            artifact_path,
            raw_ply_path=raw,
            expected_source_checksums=source_checksums,
        )

    assert error.value.reason == "evidence-malformed"


def test_artifact_normalizes_unsupported_npz_member_compression_to_malformed(
    tmp_path: Path,
):
    """Catches unsupported lazy ZIP decompression escaping the artifact boundary."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    artifact_path = staging / "verified-evidence.npz"
    source_checksums = candidate_source_checksums(staging)
    write_verified_evidence_artifact(
        artifact_path,
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=source_checksums,
        provenance={"producer": "test"},
    )
    _rewrite_npz_member_zip_header(
        artifact_path, "metadata_json.npy", compression=99
    )

    with pytest.raises(EvidenceArtifactError) as error:
        load_verified_evidence_artifact(
            artifact_path,
            raw_ply_path=raw,
            expected_source_checksums=source_checksums,
        )

    assert error.value.reason == "evidence-malformed"


def test_artifact_normalizes_encrypted_npz_metadata_member_to_malformed(
    tmp_path: Path,
):
    """Catches password-required lazy ZIP reads escaping the artifact boundary."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    artifact_path = staging / "verified-evidence.npz"
    source_checksums = candidate_source_checksums(staging)
    write_verified_evidence_artifact(
        artifact_path,
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=source_checksums,
        provenance={"producer": "test"},
    )
    _rewrite_npz_member_zip_header(
        artifact_path, "metadata_json.npy", encrypted=True
    )

    with pytest.raises(EvidenceArtifactError) as error:
        load_verified_evidence_artifact(
            artifact_path,
            raw_ply_path=raw,
            expected_source_checksums=source_checksums,
        )

    assert error.value.reason == "evidence-malformed"


@pytest.mark.parametrize("exception_type", [RuntimeError, NotImplementedError])
def test_artifact_does_not_normalize_unrelated_programming_errors(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    exception_type: type[Exception],
):
    """Catches replacing the member-only ZIP boundary with a blanket catch."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    artifact_path = staging / "verified-evidence.npz"
    source_checksums = candidate_source_checksums(staging)
    write_verified_evidence_artifact(
        artifact_path,
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=source_checksums,
        provenance={"producer": "test"},
    )

    def fail_metadata_read(_archive):
        raise exception_type("programming defect")

    monkeypatch.setattr(verified_evidence, "_read_metadata", fail_metadata_read)

    with pytest.raises(exception_type, match="programming defect"):
        load_verified_evidence_artifact(
            artifact_path,
            raw_ply_path=raw,
            expected_source_checksums=source_checksums,
        )


def test_artifact_hash_describes_the_exact_bytes_that_were_parsed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    """Catches artifact parsing and identity hashing separate path snapshots."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    artifact_path = staging / "verified-evidence.npz"
    source_checksums = candidate_source_checksums(staging)
    write_verified_evidence_artifact(
        artifact_path,
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=source_checksums,
        provenance={"producer": "original"},
    )
    original_bytes = artifact_path.read_bytes()
    replacement = staging / "replacement-evidence.npz"
    write_verified_evidence_artifact(
        replacement,
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=source_checksums,
        provenance={"producer": "replacement"},
    )
    original_sha256 = verified_evidence._sha256
    replaced = False

    def replacing_sha256(path: Path) -> str:
        nonlocal replaced
        if path == artifact_path and not replaced:
            os.replace(replacement, artifact_path)
            replaced = True
        return original_sha256(path)

    monkeypatch.setattr(verified_evidence, "_sha256", replacing_sha256)

    loaded = load_verified_evidence_artifact(
        artifact_path,
        raw_ply_path=raw,
        expected_source_checksums=source_checksums,
    )

    assert loaded.provenance["producer"] == "original"
    assert loaded.sha256 == hashlib.sha256(original_bytes).hexdigest()


def test_artifact_generation_includes_recoverable_checkpoint(tmp_path: Path):
    """Catches evidence from one checkpoint being paired with another retry."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    artifact_path = staging / "verified-evidence.npz"
    write_verified_evidence_artifact(
        artifact_path,
        inputs,
        score_gaussians(inputs),
        raw_ply_path=raw,
        source_checksums=candidate_source_checksums(staging),
        provenance={"producer": "test"},
    )
    (staging / "checkpoint_latest.pt").write_bytes(b"checkpoint-b")

    with pytest.raises(EvidenceArtifactError) as error:
        load_verified_evidence_artifact(
            artifact_path,
            raw_ply_path=raw,
            expected_source_checksums=candidate_source_checksums(staging),
        )

    assert error.value.reason == "evidence-generation-mismatch"


def test_artifact_inputs_must_match_frozen_generation_sources(tmp_path: Path):
    """Catches evidence using cameras or LiDAR from a different staged generation."""
    staging, raw = _staging(tmp_path)
    inputs = _inputs(raw)
    wrong_poses = inputs.camera_poses.copy()
    wrong_poses[0, 0, 3] += 10.0
    inputs = replace(inputs, camera_poses=wrong_poses)

    with pytest.raises(EvidenceArtifactError) as error:
        write_verified_evidence_artifact(
            staging / "verified-evidence.npz",
            inputs,
            score_gaussians(inputs),
            raw_ply_path=raw,
            source_checksums=candidate_source_checksums(staging),
            provenance={"producer": "test"},
        )

    assert error.value.reason == "evidence-generation-mismatch"
