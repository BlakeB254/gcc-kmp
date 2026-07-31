import numpy as np

import chunk_io
import gsplat_runner
from frame_io import Intrinsics
from frame_audit import FrameRecord, audit_frames


def make_records(
    *,
    x_positions: list[float],
    tracking: list[int],
    sharpness: list[float],
    depth_confidence: list[float | None],
) -> list[FrameRecord]:
    return [
        FrameRecord(
            index=index,
            position=np.array([x, 0.0, 0.0]),
            tracking=tracking[index],
            sharpness=sharpness[index],
            exposure_ev=None,
            depth_confidence_fraction=depth_confidence[index],
        )
        for index, x in enumerate(x_positions)
    ]


def test_audit_rejects_degraded_frames_but_preserves_route_coverage():
    """Catches degraded camera or depth evidence entering the training set."""
    records = make_records(
        x_positions=[0, 1, 2, 3, 4, 5],
        tracking=[0, 0, 1, 0, 0, 0],
        sharpness=[.8, .7, .9, .05, .8, .7],
        depth_confidence=[.5, .5, .5, .5, 0, .5],
    )

    result = audit_frames(records, budget=3)

    assert 2 not in result.selected_indices
    assert 3 not in result.selected_indices
    assert 4 not in result.selected_indices
    assert result.baseline_meters >= 4.0
    assert result.normal_tracking_fraction == 5 / 6
    assert result.depth_coverage_fraction == 5 / 6
    assert result.rejections[2] == ["limited-tracking"]


def test_audit_selects_the_best_candidate_in_each_populated_route_bin():
    """Catches a sharpness-only selector collapsing a walk into one area."""
    records = make_records(
        x_positions=[0, 1, 2, 3, 4, 5],
        tracking=[0, 0, 0, 0, 0, 0],
        sharpness=[.1, .9, .2, .3, .8, .4],
        depth_confidence=[.5, .5, .5, .5, .5, .5],
    )

    result = audit_frames(records, budget=3)

    assert result.selected_indices == [1, 3, 4]


def test_audit_fills_empty_route_bins_with_unique_best_remaining_candidates():
    """Catches an empty route bin reducing the frame budget or duplicating a frame."""
    records = make_records(
        x_positions=[0, 0, 0, 5],
        tracking=[0, 0, 0, 0],
        sharpness=[.2, .9, .7, .5],
        depth_confidence=[.5, .5, .5, .5],
    )

    result = audit_frames(records, budget=3)

    assert result.selected_indices == [1, 2, 3]
    assert len(result.selected_indices) == len(set(result.selected_indices))


def test_export_records_the_frame_audit_for_the_training_dataset(tmp_path, monkeypatch):
    """Catches frame-quality decisions becoming invisible to later verification."""
    intrinsics = Intrinsics(10.0, 10.0, 5.0, 5.0, 16, 16)
    frames = []
    for index, tracking in enumerate([0, 1, 0, 0]):
        transform = np.eye(4)
        transform[0, 3] = index
        frames.append(chunk_io.SidecarFrame(
            t=float(index),
            t_video=float(index),
            camera_transform=transform,
            intrinsics=intrinsics,
            tracking=tracking,
            thermal=0,
        ))
    checkerboard = np.indices((16, 16)).sum(axis=0) % 2
    sharp = np.repeat((checkerboard * 255).astype(np.uint8)[..., None], 3, axis=2)
    images = [sharp, sharp, sharp, sharp]

    monkeypatch.setattr(gsplat_runner.r2_store, "is_configured", lambda: False)
    monkeypatch.setattr(gsplat_runner.r2_store, "download_bytes", lambda _key: b"source")
    monkeypatch.setattr(gsplat_runner.chunk_io, "parse_sidecar", lambda _source: ({}, frames))
    monkeypatch.setattr(
        gsplat_runner.chunk_io,
        "extract_video_frames_at",
        lambda _video, _times: images,
    )

    poses, matrices, next_index, audits = gsplat_runner._export_session_frames(
        {"captureChunks": ["chunk-0.mov", "chunk-0.jsonl"]},
        np.eye(4),
        str(tmp_path),
        start_index=0,
        budget=2,
    )

    assert next_index == 2
    assert len(poses) == len(matrices) == 2
    assert audits[0]["chunk"] == 0
    assert audits[0]["rejections"]["1"] == ["limited-tracking"]
