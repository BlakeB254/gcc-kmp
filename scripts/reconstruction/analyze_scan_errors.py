"""Scan-error analytics over the reconstruction backend's own data (Blake
2026-07-29: "send all the error logs of the different scans to the same
backend we use for recreating the world model … examine the bugs, run
analysis, and allow those error codes to influence future scans").

Every scan session already ships its diagnostics to this bucket — the
manifest's `errorLog` (all ERROR lines, build ≥175) / `diagnosticsLog`
(mixed tail, older builds). This script sweeps them, clusters errors by
signature (tag + normalized message prefix), and prints an analysis report:

  - top error signatures with counts, affected session ids, app builds,
    and first/last seen timestamps
  - per-build error rates (sessions with ≥1 error / total sessions)
  - a PROPOSED config/scan-tuning.json when known mitigations apply
    (mapping table below) — REVIEW IT, then upload with:
      aws s3 cp scan-tuning.proposed.json s3://<bucket>/config/scan-tuning.json \\
        --endpoint-url $R2_ENDPOINT
    The app fetches /api/scan-config at scan open and applies it.

Run (DGX, reconstruction venv):
  cd scripts/reconstruction && .venv/bin/python analyze_scan_errors.py [--days 7]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
from collections import defaultdict
from datetime import datetime, timedelta, timezone

import r2_store

# Error-signature → tuning mitigation. Deliberately tiny and conservative:
# only knobs the app treats as SAFE. Grows as analysis identifies stable
# error→cause links.
MITIGATIONS = {
    "env-ar/ground candidate": {"notes": "ground-candidate pipeline errors — consider sensor cap review"},
    "spot-submit/photo upload failed": {"notes": "photo upload failures — check R2 credentials/presign TTL"},
    "env-capture/": {"notes": "capture writer errors — check disk headroom + thermal history"},
}

SIGNATURE_PREFIX_CHARS = 72


def signature_of(line: str) -> str:
    """'12:03:04 ERR [tag] message…' → 'tag/normalized-message-prefix'."""
    match = re.search(r"\[(?P<tag>[^\]]+)\]\s*(?P<msg>.*)", line)
    tag = match.group("tag") if match else "untagged"
    msg = (match.group("msg") if match else line).strip()
    msg = re.sub(r"\d+", "N", msg)           # numbers → N (ids, counts, ms)
    msg = re.sub(r"env-N+[a-z0-9-]*", "SID", msg)  # session ids
    return f"{tag}/{msg[:SIGNATURE_PREFIX_CHARS]}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--days", type=int, default=7)
    args = parser.parse_args()
    cutoff = datetime.now(timezone.utc) - timedelta(days=args.days)

    clusters: dict[str, dict] = defaultdict(lambda: {
        "count": 0, "sessions": set(), "builds": set(),
        "firstSeen": None, "lastSeen": None, "example": None,
    })
    sessions_total = 0
    sessions_with_errors = 0
    builds_sessions: dict[str, int] = defaultdict(int)
    builds_error_sessions: dict[str, int] = defaultdict(int)

    client = r2_store.client()
    paginator = client.get_paginator("list_objects_v2")
    cells = set()
    for page in paginator.paginate(Bucket=r2_store.bucket_name(), Prefix="raw/", Delimiter="/"):
        for prefix in page.get("CommonPrefixes", []):
            cells.add(prefix["Prefix"].split("/")[1])

    for cell in sorted(cells):
        try:
            manifests = r2_store.list_manifests_for_cell(cell)
        except Exception:
            continue
        for m in manifests:
            manifest = m.manifest
            completed = manifest.get("completedAtMs")
            if completed and datetime.fromtimestamp(completed / 1000, tz=timezone.utc) < cutoff:
                continue
            sid = manifest.get("sessionId", "?")
            build = str(manifest.get("osVersion", "")) and str(manifest.get("deviceModel", ""))
            build = f"{manifest.get('deviceModel', '?')}"  # model as cohort; CFBundleVersion not in manifest yet
            sessions_total += 1
            builds_sessions[build] += 1
            # Build ≥175 ships errorLog (complete); older builds: filter the
            # mixed diagnosticsLog tail for ERR lines.
            error_lines = manifest.get("errorLog") or [
                line for line in (manifest.get("diagnosticsLog") or []) if " ERR " in f" {line} "
            ]
            if not error_lines:
                continue
            sessions_with_errors += 1
            builds_error_sessions[build] += 1
            for line in error_lines:
                sig = signature_of(line)
                cluster = clusters[sig]
                cluster["count"] += 1
                cluster["sessions"].add(sid)
                cluster["builds"].add(build)
                cluster["example"] = cluster["example"] or line
                stamp = completed or 0
                cluster["firstSeen"] = min(cluster["firstSeen"] or stamp, stamp)
                cluster["lastSeen"] = max(cluster["lastSeen"] or 0, stamp)

    report = {
        "windowDays": args.days,
        "sessionsAnalyzed": sessions_total,
        "sessionsWithErrors": sessions_with_errors,
        "errorRateByCohort": {
            cohort: round(builds_error_sessions[cohort] / count, 3)
            for cohort, count in sorted(builds_sessions.items()) if count
        },
        "topSignatures": [
            {
                "signature": sig,
                "count": data["count"],
                "sessions": len(data["sessions"]),
                "example": data["example"],
                "builds": sorted(data["builds"]),
            }
            for sig, data in sorted(clusters.items(), key=lambda kv: -kv[1]["count"])[:25]
        ],
    }
    print(json.dumps(report, indent=2))

    # Tuning proposal: attach mitigation notes for known signatures; a human
    # reviews and uploads — the loop is intentionally NOT closed automatically.
    notes = [
        mitigation["notes"]
        for prefix, mitigation in MITIGATIONS.items()
        for sig in clusters
        if sig.startswith(prefix)
    ]
    if notes:
        proposal = {
            "revision": int(datetime.now(timezone.utc).timestamp()),
            "sweepEnabled": True,
            "drawnMarkEnabled": True,
            "sensorMaxFootprintM": 1.4,
            "sensorMaxHeightM": 0.9,
            "notes": "; ".join(sorted(set(notes))),
        }
        out = pathlib.Path("scan-tuning.proposed.json")
        out.write_text(json.dumps(proposal, indent=2))
        print(f"\nproposal written: {out} — review, then upload to config/scan-tuning.json")


if __name__ == "__main__":
    main()
