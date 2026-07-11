"""Phase-3 reconstruction queue worker (docs/superpowers/specs/
2026-07-10-phase3-reconstruction-queue-and-prototype-design.md §3-§7).

Leases jobs from Neon's `reconstruction_jobs` (SELECT ... FOR UPDATE SKIP
LOCKED; the Ktor backend enqueues them after each index sweep), runs the
fusion pipeline for the job's CELL (job unit = session, work unit = cell),
compresses the splat for web serving, and records provenance directly in
Postgres — `reconstruction_assets` rows plus
`scan_sessions.reconstruction_version`/`reconstructed_at` — in one
transaction that also closes every sibling job for the same cell.

Env:
  DATABASE_URL       required — Neon (or any Postgres) connection string
  R2_*               required — same four vars as r2_store.py
  WORLD_MIRROR_URL   default http://127.0.0.1:7027 — cdx-world-mirror service
  SPLAT_REQUIRE_ACTIVATION  default '0' — '1' restores the activation-count
                     gate before spending GPU on splat production

Usage:
  python recon_worker.py --once            # process at most one job
  python recon_worker.py --loop [SECONDS]  # poll forever (default 60s idle)
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import traceback
from dataclasses import dataclass

import psycopg
import requests

import fuse_cell as fuse_cell_module
from fuse_cell import FusionResult, ProducedAsset

# Must outlast the slowest job: gsplat training runs up to 3h (its
# subprocess timeout in gsplat_runner) + fusion/download overhead. A lease
# shorter than the run means a second worker double-claims mid-train.
LEASE_MINUTES = 240
MAX_ATTEMPTS = 3
DEFAULT_WORLD_MIRROR_URL = "http://127.0.0.1:7027"

CLAIM_SQL = f"""
UPDATE reconstruction_jobs
SET status='leased', leased_until=now() + interval '{LEASE_MINUTES} minutes',
    attempts=attempts+1, updated_at=now()
WHERE id = (
  SELECT id FROM reconstruction_jobs
  WHERE status='queued' OR (status='leased' AND leased_until < now())
  ORDER BY created_at
  LIMIT 1 FOR UPDATE SKIP LOCKED)
RETURNING id, session_id, location_cell_id, attempts
"""


@dataclass
class Job:
    id: int
    session_id: str
    location_cell_id: str
    attempts: int


def claim_job(conn) -> Job | None:
    """Lease the oldest claimable job. Call inside a transaction; the row
    lock taken by the inner SELECT is what makes concurrent workers skip
    each other's claims."""
    with conn.cursor() as cur:
        cur.execute(CLAIM_SQL)
        row = cur.fetchone()
    if row is None:
        return None
    return Job(id=row[0], session_id=row[1], location_cell_id=row[2], attempts=row[3])


def fail_job(conn, job: Job, err: BaseException) -> None:
    """Requeue for another attempt, or mark failed for good at MAX_ATTEMPTS.
    Failed jobs need a manual requeue (no automatic retry storm on a
    deterministic crash); the raw R2 data is untouched either way."""
    message = "".join(traceback.format_exception_only(type(err), err)).strip()[:2000]
    status = "failed" if job.attempts >= MAX_ATTEMPTS else "queued"
    with conn.cursor() as cur:
        cur.execute(
            """UPDATE reconstruction_jobs
               SET status=%s, leased_until=NULL, last_error=%s, updated_at=now()
               WHERE id=%s""",
            (status, message, job.id),
        )
    conn.commit()


