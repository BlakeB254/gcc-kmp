"""Chunk keys for a cell's sessions, read from the `capture_chunks` index.

WHY THIS EXISTS — two sources of truth that disagree:

  * The session manifest is written by the phone when the session stops, but
    chunk uploads keep landing for tens of seconds afterwards. Its
    `captureChunks` therefore routinely lists a SUBSET of what actually
    reached R2 — and sometimes a manifest is written mid-session and never
    re-uploaded, listing almost nothing.
  * `capture_chunks` is populated from an R2 LISTING, so it sees every
    artifact regardless of when it landed.

Everything downstream of fusion has historically been manifest-driven, which
made the phone's write ordering silently decide how much of a walk got
reconstructed. Observed 2026-07-28 on session env-...bm4m21: the manifest
listed 1 of 8 artifacts (a lone pose sidecar), so fusion reported "0 with
usable depth-backed frames" and threw away a complete 6-minute walk whose
depth, poses and video had all been sitting in R2 for hours.

The scrub gate already resolves chunks this way (see recon_worker's
UNSCRUBBED_SQL) — including its join-on-session_id detail, which matters
because GPS drift between manifest write and chunk upload can put a session's
chunks under a NEIGHBORING cell prefix. This module gives fusion the same
ground truth.

Reconciliation is a UNION, never a replace: if the indexer is lagging behind
R2, a key the manifest already carried must not be dropped. And every lookup
is fail-soft — an unreachable index degrades to today's manifest-only
behaviour rather than breaking fusion.
"""
from __future__ import annotations

import os

import psycopg

# Join through scan_sessions on session_id rather than filtering
# capture_chunks.location_cell_id: when GPS drifts between the manifest write
# and the chunk upload, a session's chunks land under an adjacent cell prefix.
# Fusion consumes every manifest under the cell's raw/ prefix, so the sessions
# are the unit of ownership — not the chunk keys' prefixes.
CHUNK_KEYS_SQL = """
SELECT c.session_id, c.r2_key
FROM capture_chunks c
JOIN scan_sessions s ON s.session_id = c.session_id
WHERE s.location_cell_id = %s
ORDER BY c.session_id, c.r2_key
"""

# Capture artifacts only. The index also holds photos, manifests and event
# logs for the same session; those are not chunks and must never reach
# fuse_cell._group_chunks_by_index.
_CAPTURE_SUFFIXES = (".bin", ".confidence", ".jsonl", ".mov")


def _is_capture_artifact(key: str) -> bool:
    # `events.jsonl.gz` ends in .gz so it is already excluded; a bare
    # `.jsonl` sidecar is what we want.
    return key.endswith(_CAPTURE_SUFFIXES)


def chunk_keys_by_session(conn, cell_id: str) -> dict[str, list[str]]:
    """{session_id: [capture chunk r2 keys]} for every session indexed under
    this cell, ordered so callers get a stable list."""
    with conn.cursor() as cur:
        cur.execute(CHUNK_KEYS_SQL, (cell_id,))
        rows = cur.fetchall()
    out: dict[str, list[str]] = {}
    for session_id, r2_key in rows:
        if _is_capture_artifact(r2_key):
            out.setdefault(session_id, []).append(r2_key)
    return out


def chunk_keys_for_cell(cell_id: str, dsn: str | None = None) -> dict[str, list[str]]:
    """chunk_keys_by_session against a short-lived connection of its own.

    Deliberately NOT the caller's connection: recon_worker holds one open
    across a ~45-minute gsplat train, and an extra statement on it would
    re-open the implicit transaction that PR #212 was written to close.
    Fail-soft — any connection or query problem degrades to manifest-only.
    """
    dsn = dsn or os.environ.get("DATABASE_URL")
    if not dsn:
        return {}
    try:
        with psycopg.connect(dsn, connect_timeout=10) as conn:
            return chunk_keys_by_session(conn, cell_id)
    except Exception as err:  # noqa: BLE001 - never let the index break fusion
        print(f"chunk index unavailable, falling back to manifests: {err}")
        return {}


def reconcile_manifest_chunks(manifests: list[dict], keys_by_session: dict[str, list[str]]) -> int:
    """Union each manifest's captureChunks with what the index actually saw.

    Mutates the manifest dicts in place, which is what makes this a
    single-point fix: fuse_cell hands the same dicts to gsplat_runner and
    splat_train, so the trainers see the recovered chunks too.

    Returns the number of keys added, for logging.
    """
    added = 0
    for manifest in manifests:
        session_id = manifest.get("sessionId")
        if not session_id:
            continue
        indexed = keys_by_session.get(session_id)
        if not indexed:
            continue
        existing = list(manifest.get("captureChunks") or [])
        seen = set(existing)
        new = [k for k in indexed if k not in seen]
        if not new:
            continue
        manifest["captureChunks"] = sorted(existing + new)
        added += len(new)
    return added
