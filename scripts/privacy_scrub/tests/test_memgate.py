"""Memory gate: the sweep defers below SCRUB_MIN_AVAIL_GB and stops its own
inference below SCRUB_ABORT_AVAIL_GB without uploading or marking the chunk."""
import pathlib
import sys
import types

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import memgate  # noqa: E402


def test_mem_available_reads_meminfo(tmp_path):
    p = tmp_path / "meminfo"
    p.write_text("MemTotal:  127000000 kB\nMemAvailable:    5242880 kB\n")
    assert memgate.mem_available_gb(str(p)) == 5.0


def test_budget_is_abort_floor_plus_weights_plus_decode():
    b = memgate.budget("yolo", {})
    assert (b.abort_gb, b.start_gb, b.loaded_gb) == (4.5, 7.0, 5.5)  # 3 earlyoom + 1.5 margin
    assert memgate.budget("rfdetr", {}).start_gb == 8.0
    b = memgate.budget("rfdetr", {"SCRUB_WEIGHTS_GB": "4", "SCRUB_DECODE_GB": "2",
                                  "SCRUB_EARLYOOM_GB": "3", "SCRUB_MARGIN_GB": "1"})
    assert (b.abort_gb, b.start_gb, b.loaded_gb) == (4.0, 10.0, 6.0)
    assert memgate.budget("yolo", {"SCRUB_ABORT_AVAIL_GB": "5"}).abort_gb == 5.0
    assert memgate.budget("yolo", {"SCRUB_MIN_AVAIL_GB": "12"}).start_gb == 12.0
    off = memgate.budget("rfdetr", {"SCRUB_MEMGATE": "0"})
    assert not off.enabled and off.abort_gb == 0
    memgate.check(off.abort_gb, available=lambda: 0.1)  # disabled: never raises


def test_check_raises_below_floor():
    with pytest.raises(memgate.LowMemory):
        memgate.check(4.5, available=lambda: 4.4)
    memgate.check(4.5, available=lambda: 4.6)


def test_guarded_refuses_below_floor():
    levels = iter([10.0, 4.0])
    fn = memgate.guarded(lambda x: x * 2, 4.5, available=lambda: next(levels))
    assert fn(2) == 4
    with pytest.raises(memgate.LowMemory):
        fn(2)


# --------------------------------------------------------------------------
# Whole sweep with fakes (no R2, no DB, no GPU)


@pytest.fixture
def sweep(monkeypatch):
    import scrub_worker as sw  # noqa: PLC0415

    keys = [("raw/c/s1/capture/chunk-0.mov", "s1", "c"), ("raw/c/s1/capture/chunk-1.mov", "s1", "c")]
    uploads, built = [], []
    monkeypatch.setattr(sw, "_index_chunks", lambda where, params: keys)
    monkeypatch.setattr(sw.r2_store, "capture_video_is_terminal", lambda k: False)
    monkeypatch.setattr(sw.r2_store, "download_bytes", lambda k: b"video")
    monkeypatch.setattr(sw.r2_store, "upload_bytes", lambda k, b, ct: uploads.append(k))
    monkeypatch.setattr(sw, "_sample_frames", lambda b: [])

    class FakeDetector:
        def __init__(self):
            built.append(self)

        def detect_frames(self, frames):
            return [[] for _ in frames]

        def qc_counts(self, frames):
            return {}

        def describe(self):
            return {"objects": "fake"}

    def fake_scrub(video, detect, check=None, **kw):
        check()  # the real scrub checks between every batch
        detect([object()])  # one inference batch per chunk
        return types.SimpleNamespace(video_bytes=b"scrubbed", frame_count=1,
                                     frames_with_detections=0, detections_by_label={})

    # Both switch values resolve to the fake (default SCRUB_DETECTOR=yolo).
    monkeypatch.setitem(sys.modules, "detector", types.SimpleNamespace(PrivacyDetector=FakeDetector))
    monkeypatch.setitem(sys.modules, "detector_yolo", types.SimpleNamespace(PrivacyDetector=FakeDetector))
    monkeypatch.setitem(sys.modules, "scrub_video", types.SimpleNamespace(scrub_video_bytes=fake_scrub))
    monkeypatch.setattr(sys, "argv", ["scrub_worker.py", "--sweep"])
    return sw, uploads, built


def _levels(monkeypatch, first, then):
    seq = iter(first)
    monkeypatch.setattr(memgate, "mem_available_gb", lambda: next(seq, then))


def test_sweep_defers_without_loading_the_model(sweep, monkeypatch, capsys):
    sw, uploads, built = sweep
    # 6.9 GB clears the old flat 6 GB floor but not the yolo budget (7.0).
    _levels(monkeypatch, [], 6.9)
    assert sw.main() == 0
    assert built == [] and uploads == []  # weights never loaded
    assert "deferred: low memory" in capsys.readouterr().out


def test_sweep_unloads_and_defers_when_weights_cost_more(sweep, monkeypatch, capsys):
    sw, uploads, built = sweep
    _levels(monkeypatch, [10.0], 5.0)  # fine at start, 5.0 < 5.5 once loaded
    assert sw.main() == 0
    assert len(built) == 1 and uploads == []
    assert "after loading weights" in capsys.readouterr().out


def test_gate_off_on_a_dedicated_host(sweep, monkeypatch):
    sw, uploads, built = sweep
    monkeypatch.setenv("SCRUB_MEMGATE", "0")
    _levels(monkeypatch, [], 0.5)
    assert sw.main() == 0
    assert len([k for k in uploads if k.endswith(".mov")]) == 2


def test_sweep_stops_mid_run_without_marking_the_chunk(sweep, monkeypatch, capsys):
    sw, uploads, built = sweep
    # start, after load, chunk-0 (batch check, detect, pre-QC, QC), then the
    # box gets squeezed before chunk-1's first batch.
    _levels(monkeypatch, [10.0] * 6, 4.0)
    assert sw.main() == 0
    scrubbed = [k for k in uploads if k.endswith(".mov")]
    assert scrubbed == ["scrubbed/c/s1/capture/chunk-0.mov"]  # chunk-1 neither uploaded...
    assert not any("unreadable" in k or "chunk-1" in k for k in uploads)  # ...nor marked
    out = capsys.readouterr().out
    assert "aborted: low memory" in out and "deferred: low memory" in out
