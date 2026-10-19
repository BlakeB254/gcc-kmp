import io
import pathlib
import sys

import av
import numpy as np
import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from scrub_video import (  # noqa: E402
    Detection,
    dilate_detections,
    pad_box,
    pixelate_regions,
    scrub_video_bytes,
)


def _make_video(n_frames=48, w=320, h=240, fps=24) -> bytes:
    buf = io.BytesIO()
    container = av.open(buf, "w", format="mov")
    stream = container.add_stream("libx264", rate=fps)
    stream.width, stream.height, stream.pix_fmt = w, h, "yuv420p"
    for i in range(n_frames):
        img = np.full((h, w, 3), 32, dtype=np.uint8)
        # high-frequency texture so blur is measurable
        img[:, ::4] = 255
        img[10:40, 10:60] = (i * 5) % 255
        frame = av.VideoFrame.from_ndarray(img, format="bgr24")
        container.mux(stream.encode(frame.reformat(format="yuv420p")))
    container.mux(stream.encode())
    container.close()
    return buf.getvalue()


def _decode_frames(video_bytes):
    with av.open(io.BytesIO(video_bytes)) as c:
        return [f.to_ndarray(format="bgr24") for f in c.decode(video=0)]


def _decode_rebased_pts(video_bytes):
    with av.open(io.BytesIO(video_bytes)) as c:
        stream = c.streams.video[0]
        pts = [float(f.pts * stream.time_base) for f in c.decode(video=0)]
    return [p - pts[0] for p in pts]


BOX = (100, 60, 220, 180)


def _fake_detector(frames_seen):
    def detect(batch):
        out = []
        for _ in batch:
            idx = len(frames_seen)
            frames_seen.append(idx)
            out.append([Detection(BOX, "person", 0.9)] if 10 <= idx <= 20 else [])
        return out

    return detect


def test_pad_box_grows_and_clamps():
    assert pad_box((10, 10, 20, 20), 0.5, 100, 100) == (5, 5, 25, 25)
    assert pad_box((0, 0, 100, 100), 0.5, 100, 100) == (0, 0, 100, 100)


def test_dilate_detections_bridges_gaps():
    per_frame = [[] for _ in range(10)]
    per_frame[5] = [Detection(BOX, "car", 0.8)]
    dilated = dilate_detections(per_frame, radius=2)
    assert [len(d) for d in dilated] == [0, 0, 0, 1, 1, 1, 1, 1, 0, 0]


def test_pixelate_regions_only_touches_boxes():
    img = (np.random.default_rng(0).integers(0, 255, (240, 320, 3))).astype(np.uint8)
    out = pixelate_regions(img.copy(), [BOX])
    x1, y1, x2, y2 = BOX
    inside_before = img[y1:y2, x1:x2].astype(int)
    inside_after = out[y1:y2, x1:x2].astype(int)
    assert np.abs(inside_before - inside_after).mean() > 5  # visibly changed
    assert inside_after.std() < inside_before.std() * 0.6  # detail destroyed
    mask = np.ones(img.shape[:2], bool)
    mask[y1:y2, x1:x2] = False
    assert np.array_equal(out[mask], img[mask])  # untouched outside


def test_scrub_video_blurs_detected_frames_and_preserves_timing():
    src = _make_video()
    result = scrub_video_bytes(src, _fake_detector([]), dilation_radius=2, pad_frac=0.0)
    src_frames, out_frames = _decode_frames(src), _decode_frames(result.video_bytes)
    assert len(out_frames) == len(src_frames) == result.frame_count
    assert _decode_rebased_pts(result.video_bytes) == pytest.approx(
        _decode_rebased_pts(src), abs=1e-6
    )
    x1, y1, x2, y2 = BOX

    def roi_std(f):
        return f[y1:y2, x1:x2].std()

    # dilated window 8..22 blurred; frame 0 untouched-ish (lossy re-encode:
    # compare detail, not equality)
    assert roi_std(out_frames[15]) < roi_std(src_frames[15]) * 0.6
    assert roi_std(out_frames[0]) > roi_std(src_frames[0]) * 0.8
    assert result.frames_with_detections == 11
    assert result.detections_by_label == {"person": 11}


def test_scrub_video_no_detections_still_reencodes():
    src = _make_video(n_frames=12)
    result = scrub_video_bytes(src, lambda batch: [[] for _ in batch])
    assert result.frame_count == 12 and result.frames_with_detections == 0
    assert len(_decode_frames(result.video_bytes)) == 12


def _unfinalized_mov() -> bytes:
    """A .mov the app started writing but never finalized: ftyp + mdat with
    no moov atom. This is exactly what the iOS capture uploads when a
    recording is interrupted before AVAssetWriter.finishWriting() — seen in
    real data as raw/464652:-721827/env-1785010826928-ctdbu9/…/chunk-0.mov."""
    good = _make_video(n_frames=8)
    # Strip the moov atom by keeping only the leading ftyp/mdat boxes.
    moov = good.find(b"moov")
    assert moov > 0, "fixture should contain a moov atom to remove"
    return good[: moov - 4]


def test_unfinalized_mov_raises_invalid_data():
    """scrub_video_bytes surfaces a moov-less capture as InvalidDataError.

    scrub_worker relies on this being distinguishable from transient errors:
    it skips + records these permanently-broken chunks instead of aborting
    the whole sweep (one such chunk used to starve every later session's
    chunks, deferring their reconstruction jobs forever)."""
    with pytest.raises(av.error.InvalidDataError):
        scrub_video_bytes(_unfinalized_mov(), lambda frames: [[] for _ in frames])


def test_check_runs_between_batches_in_both_passes_and_can_stop():
    video = _make_video(n_frames=48)
    calls = []
    scrub_video_bytes(video, _fake_detector([]), detect_batch=16, check=lambda: calls.append(1))
    assert len(calls) == 3 + 3  # 48 frames / 16 per batch, detect pass + encode pass

    class Stop(Exception):
        pass

    def stop_on_third():
        calls.append(1)
        if len(calls) > 2:
            raise Stop

    calls.clear()
    with pytest.raises(Stop):
        scrub_video_bytes(video, _fake_detector([]), detect_batch=16, check=stop_on_third)


def test_qc_sampler_seeks_and_spreads_over_the_clip():
    import scrub_worker as sw  # noqa: PLC0415

    # Our own encoder's output (what QC actually samples).
    out = scrub_video_bytes(_make_video(n_frames=48), _fake_detector([]))  # marker < 255, no wrap
    frames = sw._sample_frames(out.video_bytes, n=8)
    assert len(frames) == 8
    # The marker block brightens with the frame index: samples must be
    # spread out and in order, not 8 copies of the first frame.
    marks = [int(f[20:30, 20:50].mean()) for f in frames]
    assert marks == sorted(marks) and marks[-1] - marks[0] > 100