def complete_cell(conn, cell: str, result: FusionResult, assets: list[ProducedAsset]) -> None:
    """One transaction: record every produced asset (idempotent on r2_key —
    the Ktor indexer mirrors reconstructed/ into the same table), stamp the
    accepted sessions' reconstruction_version, and close every job for this
    cell (the run consumed all of the cell's sessions, so sibling jobs are
    satisfied by it)."""
    version = assets[0].version if assets else None
    provenance = json.dumps({
        "sessions": result.accepted_session_ids,
        "rejectedSessions": result.rejected_session_ids,
        # Geo-referencing of the cell frame (north-aligned axes, origin at
        # the anchor session's ARKit start) — consumers use this to place
        # cells at their true relative offsets when merging across cells.
        "anchorLat": result.anchor_lat,
        "anchorLng": result.anchor_lng,
        "northAligned": result.north_aligned,
        "northRmsM": result.north_rms_m,
        # World-layer eligibility (scan-splat-registration-contract): True
        # only when every merged session solved a geoRefEvents world
        # registration. The CDX World Mirror twin merges ONLY these; legacy
        # GPS-track-aligned cells stay display-grade in GCC's own views.
        "worldEligible": result.world_eligible,
    })
    with conn.transaction():
        with conn.cursor() as cur:
            for asset in assets:
                cur.execute(
                    """INSERT INTO reconstruction_assets
                         (location_cell_id, asset_type, format, r2_key, version,
                          source_scan_coverage_event_ids, activated_at)
                       VALUES (%s, %s, %s, %s, %s, %s::jsonb, CASE WHEN %s THEN now() END)
                       ON CONFLICT (r2_key) DO NOTHING""",
                    (cell, asset.asset_type, asset.fmt, asset.r2_key, asset.version,
                     provenance, result.activated),
                )
            if version is not None and result.accepted_session_ids:
                cur.execute(
                    """UPDATE scan_sessions
                       SET reconstruction_version=%s, reconstructed_at=now()
                       WHERE session_id = ANY(%s)""",
                    (version, result.accepted_session_ids),
                )
            # Persist each session's world-registration solve (migration
            # 0036): status + residual surface registrability in the scan
            # index; the full solve record keeps the transform reproducible.
            for sid, reg in result.session_registrations.items():
                primary = max((s for s in reg["segments"] if s["passesGate"]),
                              key=lambda s: s["events"], default=None)
                cur.execute(
                    """UPDATE scan_sessions
                       SET registration_status=%s, registration_residual_m=%s,
                           world_registration=%s::jsonb
                       WHERE session_id=%s""",
                    (reg["status"], primary["residualM"] if primary else None,
                     json.dumps(reg), sid),
                )
            cur.execute(
                """UPDATE reconstruction_jobs
                   SET status='done', completed_at=now(), updated_at=now()
                   WHERE location_cell_id=%s AND status IN ('queued', 'leased')""",
                (cell,),
            )


def _world_mirror_healthy(base_url: str) -> bool:
    try:
        resp = requests.get(f"{base_url}/health", timeout=10)
        return resp.ok and resp.json().get("status") == "ok"
    except Exception:
        return False


def compress_splat_for_web(splat_ply: bytes) -> tuple[bytes, str] | None:
    """Compress the full-fidelity splat .ply into a web-streamable format via
    @playcanvas/splat-transform: .spz preferred, .compressed.ply fallback.
    None (with a loud log) when neither converter path works — the format
    column must record what was actually produced, so no silent substitutes."""
    with tempfile.TemporaryDirectory(prefix="splatweb_") as tmp:
        src = os.path.join(tmp, "splat.ply")
        with open(src, "wb") as f:
            f.write(splat_ply)
        for out_name, fmt in (("splat.spz", "spz"), ("splat.compressed.ply", "ply")):
            out = os.path.join(tmp, out_name)
            try:
                proc = subprocess.run(
                    ["npx", "-y", "@playcanvas/splat-transform", src, out],
                    capture_output=True, text=True, timeout=600,
                )
            except (subprocess.TimeoutExpired, FileNotFoundError) as e:
                print(f"splat-web: {out_name} conversion unavailable: {e}", file=sys.stderr)
                continue
            if proc.returncode == 0 and os.path.exists(out) and os.path.getsize(out) > 0:
                with open(out, "rb") as f:
                    return f.read(), fmt
            print(
                f"splat-web: {out_name} conversion failed (rc={proc.returncode}): "
                f"{proc.stderr.strip()[:500]}",
                file=sys.stderr,
            )
    print("splat-web: NO web asset produced -- full splat.ply is still in R2", file=sys.stderr)
    return None


