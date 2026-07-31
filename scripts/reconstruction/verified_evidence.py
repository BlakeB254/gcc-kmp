"""Versioned, CPU-only serialization for captured Gaussian evidence.

This module does not produce observations. It persists caller-supplied Task 3
inputs, recomputes their result on read, and binds both to one raw PLY and one
durable training generation. The future admitted evidence producer must supply
captured visibility/colors/pixels and confidence-qualified LiDAR; absence is a
closed publication gate, never a cue to infer support.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Any

import numpy as np
from plyfile import PlyData

from evidence_score import EvidenceInputs, EvidenceResult, score_gaussians


SCHEMA_VERSION = 1
ARTIFACT_NAME = "verified-evidence.npz"
_SOURCE_FILES = {
    "rawPly": "splat_gsplat.ply",
    "poses": "poses.npy",
    "intrinsics": "intrinsics.npy",
    "initializationPoints": "init_points.npy",
    "frameAudit": "frame-audit.json",
    "trainingState": "training-state.json",
    "checkpoint": "checkpoint_latest.pt",
}
_REQUIRED_INPUTS = (
    "positions",
    "scales",
    "opacities",
    "camera_poses",
    "intrinsics",
    "lidar_points",
    "lidar_confidence",
)
_OPTIONAL_INPUTS = (
    "visibility",
    "observed_colors",
    "observed_pixels",
    "component_sizes",
    "corridor_points",
)


class EvidenceArtifactError(ValueError):
    def __init__(self, reason: str, message: str):
        super().__init__(message)
        self.reason = reason


@dataclass(frozen=True)
class VerifiedEvidenceArtifact:
    path: Path
    sha256: str
    schema_version: int
    generation_id: str
    raw_ply_sha256: str
    vertex_order_sha256: str
    source_checksums: dict[str, str]
    provenance: dict[str, Any]
    inputs: EvidenceInputs
    result: EvidenceResult


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ply_fingerprints(path: str | os.PathLike[str]) -> tuple[str, str]:
    """Return full-source and ordered vertex-record SHA-256 identities."""
    source = Path(path).expanduser().resolve()
    raw_sha256 = _sha256(source)
    vertices = PlyData.read(source)["vertex"].data
    digest = hashlib.sha256()
    descriptor = json.dumps(
        {"count": len(vertices), "dtype": vertices.dtype.descr},
        separators=(",", ":"),
    ).encode("utf-8")
    digest.update(len(descriptor).to_bytes(8, "big"))
    digest.update(descriptor)
    digest.update(np.ascontiguousarray(vertices).tobytes(order="C"))
    return raw_sha256, digest.hexdigest()


def candidate_source_checksums(
    staging_dir: str | os.PathLike[str],
) -> dict[str, str]:
    staging = Path(staging_dir).expanduser().resolve()
    checksums: dict[str, str] = {}
    for name, relative in _SOURCE_FILES.items():
        path = staging / relative
        if not path.is_file():
            raise EvidenceArtifactError(
                "evidence-incomplete", f"candidate source is missing: {path}"
            )
        checksums[name] = _sha256(path)
    return checksums


def evidence_generation_id(source_checksums: dict[str, str]) -> str:
    if set(source_checksums) != set(_SOURCE_FILES):
        raise EvidenceArtifactError(
            "evidence-incomplete", "source checksums do not cover the candidate generation"
        )
    encoded = json.dumps(source_checksums, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def _validate_result(inputs: EvidenceInputs, result: EvidenceResult) -> None:
    expected = score_gaussians(inputs)
    if result.source_vertex_sha256 != inputs.source_vertex_sha256:
        raise EvidenceArtifactError(
            "evidence-source-mismatch", "result and inputs have different vertex identity"
        )
    comparisons = (
        ("total", result.total, expected.total),
        ("conservative_mask", result.conservative_mask, expected.conservative_mask),
        ("balanced_mask", result.balanced_mask, expected.balanced_mask),
        ("diagnostic_mask", result.diagnostic_mask, expected.diagnostic_mask),
    )
    for name, actual, wanted in comparisons:
        if not np.array_equal(actual, wanted):
            raise EvidenceArtifactError(
                "evidence-malformed", f"stored {name} was not derived by Task 3"
            )
    if set(result.components) != set(expected.components):
        raise EvidenceArtifactError("evidence-malformed", "stored component set is invalid")
    for name in expected.components:
        if not np.array_equal(result.components[name], expected.components[name]):
            raise EvidenceArtifactError(
                "evidence-malformed", f"stored component {name!r} was not derived by Task 3"
            )


def _validate_inputs_match_ply(raw: Path, inputs: EvidenceInputs) -> None:
    try:
        vertices = PlyData.read(raw)["vertex"].data
        positions = np.stack(
            [vertices[name] for name in ("x", "y", "z")], axis=1
        ).astype(np.float64)
        scales = np.exp(
            np.stack([vertices[f"scale_{index}"] for index in range(3)], axis=1)
        ).astype(np.float64)
        opacities = np.asarray(vertices["opacity"], dtype=np.float64)
    except (OSError, ValueError, KeyError) as error:
        raise EvidenceArtifactError(
            "evidence-source-mismatch", "raw PLY lacks Task 3 vertex attributes"
        ) from error
    matches = (
        np.array_equal(np.asarray(inputs.positions, dtype=np.float64), positions)
        and np.array_equal(np.asarray(inputs.scales, dtype=np.float64), scales)
        and np.array_equal(np.asarray(inputs.opacities, dtype=np.float64), opacities)
    )
    if not matches:
        raise EvidenceArtifactError(
            "evidence-source-mismatch",
            "Task 3 position/scale/opacity order does not match raw PLY",
        )


def _validate_inputs_match_generation(staging: Path, inputs: EvidenceInputs) -> None:
    try:
        poses = np.load(staging / "poses.npy")
        intrinsics = np.load(staging / "intrinsics.npy")
        lidar_points = np.load(staging / "init_points.npy")
    except (OSError, ValueError) as error:
        raise EvidenceArtifactError(
            "evidence-generation-mismatch", "staged evidence sources are unreadable"
        ) from error
    input_intrinsics = np.asarray(inputs.intrinsics)
    intrinsics_match = np.array_equal(input_intrinsics, intrinsics)
    if input_intrinsics.shape == (3, 3) and intrinsics.ndim == 3:
        intrinsics_match = np.array_equal(
            np.broadcast_to(input_intrinsics, intrinsics.shape), intrinsics
        )
    if not (
        np.array_equal(np.asarray(inputs.camera_poses), poses)
        and intrinsics_match
        and np.array_equal(np.asarray(inputs.lidar_points), lidar_points)
    ):
        raise EvidenceArtifactError(
            "evidence-generation-mismatch",
            "Task 3 cameras/calibration/LiDAR do not match staged sources",
        )
def _metadata_array(metadata: dict[str, Any]) -> np.ndarray:
    return np.asarray(json.dumps(metadata, sort_keys=True, separators=(",", ":")))


def _readonly(values: np.ndarray) -> np.ndarray:
    result = np.asarray(values).copy()
    result.setflags(write=False)
    return result


def write_verified_evidence_artifact(
    path: str | os.PathLike[str],
    inputs: EvidenceInputs,
    result: EvidenceResult,
    *,
    raw_ply_path: str | os.PathLike[str],
    source_checksums: dict[str, str],
    provenance: dict[str, Any],
) -> VerifiedEvidenceArtifact:
    destination = Path(path).expanduser().resolve()
    raw = Path(raw_ply_path).expanduser().resolve()
    raw_sha256, vertex_sha256 = ply_fingerprints(raw)
    if inputs.lidar_confidence is None:
        raise EvidenceArtifactError(
            "evidence-incomplete", "verified evidence requires captured LiDAR confidence"
        )
    if inputs.source_vertex_sha256 != vertex_sha256:
        raise EvidenceArtifactError(
            "evidence-source-mismatch", "inputs are not bound to the raw PLY vertex order"
        )
    _validate_inputs_match_ply(raw, inputs)
    _validate_inputs_match_generation(raw.parent, inputs)
    if source_checksums.get("rawPly") != raw_sha256:
        raise EvidenceArtifactError(
            "evidence-source-mismatch", "source checksums do not match the raw PLY"
        )
    if not isinstance(provenance, dict) or not provenance.get("producer"):
        raise EvidenceArtifactError(
            "evidence-incomplete", "evidence provenance requires a producer"
        )
    _validate_result(inputs, result)
    generation_id = evidence_generation_id(source_checksums)
    arrays: dict[str, np.ndarray] = {
        name: np.asarray(getattr(inputs, name)) for name in _REQUIRED_INPUTS
    }
    present_optional = []
    for name in _OPTIONAL_INPUTS:
        value = getattr(inputs, name)
        if value is not None:
            arrays[name] = np.asarray(value)
            present_optional.append(name)
    arrays.update({
        "result_total": np.asarray(result.total),
        "result_conservative_mask": np.asarray(result.conservative_mask),
        "result_balanced_mask": np.asarray(result.balanced_mask),
        "result_diagnostic_mask": np.asarray(result.diagnostic_mask),
    })
    component_names = sorted(result.components)
    for name in component_names:
        arrays[f"result_component__{name}"] = np.asarray(result.components[name])
    metadata = {
        "schemaVersion": SCHEMA_VERSION,
        "generationId": generation_id,
        "rawPlySha256": raw_sha256,
        "vertexOrderSha256": vertex_sha256,
        "sourceChecksums": dict(source_checksums),
        "provenance": provenance,
        "optionalInputs": present_optional,
        "componentNames": component_names,
    }
    arrays["metadata_json"] = _metadata_array(metadata)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent, delete=False
    ) as stream:
        temporary = Path(stream.name)
        np.savez_compressed(stream, **arrays)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, destination)
    return load_verified_evidence_artifact(
        destination,
        raw_ply_path=raw,
        expected_source_checksums=source_checksums,
    )


def _read_metadata(archive: Any) -> dict[str, Any]:
    try:
        value = archive["metadata_json"]
        if value.shape != ():
            raise ValueError("metadata must be scalar")
        metadata = json.loads(str(value.item()))
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise EvidenceArtifactError("evidence-malformed", "artifact metadata is invalid") from error
    if metadata.get("schemaVersion") != SCHEMA_VERSION:
        raise EvidenceArtifactError("evidence-malformed", "unsupported evidence schema version")
    return metadata


def load_verified_evidence_artifact(
    path: str | os.PathLike[str],
    *,
    raw_ply_path: str | os.PathLike[str],
    expected_source_checksums: dict[str, str],
) -> VerifiedEvidenceArtifact:
    artifact_path = Path(path).expanduser().resolve()
    raw = Path(raw_ply_path).expanduser().resolve()
    if not artifact_path.is_file():
        raise EvidenceArtifactError("evidence-missing", f"evidence artifact is missing: {artifact_path}")
    raw_sha256, vertex_sha256 = ply_fingerprints(raw)
    try:
        with np.load(artifact_path, allow_pickle=False) as archive:
            metadata = _read_metadata(archive)
            if (
                metadata.get("rawPlySha256") != raw_sha256
                or metadata.get("vertexOrderSha256") != vertex_sha256
            ):
                raise EvidenceArtifactError(
                    "evidence-source-mismatch", "artifact does not match the raw PLY"
                )
            source_checksums = metadata.get("sourceChecksums")
            if not isinstance(source_checksums, dict):
                raise EvidenceArtifactError(
                    "evidence-malformed", "artifact source checksums are invalid"
                )
            if source_checksums != expected_source_checksums:
                raise EvidenceArtifactError(
                    "evidence-generation-mismatch", "artifact belongs to another training generation"
                )
            generation_id = evidence_generation_id(source_checksums)
            if metadata.get("generationId") != generation_id:
                raise EvidenceArtifactError(
                    "evidence-generation-mismatch", "artifact generation identity is invalid"
                )
            provenance = metadata.get("provenance")
            if not isinstance(provenance, dict) or not provenance.get("producer"):
                raise EvidenceArtifactError(
                    "evidence-incomplete", "artifact provenance is incomplete"
                )
            values = {}
            for name in _REQUIRED_INPUTS:
                if name not in archive:
                    reason = "evidence-incomplete" if name == "lidar_confidence" else "evidence-malformed"
                    raise EvidenceArtifactError(reason, f"artifact input {name!r} is missing")
                values[name] = np.asarray(archive[name])
            optional = metadata.get("optionalInputs", [])
            if not isinstance(optional, list) or any(name not in _OPTIONAL_INPUTS for name in optional):
                raise EvidenceArtifactError("evidence-malformed", "optional input list is invalid")
            for name in _OPTIONAL_INPUTS:
                values[name] = np.asarray(archive[name]) if name in optional else None
            inputs = EvidenceInputs(
                **values,
                source_vertex_sha256=vertex_sha256,
            )
            component_names = metadata.get("componentNames")
            if not isinstance(component_names, list) or not component_names:
                raise EvidenceArtifactError("evidence-malformed", "component list is invalid")
            components = {
                name: _readonly(archive[f"result_component__{name}"])
                for name in component_names
            }
            result = EvidenceResult(
                total=_readonly(archive["result_total"]),
                components=components,
                conservative_mask=_readonly(archive["result_conservative_mask"]),
                balanced_mask=_readonly(archive["result_balanced_mask"]),
                diagnostic_mask=_readonly(archive["result_diagnostic_mask"]),
                source_vertex_sha256=vertex_sha256,
            )
    except EvidenceArtifactError:
        raise
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise EvidenceArtifactError("evidence-malformed", "evidence artifact is unreadable") from error
    _validate_inputs_match_ply(raw, inputs)
    _validate_inputs_match_generation(raw.parent, inputs)
    _validate_result(inputs, result)
    return VerifiedEvidenceArtifact(
        path=artifact_path,
        sha256=_sha256(artifact_path),
        schema_version=SCHEMA_VERSION,
        generation_id=generation_id,
        raw_ply_sha256=raw_sha256,
        vertex_order_sha256=vertex_sha256,
        source_checksums=source_checksums,
        provenance=provenance,
        inputs=inputs,
        result=result,
    )
