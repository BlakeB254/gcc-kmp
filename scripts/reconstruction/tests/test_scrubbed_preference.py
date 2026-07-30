"""The scrubbed-rendition naming convention (privacy scrub pass, PRD
docs/prd/scan-session-replay-devops-prd.md decision #2): scrubbed key =
raw key with the leading 'raw/' swapped for 'scrubbed/'. Consumers prefer
the scrubbed rendition when it exists."""
import pytest

import r2_store


RAW = "raw/464650:-721828/env-1783640498732-0cgku8/capture/chunk-0.mov"
SCRUBBED = "scrubbed/464650:-721828/env-1783640498732-0cgku8/capture/chunk-0.mov"
UNREADABLE = SCRUBBED + ".unreadable.json"


def test_scrubbed_key_swaps_only_the_leading_prefix():
    assert r2_store.scrubbed_key_for(RAW) == SCRUBBED


def test_scrubbed_key_rejects_non_raw_keys():
    with pytest.raises(ValueError):
        r2_store.scrubbed_key_for("reconstructed/464650:-721828/1/splat.ply")
    with pytest.raises(ValueError):
        # 'raw/' must be a whole path segment at the start, not a substring
        r2_store.scrubbed_key_for("rawish/x/chunk-0.mov")


def test_unreadable_marker_lives_beside_would_be_scrubbed_video():
    assert r2_store.unreadable_marker_key_for(RAW) == UNREADABLE


@pytest.mark.parametrize("terminal_key", [SCRUBBED, UNREADABLE])
def test_capture_video_terminal_for_scrubbed_or_permanently_unreadable(
    monkeypatch, terminal_key
):
    monkeypatch.setattr(r2_store, "object_exists", lambda key: key == terminal_key)
    assert r2_store.capture_video_is_terminal(RAW)


def test_capture_video_not_terminal_while_both_outputs_are_absent(monkeypatch):
    monkeypatch.setattr(r2_store, "object_exists", lambda key: False)
    assert not r2_store.capture_video_is_terminal(RAW)


def test_download_prefers_scrubbed_when_it_exists(monkeypatch):
    monkeypatch.setenv("PREFER_SCRUBBED_VIDEO", "1")
    monkeypatch.setattr(r2_store, "is_configured", lambda: True)
    monkeypatch.setattr(r2_store, "object_exists", lambda key: key == SCRUBBED)
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: key.encode())
    assert r2_store.download_video_bytes(RAW) == SCRUBBED.encode()


def test_download_falls_back_to_raw_when_no_scrubbed_rendition(monkeypatch):
    monkeypatch.setenv("PREFER_SCRUBBED_VIDEO", "1")
    monkeypatch.setattr(r2_store, "is_configured", lambda: True)
    monkeypatch.setattr(r2_store, "object_exists", lambda key: False)
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: key.encode())
    assert r2_store.download_video_bytes(RAW) == RAW.encode()


def test_download_kill_switch(monkeypatch):
    monkeypatch.setenv("PREFER_SCRUBBED_VIDEO", "0")
    monkeypatch.setattr(r2_store, "is_configured", lambda: True)

    def _no_head(key):
        raise AssertionError("must not HEAD when disabled")

    monkeypatch.setattr(r2_store, "object_exists", _no_head)
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: key.encode())
    assert r2_store.download_video_bytes(RAW) == RAW.encode()


def test_download_skips_head_when_r2_unconfigured(monkeypatch):
    """Unit tests of consumers monkeypatch download_bytes without configuring
    R2 — the preference probe must not raise R2NotConfiguredError there."""
    monkeypatch.delenv("PREFER_SCRUBBED_VIDEO", raising=False)
    monkeypatch.setattr(r2_store, "is_configured", lambda: False)
    monkeypatch.setattr(r2_store, "download_bytes", lambda key: key.encode())
    assert r2_store.download_video_bytes(RAW) == RAW.encode()
