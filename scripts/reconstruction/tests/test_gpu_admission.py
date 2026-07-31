"""CPU-only admission tests for the shared DGX GPU lock."""
import json
import os

import pytest

from gpu_admission import GpuBusy, GpuReservation


def test_second_gpu_owner_is_refused(tmp_path):
    """Removing nonblocking flock admission would allow concurrent CUDA jobs."""
    first = GpuReservation(tmp_path / "gpu.lock")
    second = GpuReservation(tmp_path / "gpu.lock")

    with first.acquire("reconstruction"):
        with pytest.raises(GpuBusy, match="reconstruction"):
            with second.acquire("enhancement"):
                pass


def test_unlocked_stale_metadata_is_replaced_by_current_owner(tmp_path):
    """Removing metadata replacement would misidentify an admitted GPU job."""
    lock_path = tmp_path / "gpu.lock"
    lock_path.write_text(json.dumps({"owner": "stale-owner", "pid": 999_999}))

    with GpuReservation(lock_path, job_id="job-42").acquire("reconstruction"):
        metadata = json.loads(lock_path.read_text())
        assert metadata["owner"] == "reconstruction"
        assert metadata["pid"] == os.getpid()
        assert metadata["jobId"] == "job-42"
        assert metadata["command"]
        assert metadata["startedAt"]


def test_release_does_not_erase_newer_same_pid_metadata(tmp_path):
    """Removing reservation identity checking can erase a newer same-PID lock."""
    lock_path = tmp_path / "gpu.lock"
    reservation = GpuReservation(lock_path)

    with reservation.acquire("reconstruction"):
        metadata = json.loads(lock_path.read_text())
        metadata["reservationId"] = "newer-reservation"
        lock_path.write_text(json.dumps(metadata))

    assert json.loads(lock_path.read_text())["reservationId"] == "newer-reservation"


def test_release_clears_metadata_owned_by_its_reservation(tmp_path):
    """Removing normal release cleanup would leave a false busy owner behind."""
    lock_path = tmp_path / "gpu.lock"

    with GpuReservation(lock_path).acquire("reconstruction"):
        assert lock_path.read_text()

    assert lock_path.read_text() == ""
