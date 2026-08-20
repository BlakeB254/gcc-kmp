#!/usr/bin/env python3
"""Build a vidmap_runner bundle from a GCC iOS scan session in R2.

Downloads the session's capture chunks (.mov + .jsonl only — depth/.bin not
needed for the pose spike), extracts N evenly-spaced color frames via
chunk_io (single decode pass per chunk), and writes the runner's bundle
layout:

    <out>/frames/NNNN.jpg     evenly time-spaced across the whole session
    <out>/gps.jsonl           per-frame nearest GPS fix (name-matched)
    <out>/manifest.json       the raw EnvScanManifest (runner reads .path)

Frame wall-clock: cumulative video time across chunks, anchored on the
manifest's startedAtMs; GPS samples use the same clock via atMs - startedAtMs.

Usage: build_ios_bundle.py <manifest-r2-key> <out_dir> [--frames 80]
Env: R2_ACCOUNT_ID / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY / R2_BUCKET_NAME
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import boto3
import numpy as np

sys.path.insert(0, "/home/codex450/cdx/dev-projects/gcc/scripts/reconstruction")
import chunk_io  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest_key")
    ap.add_argument("out_dir")
    ap.add_argument("--frames", type=int, default=80)
    args = ap.parse_args()

    s3 = boto3.client(
        "s3",
        endpoint_url=f"https://{os.environ['R2_ACCOUNT_ID']}.r2.cloudflarestorage.com",
        aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"],
    )
    bucket = os.environ["R2_BUCKET_NAME"]

    manifest = json.loads(s3.get_object(Bucket=bucket, Key=args.manifest_key)["Body"].read())
    started_ms = manifest["startedAtMs"]
    path = manifest.get("path") or []
    if not path:
        raise SystemExit("manifest has no GPS path")

    chunks = sorted(
        {c.rsplit("/", 1)[-1].split(".")[0] for c in manifest.get("captureChunks") or []},
        key=lambda n: int(n.split("-")[1]),
    )
    if not chunks:
        raise SystemExit("manifest has no capture chunks")
    base_keys = {c.rsplit("/", 1)[-1]: c for c in manifest["captureChunks"]}

    out = Path(args.out_dir)
    (out / "frames").mkdir(parents=True, exist_ok=True)

    # Pass 1: download sidecars, learn per-chunk durations to lay out the global time grid.
    sidecars = {}
    durations = {}
    for ch in chunks:
        key = base_keys[f"{ch}.jsonl"]
        raw = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
        header, frames = chunk_io.parse_sidecar(raw)
        if not frames:
            print(f"  {ch}: no frames, skipped")
            continue
        sidecars[ch] = (header, frames)
        durations[ch] = frames[-1].t_video
        print(f"  {ch}: {len(frames)} sidecar frames, {frames[-1].t_video:.1f}s")

    offsets, acc = {}, 0.0
    for ch in chunks:
        if ch in sidecars:
            offsets[ch] = acc
            acc += durations[ch] + 1.0 / 30  # + one frame interval between chunks
    total_s = acc
    print(f"session video span: {total_s:.1f}s across {len(sidecars)} chunks")

    # Pass 2: per chunk, extract its share of the global frame budget in one decode pass.
    per_chunk_targets = {}
    for ch, (_, frames) in sidecars.items():
        lo, hi = offsets[ch], offsets[ch] + durations[ch]
        n = max(2, round(args.frames * (hi - lo) / total_s))
        per_chunk_targets[ch] = np.linspace(0, durations[ch], n).tolist()

    written = []  # (name, wall_ms)
    idx = 0
    for ch, targets in per_chunk_targets.items():
        video = s3.get_object(Bucket=bucket, Key=base_keys[f"{ch}.mov"])["Body"].read()
        imgs = chunk_io.extract_video_frames_at(video, targets)
        from PIL import Image
        for t, img in zip(targets, imgs):
            if img is None:
                continue
            name = f"{idx:04d}.jpg"
            Image.fromarray(img).save(out / "frames" / name, quality=92)
            written.append((name, started_ms + int((offsets[ch] + t) * 1000)))
            idx += 1
        print(f"  {ch}: extracted {sum(1 for i in imgs if i is not None)}/{len(targets)} frames")

    # gps.jsonl — nearest path sample per frame (same clock).
    gps_t = np.array([p["atMs"] for p in path])
    with open(out / "gps.jsonl", "w") as f:
        for name, wall_ms in written:
            p = path[int(np.argmin(np.abs(gps_t - wall_ms)))]
            f.write(json.dumps({
                "lat": p["lat"], "lon": p["lng"], "alt_m": 0.0,
                "t": wall_ms, "name": name,
                "accuracy_m": p.get("accuracyMeters"),
            }) + "\n")

    json.dump(manifest, open(out / "manifest.json", "w"))
    print(f"wrote {len(written)} frames + gps.jsonl + manifest.json -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
