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
  REQUIRE_SCRUBBED_VIDEO  default '1' — jobs wait (defer, not fail) until the
                     privacy scrub pass has renditions for every video chunk
                     of the cell; '0' (or PREFER_SCRUBBED_VIDEO=0) disables

Usage:
  python recon_worker.py --once            # process at most one job
  python recon_worker.py --loop [SECONDS]  # poll forever (default 60s idle)
  python recon_worker.py --drain           # process claimable jobs until the
                                            # queue is empty, then exit 0
                                            # (systemd timer mode -- see
                                            # ops/systemd/gcc-recon-worker.timer)
  python recon_worker.py --activate-reviewed # activate attributed approvals
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
import r2_store
from fuse_cell import FusionResult, ProducedAsset, StagedVerifiedCandidate
from gpu_admission import GpuBusy
from verified_manifest import build_manifest
from verified_quality import PublicationDecision, QualityMetrics

# Must outlast the slowest job: gsplat training runs up to 3h (its
# subprocess timeout in gsplat_runner) + fusion/download overhead. A lease
# shorter than the run means a second worker double-claims mid-train.
LEASE_MINUTES = 240
MAX_ATTEMPTS = 3
# A deterministic 3-hour trainer timeout must not be reclaimed immediately by
# the same --drain process. Back off to a later timer tick so one bad cell
# cannot monopolize the GPU for two consecutive attempts.
RETRY_BACKOFF_MINUTES = 30
DEFAULT_WORLD_MIRROR_URL = "http://127.0.0.1:7027"
# How long a job waits before re-checking that the privacy scrub pass has
# produced renditions for its chunks (scripts/privacy_scrub/ runs on a
# 10-minute timer — see ops/systemd/gcc-privacy-scrub.timer).
SCRUB_DEFER_MINUTES = 10

CLAIM_SQL = f"""
UPDATE reconstruction_jobs
SET status='leased', leased_until=now() + interval '{LEASE_MINUTES} minutes',
    attempts=attempts+1, updated_at=now(), last_error=NULL
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
    terminal = job.attempts >= MAX_ATTEMPTS
    status = "failed" if terminal else "leased"
    with conn.cursor() as cur:
        cur.execute(
            """UPDATE reconstruction_jobs
               SET status=%s,
                   leased_until=CASE WHEN %s THEN NULL
                     ELSE now() + make_interval(mins => %s) END,
                   last_error=%s, updated_at=now()
               WHERE id=%s""",
            (status, terminal, RETRY_BACKOFF_MINUTES, message, job.id),
        )
    conn.commit()


class ScrubPending(Exception):
    """The privacy scrub pass hasn't produced renditions for every video
    chunk this job's cell will fuse — the PRD's ordering requirement
    (docs/prd/scan-session-replay-devops-prd.md decision #2: scrub BEFORE
    reconstruction) says wait, not fall back to raw."""

    def __init__(self, missing: list[str]):
        self.missing = missing
        super().__init__(
            f"{len(missing)} video chunk(s) lack scrubbed renditions "
            f"(first: {missing[0] if missing else '?'})"
        )


# Fusion consumes every session manifest under the cell's raw/ prefix; those
# sessions are indexed with that same cell id, so their mov chunk rows are
# the gate's ground truth (chunk keys can live under a NEIGHBORING cell
# prefix when GPS drifted between manifest and chunk upload — joining via
# session_id covers that; filtering capture_chunks.location_cell_id would not).
UNSCRUBBED_SQL = """
SELECT c.r2_key FROM capture_chunks c
JOIN scan_sessions s ON s.session_id = c.session_id
WHERE s.location_cell_id = %s AND c.format = 'mov'
ORDER BY c.r2_key
"""


def scrub_gate_enabled() -> bool:
    """On unless either the preference (PREFER_SCRUBBED_VIDEO=0) or the gate
    itself (REQUIRE_SCRUBBED_VIDEO=0) is explicitly disabled — a gate that
    holds jobs for renditions nobody will consume is pure starvation."""
    return (
        os.environ.get("PREFER_SCRUBBED_VIDEO", "1") != "0"
        and os.environ.get("REQUIRE_SCRUBBED_VIDEO", "1") != "0"
    )


def unscrubbed_video_chunks(conn, cell: str) -> list[str]:
    with conn.cursor() as cur:
        cur.execute(UNSCRUBBED_SQL, (cell,))
        keys = [row[0] for row in cur.fetchall()]
    return [k for k in keys if not r2_store.capture_video_is_terminal(k)]


def defer_job(conn, job: Job, reason: str) -> None:
    """Back off without burning an attempt: re-lease the row SCRUB_DEFER_MINUTES
    into the future (invisible to claim_job until then) and undo this claim's
    attempts increment, so a slow scrub pipeline can never starve a job into
    'failed'. fail_job is for errors; this is a wait state."""
    with conn.cursor() as cur:
        cur.execute(
            """UPDATE reconstruction_jobs
               SET status='leased',
                   leased_until=now() + make_interval(mins => %s),
                   attempts=greatest(attempts - 1, 0),
                   last_error=%s, updated_at=now()
               WHERE id=%s""",
            (SCRUB_DEFER_MINUTES, reason[:2000], job.id),
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
                   SET status='done', completed_at=now(), updated_at=now(),
                       splat_skip_reason=%s
                   WHERE location_cell_id=%s AND status IN ('queued', 'leased')""",
                (result.splat_skip_reason, cell),
            )


