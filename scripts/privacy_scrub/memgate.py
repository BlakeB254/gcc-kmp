"""Memory budget for the scrub on a shared GB10.

The Spark is the intelligence engine (Blake): its unified memory idles at
~3-8 GB MemAvailable with the resident model loaded, and earlyoom fires near
3 GB and kills shared services. The scrub's real home is the cloud recon
container (SCRUB_MEMGATE=0 there); on the Spark it is a gated fallback.

Budget (all GB, env-tunable):
  abort  = SCRUB_ABORT_AVAIL_GB, else SCRUB_EARLYOOM_GB (3) + SCRUB_MARGIN_GB (1.5)
  start  = abort + weights (SCRUB_WEIGHTS_GB, else per detector: yolo 1.5,
           rfdetr 2.5) + decode (SCRUB_DECODE_GB, 1.0); raised to
           SCRUB_MIN_AVAIL_GB when that is set higher
  loaded = abort + decode — re-checked right after the weights load

The sweep only loads weights when `start` is available, unloads and defers
if `loaded` is not available once they are in, and stops its own inference
the moment availability drops below `abort` (checked between every batch).
An interrupted chunk is neither uploaded nor marked; the next sweep redoes it.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Callable

DEFAULT_WEIGHTS_GB = {"yolo": 1.5, "rfdetr": 2.5}


class LowMemory(RuntimeError):
    pass


def mem_available_gb(meminfo: str = "/proc/meminfo") -> float:
    with open(meminfo) as f:
        for line in f:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / (1024 * 1024)
    return float("inf")  # not Linux: no gate


@dataclass(frozen=True)
class Budget:
    start_gb: float
    loaded_gb: float
    abort_gb: float

    @property
    def enabled(self) -> bool:
        return self.start_gb > 0


def budget(detector: str, env=os.environ) -> Budget:
    """SCRUB_MEMGATE=0 disables it — for a dedicated host (the cloud recon
    container) with nothing else to protect."""
    if env.get("SCRUB_MEMGATE", "1") == "0":
        return Budget(0.0, 0.0, 0.0)

    def f(name, default):
        raw = env.get(name)
        return float(raw) if raw not in (None, "") else default

    abort = f("SCRUB_ABORT_AVAIL_GB", f("SCRUB_EARLYOOM_GB", 3.0) + f("SCRUB_MARGIN_GB", 1.5))
    weights = f("SCRUB_WEIGHTS_GB", DEFAULT_WEIGHTS_GB.get(detector, 3.0))
    decode = f("SCRUB_DECODE_GB", 1.0)
    start = max(abort + weights + decode, f("SCRUB_MIN_AVAIL_GB", 0.0))
    return Budget(start_gb=start, loaded_gb=abort + decode, abort_gb=abort)


def check(abort_gb: float, available: Callable[[], float] | None = None) -> None:
    """Raise LowMemory below abort_gb (no-op when the gate is disabled)."""
    if abort_gb <= 0:
        return
    avail = (available or mem_available_gb)()
    if avail < abort_gb:
        raise LowMemory(f"{avail:.1f} GB available < {abort_gb} GB")


def guarded(fn: Callable, abort_gb: float, available: Callable[[], float] | None = None):
    """Wrap an inference call so it refuses to run below abort_gb."""

    def call(*args, **kwargs):
        check(abort_gb, available)
        return fn(*args, **kwargs)

    return call
