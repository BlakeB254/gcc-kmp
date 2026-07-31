"""Host-wide, observable admission for CUDA-owning reconstruction work."""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import sys
import time
from typing import Iterator
from uuid import uuid4


DEFAULT_GPU_LOCK_PATH = Path("/tmp/gcc-dgx-gpu.lock")


class GpuBusy(RuntimeError):
    """Raised before GPU work begins when another process owns the lock."""

    def __init__(self, metadata: dict[str, object]):
        self.metadata = metadata
        owner = metadata.get("owner", "unknown")
        pid = metadata.get("pid", "unknown")
        job_id = metadata.get("jobId")
        job = f", job={job_id}" if job_id is not None else ""
        super().__init__(f"GPU busy: owner={owner}, pid={pid}{job}")


class GpuReservation:
    """An advisory ``flock`` lease with inspectable owner metadata.

    The same descriptor that owns the flock writes and clears metadata.  A
    reservation identifier prevents an older cleanup in this PID from
    deleting metadata that a newer reservation has installed.
    """

    def __init__(
        self,
        lock_path: str | os.PathLike[str] | None = None,
        *,
        job_id: str | int | None = None,
        command: str | None = None,
    ) -> None:
        self.lock_path = Path(lock_path or os.environ.get(
            "GCC_GPU_LOCK_PATH", DEFAULT_GPU_LOCK_PATH
        ))
        self.job_id = job_id if job_id is not None else os.environ.get("RECONSTRUCTION_JOB_ID")
        self.command = command or " ".join(sys.argv)
        self._handle = None
        self._reservation_id: str | None = None

    @staticmethod
    def _read_metadata(handle) -> dict[str, object]:
        handle.seek(0)
        raw = handle.read()
        if not raw.strip():
            return {}
        try:
            value = json.loads(raw)
        except json.JSONDecodeError:
            return {"owner": "unknown", "metadata": "unreadable"}
        return value if isinstance(value, dict) else {"owner": "unknown", "metadata": "invalid"}

    @staticmethod
    def _write_metadata(handle, metadata: dict[str, object]) -> None:
        handle.seek(0)
        handle.truncate()
        json.dump(metadata, handle, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())

    @staticmethod
    def _clear_metadata(handle) -> None:
        handle.seek(0)
        handle.truncate()
        handle.flush()
        os.fsync(handle.fileno())

    def _metadata(self, owner: str, reservation_id: str) -> dict[str, object]:
        return {
            "owner": owner,
            "pid": os.getpid(),
            "startedAt": datetime.now(timezone.utc).isoformat(),
            "command": self.command,
            "jobId": self.job_id,
            "reservationId": reservation_id,
        }

    @contextmanager
    def acquire(self, owner: str, timeout_seconds: int = 0) -> Iterator["GpuReservation"]:
        """Acquire the reservation or raise :class:`GpuBusy` without waiting.

        A positive timeout is useful for controlled maintenance jobs.  Queue
        workers use the default nonblocking mode so a busy GPU defers the job
        instead of spending a worker lease waiting.
        """
        if not owner.strip():
            raise ValueError("GPU reservation owner is required")
        if timeout_seconds < 0:
            raise ValueError("timeout_seconds must be non-negative")
        if self._handle is not None:
            raise RuntimeError("GPU reservation is already active")

        self.lock_path.parent.mkdir(parents=True, exist_ok=True)
        handle = open(self.lock_path, "a+", encoding="utf-8")
        deadline = time.monotonic() + timeout_seconds
        while True:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                metadata = self._read_metadata(handle)
                if time.monotonic() >= deadline:
                    handle.close()
                    raise GpuBusy(metadata)
                time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))

        reservation_id = str(uuid4())
        self._handle = handle
        self._reservation_id = reservation_id
        try:
            self._write_metadata(handle, self._metadata(owner, reservation_id))
        except Exception:
            self._handle = None
            self._reservation_id = None
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            finally:
                handle.close()
            raise
        try:
            yield self
        finally:
            self._release()

    def _release(self) -> None:
        handle, reservation_id = self._handle, self._reservation_id
        self._handle = None
        self._reservation_id = None
        if handle is None:
            return
        try:
            metadata = self._read_metadata(handle)
            if (
                metadata.get("pid") == os.getpid()
                and metadata.get("reservationId") == reservation_id
            ):
                self._clear_metadata(handle)
        finally:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            finally:
                handle.close()