def _verified_decision(manifest_document) -> tuple[bool, list[str]]:
    if not isinstance(manifest_document, dict):
        return False, ["automated-quality-rejected"]
    decision = manifest_document.get("decision")
    if not isinstance(decision, dict) or decision.get("publish") is not True:
        reasons = decision.get("reasons") if isinstance(decision, dict) else None
        normalized = [reason for reason in (reasons or []) if isinstance(reason, str)]
        return False, normalized or ["automated-quality-rejected"]
    reasons = decision.get("reasons")
    if not isinstance(reasons, list) or any(not isinstance(reason, str) for reason in reasons):
        return False, ["automated-quality-rejected"]
    if reasons:
        return False, reasons
    return True, []


def record_verified_candidate(
    conn,
    staged,
    manifest,
    decision,
    *,
    source_session_ids: list[str],
    parent_asset_id: int | None = None,
) -> int:
    """Create one immutable candidate row after every R2 upload completed.

    ``staged`` is the receipt returned by fuse_cell's staging function. This
    function performs no object I/O, so the first database transaction opens
    only after the PLY, web asset, evidence, manifest and checksums exist.
    Identical retries return the original row; a changed retry is rejected.
    """
    if not isinstance(staged, StagedVerifiedCandidate):
        raise ValueError("verified candidate requires a complete upload receipt")
    manifest_document = manifest.to_dict()
    decision_document = decision.to_dict()
    if not isinstance(manifest_document, dict) or manifest_document.get("kind") != "verified":
        raise ValueError("verified candidate manifest is invalid")
    if manifest_document.get("decision") != decision_document:
        raise ValueError("verified candidate decision differs from its manifest")
    if getattr(staged, "asset_sha256", None) != getattr(manifest, "asset_sha256", None):
        raise ValueError("staged candidate checksum differs from its manifest")
    cell = getattr(staged, "cell_id", None)
    version = getattr(staged, "version", None)
    r2_key = getattr(staged, "r2_key", None)
    expected_prefix = f"reconstructed/{cell}/{version}/verified-candidate/"
    if (
        not isinstance(cell, str)
        or isinstance(version, bool)
        or not isinstance(version, int)
        or version < 1
        or not isinstance(r2_key, str)
        or not r2_key.startswith(expected_prefix)
    ):
        raise ValueError("staged verified candidate receipt is invalid")
    artifact_keys = staged.artifact_keys
    fixed_suffixes = {
        "verified.ply",
        "manifest.json",
        "frame-audit.json",
        "cleanup-report.json",
        "validation-metrics.json",
        "validation-contact-sheet.jpg",
        "checksums.json",
    }
    suffixes = {
        key.removeprefix(expected_prefix)
        for key in artifact_keys
        if isinstance(key, str) and key.startswith(expected_prefix)
    }
    web_suffixes = {
        suffix
        for suffix in suffixes
        if (suffix.startswith("verified.") and suffix != "verified.ply")
        or suffix == "verified-web.ply"
    }
    if (
        len(artifact_keys) != 8
        or len(set(artifact_keys)) != 8
        or not fixed_suffixes.issubset(suffixes)
        or len(web_suffixes) != 1
    ):
        raise ValueError("verified candidate requires a complete upload receipt")
    if manifest_document.get("cellIds") and cell not in manifest_document["cellIds"]:
        raise ValueError("verified candidate cell differs from its manifest")
    publish, reasons = _verified_decision(manifest_document)
    if publish != bool(decision_document.get("publish")):
        raise ValueError("verified candidate automated decision is invalid")
    status = "candidate" if publish else "rejected"
    manifest_json = json.dumps(manifest_document, sort_keys=True, separators=(",", ":"))
    sources_json = json.dumps(list(source_session_ids))
    rejection_json = json.dumps(reasons) if reasons else None

    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO reconstruction_assets
                     (location_cell_id, asset_type, format, r2_key, version,
                      source_scan_coverage_event_ids, asset_kind,
                      processing_status, review_status, parent_asset_id,
                      manifest, asset_sha256, rejected_at, rejection_reasons)
                   VALUES (%s, 'splat', 'ply', %s, %s, %s::jsonb, 'verified',
                           %s, 'pending', %s, %s::jsonb, %s,
                           CASE WHEN %s='rejected' THEN now() END, %s::jsonb)
                   ON CONFLICT (r2_key) DO NOTHING
                   RETURNING id""",
                (
                    cell,
                    r2_key,
                    version,
                    sources_json,
                    status,
                    parent_asset_id,
                    manifest_json,
                    staged.asset_sha256,
                    status,
                    rejection_json,
                ),
            )
            inserted = cur.fetchone()
            if inserted is not None:
                return inserted[0]
            cur.execute(
                """SELECT id, location_cell_id, version,
                          source_scan_coverage_event_ids, asset_kind, parent_asset_id,
                          manifest, asset_sha256
                   FROM reconstruction_assets WHERE r2_key=%s FOR UPDATE""",
                (r2_key,),
            )
            existing = cur.fetchone()
            expected = (
                cell,
                version,
                list(source_session_ids),
                "verified",
                parent_asset_id,
                manifest_document,
                staged.asset_sha256,
            )
            if existing is None or tuple(existing[1:]) != expected:
                raise ValueError("immutable candidate row differs from staged candidate")
            return existing[0]


def review_verified_candidate(
    conn,
    candidate_id: int,
    *,
    approved: bool,
    reviewed_by: str,
    rejection_reasons: list[str] | None = None,
) -> None:
    """Persist an attributed pilot decision without activating the row."""
    if not isinstance(reviewed_by, str) or not reviewed_by.strip():
        raise ValueError("verified review requires reviewed_by")
    reasons = [reason for reason in (rejection_reasons or []) if isinstance(reason, str)]
    if not approved and not reasons:
        reasons = ["human-review-rejected"]
    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute(
                """UPDATE reconstruction_assets
                   SET review_status=%s, reviewed_at=now(), reviewed_by=%s,
                       processing_status=CASE WHEN %s THEN processing_status ELSE 'rejected' END,
                       rejected_at=CASE WHEN %s THEN rejected_at ELSE now() END,
                       rejection_reasons=CASE WHEN %s THEN rejection_reasons ELSE %s::jsonb END
                   WHERE id=%s AND asset_kind='verified'
                     AND processing_status='candidate'""",
                (
                    "approved" if approved else "rejected",
                    reviewed_by.strip(),
                    approved,
                    approved,
                    approved,
                    json.dumps(reasons),
                    candidate_id,
                ),
            )
            if cur.rowcount != 1:
                raise ValueError("verified candidate is not reviewable")


def activate_verified_candidate(
    conn,
    candidate_id: int,
    *,
    source_session_ids: list[str] | None = None,
) -> bool:
    """Atomically swap an approved automated-pass candidate into service.

    Any exception rolls back both the old-row supersession and the new-row
    activation. Rejection and incomplete pilot attribution return False while
    leaving the current published verified row untouched.
    """
    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute(
                """SELECT location_cell_id, version, asset_kind,
                          processing_status, review_status, reviewed_at,
                          reviewed_by, manifest, activated_at
                   FROM reconstruction_assets WHERE id=%s FOR UPDATE""",
                (candidate_id,),
            )
            row = cur.fetchone()
            if row is None:
                raise ValueError("verified candidate does not exist")
            (
                cell,
                version,
                asset_kind,
                processing_status,
                review_status,
                reviewed_at,
                reviewed_by,
                manifest_document,
                activated_at,
            ) = row
            if asset_kind != "verified":
                raise ValueError("asset is not a verified candidate")
            if processing_status == "published" and activated_at is not None:
                return True
            automated_pass, automated_reasons = _verified_decision(manifest_document)
            if not automated_pass:
                if processing_status != "rejected":
                    cur.execute(
                        """UPDATE reconstruction_assets
                           SET processing_status='rejected', rejected_at=now(),
                               rejection_reasons=%s::jsonb
                           WHERE id=%s""",
                        (json.dumps(automated_reasons), candidate_id),
                    )
                return False
            if review_status == "rejected":
                if processing_status != "rejected":
                    cur.execute(
                        """UPDATE reconstruction_assets
                           SET processing_status='rejected', rejected_at=now(),
                               rejection_reasons='[\"human-review-rejected\"]'::jsonb
                           WHERE id=%s""",
                        (candidate_id,),
                    )
                return False
            if (
                processing_status != "candidate"
                or review_status != "approved"
                or reviewed_at is None
                or not isinstance(reviewed_by, str)
                or not reviewed_by.strip()
            ):
                return False

            # Lock the current active row before changing either side of the
            # swap. The partial unique index is the final concurrency guard.
            cur.execute(
                """SELECT id FROM reconstruction_assets
                   WHERE location_cell_id=%s AND asset_kind='verified'
                     AND processing_status='published'
                     AND activated_at IS NOT NULL AND id<>%s
                   FOR UPDATE""",
                (cell, candidate_id),
            )
            cur.fetchall()
            cur.execute(
                """UPDATE reconstruction_assets
                   SET processing_status='superseded'
                   WHERE location_cell_id=%s AND asset_kind='verified'
                     AND processing_status='published'
                     AND activated_at IS NOT NULL AND id<>%s""",
                (cell, candidate_id),
            )
            cur.execute(
                """UPDATE reconstruction_assets
                   SET processing_status='published', activated_at=now(),
                       rejected_at=NULL, rejection_reasons=NULL
                   WHERE id=%s AND processing_status='candidate'
                   RETURNING id""",
                (candidate_id,),
            )
            if cur.fetchone() is None:
                raise RuntimeError("verified candidate changed during activation")
            if source_session_ids:
                cur.execute(
                    """UPDATE scan_sessions
                       SET reconstruction_version=%s, reconstructed_at=now()
                       WHERE session_id=ANY(%s)""",
                    (version, source_session_ids),
                )
    return True


def activate_reviewed_candidates(conn) -> int:
    """Resume candidates whose attributed pilot approval arrived asynchronously."""
    with conn.cursor() as cur:
        cur.execute(
            """SELECT id, source_scan_coverage_event_ids
               FROM reconstruction_assets
               WHERE asset_kind='verified' AND processing_status='candidate'
                 AND review_status='approved' AND reviewed_at IS NOT NULL
                 AND reviewed_by IS NOT NULL AND btrim(reviewed_by)<>''
               ORDER BY id"""
        )
        rows = cur.fetchall()
    conn.commit()
    activated = 0
    for candidate_id, source_value in rows:
        if isinstance(source_value, list):
            source_ids = [value for value in source_value if isinstance(value, str)]
        elif isinstance(source_value, dict):
            source_ids = [
                value
                for value in source_value.get("sessions", [])
                if isinstance(value, str)
            ]
        else:
            source_ids = []
        if activate_verified_candidate(
            conn, candidate_id, source_session_ids=source_ids
        ):
            activated += 1
    return activated


def _verified_publication(result: FusionResult, cell: str):
    """Normalize producer-owned metrics/decision, failing closed when absent."""
    candidate = result.verified_candidate
    supplied_decision = result.publication_decision
    metrics = result.verified_metrics
    invalid_reason: str | None = None
    if supplied_decision is None:
        invalid_reason = "publication-decision-missing"
    elif not isinstance(supplied_decision, PublicationDecision):
        invalid_reason = "publication-decision-invalid"
    elif not isinstance(metrics, QualityMetrics) or any(
        value is None for value in metrics.to_dict().values()
    ):
        invalid_reason = "publication-decision-invalid"

    if invalid_reason is not None:
        decision = PublicationDecision(False, [invalid_reason])
        manifest = build_manifest(
            candidate,
            cell_ids=[cell],
            source_session_ids=result.accepted_session_ids,
            metrics=None,
            review_state={"status": "pending"},
            decision=decision,
        )
        return manifest, decision

    decision = supplied_decision
    manifest = result.verified_manifest
    if manifest is None:
        manifest = build_manifest(
            candidate,
            cell_ids=[cell],
            source_session_ids=result.accepted_session_ids,
            metrics=metrics,
            review_state={"status": "pending"},
            decision=decision,
        )
    try:
        document = manifest.to_dict()
        manifest_valid = (
            document.get("kind") == "verified"
            and document.get("decision") == decision.to_dict()
            and document.get("metrics") == metrics.to_dict()
        )
    except (AttributeError, TypeError, ValueError):
        manifest_valid = False
    if not manifest_valid:
        decision = PublicationDecision(False, ["publication-decision-invalid"])
        manifest = build_manifest(
            candidate,
            cell_ids=[cell],
            source_session_ids=result.accepted_session_ids,
            metrics=None,
            review_state={"status": "pending"},
            decision=decision,
        )
    return manifest, decision


def _finish_candidate_jobs(conn, cell: str, splat_skip_reason: str | None) -> None:
    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute(
                """UPDATE reconstruction_jobs
                   SET status='done', completed_at=now(), updated_at=now(),
                       splat_skip_reason=%s
                   WHERE location_cell_id=%s AND status IN ('queued', 'leased')""",
                (splat_skip_reason, cell),
            )


def _record_verified_completion(
    conn,
    result: FusionResult,
    staged: StagedVerifiedCandidate,
    manifest,
    decision: PublicationDecision,
) -> int:
    candidate_id = record_verified_candidate(
        conn,
        staged,
        manifest,
        decision,
        source_session_ids=result.accepted_session_ids,
    )
    _finish_candidate_jobs(conn, staged.cell_id, result.splat_skip_reason)
    return candidate_id


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
    if scrub_gate_enabled():
        missing = unscrubbed_video_chunks(conn, job.location_cell_id)
        if missing:
            raise ScrubPending(missing)
    # End the implicit transaction the gate's SELECT opened. psycopg is not
    # autocommit, so leaving it open parks the connection idle-in-transaction
    # for the entire fusion + gsplat training (30min+) — Neon's
    # idle_in_transaction_session_timeout then severs it and the completion
    # write (and the training's GPU time with it) is lost.
    conn.commit()

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
        gpu_job_id=job.id,
    )

    if result.verified_candidate is not None:
        if result.verified_version is None:
            raise RuntimeError("verified candidate has no immutable version")
        compressed = compress_splat_for_web(result.verified_candidate.balanced_bytes)
        if compressed is None:
            raise RuntimeError("verified candidate web asset could not be produced")
        web_asset, web_format = compressed
        manifest, decision = _verified_publication(result, job.location_cell_id)
        staged = fuse_cell_module.stage_verified_candidate_assets(
            job.location_cell_id,
            result.verified_version,
            result.verified_candidate,
            manifest,
            web_asset=web_asset,
            web_format=web_format,
            validation_metrics=result.validation_metrics,
            contact_sheet=result.validation_contact_sheet,
        )
        try:
            candidate_id = _record_verified_completion(
                conn, result, staged, manifest, decision
            )
        except psycopg.OperationalError:
            print(
                "db connection lost before candidate recording -- retrying on a fresh one",
                file=sys.stderr,
            )
            with psycopg.connect(os.environ["DATABASE_URL"]) as fresh:
                candidate_id = _record_verified_completion(
                    fresh, result, staged, manifest, decision
                )
        print(
            f"job {job.id} done: cell {job.location_cell_id} verified candidate "
            f"{candidate_id} v{staged.version}, status="
            f"{'pending-review' if decision.publish else 'rejected'}, "
            f"accepted={result.accepted_session_ids}, "
            f"rejected={result.rejected_session_ids}"
        )
        return

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
    try:
        complete_cell(conn, job.location_cell_id, result, assets)
    except psycopg.OperationalError:
        # The link can still die during a long training run even with no
        # transaction open. The GPU work is done and the assets are already
        # in R2 — retry the bookkeeping once on a fresh connection rather
        # than throwing the run away.
        print("db connection lost before completion -- retrying on a fresh one",
              file=sys.stderr)
        with psycopg.connect(os.environ["DATABASE_URL"]) as fresh:
            complete_cell(fresh, job.location_cell_id, result, assets)
    print(
        f"job {job.id} done: cell {job.location_cell_id} v{assets[0].version}, "
        f"{len(assets)} asset(s), accepted={result.accepted_session_ids}, "
        f"rejected={result.rejected_session_ids}, activated={result.activated}"
    )


def _safe_rollback(conn) -> None:
    try:
        conn.rollback()
    except psycopg.OperationalError:
        pass  # connection already gone; _record_outcome falls back to a fresh one


def _record_outcome(conn, fn, *args) -> None:
    """Run a small bookkeeping write (fail_job/defer_job), falling back to a
    fresh connection when this one died during the long fusion/training run —
    without this, a lost connection also loses the attempts accounting, so a
    crashing job never reaches MAX_ATTEMPTS and retries at every lease expiry
    forever."""
    try:
        fn(conn, *args)
    except psycopg.OperationalError:
        with psycopg.connect(os.environ["DATABASE_URL"]) as fresh:
            fn(fresh, *args)


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
    except ScrubPending as pending:
        _safe_rollback(conn)
        print(f"job {job.id} deferred {SCRUB_DEFER_MINUTES}min: {pending}")
        _record_outcome(conn, defer_job, job, str(pending))
    except GpuBusy as busy:
        _safe_rollback(conn)
        print(f"job {job.id} deferred {SCRUB_DEFER_MINUTES}min: {busy}")
        _record_outcome(conn, defer_job, job, str(busy))
    except Exception as err:
        _safe_rollback(conn)
        traceback.print_exc()
        _record_outcome(conn, fail_job, job, err)
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--once", action="store_true", help="process at most one job, then exit")
    group.add_argument("--loop", nargs="?", const=60, type=int, metavar="SECONDS",
                       help="poll forever, sleeping SECONDS (default 60) when idle")
    group.add_argument("--drain", action="store_true",
                       help="process jobs until the queue is empty, then exit (systemd timer mode)")
    group.add_argument(
        "--activate-reviewed",
        action="store_true",
        help="atomically activate every attributed approved verified candidate",
    )
    args = parser.parse_args()

    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        parser.error("DATABASE_URL is required")

    conn = psycopg.connect(dsn)
    try:
        if args.activate_reviewed:
            print(f"activated {activate_reviewed_candidates(conn)} reviewed candidate(s)")
            return
        if args.once:
            processed = run_once(conn)
            print("queue empty" if not processed else "done")
            return
        while True:
            try:
                busy = run_once(conn)
            except psycopg.OperationalError as err:
                # The shared connection died mid-cycle (Neon severs idle
                # links); the job's outcome was already recorded on a fresh
                # connection by run_once. Reconnect and keep draining instead
                # of taking the whole worker down.
                print(f"db connection lost ({err}); reconnecting", file=sys.stderr)
                try:
                    conn.close()
                except Exception:
                    pass
                conn = psycopg.connect(dsn)
                continue
            if not busy:
                if args.drain:
                    print("queue empty, draining done")
                    return
                time.sleep(args.loop)
    finally:
        try:
            conn.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()
