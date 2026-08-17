"""Trains the cell's Gaussian splat with gsplat from ARKit-POSED frames —
the geo-registered splat producer (replaced world-mirror for this role
2026-07-11; see docs/superpowers/specs/2026-07-10-phase3-... §splat).

Why this beats the world-mirror + Sim3 path it replaces: world-mirror
re-solves cameras from pixels alone, and at street scale its content was
internally inconsistent with its own returned cameras (up to ~78° off), so
no rigid fit could place it. Here the poses ARE ARKit's (gravity-aligned,
metric), mapped through the exact per-session transform fuse_sessions()
computed — so the splat trains natively in the north-aligned cell frame
and is geo-true by construction. Verified on nl-0cgku8: near-field bearing
0.1° vs the 10.6° GPS walk, footprint matching the LiDAR cloud.

The training itself runs in scripts/gsplat_train/.venv (torch nightly
cu130 for the GB10's sm_121) as a subprocess; this module only builds the
dataset and shuttles bytes. Env:
  GSPLAT_ITERS  training iterations (default 15000, ~35 min on GB10)
"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import av
import numpy as np
from PIL import Image
from plyfile import PlyData

import chunk_io
import r2_store
from frame_audit import FrameRecord, audit_frames
from fuse_cell import FusionResult, _group_chunks_by_index
from verified_evidence import (
    ARTIFACT_NAME,
    EvidenceArtifactError,
    candidate_source_checksums,
    load_verified_evidence_artifact,
    read_file_snapshot,
    read_ply_snapshot,
)
from cleanup_policy import evaluate_cleanup, validate_policy_thresholds
from gpu_admission import GpuReservation

GSPLAT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "gsplat_train"))
GSPLAT_PYTHON = os.path.join(GSPLAT_DIR, ".venv", "bin", "python")
VALIDATION_RENDERER = os.path.join(GSPLAT_DIR, "render_validation.py")
MAX_TOTAL_FRAMES = 160
# ARKit camera convention is GL (-Z forward, +Y up); gsplat wants OpenCV.
GL_TO_CV = np.diag([1.0, -1.0, -1.0, 1.0])
MIN_NOVEL_VIEW_BASELINE_METERS = 2.0
MAX_CELL_POSE_SPAN_METERS = 40.0

sys.path.insert(0, GSPLAT_DIR)
try:
    from render_validation import write_validation_view_spec
finally:
    sys.path.pop(0)


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _matches_ordered_mask(
    raw_vertices: np.ndarray,
    mask: np.ndarray,
    output_vertices: np.ndarray,
    *,
    chunk_size: int = 65_536,
) -> bool:
    """Compare selected records in bounded chunks without copying the full output."""
    if raw_vertices.dtype != output_vertices.dtype or int(mask.sum()) != len(output_vertices):
        return False
    output_offset = 0
    for start in range(0, len(raw_vertices), chunk_size):
        selected = raw_vertices[start : start + chunk_size][mask[start : start + chunk_size]]
        stop = output_offset + len(selected)
        if not np.array_equal(output_vertices[output_offset:stop], selected):
            return False
        output_offset = stop
    return output_offset == len(output_vertices)


@dataclass(frozen=True, init=False)
class VerifiedCandidate:
    """Validated composition of one immutable cleanup generation."""

    raw: Path
    conservative: Path
    balanced: Path
    diagnostic: Path
    cleanup_report: Path
    evidence_artifact: Path
    poses: Path
    frame_audit: Path
    training_state: Path
    checkpoint: Path
    generation_id: str
    _balanced_sha256: str

    publishable_policy = "balanced"

    def __init__(self):
        raise TypeError("VerifiedCandidate must be constructed from validated staging")

    @classmethod
    def from_staging(cls, staging_dir: str | os.PathLike[str]) -> "VerifiedCandidate":
        staging = Path(staging_dir).expanduser().resolve()
        manifest = staging / "candidate-manifest"
        paths = {
            "raw": staging / "splat_gsplat.ply",
            "conservative": staging / "splat_conservative.ply",
            "balanced": staging / "splat_balanced.ply",
            "diagnostic": staging / "splat_diagnostic.ply",
            "cleanup_report": staging / "cleanup-report.json",
            "evidence_artifact": staging / ARTIFACT_NAME,
            "poses": manifest / "poses.npy",
            "frame_audit": manifest / "frame-audit.json",
            "training_state": manifest / "training-state.json",
            "checkpoint": manifest / "checkpoint_latest.pt",
        }
        missing = [str(path) for path in paths.values() if not path.is_file()]
        if missing:
            raise ValueError("cleanup report candidate is incomplete: " + ", ".join(missing))
        try:
            report_snapshot = read_file_snapshot(paths["cleanup_report"])
            report = json.loads(report_snapshot.data)
        except (OSError, ValueError, json.JSONDecodeError, EvidenceArtifactError) as error:
            raise ValueError("cleanup report is unreadable") from error
        if not isinstance(report, dict):
            raise ValueError("cleanup report is unreadable")
        del report_snapshot
        if report.get("schemaVersion") != 1:
            raise ValueError("cleanup report schema version is invalid")
        required_sections = (
            "counts",
            "policyThresholds",
            "componentScoreHistograms",
            "removedReasonCounts",
            "verifiedEvidence",
        )
        if any(not isinstance(report.get(name), dict) for name in required_sections):
            raise ValueError("cleanup report is incomplete")
        source = report.get("source")
        if not isinstance(source, dict):
            raise ValueError("cleanup report source is invalid")
        if source.get("path") != str(paths["raw"]):
            raise ValueError("cleanup report source path does not match raw PLY")
        raw_snapshot = read_ply_snapshot(paths["raw"])
        if source.get("sha256") != raw_snapshot.sha256:
            raise ValueError("cleanup report source checksum does not match raw PLY")
        if source.get("count") != len(raw_snapshot.vertices):
            raise ValueError("cleanup report source count does not match raw PLY")
        outputs = report.get("outputs")
        if not isinstance(outputs, dict) or set(outputs) != {
            "conservative", "balanced", "diagnostic"
        }:
            raise ValueError("cleanup report outputs are invalid")
        for policy in ("conservative", "balanced", "diagnostic"):
            entry = outputs.get(policy)
            path = paths[policy]
            if not isinstance(entry, dict) or entry.get("path") != str(path):
                raise ValueError(f"cleanup report {policy} path is invalid")
            if entry.get("publishable") is not (policy == "balanced"):
                raise ValueError(f"cleanup report {policy} publishability is invalid")
        thresholds = validate_policy_thresholds(
            report["policyThresholds"], require_verified_base=True
        )
        removed_reasons = report["removedReasonCounts"]
        for policy in ("conservative", "balanced", "diagnostic"):
            if not isinstance(removed_reasons.get(policy), dict) or not {
                "policy-mask-rejected",
                "below-ground",
                "above-cap",
                "outside-corridor",
            } <= set(removed_reasons[policy]):
                raise ValueError("cleanup report removal reasons are incomplete")
        artifact = load_verified_evidence_artifact(
            paths["evidence_artifact"],
            raw_ply_path=paths["raw"],
            expected_source_checksums=None,
            raw_ply_snapshot=raw_snapshot,
        )
        evidence_report = report.get("verifiedEvidence")
        expected_evidence_report = {
            "path": str(artifact.path),
            "sha256": artifact.sha256,
            "schemaVersion": artifact.schema_version,
            "rawPlySha256": artifact.raw_ply_sha256,
            "vertexOrderSha256": artifact.vertex_order_sha256,
            "sourceChecksums": artifact.source_checksums,
            "provenance": artifact.provenance,
        }
        if evidence_report != expected_evidence_report:
            raise ValueError("cleanup report verified evidence is invalid")
        if report.get("generationId") != artifact.generation_id:
            raise ValueError("cleanup report generation does not match evidence")
        evaluation = evaluate_cleanup(
            raw_snapshot.vertices,
            artifact.result,
            thresholds,
            poses=np.asarray(artifact.inputs.camera_poses),
        )
        if report["counts"] != evaluation.counts:
            raise ValueError("cleanup report aggregate counts are semantically invalid")
        if report["componentScoreHistograms"] != evaluation.histograms:
            raise ValueError("cleanup report component histograms are invalid")
        if removed_reasons != evaluation.removed_reason_counts:
            raise ValueError("cleanup report removal reasons are invalid")
        if report.get("qualityMetrics") != evaluation.quality_metrics:
            raise ValueError("cleanup report quality metrics are invalid")
        output_checksums: dict[str, str] = {}
        for policy in ("conservative", "balanced", "diagnostic"):
            entry = outputs[policy]
            snapshot = read_ply_snapshot(paths[policy])
            if entry.get("sha256") != snapshot.sha256:
                raise ValueError(f"cleanup report {policy} checksum is invalid")
            if entry.get("count") != len(snapshot.vertices):
                raise ValueError(f"cleanup report {policy} count is invalid")
            if not _matches_ordered_mask(
                raw_snapshot.vertices,
                evaluation.masks[policy],
                snapshot.vertices,
            ):
                raise ValueError(
                    f"cleanup report {policy} output does not match its validated mask"
                )
            output_checksums[policy] = snapshot.sha256
            del snapshot
        for source_name, manifest_name in (
            ("poses", "poses"),
            ("frameAudit", "frame_audit"),
            ("trainingState", "training_state"),
            ("checkpoint", "checkpoint"),
        ):
            if _file_sha256(paths[manifest_name]) != artifact.source_checksums[source_name]:
                raise ValueError("cleanup report candidate metadata spans multiple generations")
        candidate = object.__new__(cls)
        for name, path in paths.items():
            object.__setattr__(candidate, name, path)
        object.__setattr__(candidate, "generation_id", artifact.generation_id)
        object.__setattr__(candidate, "_balanced_sha256", output_checksums["balanced"])
        return candidate

    @property
    def publishable(self) -> Path:
        return self.balanced

    @property
    def balanced_bytes(self) -> bytes:
        with self.balanced.open("rb") as stream:
            data = stream.read()
        if hashlib.sha256(data).hexdigest() != self._balanced_sha256:
            raise ValueError("balanced checksum changed after candidate validation")
        return data


def verified_candidate_result(
    staging_dir: str | os.PathLike[str],
) -> tuple[VerifiedCandidate | None, str | None]:
    """Fail closed unless the complete staged generation validates."""
    try:
        return VerifiedCandidate.from_staging(staging_dir), None
    except EvidenceArtifactError as error:
        return None, error.reason
    except ValueError:
        return None, "cleanup-incomplete"


def _build_train_command(
    data_dir: str,
    iters: str,
    max_gaussians: str,
    *,
    resume: str | None = None,
) -> list[str]:
    """Build the explicit verified-publication trainer contract."""
    command = [
        GSPLAT_PYTHON,
        os.path.join(GSPLAT_DIR, "train_gsplat.py"),
        "--data", data_dir,
        "--iters", iters,
        "--max-gaussians", max_gaussians,
        "--out", "splat_gsplat.ply",
    ]
    if resume is not None:
        command.extend(["--resume", resume])
    command.extend([
        "--pose-mode", "frozen",
        "--checkpoint-every", "1000",
    ])
    return command


def candidate_staging_dir(cell_id: str) -> Path:
    """Return the durable per-cell seam that Task 4 can wrap in a candidate."""
    configured = os.environ.get("GCC_GSPLAT_STAGING_ROOT")
    if configured:
        root = Path(configured).expanduser()
    else:
        state_home = Path(
            os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")
        ).expanduser()
        root = state_home / "gcc" / "gsplat-candidates"
    cell_key = hashlib.sha256(cell_id.encode("utf-8")).hexdigest()
    return root / cell_key


@contextmanager
def _durable_training_workspace(
    cell_id: str,
    staging_dir: str | os.PathLike[str] | None,
):
    """Yield a caller-visible workspace without deleting it on any exit path."""
    path = (
        Path(staging_dir).expanduser().resolve()
        if staging_dir is not None
        else candidate_staging_dir(cell_id).expanduser().resolve()
    )
    path.mkdir(parents=True, exist_ok=True)
    yield str(path)


def _run_validation_subprocess(
    candidate: VerifiedCandidate,
    staging_dir: str | os.PathLike[str],
    env: dict[str, str],
    *,
    gpu_job_id: str | int | None,
):
    """Render a balanced candidate only while the GPU reservation is held.

    The output directory is durable: a failed validation remains diagnosable,
    but its candidate is not returned for publication.
    """
    staging = Path(staging_dir)
    manifest = staging / "validation-manifest.json"
    manifest.write_text('{"frameSemanticTags": []}\n', encoding="utf-8")
    command = [
        GSPLAT_PYTHON,
        VALIDATION_RENDERER,
        "--ply",
        str(candidate.balanced),
        "--data",
        str(staging),
        "--manifest",
        str(manifest),
        "--out",
        str(staging / "validation"),
    ]
    # render_validation.main imports torch/gsplat only after this subprocess
    # starts; a busy reservation therefore prevents CUDA initialization.
    with GpuReservation(
        job_id=gpu_job_id, command=" ".join(command)
    ).acquire("validation"):
        return subprocess.run(command, env=env, timeout=1800)


def _validated_candidate(
    candidate: VerifiedCandidate,
    staging_dir: str | os.PathLike[str],
    env: dict[str, str],
    *,
    cell_id: str,
    gpu_job_id: str | int | None,
) -> tuple[VerifiedCandidate | None, str | None]:
    """Fail closed on renderer failure without deleting candidate evidence."""
    validation = _run_validation_subprocess(
        candidate, staging_dir, env, gpu_job_id=gpu_job_id
    )
    if validation.returncode != 0:
        print(
            f"gsplat_runner: cell {cell_id}: validation failed "
            f"(rc={validation.returncode}); candidate retained but not publishable",
            file=sys.stderr,
        )
        return None, "validation-failed"
    return candidate, None


def _stage_verified_training_metadata(
    dataset_dir: str | os.PathLike[str],
    staging_dir: str | os.PathLike[str],
) -> None:
    """Copy immutable trainer evidence into the candidate-manifest staging area."""
    source = Path(dataset_dir)
    destination = Path(staging_dir)
    destination.mkdir(parents=True, exist_ok=True)
    for name in (
        "poses.npy",
        "frame-audit.json",
        "validation-view-spec.json",
        "training-state.json",
        "checkpoint_latest.pt",
    ):
        shutil.copy2(source / name, destination / name)


def splat_quality_gate(poses: np.ndarray) -> str | None:
    """Reject camera tracks that cannot form a trustworthy cell splat.

    Map/evidence ingestion happens before this trainer and is unaffected.
    The horizontal bounding-box diagonal is intentionally conservative:
    below 2 m there is not enough parallax for a useful novel view, while
    above 40 m indicates that independent session frames were misregistered
    into one local cell coordinate frame.
    """
    camera_positions = np.asarray(poses, dtype=np.float64)[:, :3, 3]
    horizontal_extent = np.ptp(camera_positions[:, [0, 2]], axis=0)
    span = float(np.linalg.norm(horizontal_extent))
    if span < MIN_NOVEL_VIEW_BASELINE_METERS:
        return "insufficient-parallax"
    if span > MAX_CELL_POSE_SPAN_METERS:
        return "implausible-pose-span"
    return None


def _frame_sharpness(image: np.ndarray) -> float:
    """Resolution-normalized high-frequency energy used only for ranking."""
    gray = np.asarray(image, dtype=np.float32).mean(axis=2) / 255.0
    if min(gray.shape) < 3:
        return 0.0
    laplacian = (
        -4.0 * gray[1:-1, 1:-1]
        + gray[:-2, 1:-1]
        + gray[2:, 1:-1]
        + gray[1:-1, :-2]
        + gray[1:-1, 2:]
    )
    return float(np.var(laplacian))


def _frame_sharpness_by_sidecar_index(
    video_bytes: bytes,
    sidecar: list[chunk_io.SidecarFrame],
) -> dict[int, float]:
    """Map every pose sample to its nearest decoded RGB frame's sharpness.

    A chunk is decoded once and retains only timestamp/metric pairs, rather
    than one RGB array per sidecar sample. This lets the audit cover the full
    captured path without multiplying decoder work or holding the video in
    memory, while final image extraction remains bounded by the frame budget.
    """
    if not sidecar:
        return {}
    container = av.open(io.BytesIO(video_bytes))
    try:
        stream = next((stream for stream in container.streams if stream.type == "video"), None)
        if stream is None:
            return {}
        video_times: list[float] = []
        sharpness: list[float] = []
        pts0: float | None = None
        for frame in container.decode(stream):
            if frame.pts is None:
                continue
            timestamp = float(frame.pts * stream.time_base)
            if pts0 is None:
                pts0 = timestamp
            video_times.append(timestamp - pts0)
            sharpness.append(_frame_sharpness(frame.to_ndarray(format="rgb24")))
    finally:
        container.close()
    if not video_times:
        return {}

    decoded_times = np.asarray(video_times)
    targets = np.asarray([frame.t_video for frame in sidecar])
    right = np.searchsorted(decoded_times, targets, side="left")
    right = np.clip(right, 0, len(decoded_times) - 1)
    left = np.clip(right - 1, 0, len(decoded_times) - 1)
    nearest = np.where(
        np.abs(decoded_times[left] - targets) <= np.abs(decoded_times[right] - targets),
        left,
        right,
    )
    return {
        index: sharpness[int(decoded_index)]
        for index, decoded_index in enumerate(nearest)
    }


def select_training_frame_indices(images: list[np.ndarray], budget: int) -> list[int]:
    """Drop the blurriest fifth, then retain uniform temporal coverage.

    This is deliberately relative within a capture: concrete, grass, and
    brick have very different absolute texture energy, so a global threshold
    would reject legitimate low-texture scenes. Short captures stay intact.
    """
    if budget <= 0 or not images:
        return []
    if len(images) <= budget:
        return list(range(len(images)))

    scores = np.asarray([_frame_sharpness(image) for image in images])
    keep_count = max(budget, int(np.ceil(len(images) * 0.8)))
    sharpest = np.argsort(scores, kind="stable")[-keep_count:]
    candidates = np.sort(sharpest)
    if len(candidates) <= budget:
        return candidates.tolist()
    picks = np.linspace(0, len(candidates) - 1, budget, dtype=int)
    return candidates[picks].tolist()


def available() -> bool:
    return os.path.exists(GSPLAT_PYTHON)


def _export_session_frames(manifest: dict, T_cell: np.ndarray, frames_dir: str,
                           start_index: int, budget: int) -> tuple[list, list, int, list[dict]]:
    """Sample up to `budget` posed frames from one session's chunks, writing
    jpgs into frames_dir and returning (poses_cv, intrinsics, next_index,
    serialized_frame_audits)."""
    chunks = _group_chunks_by_index(manifest.get("captureChunks") or [])
    chunks = {i: k for i, k in sorted(chunks.items()) if "mov" in k and "jsonl" in k}
    if not chunks:
        return [], [], start_index, []
    per_chunk = max(1, budget // len(chunks))

    poses, intrinsics, n = [], [], start_index
    audits: list[dict] = []
    for chunk_index, kinds in chunks.items():
        if n - start_index >= budget:
            break
        if r2_store.is_configured() and r2_store.object_exists(
            r2_store.unreadable_marker_key_for(kinds["mov"])
        ):
            print(f"gsplat_runner: skipping permanently unreadable video {kinds['mov']}")
            continue
        video = r2_store.download_bytes(kinds["mov"])
        _header, sidecar = chunk_io.parse_sidecar(r2_store.download_bytes(kinds["jsonl"]))
        if not sidecar:
            continue
        confidence_records = (
            chunk_io.parse_confidence_chunk(r2_store.download_bytes(kinds["confidence"]))
            if "confidence" in kinds
            else []
        )
        depth_records = (
            chunk_io.parse_depth_chunk(r2_store.download_bytes(kinds["depth"]))
            if "depth" in kinds
            else []
        )
        sharpness_by_index = _frame_sharpness_by_sidecar_index(video, sidecar)
        records: list[FrameRecord] = []
        for i, frame in enumerate(sidecar):
            depth_confidence_fraction = None
            if confidence_records:
                confidence_record = min(
                    confidence_records, key=lambda record: abs(record.t - frame.t)
                )
                if abs(confidence_record.t - frame.t) <= 0.2:
                    depth_confidence_fraction = float(
                        np.mean(confidence_record.confidence >= 1)
                    )
            exposure_ev = None
            if (
                frame.exposure_duration_seconds is not None
                and frame.iso is not None
                and frame.exposure_duration_seconds > 0
                and frame.iso > 0
            ):
                exposure_ev = float(
                    np.log2(100.0 / (frame.exposure_duration_seconds * frame.iso))
                )
            records.append(FrameRecord(
                index=i,
                position=frame.camera_transform[:3, 3],
                tracking=frame.tracking,
                sharpness=sharpness_by_index.get(i, float("nan")),
                exposure_ev=exposure_ev,
                depth_confidence_fraction=depth_confidence_fraction,
            ))
        audit = audit_frames(records, budget=per_chunk)
        audits.append({
            "chunk": chunk_index,
            "selectedIndices": audit.selected_indices,
            "rejections": {str(index): reasons for index, reasons in audit.rejections.items()},
            "baselineMeters": audit.baseline_meters,
            "normalTrackingFraction": audit.normal_tracking_fraction,
            "depthCoverageFraction": audit.depth_coverage_fraction,
        })
        selected = set(audit.selected_indices)
        selected_indices = sorted(selected)
        imgs = chunk_io.extract_video_frames_at(
            video, [sidecar[i].t_video for i in selected_indices]
        )
        for i, img in zip(selected_indices, imgs):
            if img is None:
                continue
            f = sidecar[i]
            poses.append(T_cell @ f.camera_transform @ GL_TO_CV)
            intrinsics.append(np.array([[f.intrinsics.fx, 0, f.intrinsics.cx],
                                        [0, f.intrinsics.fy, f.intrinsics.cy],
                                        [0, 0, 1]], dtype=np.float64))
            Image.fromarray(img).save(os.path.join(frames_dir, f"{n:04d}.jpg"), quality=92)
            if depth_records and confidence_records:
                depth_record = min(
                    depth_records, key=lambda record: abs(record.t - f.t)
                )
                confidence_record = min(
                    confidence_records, key=lambda record: abs(record.t - f.t)
                )
                if (
                    abs(depth_record.t - f.t) <= 0.2
                    and abs(confidence_record.t - f.t) <= 0.2
                    and depth_record.depth_meters.shape
                    == confidence_record.confidence.shape
                ):
                    captured_depth = np.asarray(
                        depth_record.depth_meters, dtype=np.float32
                    ).copy()
                    captured_confidence = np.asarray(
                        confidence_record.confidence, dtype=np.uint8
                    ).copy()
                    qualified = (
                        np.isfinite(captured_depth)
                        & (captured_depth > 0.0)
                        & (captured_confidence >= 1)
                        & (captured_confidence <= 2)
                    )
                    captured_depth[~qualified] = 0.0
                    captured_confidence[~qualified] = 0
                    evidence_dir = Path(frames_dir).parent / "depth-evidence"
                    evidence_dir.mkdir(parents=True, exist_ok=True)
                    np.savez(
                        evidence_dir / f"{n:04d}.npz",
                        depthMeters=captured_depth,
                        confidence=captured_confidence,
                    )
            n += 1
    return poses, intrinsics, n, audits


def _export_vidmap_frames(
    manifest: dict,
    fusion_result: FusionResult,
    frames_dir: str,
    start_index: int,
    budget: int,
) -> tuple[list, list, int, list[dict]]:
    """Walk-bundle fallback: VidMap COLMAP poses + on-disk frames → cell frame.

    Used when a session has no ARKit mov+jsonl chunks (civic walk video).
    Requires ``bundleDir`` / ``priorCamPath`` from attach_vidmap_priors and a
    GPS Sim3 in rec/vidmap/poses_gps.json. COLMAP is already OpenCV, so
    GL_TO_CV is not applied.
    """
    import vidmap_runner

    bundle = manifest.get("bundleDir")
    if not bundle:
        return [], [], start_index, []
    root = Path(bundle).expanduser()
    model = vidmap_runner.load_colmap_model(root / "rec" / "vidmap" / "colmap")
    poses_path = root / "rec" / "vidmap" / "poses_gps.json"
    if model is None or model.empty or not poses_path.is_file():
        return [], [], start_index, []
    try:
        poses_gps = json.loads(poses_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return [], [], start_index, []
    transform = poses_gps.get("transform")
    if not transform:
        return [], [], start_index, []
    lat0 = fusion_result.anchor_lat
    lon0 = fusion_result.anchor_lng
    if lat0 is None or lon0 is None:
        return [], [], start_index, []
    cameras = {c.camera_id: c for c in model.cameras}
    images = model.images[:budget]
    poses, intrinsics, n = [], [], start_index
    copied = 0
    for image in images:
        src = None
        for folder in (root / "frames", root / "images", root / "keyframes", root):
            candidate = folder / image.name
            if candidate.is_file():
                src = candidate
                break
        if src is None:
            continue
        dest = Path(frames_dir) / f"{n:04d}.jpg"
        shutil.copy2(src, dest)
        c2w = vidmap_runner.c2w_from_w2c(image.qvec, image.tvec)
        poses.append(vidmap_runner.colmap_c2w_to_cell(c2w, transform, float(lat0), float(lon0)))
        cam = cameras.get(image.camera_id)
        if cam is None:
            dest.unlink(missing_ok=True)
            continue
        params = cam.params
        if cam.model in {"SIMPLE_PINHOLE", "SIMPLE_RADIAL", "SIMPLE_RADIAL_FISHEYE"}:
            fx = fy = params[0]
            cx, cy = params[1], params[2]
        else:
            fx, fy, cx, cy = params[0], params[1], params[2], params[3]
        intrinsics.append(np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1]], dtype=np.float64))
        n += 1
        copied += 1
    audits = [{
        "chunk": "vidmap",
        "selectedIndices": list(range(copied)),
        "rejections": {},
        "baselineMeters": None,
        "normalTrackingFraction": None,
        "depthCoverageFraction": None,
        "source": "vidmap-colmap",
    }]
    return poses, intrinsics, n, audits


def train_gsplat_for_cell(
    cell_id: str,
    fusion_result: FusionResult,
    session_manifests_by_id: dict[str, dict],
    *,
    staging_dir: str | os.PathLike[str] | None = None,
    evidence_artifact_path: str | os.PathLike[str] | None = None,
    gpu_job_id: str | int | None = None,
) -> tuple[VerifiedCandidate | None, str | None]:
    """Train one splat for the whole cell: every accepted session's frames,
    posed through its fusion transform into the shared cell frame, with the
    already-merged cell point cloud as gaussian init. Returns
    (VerifiedCandidate, None) on success, or (None, reason) when training,
    captured evidence, cleanup, or candidate validation fails. The point-cloud
    asset is unaffected either way. The exported dataset, raw PLY, checkpoints,
    and candidate metadata remain at ``staging_dir`` (or
    :func:`candidate_staging_dir`) for recovery and later evidence production."""
    if not available():
        print(f"gsplat_runner: no venv at {GSPLAT_PYTHON} -- run gsplat_train/setup.sh", file=sys.stderr)
        return None, "training-failed"
    if fusion_result.merged_points.shape[0] == 0:
        return None, "training-failed"

    with _durable_training_workspace(cell_id, staging_dir) as tmp:
        frames_dir = os.path.join(tmp, "frames")
        Path(frames_dir).mkdir(parents=True, exist_ok=True)
        for stale_frame in Path(frames_dir).glob("*.jpg"):
            stale_frame.unlink()
        depth_evidence_dir = Path(tmp) / "depth-evidence"
        depth_evidence_dir.mkdir(parents=True, exist_ok=True)
        for stale_evidence in depth_evidence_dir.glob("*.npz"):
            stale_evidence.unlink()
        budget = max(1, MAX_TOTAL_FRAMES // len(fusion_result.accepted_session_ids))
        poses, intrinsics, n, frame_audits = [], [], 0, []
        for sid in fusion_result.accepted_session_ids:
            manifest = session_manifests_by_id.get(sid)
            if manifest is None:
                continue
            p, K, n, audits = _export_session_frames(
                manifest, fusion_result.session_transforms[sid], frames_dir, n, budget)
            if not p:
                p, K, n, audits = _export_vidmap_frames(
                    manifest, fusion_result, frames_dir, n, budget)
            poses.extend(p)
            intrinsics.extend(K)
            frame_audits.extend(audits)
        if n == 0:
            print(f"gsplat_runner: cell {cell_id}: no posed frames")
            return None, "no-video-frames"
        if n < 8:
            print(f"gsplat_runner: cell {cell_id}: only {n} posed frames (need 8+)")
            return None, "too-few-frames"
        quality_reason = splat_quality_gate(np.stack(poses))
        if quality_reason is not None:
            print(
                f"gsplat_runner: cell {cell_id}: splat publication held: "
                f"{quality_reason}"
            )
            return None, quality_reason

        sample = Image.open(os.path.join(frames_dir, "0000.jpg"))
        with open(os.path.join(tmp, "frame-audit.json"), "w") as fh:
            json.dump({"version": 1, "audits": frame_audits}, fh, indent=2)
        np.save(os.path.join(tmp, "poses.npy"), np.stack(poses).astype(np.float64))
        np.save(os.path.join(tmp, "intrinsics.npy"), np.stack(intrinsics).astype(np.float64))
        write_validation_view_spec(
            np.stack(poses), os.path.join(tmp, "validation-view-spec.json")
        )
        np.save(os.path.join(tmp, "init_points.npy"), fusion_result.merged_points.astype(np.float32))
        np.save(os.path.join(tmp, "init_colors.npy"), fusion_result.merged_colors.astype(np.float32))
        with open(os.path.join(tmp, "meta.json"), "w") as fh:
            json.dump({"cell": cell_id, "frames": n,
                       "width": sample.width, "height": sample.height,
                       "anchorLat": fusion_result.anchor_lat,
                       "anchorLng": fusion_result.anchor_lng,
                       "northAligned": fusion_result.north_aligned,
                       "initPoints": int(fusion_result.merged_points.shape[0])}, fh)

        # 30k default since the quality-recipe upgrade (antialiased rendering,
        # frozen ARKit poses, exposure comp, and LiDAR depth loss in trainer):
        # ~2x the old 15k wall-clock (~95min/cell on the GB10). Set
        # GSPLAT_ITERS=15000 to trade quality for turnaround.
        iters = os.environ.get("GSPLAT_ITERS", "30000")
        max_gaussians = os.environ.get("GSPLAT_MAX_GAUSSIANS", "5000000")
        print(
            f"gsplat_runner: cell {cell_id}: training on {n} frames, "
            f"{iters} iters, cap {max_gaussians} gaussians..."
        )
        env = dict(os.environ, TORCH_CUDA_ARCH_LIST="12.0;12.1")
        resume = (
            tmp
            if os.path.isfile(os.path.join(tmp, "checkpoint_latest.pt"))
            and os.path.isfile(os.path.join(tmp, "training-state.json"))
            else None
        )
        # The child imports torch and initializes CUDA in train_gsplat.main;
        # acquire before spawning so a busy GPU starts no CUDA process at all.
        train_command = _build_train_command(tmp, iters, max_gaussians, resume=resume)
        with GpuReservation(
            job_id=gpu_job_id, command=" ".join(train_command)
        ).acquire("reconstruction"):
            proc = subprocess.run(
                train_command,
                env=env, timeout=3 * 3600,
            )
        out_ply = os.path.join(tmp, "splat_gsplat.ply")
        if proc.returncode != 0 or not os.path.exists(out_ply):
            print(f"gsplat_runner: cell {cell_id}: training failed (rc={proc.returncode})", file=sys.stderr)
            return None, "training-failed"
        _stage_verified_training_metadata(
            tmp, os.path.join(tmp, "candidate-manifest")
        )

        # Cleanup consumes only a serialized Task 3 artifact produced from
        # captured observations. This runner never infers visibility, RGB
        # observations, pixels, or LiDAR confidence from the staged files.
        candidate_checksums = candidate_source_checksums(tmp)
        staged_evidence_path = Path(tmp) / ARTIFACT_NAME
        requested_evidence_path = (
            Path(evidence_artifact_path).expanduser().resolve()
            if evidence_artifact_path is not None
            else staged_evidence_path
        )
        try:
            load_verified_evidence_artifact(
                requested_evidence_path,
                raw_ply_path=out_ply,
                expected_source_checksums=candidate_checksums,
            )
        except EvidenceArtifactError as error:
            print(f"gsplat_runner: cell {cell_id}: {error}", file=sys.stderr)
            return None, error.reason
        if requested_evidence_path != staged_evidence_path:
            shutil.copy2(requested_evidence_path, staged_evidence_path)
        reconstruction_dir = str(Path(__file__).resolve().parent)
        existing_pythonpath = env.get("PYTHONPATH")
        env["PYTHONPATH"] = (
            reconstruction_dir
            if not existing_pythonpath
            else os.pathsep.join((reconstruction_dir, existing_pythonpath))
        )
        cproc = subprocess.run(
            [GSPLAT_PYTHON, os.path.join(GSPLAT_DIR, "clean_splat.py"),
             "--ply", out_ply, "--points", os.path.join(tmp, "init_points.npy"),
             "--poses", os.path.join(tmp, "poses.npy"),
             "--evidence-artifact", str(staged_evidence_path),
             "--out-dir", tmp],
            env=env, timeout=1800,
        )
        if cproc.returncode != 0:
            print(
                f"gsplat_runner: cell {cell_id}: cleanup failed (rc={cproc.returncode}); "
                "raw training output retained but not publishable",
                file=sys.stderr,
            )
            return None, "cleanup-failed"
        candidate, candidate_reason = verified_candidate_result(tmp)
        if candidate is None:
            print(
                f"gsplat_runner: cell {cell_id}: incomplete cleanup artifacts",
                file=sys.stderr,
            )
            return None, candidate_reason
        if candidate_reason is not None:
            return None, candidate_reason

        return _validated_candidate(
            candidate, tmp, env, cell_id=cell_id, gpu_job_id=gpu_job_id
        )
