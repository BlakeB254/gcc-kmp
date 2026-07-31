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
import io
import json
import os
from pathlib import Path
import tempfile
from typing import Any
import zipfile
import zlib

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


@dataclass(frozen=True)
class FileSnapshot:
    path: Path
    data: bytes
    sha256: str


@dataclass(frozen=True)
class PlySnapshot(FileSnapshot):
    ply: PlyData
    vertex_order_sha256: str

    @property
    def vertices(self) -> np.ndarray:
        return self.ply["vertex"].data


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_file_snapshot(path: str | os.PathLike[str]) -> FileSnapshot:
    source = Path(path).expanduser().resolve()
    try:
        with source.open("rb") as stream:
            data = stream.read()
    except OSError as error:
        raise EvidenceArtifactError("evidence-incomplete", f"source is unreadable: {source}") from error
    return FileSnapshot(source, data, hashlib.sha256(data).hexdigest())


def read_ply_snapshot(path: str | os.PathLike[str]) -> PlySnapshot:
    file_snapshot = read_file_snapshot(path)
    try:
        ply = PlyData.read(io.BytesIO(file_snapshot.data))
        vertices = ply["vertex"].data
    except (OSError, ValueError, KeyError) as error:
        raise EvidenceArtifactError(
            "evidence-source-mismatch", "raw PLY is unreadable or lacks vertices"
        ) from error
    digest = hashlib.sha256()
    descriptor = json.dumps(
        {"count": len(vertices), "dtype": vertices.dtype.descr},
        separators=(",", ":"),
    ).encode("utf-8")
    digest.update(len(descriptor).to_bytes(8, "big"))
    digest.update(descriptor)
    digest.update(np.ascontiguousarray(vertices).tobytes(order="C"))
    return PlySnapshot(
        file_snapshot.path,
        file_snapshot.data,
        file_snapshot.sha256,
        ply,
        digest.hexdigest(),
    )


def ply_fingerprints(path: str | os.PathLike[str]) -> tuple[str, str]:
    """Return full-source and ordered vertex-record SHA-256 identities."""
    snapshot = read_ply_snapshot(path)
    return snapshot.sha256, snapshot.vertex_order_sha256


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


def _validate_inputs_match_ply(raw: PlySnapshot, inputs: EvidenceInputs) -> None:
    try:
        vertices = raw.vertices
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


def _load_numpy_snapshot(snapshot: FileSnapshot) -> np.ndarray:
    return np.load(io.BytesIO(snapshot.data), allow_pickle=False)


def _validate_inputs_match_generation(
    staging: Path,
    inputs: EvidenceInputs,
    source_checksums: dict[str, str],
    raw_snapshot: PlySnapshot,
) -> None:
    if set(source_checksums) != set(_SOURCE_FILES):
        raise EvidenceArtifactError(
            "evidence-generation-mismatch", "artifact source checksums are incomplete"
        )
    if source_checksums["rawPly"] != raw_snapshot.sha256:
        raise EvidenceArtifactError(
            "evidence-source-mismatch", "artifact source checksum does not match raw PLY"
        )

    def load_bound_array(name: str) -> np.ndarray:
        path = staging / _SOURCE_FILES[name]
        try:
            snapshot = read_file_snapshot(path)
            if snapshot.sha256 != source_checksums[name]:
                raise EvidenceArtifactError(
                    "evidence-generation-mismatch",
                    f"staged source changed: {path}",
                )
            return _load_numpy_snapshot(snapshot)
        except EvidenceArtifactError:
            raise
        except (OSError, EOFError, ValueError) as error:
            raise EvidenceArtifactError(
                "evidence-generation-mismatch", f"staged source is unreadable: {path}"
            ) from error

    try:
        poses = load_bound_array("poses")
        if not np.array_equal(np.asarray(inputs.camera_poses), poses):
            raise EvidenceArtifactError(
                "evidence-generation-mismatch",
                "Task 3 cameras do not match staged poses",
            )
        del poses

        intrinsics = load_bound_array("intrinsics")
        input_intrinsics = np.asarray(inputs.intrinsics)
        intrinsics_match = np.array_equal(input_intrinsics, intrinsics)
        if input_intrinsics.shape == (3, 3) and intrinsics.ndim == 3:
            intrinsics_match = np.array_equal(
                np.broadcast_to(input_intrinsics, intrinsics.shape), intrinsics
            )
        if not intrinsics_match:
            raise EvidenceArtifactError(
                "evidence-generation-mismatch",
                "Task 3 calibration does not match staged intrinsics",
            )
        del intrinsics

        lidar_points = load_bound_array("initializationPoints")
        if not np.array_equal(np.asarray(inputs.lidar_points), lidar_points):
            raise EvidenceArtifactError(
                "evidence-generation-mismatch",
                "Task 3 LiDAR does not match staged initialization points",
            )
        del lidar_points
    except EvidenceArtifactError:
        raise
    except (OSError, EOFError, ValueError) as error:
        raise EvidenceArtifactError(
            "evidence-generation-mismatch", "staged evidence sources are unreadable"
        ) from error
    for name in ("frameAudit", "trainingState", "checkpoint"):
        path = staging / _SOURCE_FILES[name]
        try:
            checksum = _sha256(path)
        except OSError as error:
            raise EvidenceArtifactError(
                "evidence-generation-mismatch", f"staged source is unreadable: {path}"
            ) from error
        if checksum != source_checksums[name]:
            raise EvidenceArtifactError(
                "evidence-generation-mismatch", f"staged source changed: {path}"
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
    raw_snapshot = read_ply_snapshot(raw)
    raw_sha256 = raw_snapshot.sha256
    vertex_sha256 = raw_snapshot.vertex_order_sha256
    if inputs.lidar_confidence is None:
        raise EvidenceArtifactError(
            "evidence-incomplete", "verified evidence requires captured LiDAR confidence"
        )
    if inputs.source_vertex_sha256 != vertex_sha256:
        raise EvidenceArtifactError(
            "evidence-source-mismatch", "inputs are not bound to the raw PLY vertex order"
        )
    _validate_inputs_match_ply(raw_snapshot, inputs)
    _validate_inputs_match_generation(raw.parent, inputs, source_checksums, raw_snapshot)
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
        raw_ply_snapshot=raw_snapshot,
    )


