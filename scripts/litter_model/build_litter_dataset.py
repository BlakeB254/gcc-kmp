"""Builds the GCC litter-classification dataset from field scans — every
human-confirmed observation becomes a labeled training image (Blake,
2026-07-11: "a model we train and continue to train on classifying and
annotating litter").

For each scan session with capture chunks, every litter-domain observed
item's video frame is extracted (same clock-bridge machinery the block
reports use) and saved as dataset/<itemType>/<session>-<idx>.jpg.
Incremental: sessions already ingested are recorded in .ingested and
skipped, so the dataset grows with every new scan — the continual-training
loop's data side.

Run:  cd scripts/reconstruction && .venv/bin/python ../litter_model/build_litter_dataset.py
"""
from __future__ import annotations

import io
import json
import os
import pathlib
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "reconstruction"))

from PIL import Image  # noqa: E402

import r2_store  # noqa: E402
from block_report import SessionMedia, _item_epoch_ms  # noqa: E402

DATASET = pathlib.Path(os.path.expanduser("~/models/gcc/litter-dataset"))
INGESTED = DATASET / ".ingested"


def _slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", (s or "other").lower()).strip("-") or "other"


def main() -> None:
    DATASET.mkdir(parents=True, exist_ok=True)
    done = set(INGESTED.read_text().split()) if INGESTED.exists() else set()

    # every cell with manifests (walk raw/ via the same listing the fusion uses)
    cells = set()
    c = r2_store.client()
    paginator = c.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=r2_store.bucket_name(), Prefix="raw/", Delimiter="/"):
        for pre in page.get("CommonPrefixes", []):
            cells.add(pre["Prefix"].split("/")[1])

    new_images = 0
    for cell in sorted(cells):
        try:
            manifests = r2_store.list_manifests_for_cell(cell)
        except Exception:
            continue
        for m in manifests:
            sid = m.manifest.get("sessionId", "")
            if not sid or sid in done:
                continue
            items = [i for i in (m.manifest.get("observedItems") or [])
                     if (i.get("domain") or "litter") == "litter"]
            if not items or not (m.manifest.get("captureChunks") or []):
                done.add(sid)
                continue
            media = SessionMedia.load(m.manifest)
            for idx, item in enumerate(items):
                epoch = _item_epoch_ms(item, m.manifest)
                if not epoch:
                    continue
                frame = media.frame_at_epoch_ms(epoch)
                if frame is None:
                    continue
                label_dir = DATASET / _slug(item.get("itemType"))
                label_dir.mkdir(exist_ok=True)
                Image.fromarray(frame).save(label_dir / f"{sid}-{idx}.jpg", quality=90)
                new_images += 1
            done.add(sid)

    INGESTED.write_text("\n".join(sorted(done)))
    counts = {d.name: len(list(d.glob("*.jpg"))) for d in DATASET.iterdir() if d.is_dir()}
    print(json.dumps({"newImages": new_images, "datasetCounts": counts}))


if __name__ == "__main__":
    main()
