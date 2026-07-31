"""Immutable provenance manifests for verified reconstruction candidates.

The builder only accepts the durable, balanced member of a verified candidate.
It snapshots every referenced file checksum while building a deterministic JSON
document; it neither generates evidence nor invokes validation/training code.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping

from verified_quality import PublicationDecision, QualityMetrics


SCHEMA_VERSION = 1
ASSET_KIND = "verified"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _json_value(value: Any) -> Any:
    """Deep-copy JSON-compatible provenance into an immutable representation."""
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, Mapping):
        return MappingProxyType({str(key): _json_value(item) for key, item in value.items()})
    if isinstance(value, (list, tuple)):
        return tuple(_json_value(item) for item in value)
    raise TypeError(f"manifest value is not JSON-compatible: {type(value).__name__}")


def _plain(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {str(key): _plain(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [_plain(item) for item in value]
    return value


def _canonical_json(value: Any) -> str:
    return json.dumps(_plain(value), sort_keys=True, separators=(",", ":"), allow_nan=False)


def _candidate_file(candidate: Any, name: str) -> Path:
    value = getattr(candidate, name, None)
    if value is None:
        raise ValueError(f"verified candidate is missing {name}")
    path = Path(value).expanduser().resolve()
    if not path.is_file():
        raise ValueError(f"verified candidate file is missing: {path}")
    return path


def _candidate_snapshot(candidate: Any) -> tuple[Path, dict[str, str]]:
    if getattr(candidate, "publishable_policy", None) != "balanced":
        raise ValueError("manifest requires a verified balanced candidate")
    balanced = _candidate_file(candidate, "balanced")
    publishable = getattr(candidate, "publishable", None)
    if publishable is None or Path(publishable).expanduser().resolve() != balanced:
        raise ValueError("manifest requires a verified balanced candidate")
    verified_bytes = getattr(candidate, "balanced_bytes", None)
    if not isinstance(verified_bytes, bytes) or hashlib.sha256(verified_bytes).hexdigest() != _sha256(balanced):
        raise ValueError("balanced candidate checksum is not verified")
    names = {
        "raw": "raw",
        "conservative": "conservative",
        "balanced": "balanced",
        "diagnostic": "diagnostic",
        "cleanupReport": "cleanup_report",
        "verifiedEvidence": "evidence_artifact",
        "poses": "poses",
        "frameAudit": "frame_audit",
        "trainingState": "training_state",
        "checkpoint": "checkpoint",
    }
    checksums = {json_name: _sha256(_candidate_file(candidate, attribute)) for json_name, attribute in names.items()}
    return balanced, checksums


@dataclass(frozen=True)
class VerifiedManifest:
    """A fixed snapshot of a verified candidate and its publication facts."""

    asset_id: str
    cell_ids: tuple[str, ...]
    parent_asset_id: str | None
    source_session_ids: tuple[str, ...]
    capture_times: Mapping[str, Any]
    build_time: str | None
    audit_statistics: Mapping[str, Any]
    recipe_revision: str | None
    training: Mapping[str, Any]
    cleanup_report: Mapping[str, Any]
    metrics: Mapping[str, Any] | None
    review_state: Mapping[str, Any]
    decision: Mapping[str, Any]
    asset_path: Path
    asset_sha256: str
    artifact_checksums: Mapping[str, str]

    @property
    def manifest_sha256(self) -> str:
        return hashlib.sha256(_canonical_json(self._document(include_manifest_checksum=False)).encode()).hexdigest()

    def _document(self, *, include_manifest_checksum: bool) -> dict[str, Any]:
        checksums = {
            "assetSha256": self.asset_sha256,
            "candidateArtifacts": _plain(self.artifact_checksums),
        }
        if include_manifest_checksum:
            checksums["manifestPayloadSha256"] = self.manifest_sha256
        return {
            "schemaVersion": SCHEMA_VERSION,
            "kind": ASSET_KIND,
            "assetId": self.asset_id,
            "cellIds": list(self.cell_ids),
            "parentAssetId": self.parent_asset_id,
            "sourceSessionIds": list(self.source_session_ids),
            "captureTimes": _plain(self.capture_times),
            "buildTime": self.build_time,
            "auditStatistics": _plain(self.audit_statistics),
            "recipeRevision": self.recipe_revision,
            "training": _plain(self.training),
            "cleanupReport": _plain(self.cleanup_report),
            "metrics": _plain(self.metrics),
            "reviewState": _plain(self.review_state),
            "decision": _plain(self.decision),
            "checksums": checksums,
        }

    def to_dict(self) -> dict[str, Any]:
        return self._document(include_manifest_checksum=True)

    def to_json(self) -> str:
        return _canonical_json(self.to_dict())


def build_manifest(
    candidate: Any,
    *,
    asset_id: str | None = None,
    cell_ids: list[str] | tuple[str, ...] = (),
    parent_asset_id: str | None = None,
    source_session_ids: list[str] | tuple[str, ...] = (),
    capture_times: Mapping[str, Any] | None = None,
    build_time: str | None = None,
    audit_statistics: Mapping[str, Any] | None = None,
    recipe_revision: str | None = None,
    training: Mapping[str, Any] | None = None,
    metrics: QualityMetrics | Mapping[str, Any] | None = None,
    review_state: Mapping[str, Any] | None = None,
    decision: PublicationDecision | Mapping[str, Any] | None = None,
) -> VerifiedManifest:
    """Snapshot a validated candidate without creating or deriving evidence."""
    asset_path, artifact_checksums = _candidate_snapshot(candidate)
    asset_sha256 = artifact_checksums["balanced"]
    generation_id = getattr(candidate, "generation_id", None)
    if asset_id is None:
        asset_id = str(generation_id) if isinstance(generation_id, str) and generation_id else asset_sha256
    if not isinstance(asset_id, str) or not asset_id:
        raise ValueError("asset_id must be a non-empty string")
    if parent_asset_id is not None and (not isinstance(parent_asset_id, str) or not parent_asset_id):
        raise ValueError("parent_asset_id must be a non-empty string or None")
    normalized_training = dict(training or {})
    normalized_training["checkpoint"] = {
        "path": str(_candidate_file(candidate, "checkpoint")),
        "sha256": artifact_checksums["checkpoint"],
    }
    cleanup = {
        "path": str(_candidate_file(candidate, "cleanup_report")),
        "sha256": artifact_checksums["cleanupReport"],
    }
    if isinstance(metrics, QualityMetrics):
        metric_values: Mapping[str, Any] | None = metrics.to_dict()
    else:
        metric_values = metrics
    if isinstance(decision, PublicationDecision):
        decision_values: Mapping[str, Any] = decision.to_dict()
    elif decision is None:
        decision_values = {"publish": False, "reasons": ["decision-missing"]}
    else:
        decision_values = decision
    try:
        return VerifiedManifest(
            asset_id=asset_id,
            cell_ids=tuple(str(cell_id) for cell_id in cell_ids),
            parent_asset_id=parent_asset_id,
            source_session_ids=tuple(str(session_id) for session_id in source_session_ids),
            capture_times=_json_value(capture_times or {}),
            build_time=build_time,
            audit_statistics=_json_value(audit_statistics or {}),
            recipe_revision=recipe_revision,
            training=_json_value(normalized_training),
            cleanup_report=_json_value(cleanup),
            metrics=None if metric_values is None else _json_value(metric_values),
            review_state=_json_value(review_state or {"status": "pending"}),
            decision=_json_value(decision_values),
            asset_path=asset_path,
            asset_sha256=asset_sha256,
            artifact_checksums=_json_value(artifact_checksums),
        )
    except (TypeError, ValueError) as error:
        raise ValueError("manifest metadata is malformed") from error
