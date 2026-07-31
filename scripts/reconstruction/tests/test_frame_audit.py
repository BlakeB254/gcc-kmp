import io
from fractions import Fraction

import av
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


def test_export_audits_the_full_sidecar_route_before_bounded_rgb_decode(tmp_path, monkeypatch):
    """Catches unsampled camera evidence changing the route audit or selection."""
    intrinsics = Intrinsics(10.0, 10.0, 5.0, 5.0, 16, 16)
    frames = []
    for index, (x_position, tracking) in enumerate(zip(
        [0, .1, .2, .3, .4, .5, .6, .7, .8, 10],
        [1, 0, 0, 0, 1, 0, 0, 0, 0, 0],
    )):
        transform = np.eye(4)
        transform[0, 3] = x_position
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

    monkeypatch.setattr(gsplat_runner.r2_store, "is_configured", lambda: False)
    monkeypatch.setattr(gsplat_runner.r2_store, "download_bytes", lambda _key: b"source")
    monkeypatch.setattr(gsplat_runner.chunk_io, "parse_sidecar", lambda _source: ({}, frames))
    monkeypatch.setattr(
        gsplat_runner.chunk_io,
        "extract_video_frames_at",
        lambda _video, _times: [sharp for _ in _times],
    )
    monkeypatch.setattr(
        gsplat_runner,
        "_frame_sharpness_by_sidecar_index",
        lambda _video, _sidecar: {index: 1.0 for index in range(len(_sidecar))},
        raising=False,
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
    assert audits[0]["selectedIndices"] == [1, 9]
    assert audits[0]["rejections"]["4"] == ["limited-tracking"]


def test_frame_sharpness_map_covers_every_sidecar_frame_from_one_video_decode():
    """Catches full-path auditing reintroducing one decode or RGB array per pose."""
    checkerboard = np.indices((32, 32)).sum(axis=0) % 2
    image = np.repeat((checkerboard * 255).astype(np.uint8)[..., None], 3, axis=2)
    buffer = io.BytesIO()
    container = av.open(buffer, mode="w", format="mp4")
    stream = container.add_stream("h264", rate=30)
    stream.width, stream.height = 32, 32
    stream.pix_fmt = "yuv420p"
    stream.codec_context.time_base = Fraction(1, 90000)
    for timestamp in (0.0, 1.0):
        frame = av.VideoFrame.from_ndarray(image, format="rgb24").reformat(format="yuv420p")
        frame.pts = int(timestamp * 90000)
        for packet in stream.encode(frame):
            container.mux(packet)
    for packet in stream.encode():
        container.mux(packet)
    container.close()

    intrinsics = Intrinsics(10.0, 10.0, 5.0, 5.0, 32, 32)
    sidecar = [
        chunk_io.SidecarFrame(
            t=timestamp,
            t_video=timestamp,
            camera_transform=np.eye(4),
            intrinsics=intrinsics,
            tracking=0,
            thermal=0,
        )
        for timestamp in (0.0, .25, .5, .75, 1.0)
    ]

    sharpness = gsplat_runner._frame_sharpness_by_sidecar_index(buffer.getvalue(), sidecar)

    assert set(sharpness) == {0, 1, 2, 3, 4}
    assert all(score > 0 for score in sharpness.values())