def process_job(conn, job: Job) -> None:
    """Fuse the job's whole cell, publish, record. Raises on failure —
    run_once translates that into fail_job."""
    train_splat = os.environ.get("RECON_TRAIN_SPLAT", "1") == "1"
    require_activation = os.environ.get("SPLAT_REQUIRE_ACTIVATION", "0") == "1"
    world_mirror_url = os.environ.get("WORLD_MIRROR_URL", DEFAULT_WORLD_MIRROR_URL)

    splat_engine = os.environ.get("SPLAT_ENGINE", "gsplat")
    if train_splat and splat_engine == "gsplat":
        import gsplat_runner

        if not gsplat_runner.available():
            raise RuntimeError(
                f"gsplat venv missing at {gsplat_runner.GSPLAT_PYTHON} -- run "
                "scripts/gsplat_train/setup.sh, or set SPLAT_ENGINE=world-mirror"
            )
    elif train_splat and not _world_mirror_healthy(world_mirror_url):
        raise RuntimeError(
            f"cdx-world-mirror not healthy at {world_mirror_url} -- requeueing rather than "
            "producing a splat-less reconstruction silently"
        )
    # splat_train reaches world-mirror through world_mirror_client's default
    # base_url parameter; export the env var it reads.
    os.environ.setdefault("WORLD_MIRROR_URL", world_mirror_url)

    result = fuse_cell_module.fuse_cell(
        job.location_cell_id,
        api_base_url=None,  # provenance is written below, directly in Postgres
        train_splat=train_splat,
        require_activation_for_splat=require_activation,
    )

    assets = list(result.assets)
    splat = next((a for a in assets if a.asset_type == "splat" and a.data), None)
    if splat is not None:
        compressed = compress_splat_for_web(splat.data)
        if compressed is not None:
            data, fmt = compressed
            key = fuse_cell_module._write_bytes_to_r2(
                job.location_cell_id, "splat-web", fmt, data, splat.version
            )
            assets.append(ProducedAsset("splat-web", fmt, key, splat.version, data))
            print(f"cell {job.location_cell_id}: wrote {key} ({len(data)/1e6:.1f} MB)")

    if not assets:
        raise RuntimeError(
            f"cell {job.location_cell_id}: fusion produced no assets "
            f"(accepted={result.accepted_session_ids}, rejected={result.rejected_session_ids})"
        )
    complete_cell(conn, job.location_cell_id, result, assets)
    print(
        f"job {job.id} done: cell {job.location_cell_id} v{assets[0].version}, "
        f"{len(assets)} asset(s), accepted={result.accepted_session_ids}, "
        f"rejected={result.rejected_session_ids}, activated={result.activated}"
    )


def run_once(conn) -> bool:
    """One claim/process cycle. True if a job was processed (successfully or
    not), False when the queue is empty."""
    with conn.transaction():
        job = claim_job(conn)
    if job is None:
        return False
    print(f"claimed job {job.id}: session {job.session_id}, cell {job.location_cell_id}, attempt {job.attempts}")
    try:
        process_job(conn, job)
    except Exception as err:
        conn.rollback()
        traceback.print_exc()
        fail_job(conn, job, err)
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--once", action="store_true", help="process at most one job, then exit")
    group.add_argument("--loop", nargs="?", const=60, type=int, metavar="SECONDS",
                       help="poll forever, sleeping SECONDS (default 60) when idle")
    args = parser.parse_args()

    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        parser.error("DATABASE_URL is required")

    with psycopg.connect(dsn) as conn:
        if args.once:
            processed = run_once(conn)
            print("queue empty" if not processed else "done")
            return
        while True:
            if not run_once(conn):
                time.sleep(args.loop)


if __name__ == "__main__":
    main()
