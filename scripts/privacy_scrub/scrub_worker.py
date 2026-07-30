"""Privacy scrub worker — PRD docs/prd/scan-session-replay-devops-prd.md
(branch scan-replay-plan) decision #2, DGX ingest side. For each raw video
capture chunk named by the scan index, produce a bystander/vehicle-blurred
rendition at r2_store.scrubbed_key_for(raw_key) and a per-session
scrub-report.json. Idempotent on rendition existence.

Env: R2_ACCOUNT_ID/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY/R2_BUCKET_NAME
     + DATABASE_URL (scan-index lookups) — same set as recon_worker.py;
     source backend/deploy/.env.

Usage:
  python scrub_worker.py --session env-…-0cgku8 [--session …] [--force] [--dry-run]
  python scrub_worker.py --cell 464650:-721828
  python scrub_worker.py --sweep --limit 10
"""
from __future__ import annotations

import argparse
import datetime as dt
import io
import json
import os
import sys
from pathlib import Path

import av
import numpy as np
import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "reconstruction"))
import r2_store  # noqa: E402

MOV_CHUNKS_SQL = """
SELECT r2_key, session_id, location_cell_id FROM capture_chunks
WHERE format = 'mov' {where}
ORDER BY session_id, r2_key
"""


def _index_chunks(where: str, params: tuple) -> list[tuple[str, str, str]]:
    dsn = os.environ.get("DATABASE_URL")
    if not dsn:
        raise SystemExit("DATABASE_URL required to query the scan index")
    with psycopg.connect(dsn) as conn:
        return conn.execute(MOV_CHUNKS_SQL.format(where=where), params).fetchall()


