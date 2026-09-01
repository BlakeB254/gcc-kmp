#!/usr/bin/env python3
"""Per-session scan→twin trace: the answer to "how long does a scan take?"

Every hop of the environment-scan→World-Twin flow already records a
timestamp; this joins them into one timeline per session (the registry's
`app.environment_scan_to_twin` hops — see cdx-platform
_shared/registry/workflow_catalog.yaml, which annotates which column backs
each span). No live telemetry emission needed: the DB is the trace ledger.

Spans (all durations in seconds):
  capture   scan_sessions.started_at   → scan_sessions.completed_at
  ingest    scan_sessions.completed_at → scan_sessions.indexed_at
            (covers upload + the ScanIndexer sweep; sweep cadence ~30 min
            bounds it — a large value here usually means upload policy held
            chunks for Wi-Fi/charging, not a slow network)
  queue     reconstruction_jobs.created_at → reconstruction_jobs.completed_at
            (includes the privacy-scrub deferral: REQUIRE_SCRUBBED_VIDEO
            re-queues video-bearing sessions until scrubbed renditions exist)
  publish   reconstruction_jobs.completed_at → reconstruction_assets.created_at
            (usually ~0: the worker writes the asset row on completion)
  total     started_at → newest asset created_at for the session's cell

Usage:
  DATABASE_URL=postgres://… python3 scan_trace.py [--limit 10] [--session ID]

⚠ DATABASE POINTING (2026-09-01 incident): the GCC RUNTIME's DATABASE_URL
is a different Neon branch from the Vercel/Neon-integration POSTGRES_URL*
vars. For production truth use the URL the gcc-backend container carries
(on the Spark: docker inspect deploy-gcc-backend-1). Pointing this script
at the integration branch silently traces the stale fork.
"""
from __future__ import annotations

import argparse
import os
import sys

import psycopg

QUERY = """
SELECT s.session_id, s.location_cell_id, s.platform,
       s.started_at, s.completed_at, s.indexed_at, s.reconstructed_at,
       j.created_at AS job_created_at, j.completed_at AS job_completed_at,
       j.status AS job_status, j.attempts,
       a.created_at AS asset_created_at, a.activated_at, a.format
FROM scan_sessions s
LEFT JOIN reconstruction_jobs j ON j.session_id = s.session_id
LEFT JOIN LATERAL (
    SELECT created_at, activated_at, format
    FROM reconstruction_assets ra
    WHERE ra.location_cell_id = s.location_cell_id
      AND ra.created_at >= s.completed_at
    ORDER BY ra.created_at ASC
    LIMIT 1
) a ON TRUE
WHERE s.completed_at IS NOT NULL
  AND (%(session_id)s::text IS NULL OR s.session_id = %(session_id)s)
ORDER BY s.completed_at DESC
LIMIT %(limit)s
"""


def _seconds(a, b) -> str:
    if a is None or b is None:
        return "—"
    delta = (b - a).total_seconds()
    if delta < 0:
        return f"{delta:.0f}?"  # clock skew / cross-cell asset — flag, don't hide
    if delta >= 3600:
        return f"{delta / 3600:.1f}h"
    if delta >= 60:
        return f"{delta / 60:.1f}m"
    return f"{delta:.0f}s"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--session", default=None, help="one session id")
    args = parser.parse_args()

    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        print("DATABASE_URL required (see the ⚠ DATABASE POINTING note in the header)", file=sys.stderr)
        return 2

    with psycopg.connect(dsn, connect_timeout=10) as conn:
        with conn.cursor() as cur:
            cur.execute(QUERY, {"limit": args.limit, "session_id": args.session})
            rows = cur.fetchall()

    if not rows:
        print("no completed sessions matched")
        return 0

    header = (
        f"{'session':32} {'cell':16} {'capture':>8} {'ingest':>8} "
        f"{'queue':>8} {'publish':>8} {'total':>8}  job/asset"
    )
    print(header)
    print("-" * len(header))
    for (session_id, cell, platform, started, completed, indexed, _reconstructed,
         job_created, job_done, job_status, attempts,
         asset_created, activated, fmt) in rows:
        total_end = asset_created or job_done
        state = job_status or "no-job"
        if attempts and attempts > 1:
            state += f"×{attempts}"
        if fmt:
            state += f" → {fmt}" + (" (activated)" if activated else "")
        print(
            f"{session_id:32.32} {cell:16.16} "
            f"{_seconds(started, completed):>8} "
            f"{_seconds(completed, indexed):>8} "
            f"{_seconds(job_created, job_done):>8} "
            f"{_seconds(job_done, asset_created):>8} "
            f"{_seconds(started, total_end):>8}  {state}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
