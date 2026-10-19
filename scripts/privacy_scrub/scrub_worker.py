"""Privacy scrub worker — PRD docs/prd/scan-session-replay-devops-prd.md
(branch scan-replay-plan) decision #2, DGX ingest side. For each raw video
capture chunk named by the scan index, produce a bystander/vehicle-blurred
rendition at r2_store.scrubbed_key_for(raw_key) and a per-session
scrub-report.json. Idempotent on rendition existence.

Env: R2_ACCOUNT_ID/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY/R2_BUCKET_NAME
     + DATABASE_URL (scan-index lookups) — same set as recon_worker.py;
     SCRUB_ABORT_AVAIL_GB / SCRUB_WEIGHTS_GB / SCRUB_DECODE_GB … — memory
     budget, see memgate.py (SCRUB_MEMGATE=0 disables it on a dedicated host);
     SCRUB_DETECTOR yolo|rfdetr (default yolo — README "Detector switch");
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
from botocore.exceptions import ClientError

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "reconstruction"))
import r2_store  # noqa: E402

import memgate  # noqa: E402

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


DETECTORS = {
    # Current live behaviour; Ultralytics is AGPL — internal only, retired
    # once the switch flips.
    "yolo": "detector_yolo",
    # Apache-2.0 / MIT. Becomes the default only after the rotated-face
    # sweep passes (README "Detector switch").
    "rfdetr": "detector",
}


def detector_kind(env=os.environ) -> str:
    kind = env.get("SCRUB_DETECTOR", "yolo")
    if kind not in DETECTORS:
        raise SystemExit(f"SCRUB_DETECTOR must be one of {sorted(DETECTORS)}, got {kind!r}")
    return kind


def load_detector(env=os.environ):
    """SCRUB_DETECTOR=yolo|rfdetr (default yolo). Same interface either way:
    detect_frames, qc_counts, describe. Imports only the chosen backend."""
    module = __import__(DETECTORS[detector_kind(env)])
    return module.PrivacyDetector()


def _unload(det) -> None:
    """Drop the detector and hand its GPU memory back before deferring."""
    import gc  # noqa: PLC0415

    del det
    gc.collect()
    torch = sys.modules.get("torch")
    if torch is not None and torch.cuda.is_available():
        torch.cuda.empty_cache()


def _sample_frames(video_bytes: bytes, n: int = 8) -> list[np.ndarray]:
    """n frames spread over the clip, decoding only around each sample: seek
    to the keyframe before each target and decode forward to it. Never holds
    more than one decoded frame besides the n kept (the old version decoded
    and kept the whole ~1800-frame chunk)."""
    frames: list[np.ndarray] = []
    with av.open(io.BytesIO(video_bytes)) as c:
        st = c.streams.video[0]
        if not st.duration or not st.time_base:
            # No index to seek by: stride-decode, keeping only n frames.
            total = st.frames or 0
            step = max(1, total // n) if total else 1
            for i, f in enumerate(c.decode(st)):
                if i % step == 0:
                    frames.append(f.to_ndarray(format="bgr24"))
                    if len(frames) >= n:
                        break
            return frames
        start = st.start_time or 0
        for i in range(n):
            target = start + int(st.duration * (i + 0.5) / n)
            c.seek(target, stream=st, backward=True, any_frame=False)
            for f in c.decode(st):
                if f.pts is None or f.pts >= target:
                    frames.append(f.to_ndarray(format="bgr24"))
                    break
    return frames


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

    kind = detector_kind()
    budget = memgate.budget(kind)
    if budget.enabled:
        avail = memgate.mem_available_gb()
        if avail < budget.start_gb:
            # Shared GB10 near its floor: defer the whole sweep WITHOUT
            # loading weights (the next timer tick retries). Exit 0 —
            # waiting for memory is not a failure.
            print(f"deferred: low memory ({avail:.1f} GB available < {budget.start_gb:.1f} GB "
                  f"budget for {kind}: abort floor + weights + decode)")
            return 0

    # Deferred: importing torch costs seconds; --dry-run stays instant.
    from scrub_video import scrub_video_bytes  # noqa: PLC0415

    det = load_detector()
    if budget.enabled:
        avail = memgate.mem_available_gb()
        if avail < budget.loaded_gb:
            # The weights cost more than estimated (or something else grew):
            # give the memory back before decoding anything.
            _unload(det)
            print(f"deferred: low memory after loading weights ({avail:.1f} GB available < "
                  f"{budget.loaded_gb:.1f} GB; raise SCRUB_WEIGHTS_GB?)")
            return 0
    abort_gb = budget.abort_gb
    detect = memgate.guarded(det.detect_frames, abort_gb)
    qc_counts = memgate.guarded(det.qc_counts, abort_gb)

    def between_batches():
        memgate.check(abort_gb)
    low_memory_abort: str | None = None
    reports: dict[str, dict] = {}

    def _report_for(session_id: str, cell: str) -> dict:
        return reports.setdefault(session_id, {
            "schema": 1,
            "sessionId": session_id,
            "locationCellId": cell,
            "scrubbedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "detector": {
                **det.describe(),
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
    pending: list[str] = []
    for raw_key, session_id, cell in todo:
        print(f"[{session_id}] {raw_key}")
        try:
            video = r2_store.download_bytes(raw_key)
            result = scrub_video_bytes(
                video, detect,
                dilation_radius=args.dilation_radius, pad_frac=args.pad_frac,
                check=between_batches,
            )
            memgate.check(abort_gb)  # before decoding the QC samples
            qc = qc_counts(_sample_frames(result.video_bytes))
        except memgate.LowMemory as exc:
            # Stop our own inference before earlyoom picks a victim. This
            # chunk is neither uploaded nor marked; the next sweep redoes it.
            low_memory_abort = f"{raw_key}: {exc}"
            print(f"  !! aborted: low memory ({exc}); stopping the sweep")
            break
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
        except ClientError as exc:
            # NoSuchKey = the chunk row is indexed (the manifest names it)
            # but the phone hasn't uploaded the blob yet — the capture-chunk
            # policy holds video for Wi-Fi/charging/opt-in, so this is a
            # NORMAL waiting state, not a failure (2026-09-01 field walk:
            # the sweep red-flagged every fresh session for exactly this).
            # Skip quietly; the next sweep retries once the upload lands.
            if exc.response.get("Error", {}).get("Code") in ("NoSuchKey", "404"):
                print("  .. pending upload (blob not in R2 yet), will retry next sweep")
                pending.append(raw_key)
                continue
            print(f"  !! FAILED ({type(exc).__name__}), skipping: {exc}")
            errored.append(raw_key)
            continue
        except Exception as exc:  # noqa: BLE001 — transient/unknown, keep sweeping
            print(f"  !! FAILED ({type(exc).__name__}), skipping: {exc}")
            errored.append(raw_key)
            continue

        scrub_key = r2_store.scrubbed_key_for(raw_key)
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

    if pending:
        print(f"\n{len(pending)} chunk(s) pending upload (normal — device holds video for Wi-Fi/charging):")
        for k in pending:
            print(f"  {k}")
    if unreadable:
        print(f"\n{len(unreadable)} unreadable chunk(s) skipped (permanent):")
        for k in unreadable:
            print(f"  {k}")
    if low_memory_abort:
        # Not a failure: the chunk stays unscrubbed and the next tick retries.
        print(f"\ndeferred: low memory, sweep stopped at {low_memory_abort}")
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
