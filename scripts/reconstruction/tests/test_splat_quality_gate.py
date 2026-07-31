from __future__ import annotations

import json
from pathlib import Path
import subprocess

import numpy as np
from PIL import Image
import pytest

import gsplat_runner
from fuse_cell import FusionResult


def _poses(points: list[tuple[float, float, float]]) -> np.ndarray:
    poses = np.repeat(np.eye(4, dtype=np.float64)[None], len(points), axis=0)
    poses[:, :3, 3] = points
    return poses


def test_rejects_scan_that_cannot_support_a_novel_view():
    """Catches publication of a fuzzy splat trained from a near-static scan."""
    quality_gate = getattr(gsplat_runner, "splat_quality_gate", None)
    assert callable(quality_gate), "splat publication quality gate is missing"

    poses = _poses([(0.0, 1.6, 0.0), (0.4, 1.6, 0.2), (0.9, 1.6, 0.4)])

    assert quality_gate(poses) == "insufficient-parallax"


def test_rejects_pose_track_that_is_implausibly_large_for_one_cell():
    """Catches cross-session registration jumps entering one cell model."""
    quality_gate = getattr(gsplat_runner, "splat_quality_gate", None)
    assert callable(quality_gate), "splat publication quality gate is missing"

    poses = _poses([(0.0, 1.6, 0.0), (5.0, 1.6, 4.0), (80.0, 1.6, 65.0)])

    assert quality_gate(poses) == "implausible-pose-span"


def test_accepts_a_walk_with_useful_parallax_inside_a_cell():
    """Catches an over-strict gate that would suppress a healthy street scan."""
    quality_gate = getattr(gsplat_runner, "splat_quality_gate", None)
    assert callable(quality_gate), "splat publication quality gate is missing"

    poses = _poses([(0.0, 1.6, 0.0), (1.5, 1.6, -1.0), (3.0, 1.7, -2.0)])

    assert quality_gate(poses) is None


def test_frame_selector_drops_blurry_candidates_before_training():
    """Catches a low-detail frame surviving while sharper coverage is available."""
    selector = getattr(gsplat_runner, "select_training_frame_indices", None)
    assert callable(selector), "training-frame quality selector is missing"

    sharp = np.indices((32, 32)).sum(axis=0) % 2
    images = [
        np.repeat((sharp * 255).astype(np.uint8)[..., None], 3, axis=2)
        for _ in range(10)
    ]
    images[5] = np.full((32, 32, 3), 127, dtype=np.uint8)

    selected = selector(images, budget=8)

    assert len(selected) == 8
    assert 5 not in selected
    assert selected == sorted(selected)


def test_frame_selector_keeps_short_capture_intact():
    """Catches quality filtering needlessly deleting frames below the budget."""
    selector = getattr(gsplat_runner, "select_training_frame_indices", None)
    assert callable(selector), "training-frame quality selector is missing"
    images = [np.full((16, 16, 3), value, dtype=np.uint8) for value in (20, 40, 60)]

    assert selector(images, budget=8) == [0, 1, 2]


