"""End-to-end check against the REAL seat and a LOCAL Postgres.

Serves a directory of photos over a loopback HTTP server, inserts one
`pending_review` job per case, runs the worker, prints each verdict, then
deletes everything it inserted. Refuses any DATABASE_URL that is not on
localhost, so it can never write to Neon.

Cases come from file names in PAIRS_DIR:
  <case>-before.jpg + <case>-after.jpg   a before/after pair
  <case>.jpg                             submitted as both before AND after

  python integration_check.py PAIRS_DIR [--expect case=status ...]

Env: DATABASE_URL (local), LOCAL_SEAT_URL (e.g. an ssh tunnel to the Spark's
seat: ssh -N -L 18001:127.0.0.1:8001 dgx-spark → http://127.0.0.1:18001/v1).
Exit 1 if any --expect does not hold.
"""
from __future__ import annotations

import argparse
import dataclasses
import functools
import http.server
import json
import os
import shutil
import sys
import tempfile
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

import psycopg

import review_worker as rw
from seat import SeatClient

# Only for a local test database that lacks the migration.
DDL = """
CREATE TABLE IF NOT EXISTS job_ai_reviews (
  job_id integer PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,
  status text NOT NULL, verdict jsonb NOT NULL, model text NOT NULL,
  input_hash text, created_at timestamptz DEFAULT now(), updated_at timestamptz)
"""


class _QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def cases(pairs_dir: Path) -> dict[str, tuple[str, str]]:
    out = {}
    for p in sorted(pairs_dir.glob("*.jpg")):
        stem = p.stem
        if stem.endswith("-before"):
            name = stem[: -len("-before")]
            if (pairs_dir / f"{name}-after.jpg").exists():
                out[name] = (p.name, f"{name}-after.jpg")
        elif not stem.endswith("-after"):
            out[stem] = (p.name, p.name)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pairs_dir", type=Path)
    ap.add_argument("--expect", action="append", default=[], metavar="CASE=STATUS")
    ap.add_argument("--port", type=int, default=18890)
    args = ap.parse_args()

    dsn = os.environ["DATABASE_URL"]
    host = urlparse(dsn).hostname
    if host not in ("127.0.0.1", "localhost", "::1"):
        raise SystemExit(f"refusing non-local DATABASE_URL host {host!r}")

    # Production photo URLs are /spots/{worker_id}/<file>; serve the same
    # shape for a synthetic worker so the real URL rule is exercised.
    worker_id = 999999
    served = Path(tempfile.mkdtemp(prefix="ai-review-itest-"))
    shutil.copytree(args.pairs_dir, served / "spots" / str(worker_id))
    handler = functools.partial(_QuietHandler, directory=str(served))
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    origin = f"http://127.0.0.1:{args.port}"
    base = f"{origin}/spots/{worker_id}/"

    cfg = dataclasses.replace(rw.Config.from_env(), loopback_test_origins=(origin,), max_jobs=100)
    seat = SeatClient(base_url=cfg.seat_url, timeout_s=cfg.timeout_s)

    def ev(f):
        return json.dumps([{"url": base + f, "capturedAt": "2026-10-18T00:00:00Z"}])

    job_ids: dict[str, int] = {}
    failures: list[str] = []
    with psycopg.connect(dsn) as conn:
        conn.execute(DDL)
        for name, (b, a) in cases(args.pairs_dir).items():
            job_ids[name] = conn.execute(
                "INSERT INTO jobs (block_id, worker_id, status, before_photos, after_photos)"
                " VALUES (-1, %s, 'pending_review', %s::jsonb, %s::jsonb) RETURNING id",
                (worker_id, ev(b), ev(a)),
            ).fetchone()[0]
        conn.commit()
        try:
            t0 = time.monotonic()
            code = rw.run(conn, seat, cfg)
            print(f"worker exit {code} in {time.monotonic() - t0:.1f}s\n")
            expect = dict(e.split("=", 1) for e in args.expect)
            model = ""
            for name, jid in job_ids.items():
                row = conn.execute(
                    "SELECT status, verdict, model FROM job_ai_reviews WHERE job_id=%s", (jid,)
                ).fetchone()
                status, verdict, model = row if row else ("<none>", {}, model)
                pair = (verdict.get("pairs") or [{}])[0]
                v = pair.get("verdict", {})
                print(f"{name:12s} {status:8s} before={v.get('items_before', {}).get('count')} "
                      f"after={v.get('items_after', {}).get('count')} "
                      f"same={v.get('same_location', {}).get('value')} cleaned={v.get('cleaned')} "
                      f"conf={v.get('confidence')} reasons={pair.get('flag_reasons')}")
                if v.get("concerns"):
                    print(f"{'':12s} concerns: {v['concerns']}")
                if verdict.get("error"):
                    print(f"{'':12s} error: {verdict['error']}")
                if name in expect and expect[name] != status:
                    failures.append(f"{name}: expected {expect[name]}, got {status}")
            print(f"\nmodel: {model}")
        finally:
            conn.rollback()
            conn.execute("DELETE FROM jobs WHERE id = ANY(%s)", (list(job_ids.values()),))
            conn.commit()
            httpd.shutdown()
            shutil.rmtree(served, ignore_errors=True)
    for f in failures:
        print("FAIL", f, file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