def _sample_frames(video_bytes: bytes, n: int = 8) -> list[np.ndarray]:
    frames: list[np.ndarray] = []
    with av.open(io.BytesIO(video_bytes)) as c:
        for f in c.decode(video=0):
            frames.append(f.to_ndarray(format="bgr24"))
    if not frames:
        return []
    step = max(1, len(frames) // n)
    return frames[::step][:n]


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--session", action="append", default=[])
    ap.add_argument("--cell")
    ap.add_argument("--sweep", action="store_true")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--dilation-radius", type=int, default=6)
    ap.add_argument("--pad-frac", type=float, default=0.08)
    args = ap.parse_args()

    if args.session:
        rows = _index_chunks("AND session_id = ANY(%s)", (args.session,))
    elif args.cell:
        rows = _index_chunks("AND location_cell_id = %s", (args.cell,))
    elif args.sweep:
        rows = _index_chunks("", ())
    else:
        ap.error("one of --session/--cell/--sweep required")

    todo: list[tuple[str, str, str]] = []
    for raw_key, session_id, cell in rows:
        if not args.force and r2_store.capture_video_is_terminal(raw_key):
            continue
        todo.append((raw_key, session_id, cell))
        if args.limit and len(todo) >= args.limit:
            break
    print(f"{len(todo)} chunk(s) to scrub ({len(rows)} indexed)")
    if args.dry_run:
        for raw_key, *_ in todo:
            print("would scrub:", raw_key, "->", r2_store.scrubbed_key_for(raw_key))
        return
    if not todo:
        return

    # Deferred: importing torch/ultralytics costs seconds; --dry-run stays instant.
    from detector import PrivacyDetector  # noqa: PLC0415
    from scrub_video import scrub_video_bytes  # noqa: PLC0415

    det = PrivacyDetector()
    reports: dict[str, dict] = {}

    def _report_for(session_id: str, cell: str) -> dict:
        return reports.setdefault(session_id, {
            "schema": 1,
            "sessionId": session_id,
            "locationCellId": cell,
            "scrubbedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "detector": {
                "coco": "yolo11m.pt", "face": "face_yolov8m.pt",
                "conf": det.conf, "imgsz": det.imgsz,
                "dilationRadius": args.dilation_radius, "padFrac": args.pad_frac,
            },
            "chunks": [],
            "unreadableChunks": [],
        })

    # One unreadable chunk must not abort the sweep. Chunks are processed in
    # (session_id, r2_key) order, so an un-finalized .mov uploaded by the app
    # (ftyp+mdat but no moov atom — recording interrupted before
    # AVAssetWriter.finishWriting) used to raise here and starve EVERY later
    # session's chunks forever: recon jobs then defer on the scrub gate on a
    # 10-minute loop with no way out. Isolate per chunk instead.
    unreadable: list[str] = []
    errored: list[str] = []
    for raw_key, session_id, cell in todo:
        print(f"[{session_id}] {raw_key}")
        try:
            video = r2_store.download_bytes(raw_key)
            result = scrub_video_bytes(
                video, det.detect_frames,
                dilation_radius=args.dilation_radius, pad_frac=args.pad_frac,
            )
        except av.error.InvalidDataError as exc:
            # Permanent: the object in R2 is not a decodable video. Record it
            # beside the would-be scrubbed rendition so later sweeps and the
            # reconstruction gate can distinguish "permanently unreadable"
            # from "still waiting to be scrubbed".
            print(f"  !! UNREADABLE video, skipping: {exc}")
            unreadable.append(raw_key)
            _report_for(session_id, cell)["unreadableChunks"].append({
                "rawKey": raw_key, "error": str(exc),
            })
            marker = {
                "schema": 1,
                "rawKey": raw_key,
                "sessionId": session_id,
                "locationCellId": cell,
                "markedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                "reason": "invalid-video",
                "errorType": type(exc).__name__,
            }
            r2_store.upload_bytes(
                r2_store.unreadable_marker_key_for(raw_key),
                json.dumps(marker, indent=2).encode(),
                "application/json",
            )
            continue
        except Exception as exc:  # noqa: BLE001 — transient/unknown, keep sweeping
            print(f"  !! FAILED ({type(exc).__name__}), skipping: {exc}")
            errored.append(raw_key)
            continue

        scrub_key = r2_store.scrubbed_key_for(raw_key)
        qc = det.qc_counts(_sample_frames(result.video_bytes))
        r2_store.upload_bytes(scrub_key, result.video_bytes, "video/quicktime")
        print(
            f"  {result.frame_count} frames, {result.frames_with_detections} with detections "
            f"{result.detections_by_label}; QC residual {qc}; "
            f"{len(result.video_bytes) / 1e6:.1f}MB -> {scrub_key}"
        )
        _report_for(session_id, cell)["chunks"].append({
            "rawKey": raw_key,
            "scrubbedKey": scrub_key,
            "frames": result.frame_count,
            "framesWithDetections": result.frames_with_detections,
            "detectionsByLabel": result.detections_by_label,
            "qcResidualDetections": qc,
            "bytes": len(result.video_bytes),
        })

    for session_id, rep in reports.items():
        # A session whose every chunk was unreadable still gets a report, so
        # the failure is recorded rather than silently absent.
        anchor = (rep["chunks"] or rep["unreadableChunks"])[0]
        anchor_key = anchor.get("scrubbedKey") or r2_store.scrubbed_key_for(anchor["rawKey"])
        report_key = anchor_key.rsplit("/", 1)[0] + "/scrub-report.json"
        r2_store.upload_bytes(report_key, json.dumps(rep, indent=2).encode(), "application/json")
        print(f"[{session_id}] report -> {report_key}")

    if unreadable:
        print(f"\n{len(unreadable)} unreadable chunk(s) skipped (permanent):")
        for k in unreadable:
            print(f"  {k}")
    if errored:
        # Transient/unknown failures are worth surfacing as a unit failure so
        # they don't rot silently; unreadable ones are permanent and expected.
        print(f"\n{len(errored)} chunk(s) failed with unexpected errors:")
        for k in errored:
            print(f"  {k}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