def _read_metadata(archive: Any) -> dict[str, Any]:
    try:
        value = archive["metadata_json"]
        if value.shape != ():
            raise ValueError("metadata must be scalar")
        metadata = json.loads(str(value.item()))
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise EvidenceArtifactError("evidence-malformed", "artifact metadata is invalid") from error
    if not isinstance(metadata, dict):
        raise EvidenceArtifactError("evidence-malformed", "artifact metadata is invalid")
    if metadata.get("schemaVersion") != SCHEMA_VERSION:
        raise EvidenceArtifactError("evidence-malformed", "unsupported evidence schema version")
    return metadata


def load_verified_evidence_artifact(
    path: str | os.PathLike[str],
    *,
    raw_ply_path: str | os.PathLike[str],
    expected_source_checksums: dict[str, str] | None = None,
    raw_ply_snapshot: PlySnapshot | None = None,
) -> VerifiedEvidenceArtifact:
    """Load one artifact snapshot and bind it to sequentially checked sources.

    ``expected_source_checksums`` is an optional caller-side generation claim.
    Regardless of whether it is supplied, each staged source is checked against
    artifact metadata; array sources are hashed and parsed from the same bytes,
    while opaque files such as checkpoints are digested as bounded streams.
    """
    artifact_path = Path(path).expanduser().resolve()
    raw = Path(raw_ply_path).expanduser().resolve()
    if not artifact_path.is_file():
        raise EvidenceArtifactError("evidence-missing", f"evidence artifact is missing: {artifact_path}")
    raw_snapshot = raw_ply_snapshot or read_ply_snapshot(raw)
    if raw_snapshot.path != raw:
        raise EvidenceArtifactError("evidence-source-mismatch", "raw PLY snapshot path is invalid")
    raw_sha256 = raw_snapshot.sha256
    vertex_sha256 = raw_snapshot.vertex_order_sha256
    try:
        artifact_snapshot = read_file_snapshot(artifact_path)
        with np.load(io.BytesIO(artifact_snapshot.data), allow_pickle=False) as archive:
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
            if (
                expected_source_checksums is not None
                and source_checksums != expected_source_checksums
            ):
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
    except (
        OSError,
        EOFError,
        ValueError,
        KeyError,
        TypeError,
        AttributeError,
        IndexError,
        OverflowError,
        zipfile.BadZipFile,
        zipfile.LargeZipFile,
        zlib.error,
    ) as error:
        raise EvidenceArtifactError("evidence-malformed", "evidence artifact is unreadable") from error
    artifact_sha256 = artifact_snapshot.sha256
    del artifact_snapshot
    _validate_inputs_match_ply(raw_snapshot, inputs)
    _validate_inputs_match_generation(
        raw.parent, inputs, source_checksums, raw_snapshot
    )
    _validate_result(inputs, result)
    return VerifiedEvidenceArtifact(
        path=artifact_path,
        sha256=artifact_sha256,
        schema_version=SCHEMA_VERSION,
        generation_id=generation_id,
        raw_ply_sha256=raw_sha256,
        vertex_order_sha256=vertex_sha256,
        source_checksums=source_checksums,
        provenance=provenance,
        inputs=inputs,
        result=result,
    )
