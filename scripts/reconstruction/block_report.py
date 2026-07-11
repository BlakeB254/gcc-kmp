"""Block cleaning report generator (docs/superpowers/specs/
2026-07-11-block-cleaning-report-design.md §3).

Given a BEFORE (baseline) and AFTER (clean) scan session, extracts a still
frame for every detected issue from the before lap's video, finds a
matched-angle frame in the after lap, and publishes a report (JSON + JPEGs)
to R2 under reports/{cellId}/{before}__{after}/.

Domain note (Blake, 2026-07-11): trash/litter data — including these
reports — is GCC's; the cleaned-world splats/meshes the same scans produce
belong to the CDX World Mirror twin layer.

Usage:
  export R2_* ...
  python block_report.py --before <sessionId> --after <sessionId> [--dry-run DIR]
"""
from __future__ import annotations

import argparse
import io
import json
import math
import time
from dataclasses import dataclass

import numpy as np
from PIL import Image

import chunk_io
import r2_store
from fuse_cell import _group_chunks_by_index

MAX_MATCH_DISTANCE_M = 12.0
MAX_HEADING_DELTA_DEG = 60.0
METERS_PER_DEG_LAT = 111_000.0
METERS_PER_DEG_LNG = 82_300.0
JPEG_QUALITY = 88


def _distance_m(lat_a, lng_a, lat_b, lng_b) -> float:
    return math.hypot((lat_a - lat_b) * METERS_PER_DEG_LAT, (lng_a - lng_b) * METERS_PER_DEG_LNG)


def _heading_delta(a: float | None, b: float | None) -> float | None:
    if a is None or b is None:
        return None
    d = abs(a - b) % 360.0
    return min(d, 360.0 - d)


@dataclass
class SessionMedia:
    """One session's manifest + lazily-shared chunk media, with the
    wall-clock -> sidecar-clock bridge."""
    manifest: dict
    chunks: dict[int, dict[str, str]]
    _sidecars: dict[int, list] | None = None
    _videos: dict[int, bytes] | None = None

    @classmethod
    def load(cls, manifest: dict) -> "SessionMedia":
        chunks = _group_chunks_by_index(manifest.get("captureChunks") or [])
        return cls(manifest=manifest, chunks={i: k for i, k in chunks.items() if "mov" in k and "jsonl" in k})

    def _ensure_loaded(self) -> None:
        if self._sidecars is not None:
            return
        self._sidecars, self._videos = {}, {}
        for idx, kinds in sorted(self.chunks.items()):
            _header, frames = chunk_io.parse_sidecar(r2_store.download_bytes(kinds["jsonl"]))
            if frames:
                self._sidecars[idx] = frames
                self._videos[idx] = r2_store.download_bytes(kinds["mov"])

    def sidecar_epoch(self) -> tuple[float, float] | None:
        """(first sidecar t, its wall-clock epoch ms) — the clock bridge.
        The recorder starts with the session, so the first sidecar frame is
        anchored to the manifest's startedAtMs."""
        self._ensure_loaded()
        if not self._sidecars:
            return None
        first_idx = min(self._sidecars)
        return self._sidecars[first_idx][0].t, float(self.manifest.get("startedAtMs") or 0)

    def frame_at_epoch_ms(self, epoch_ms: float) -> np.ndarray | None:
        """Extract the video frame nearest a wall-clock time, across chunks."""
        bridge = self.sidecar_epoch()
        if bridge is None or not epoch_ms:
            return None
        t0, epoch0 = bridge
        target_t = t0 + (epoch_ms - epoch0) / 1000.0
        best = None  # (gap, chunk_idx)
        for idx, frames in self._sidecars.items():
            times = np.array([f.t for f in frames])
            gap = float(np.min(np.abs(times - target_t)))
            if best is None or gap < best[0]:
                best = (gap, idx)
        if best is None or best[0] > 5.0:  # >5s from any captured frame: no honest still
            return None
        # target_t is boot-clock; the chunk's video pts start at 0 at its
        # own first sidecar frame -- rebase before matching.
        chunk_t0 = self._sidecars[best[1]][0].t
        frames = chunk_io.extract_video_frames_at(self._videos[best[1]], [target_t - chunk_t0])
        return frames[0] if frames else None


def _path_samples(manifest: dict) -> list[dict]:
    return [p for p in (manifest.get("path") or []) if p.get("atMs")]


def _matched_after_epoch(item: dict, before_heading: float | None, after_manifest: dict) -> float | None:
    """The after-lap wall-clock moment whose camera position (and heading,
    when known) best matches the before item's spot. None if the after lap
    never came within MAX_MATCH_DISTANCE_M — reported honestly as missing."""
    best: tuple[float, float] | None = None  # (distance, atMs)
    for sample in _path_samples(after_manifest):
        dist = _distance_m(item["lat"], item["lng"], sample["lat"], sample["lng"])
        if dist > MAX_MATCH_DISTANCE_M:
            continue
        delta = _heading_delta(before_heading, sample.get("headingDegrees"))
        if delta is not None and delta > MAX_HEADING_DELTA_DEG:
            continue
        if best is None or dist < best[0]:
            best = (dist, float(sample["atMs"]))
    return best[1] if best else None


def _item_epoch_ms(item: dict, manifest: dict) -> float | None:
    """The wall-clock moment the camera was at the item. Prefers the item's
    own observedAtMs; falls back to the GPS path sample nearest the item's
    position (the app doesn't populate observedAtMs yet — mobile follow-up,
    spec §4)."""
    at_ms = item.get("observedAtMs")
    if at_ms:
        return float(at_ms)
    best: tuple[float, float] | None = None
    for sample in _path_samples(manifest):
        dist = _distance_m(item["lat"], item["lng"], sample["lat"], sample["lng"])
        if best is None or dist < best[0]:
            best = (dist, float(sample["atMs"]))
    return best[1] if best and best[0] <= MAX_MATCH_DISTANCE_M else None


