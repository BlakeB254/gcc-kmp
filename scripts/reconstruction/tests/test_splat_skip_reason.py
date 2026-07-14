"""Pure splat_skip_reason resolution -- no R2/DB/GPU deps, so this file is
safe to run in a minimal venv (pytest only, no open3d/boto3/av/liblzfse).
fuse_cell.py imports open3d/requests at module level, which is why this
logic lives in its own splat_outcome.py rather than being tested by calling
into fuse_cell directly (see tests/test_fuse_cell.py for the
heavier-dependency version of this same contract).

See splat_outcome.py's module docstring for the full reason vocabulary;
recon_worker.py writes the result into reconstruction_jobs.splat_skip_reason
(varchar(32), migration 0039)."""
from splat_outcome import resolve_splat_outcome


def test_disabled_when_splat_not_wanted():
    assert resolve_splat_outcome(False, None, None) == "disabled"
    # "disabled" wins even if a trainer_reason somehow got passed in --
    # splat_wanted=False means no trainer should have run at all.
    assert resolve_splat_outcome(False, None, "training-failed") == "disabled"
    assert resolve_splat_outcome([], None, None) == "disabled"  # falsy, not bool


def test_none_when_trainer_produced_bytes():
    assert resolve_splat_outcome(True, b"ply-bytes", None) is None
    # bytes always win over any stray reason
    assert resolve_splat_outcome(True, b"ply-bytes", "training-failed") is None


def test_trainer_reason_passed_through_when_no_bytes():
    for reason in ("no-video-frames", "too-few-frames", "training-failed"):
        assert resolve_splat_outcome(True, None, reason) == reason
