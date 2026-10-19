"""Server-side AI review of litter-cleanup evidence.

Payouts used to rest on what the phone reported. This worker has the
resident vision model (via the local seat — see seat.py) look at every job
waiting for review: each before/after evidence pair on the job, plus the
before/after photos of the hotspots on the route scan that completed it. The
verdict lands in `job_ai_reviews` (one row per job) for the admin review
screen; it never approves, denies or pays anything itself.

A job is (re)reviewed when it is `pending_review` and either has no review
row, or its evidence changed since the last review (input_hash differs — the
hash covers the photo URLs, the prompt version, the model and the threshold),
or its last review was an 'error' older than AI_REVIEW_ERROR_RETRY_MINUTES.

Concurrency: each job is reviewed inside its own transaction holding
pg_try_advisory_xact_lock(AI_REVIEW_LOCK_CLASS, job_id). A second worker skips
jobs another one is on; nothing locks the `jobs` row, so the app and admins
are never blocked by a slow model call. Transaction-scoped, so it is safe
behind Neon's transaction pooler.

Env:
  DATABASE_URL                    required
  LOCAL_SEAT_URL                  default http://127.0.0.1:8001/v1 (Spark only)
  AI_REVIEW_MAX_JOBS              default 20   jobs per run
  AI_REVIEW_MAX_PAIRS             default 4    photo pairs per job (one request each)
  AI_REVIEW_MAX_EDGE              default 1024 px, longest image edge sent
  AI_REVIEW_TIMEOUT_S             default 180  per model request
  AI_REVIEW_MIN_CONFIDENCE        default 0.6  below this a pair is flagged
  AI_REVIEW_ERROR_RETRY_MINUTES   default 360
  AI_REVIEW_BLOB_HOST             our Vercel Blob public host, exact
                                  (<store>.public.blob.vercel-storage.com);
                                  else derived from BLOB_READ_WRITE_TOKEN the
                                  way the site's ownBlobHost() does. Required:
                                  without it the worker refuses to run.

Photo URL rule (the SSRF guard — hotspot URLs are client-supplied): https,
host == our blob host exactly, path /spots/{job.worker_id}/<one file name>,
no query, fragment, port or userinfo. Anything else is never fetched; it
becomes a job concern, which flags the job.

Usage:
  python review_worker.py              # review up to AI_REVIEW_MAX_JOBS jobs
  python review_worker.py --dry-run    # list what would be reviewed, no model, no writes
  python review_worker.py --job 123    # one job (still must be pending_review)

Exit codes: 0 done (including "nothing to do"); 1 some jobs recorded 'error';
2 the seat was unavailable, run stopped, nothing recorded for the jobs left;
3 misconfigured (no blob host).

Advisory only: the single write is the job_ai_reviews upsert. No code path
approves, denies, completes or pays a job, whatever the verdict. Free text in
the verdict (reasons, concerns) is model output about untrusted images; the
admin app must render it as plain text.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import re
import sys
from dataclasses import dataclass
from urllib.parse import urlparse

import psycopg
import requests
from PIL import Image, ImageOps

from seat import DEFAULT_SEAT_URL, SEAT_MODEL, SeatClient, SeatRequestError, SeatUnavailable
from verdict import (
    PROMPT_VERSION,
    SYSTEM_PROMPT,
    USER_PROMPT,
    PairResult,
    VerdictError,
    aggregate,
    parse_verdict,
)

# 'gc' — namespaces the advisory locks so they cannot collide with other users.
AI_REVIEW_LOCK_CLASS = 0x6763
MAX_IMAGE_BYTES = 20 * 1024 * 1024
CANDIDATE_SCAN_LIMIT = 500

CANDIDATES_SQL = """
SELECT j.id, r.input_hash, r.status,
       (r.status = 'error' AND coalesce(r.updated_at, r.created_at)
          < now() - make_interval(mins => %(retry)s)) AS error_retry_due
FROM jobs j
LEFT JOIN job_ai_reviews r ON r.job_id = j.id
WHERE j.status = 'pending_review' {extra}
ORDER BY j.updated_at, j.id
LIMIT %(limit)s
"""

JOB_SQL = """
SELECT id, status, before_photos, after_photos, completion_scan_id,
       block_id, worker_id, claimed_at