def _before_heading(item: dict, before_manifest: dict) -> float | None:
    at_ms = _item_epoch_ms(item, before_manifest)
    samples = _path_samples(before_manifest)
    if not samples or not at_ms:
        return None
    nearest = min(samples, key=lambda s: abs(float(s["atMs"]) - float(at_ms)))
    return nearest.get("headingDegrees")


def _polyline(manifest: dict) -> list[list[float]]:
    return [[s["lng"], s["lat"]] for s in _path_samples(manifest)]


def _walked_meters(manifest: dict) -> float:
    samples = _path_samples(manifest)
    return round(sum(
        _distance_m(a["lat"], a["lng"], b["lat"], b["lng"])
        for a, b in zip(samples, samples[1:])
    ), 1)


def generate_report(before_manifest: dict, after_manifest: dict, sink) -> dict:
    """sink(relative_name, bytes, content_type) persists artifacts; returns
    the report dict (also persisted as report.json)."""
    before = SessionMedia.load(before_manifest)
    after = SessionMedia.load(after_manifest)

    items_out = []
    total_grams = 0
    for i, item in enumerate(before_manifest.get("observedItems") or []):
        if (item.get("domain") or "litter") != "litter":
            continue  # trees/fences are landmarks, not trash — GCC reports litter
        entry = {
            "itemType": item.get("itemType"),
            "severity": item.get("severity"),
            "estimatedWeightGrams": item.get("estimatedWeightGrams"),
            "lat": item.get("lat"), "lng": item.get("lng"),
            "observedAtMs": item.get("observedAtMs"),
            "beforeImage": None, "afterImage": None, "afterMatched": False,
        }
        total_grams += int(item.get("estimatedWeightGrams") or 0)

        frame = before.frame_at_epoch_ms(_item_epoch_ms(item, before_manifest) or 0)
        if frame is not None:
            name = f"item-{i:03d}-before.jpg"
            entry["beforeImage"] = sink(name, _jpeg(frame), "image/jpeg")

        after_epoch = _matched_after_epoch(item, _before_heading(item, before_manifest), after_manifest)
        if after_epoch is not None:
            frame = after.frame_at_epoch_ms(after_epoch)
            if frame is not None:
                name = f"item-{i:03d}-after.jpg"
                entry["afterImage"] = sink(name, _jpeg(frame), "image/jpeg")
                entry["afterMatched"] = True
        items_out.append(entry)

    report = {
        "schema": 1,
        "generatedAtMs": int(time.time() * 1000),
        "before": _lap_summary(before_manifest),
        "after": _lap_summary(after_manifest),
        "items": items_out,
        "totals": {
            "litterItems": len(items_out),
            "estimatedGrams": total_grams,
            "beforeImages": sum(1 for x in items_out if x["beforeImage"]),
            "afterMatchedImages": sum(1 for x in items_out if x["afterImage"]),
        },
        "route": {
            "beforePolyline": _polyline(before_manifest),
            "afterPolyline": _polyline(after_manifest),
            "beforeWalkedMeters": _walked_meters(before_manifest),
            "afterWalkedMeters": _walked_meters(after_manifest),
        },
    }
    sink("report.json", json.dumps(report, indent=2).encode(), "application/json")
    return report


def _lap_summary(manifest: dict) -> dict:
    return {
        "sessionId": manifest.get("sessionId"),
        "lapKind": manifest.get("lapKind"),
        "startedAtMs": manifest.get("startedAtMs"),
        "completedAtMs": manifest.get("completedAtMs"),
        "scannedCells": len(manifest.get("scannedCells") or []),
        "routeId": manifest.get("routeId"),
    }


def _jpeg(frame: np.ndarray) -> bytes:
    buf = io.BytesIO()
    Image.fromarray(frame).save(buf, format="JPEG", quality=JPEG_QUALITY)
    return buf.getvalue()


def _find_manifest(session_id: str, cell_hint: str | None) -> dict:
    """Locate a session's manifest in R2 by cell prefix (from the hint or by
    scanning the cells the caller supplies)."""
    if cell_hint:
        for m in r2_store.list_manifests_for_cell(cell_hint):
            if m.manifest.get("sessionId") == session_id:
                return m.manifest
    raise SystemExit(f"manifest for {session_id} not found under cell {cell_hint!r} — pass --cell")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True, help="baseline-lap sessionId")
    parser.add_argument("--after", required=True, help="clean-lap sessionId")
    parser.add_argument("--cell", required=True, help="locationCellId both sessions live under")
    parser.add_argument("--dry-run", metavar="DIR", help="write artifacts to a local dir instead of R2")
    args = parser.parse_args()

    before_manifest = _find_manifest(args.before, args.cell)
    after_manifest = _find_manifest(args.after, args.cell)

    prefix = f"reports/{args.cell}/{args.before}__{args.after}/"
    if args.dry_run:
        import pathlib
        outdir = pathlib.Path(args.dry_run); outdir.mkdir(parents=True, exist_ok=True)
        def sink(name, data, _ct):
            (outdir / name).write_bytes(data); return name
    else:
        def sink(name, data, content_type):
            key = prefix + name
            r2_store.upload_bytes(key, data, content_type=content_type)
            return key

    report = generate_report(before_manifest, after_manifest, sink)
    print(json.dumps(report["totals"], indent=2))
    print(f"report at: {args.dry_run or prefix}")


if __name__ == "__main__":
    main()