def test_durable_staging_survives_timeout_and_retry_resumes(tmp_path, monkeypatch):
    """Catches temporary cleanup deleting the only recoverable trainer state."""
    staging = tmp_path / "durable-candidate"
    fusion = FusionResult(
        accepted_session_ids=["session"],
        merged_points=np.array([[0.0, 0.0, 0.0]], dtype=np.float32),
        merged_colors=np.array([[0.5, 0.5, 0.5]], dtype=np.float32),
        session_transforms={"session": np.eye(4)},
        anchor_lat=1.0,
        anchor_lng=2.0,
        north_aligned=True,
    )
    poses = _poses([(float(index), 1.6, 0.0) for index in range(8)])
    intrinsics = [np.eye(3, dtype=np.float64) for _ in range(8)]

    def fake_export(_manifest, _transform, frames_dir, start_index, _budget):
        for index in range(8):
            Image.fromarray(np.full((4, 4, 3), index, dtype=np.uint8)).save(
                Path(frames_dir) / f"{index:04d}.jpg"
            )
        return list(poses), intrinsics, start_index + 8, [{"chunk": 0}]

    monkeypatch.setattr(gsplat_runner, "available", lambda: True)
    monkeypatch.setattr(gsplat_runner, "_export_session_frames", fake_export)

    def timeout_after_checkpoint(command, **_kwargs):
        Path(command[command.index("--data") + 1], "checkpoint_latest.pt").write_bytes(
            b"periodic"
        )
        Path(command[command.index("--data") + 1], "training-state.json").write_text(
            json.dumps({"checkpoint": "checkpoint_latest.pt"})
        )
        raise subprocess.TimeoutExpired(command, 1)

    monkeypatch.setattr(gsplat_runner.subprocess, "run", timeout_after_checkpoint)
    with pytest.raises(subprocess.TimeoutExpired):
        gsplat_runner.train_gsplat_for_cell(
            "cell", fusion, {"session": {}}, staging_dir=staging
        )

    assert (staging / "checkpoint_latest.pt").read_bytes() == b"periodic"
    assert (staging / "poses.npy").is_file()
    assert (staging / "frame-audit.json").is_file()
    split = json.loads((staging / "validation-view-spec.json").read_text())
    assert set(split["recorded"]).isdisjoint(split["withheld"])
    assert set(split["recorded"]).union(split["withheld"]) == set(range(8))

    commands = []

    def successful_retry(command, **_kwargs):
        commands.append(command)
        output = Path(command[command.index("--out") + 1])
        if not output.is_absolute():
            output = staging / output
        if command[1].endswith("train_gsplat.py"):
            output.write_bytes(b"raw-ply")
            (staging / "checkpoint_latest.pt").write_bytes(b"completed")
            (staging / "training-state.json").write_text(json.dumps({"status": "completed"}))
        else:
            raise AssertionError("cleanup must not run without verified evidence")
        return type("Result", (), {"returncode": 0})()

    monkeypatch.setattr(gsplat_runner.subprocess, "run", successful_retry)
    candidate, reason = gsplat_runner.train_gsplat_for_cell(
        "cell", fusion, {"session": {}}, staging_dir=staging
    )

    assert (candidate, reason) == (None, "evidence-missing")
    assert ["--resume", str(staging)] == commands[0][
        commands[0].index("--resume"):commands[0].index("--resume") + 2
    ]
    candidate = staging / "candidate-manifest"
    assert {
        path.name for path in candidate.iterdir()
    } == {
        "poses.npy",
        "frame-audit.json",
        "validation-view-spec.json",
        "training-state.json",
        "checkpoint_latest.pt",
    }


def test_caller_provided_staging_path_is_absolute_and_survives_cwd_change(
    tmp_path, monkeypatch
):
    """Catches a retry path changing identity when the worker cwd changes."""
    original = tmp_path / "original"
    other = tmp_path / "other"
    original.mkdir()
    other.mkdir()
    monkeypatch.chdir(original)

    with gsplat_runner._durable_training_workspace("cell", Path("candidate")) as path:
        resolved = Path(path)
        monkeypatch.chdir(other)
        (resolved / "marker").write_text("durable")

    assert resolved.is_absolute()
    assert resolved == original / "candidate"
    assert (resolved / "marker").read_text() == "durable"


def test_default_candidate_staging_is_deterministic_and_cell_scoped(tmp_path, monkeypatch):
    """Catches different cells colliding or one cell losing its resume ownership."""
    monkeypatch.setenv("GCC_GSPLAT_STAGING_ROOT", str(tmp_path))

    first = gsplat_runner.candidate_staging_dir("cell:a")
    retry = gsplat_runner.candidate_staging_dir("cell:a")
    other = gsplat_runner.candidate_staging_dir("cell:b")

    assert first == retry
    assert first.parent == tmp_path
    assert first != other