FROM jobs WHERE id = %s
"""

# The scan that proved the work; for jobs submitted before completion_scan_id
# existed, the worker's latest scan of the block since the claim (never an
# older scan — without a claim time there is no fallback).
SCAN_FALLBACK_SQL = """
SELECT id FROM route_scans
WHERE block_id = %s AND worker_id = %s AND created_at >= %s
ORDER BY created_at DESC LIMIT 1
"""

HOTSPOTS_SQL = """
SELECT id, before_photo_url, after_photo_url FROM scan_hotspots
WHERE scan_id = %s ORDER BY id
"""

UPSERT_SQL = """
INSERT INTO job_ai_reviews (job_id, status, verdict, model, input_hash, created_at, updated_at)
VALUES (%s, %s, %s::jsonb, %s, %s, now(), now())
ON CONFLICT (job_id) DO UPDATE SET
  status = EXCLUDED.status, verdict = EXCLUDED.verdict, model = EXCLUDED.model,
  input_hash = EXCLUDED.input_hash, updated_at = now()
"""


@dataclass(frozen=True)
class Config:
    seat_url: str = DEFAULT_SEAT_URL
    max_jobs: int = 20
    max_pairs: int = 4
    max_edge: int = 1024
    timeout_s: float = 180.0
    min_confidence: float = 0.6
    error_retry_minutes: int = 360
    blob_host: str | None = None
    # Tests only, never read from the environment: exact loopback origins
    # ("http://127.0.0.1:18890") that stand in for the blob host. The
    # /spots/{worker}/<file> path rule still applies.
    loopback_test_origins: tuple[str, ...] = ()

    @classmethod
    def from_env(cls, env=os.environ) -> "Config":
        blob_host = (env.get("AI_REVIEW_BLOB_HOST") or "").strip().lower() or own_blob_host(
            env.get("BLOB_READ_WRITE_TOKEN")
        )
        return cls(
            seat_url=env.get("LOCAL_SEAT_URL", DEFAULT_SEAT_URL).rstrip("/"),
            max_jobs=int(env.get("AI_REVIEW_MAX_JOBS", 20)),
            max_pairs=int(env.get("AI_REVIEW_MAX_PAIRS", 4)),
            max_edge=int(env.get("AI_REVIEW_MAX_EDGE", 1024)),
            timeout_s=float(env.get("AI_REVIEW_TIMEOUT_S", 180)),
            min_confidence=float(env.get("AI_REVIEW_MIN_CONFIDENCE", 0.6)),
            error_retry_minutes=int(env.get("AI_REVIEW_ERROR_RETRY_MINUTES", 360)),
            blob_host=blob_host,
        )


VERCEL_BLOB_PUBLIC_SUFFIX = ".public.blob.vercel-storage.com"


def own_blob_host(token: str | None) -> str | None:
    """Mirror of site/src/lib/job-lifecycle.ts ownBlobHost():
    vercel_blob_rw_<storeId>_<secret> → <storeid>.public.blob.vercel-storage.com."""
    m = re.match(r"^vercel_blob_rw_([A-Za-z0-9]+)_", token or "")
    return f"{m.group(1).lower()}{VERCEL_BLOB_PUBLIC_SUFFIX}" if m else None


class EvidenceError(RuntimeError):
    """This job's evidence cannot be reviewed (bad URL, fetch failed,
    not an image). Recorded on the job as status 'error'."""


# --------------------------------------------------------------------------
# Evidence: which photo pairs a job has


@dataclass(frozen=True)
class Pair:
    label: str
    before_url: str
    after_url: str


def evidence_urls(raw, media_urls: dict[int, str]) -> list[str]:
    """jobs.before_photos/after_photos → URLs. Same shapes as
    site/src/lib/job-evidence.ts: {url, capturedAt} objects, bare URL strings,
    legacy integer media ids."""
    if not isinstance(raw, list):
        return []
    out = []
    for item in raw:
        if isinstance(item, str) and item:
            out.append(item)
        elif isinstance(item, int) and not isinstance(item, bool):
            if item in media_urls:
                out.append(media_urls[item])
        elif isinstance(item, dict) and isinstance(item.get("url"), str) and item["url"]:
            out.append(item["url"])
    return out


def legacy_media_ids(*columns) -> list[int]:
    return sorted({
        i for col in columns if isinstance(col, list)
        for i in col if isinstance(i, int) and not isinstance(i, bool)
    })


def build_pairs(
    before: list[str],
    after: list[str],
    hotspots: list[tuple[int, str | None, str | None]],
    max_pairs: int,
    *,
    worker_id: int | None,
    cfg: "Config",
) -> tuple[list[Pair], list[str]]:
    """(pairs to review, job-level concerns). PATCH /api/jobs/:id/evidence
    appends one before and one after per call, so position i pairs up.

    Every concern flags the job (verdict.aggregate): a pair the model did not
    see — rejected URL, missing after photo, count mismatch, truncation — can
    never let a job pass."""
    concerns: list[str] = []
    evidence = [
        Pair(f"evidence #{i + 1}", b, a) for i, (b, a) in enumerate(zip(before, after))
    ]
    if len(before) != len(after):
        concerns.append(
            f"evidence photo counts differ: {len(before)} before, {len(after)} after"
        )
    hotspot_pairs: list[Pair] = []
    missing_after = 0
    for hid, b, a in hotspots:
        if b and a:
            hotspot_pairs.append(Pair(f"hotspot {hid}", b, a))
        else:
            missing_after += 1
    if missing_after:
        concerns.append(f"{missing_after} hotspot(s) have no after photo")

    def _accept(pairs: list[Pair]) -> list[Pair]:
        kept = []
        for p in pairs:
            bad = [u for u in (p.before_url, p.after_url) if not url_allowed(u, cfg, worker_id)]
            if bad:
                concerns.append(
                    f"{p.label}: photo URL is not an upload by this job's worker; not reviewed"
                )
            else:
                kept.append(p)
        return kept

    evidence, hotspot_pairs = _accept(evidence), _accept(hotspot_pairs)
    total = len(evidence) + len(hotspot_pairs)
    if total > max_pairs:
        # Reserve slots for both sources so neither can be crowded out:
        # half (rounded up) for the worker's evidence, the rest for hotspots,
        # unused slots go to whichever side has more.
        n_ev = min(len(evidence), (max_pairs + 1) // 2)
        n_hs = min(len(hotspot_pairs), max_pairs - n_ev)
        n_ev = min(len(evidence), max_pairs - n_hs)
        concerns.append(
            f"only {max_pairs} of {total} photo pairs were reviewed "
            f"({n_ev}/{len(evidence)} evidence, {n_hs}/{len(hotspot_pairs)} hotspot)"
        )
        evidence, hotspot_pairs = evidence[:n_ev], hotspot_pairs[:n_hs]
    return evidence + hotspot_pairs, concerns


def input_hash(pairs: list[Pair], concerns: list[str], model: str, min_confidence: float) -> str:
    doc = {
        "prompt_version": PROMPT_VERSION,
        "model": model,
        "min_confidence": min_confidence,
        "pairs": [[p.label, p.before_url, p.after_url] for p in pairs],
        "concerns": concerns,
    }
    return hashlib.sha256(json.dumps(doc, sort_keys=True).encode()).hexdigest()


# --------------------------------------------------------------------------
# Images


_FILE_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$")


def url_allowed(url: str, cfg: Config, worker_id: int | None) -> bool:
    """https://<our blob host>/spots/<worker_id>/<file> and nothing else —
    the site's isOwnEvidenceUrl rule, but exact-host only and one path
    segment, no query/fragment (every production upload has that shape)."""
    if worker_id is None or isinstance(worker_id, bool):
        return False
    try:
        parsed = urlparse(url)
        port = parsed.port
    except ValueError:
        return False
    if parsed.username or parsed.password or parsed.query or parsed.fragment or "?" in url or "#" in url:
        return False
    host = (parsed.hostname or "").lower()
    origin = f"{parsed.scheme}://{host}:{port}" if port else f"{parsed.scheme}://{host}"
    test_origin = host in ("127.0.0.1", "localhost") and origin in cfg.loopback_test_origins
    if not test_origin:
        if not cfg.blob_host or parsed.scheme != "https" or port:
            return False
        if host != cfg.blob_host:
            return False
    parts = parsed.path.split("/")
    return (
        len(parts) == 4 and parts[0] == "" and parts[1] == "spots"
        and parts[2] == str(int(worker_id)) and bool(_FILE_NAME_RE.match(parts[3]))
        and ".." not in parts[3]
    )


def fetch_image(url: str, cfg: Config, worker_id: int | None, get=requests.get) -> bytes:
    if not url_allowed(url, cfg, worker_id):  # build_pairs already filtered; belt and braces
        raise EvidenceError(f"photo URL not allowed: {url[:200]}")
    try:
        resp = get(url, timeout=30, stream=True, allow_redirects=False)
    except requests.RequestException as exc:
        raise EvidenceError(f"fetch failed for {url[:200]}: {exc}") from exc
    if resp.status_code != 200:
        raise EvidenceError(f"fetch {url[:200]} -> HTTP {resp.status_code}")
    data = b""
    for chunk in resp.iter_content(1 << 16):
        data += chunk
        if len(data) > MAX_IMAGE_BYTES:
            raise EvidenceError(f"photo larger than {MAX_IMAGE_BYTES} bytes: {url[:200]}")
    return data


@dataclass(frozen=True)
class PreparedImage:
    data_url: str
    # sha256 of the decoded, downscaled pixels: equal means the same photo
    # was submitted twice (whatever its URL or file name).
    pixel_sha: str


def prepare_image(data: bytes, max_edge: int) -> PreparedImage:
    try:
        img = Image.open(io.BytesIO(data))
        img = ImageOps.exif_transpose(img).convert("RGB")
    except Exception as exc:  # noqa: BLE001 — PIL raises many types
        raise EvidenceError(f"not a readable image: {exc}") from exc
    img.thumbnail((max_edge, max_edge), Image.Resampling.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=85)
    return PreparedImage(
        data_url="data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode(),
        pixel_sha=hashlib.sha256(img.tobytes()).hexdigest(),
    )


def identical_reason(before: PreparedImage, after: PreparedImage) -> str | None:
    """Deterministic backstop for the model: an exact re-submission is never
    a cleanup. Near-duplicates are left to the model — a real before/after of
    a small piece of litter is itself a near-duplicate."""
    if before.pixel_sha == after.pixel_sha:
        return "before and after photos are the identical image"
    return None


# --------------------------------------------------------------------------
# Review


def review_pair(seat: SeatClient, pair: Pair, before: PreparedImage, after: PreparedImage):
    """Ask the model; on an invalid reply ask once more, then give up with
    VerdictError (the job is recorded as 'error')."""
    prompt = USER_PROMPT.format(label=pair.label)
    reply = seat.chat(SYSTEM_PROMPT, prompt, [before.data_url, after.data_url])
    try:
        return parse_verdict(reply)
    except VerdictError as first:
        print(f"  {pair.label}: invalid verdict ({first}); retrying once")
        retry = (
            f"{prompt}\nYour previous reply was rejected ({first}). Reply with only "
            "the JSON object, exactly the keys and types specified."
        )
        reply2 = seat.chat(SYSTEM_PROMPT, retry, [before.data_url, after.data_url])
        try:
            return parse_verdict(reply2)
        except VerdictError as second:
            raise VerdictError(f"{second}; reply: {reply2[:300]!r}") from second


def load_job_evidence(conn, job_id: int, cfg: Config):
    """(job row dict, pairs, concerns) or None when the job is gone or no
    longer pending_review."""
    row = conn.execute(JOB_SQL, (job_id,)).fetchone()
    if row is None or row[1] != "pending_review":
        return None
    jid, _status, before_raw, after_raw, scan_id, block_id, worker_id, claimed_at = row
    media_urls: dict[int, str] = {}
    ids = legacy_media_ids(before_raw, after_raw)
    if ids:
        media_urls = {
            i: u for i, u in conn.execute(
                "SELECT id, url FROM media WHERE id = ANY(%s) AND url IS NOT NULL", (ids,)
            ).fetchall()
        }
    if scan_id is None and None not in (block_id, worker_id, claimed_at):
        found = conn.execute(SCAN_FALLBACK_SQL, (block_id, worker_id, claimed_at)).fetchone()
        scan_id = found[0] if found else None
    hotspots = conn.execute(HOTSPOTS_SQL, (scan_id,)).fetchall() if scan_id else []
    pairs, concerns = build_pairs(
        evidence_urls(before_raw, media_urls),
        evidence_urls(after_raw, media_urls),
        hotspots,
        cfg.max_pairs,
        worker_id=worker_id,
        cfg=cfg,
    )
    return {"id": jid, "scan_id": scan_id, "worker_id": worker_id}, pairs, concerns


def review_job(conn, job_id: int, seat: SeatClient, cfg: Config, model: str, get=requests.get):
    """Review one job in its own transaction. Returns 'skipped', 'unchanged',
    or the recorded status. Raises SeatUnavailable (nothing recorded)."""
    with conn.transaction():
        locked = conn.execute(
            "SELECT pg_try_advisory_xact_lock(%s, %s)", (AI_REVIEW_LOCK_CLASS, job_id)
        ).fetchone()[0]
        if not locked:
            return "skipped"
        loaded = load_job_evidence(conn, job_id, cfg)
        if loaded is None:
            return "skipped"
        job, pairs, concerns = loaded
        digest = input_hash(pairs, concerns, model, cfg.min_confidence)
        prev = conn.execute(
            "SELECT input_hash, status FROM job_ai_reviews WHERE job_id = %s", (job_id,)
        ).fetchone()
        # A current, non-error review: nothing to do. (Due errors were
        # selected by due_jobs; re-review them.)
        if prev and prev[0] == digest and prev[1] != "error":
            return "unchanged"

        try:
            results = []
            for pair in pairs:
                raw_before = fetch_image(pair.before_url, cfg, job["worker_id"], get)
                raw_after = fetch_image(pair.after_url, cfg, job["worker_id"], get)
                before = prepare_image(raw_before, cfg.max_edge)
                after = prepare_image(raw_after, cfg.max_edge)
                same = identical_reason(before, after)
                verdict = review_pair(seat, pair, before, after)
                results.append(PairResult(
                    pair.label, pair.before_url, pair.after_url, verdict,
                    extra_reasons=(same,) if same else (),
                    before_sha256=hashlib.sha256(raw_before).hexdigest(),
                    after_sha256=hashlib.sha256(raw_after).hexdigest(),
                ))
            status, verdict_json = aggregate(
                results, min_confidence=cfg.min_confidence, job_concerns=concerns
            )
            verdict_json["scan_id"] = job["scan_id"]
        except (EvidenceError, VerdictError, SeatRequestError) as exc:
            status = "error"
            verdict_json = {
                "prompt_version": PROMPT_VERSION,
                "error": f"{type(exc).__name__}: {exc}"[:2000],
                "concerns": concerns,
                "scan_id": job["scan_id"],
            }
        conn.execute(UPSERT_SQL, (job_id, status, json.dumps(verdict_json), model, digest))
        return status


def due_jobs(conn, cfg: Config, only_job: int | None = None) -> list[int]:
    extra = "AND j.id = %(job)s" if only_job is not None else ""
    rows = conn.execute(
        CANDIDATES_SQL.format(extra=extra),
        {"retry": cfg.error_retry_minutes, "limit": CANDIDATE_SCAN_LIMIT, "job": only_job},
    ).fetchall()
    # The evidence hash needs the photos' URLs, so a job with a current
    # 'ok'/'flagged' row is re-checked in review_job (cheap: no model call
    # unless it changed). Never-reviewed jobs go first, then due errors.
    fresh = [r[0] for r in rows if r[1] is None and r[2] is None]
    retry = [r[0] for r in rows if r[2] == "error" and r[3]]
    recheck = [r[0] for r in rows if r[2] is not None and r[2] != "error"]
    return fresh + retry + recheck


def seat_model_name(seat: SeatClient) -> str:
    """'local' plus the engine the seat reports, for provenance."""
    try:
        resp = seat.get(f"{seat.base_url}/models", timeout=10)
        for m in resp.json().get("data", []):
            if m.get("id") == SEAT_MODEL and m.get("root"):
                return f"{SEAT_MODEL} ({m['root']})"
    except Exception:  # noqa: BLE001 — provenance only
        pass
    return SEAT_MODEL


def run(conn, seat: SeatClient, cfg: Config, *, only_job: int | None = None,
        dry_run: bool = False, get=requests.get) -> int:
    if not cfg.blob_host and not cfg.loopback_test_origins:
        # Fail closed: without the exact host no photo URL can be trusted.
        print("AI_REVIEW_BLOB_HOST (or BLOB_READ_WRITE_TOKEN) is required", file=sys.stderr)
        return 3
    candidates = due_jobs(conn, cfg, only_job)
    conn.commit()
    if dry_run:
        for jid in candidates:
            print(f"candidate job {jid}")
        print(f"{len(candidates)} candidate job(s)")
        return 0
    if not candidates:
        print("no jobs awaiting AI review")
        return 0
    if not seat.healthy():
        print(f"seat {seat.base_url} not healthy; nothing reviewed", file=sys.stderr)
        return 2
    model = seat_model_name(seat)

    counts: dict[str, int] = {}
    reviewed = 0
    for jid in candidates:
        if reviewed >= cfg.max_jobs:
            break
        try:
            outcome = review_job(conn, jid, seat, cfg, model, get)
        except SeatUnavailable as exc:
            print(f"job {jid}: seat unavailable after retries ({exc}); stopping run", file=sys.stderr)
            print(f"summary: {counts}")
            return 2
        counts[outcome] = counts.get(outcome, 0) + 1
        if outcome in ("ok", "flagged", "error"):
            reviewed += 1
            print(f"job {jid}: {outcome}")
    print(f"summary: {counts}")
    return 1 if counts.get("error") else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--job", type=int)
    args = ap.parse_args()
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        ap.error("DATABASE_URL is required")
    cfg = Config.from_env()
    seat = SeatClient(base_url=cfg.seat_url, timeout_s=cfg.timeout_s)
    with psycopg.connect(dsn) as conn:
        return run(conn, seat, cfg, only_job=args.job, dry_run=args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())
