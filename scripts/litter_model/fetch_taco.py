"""Seeds the litter-crop dataset from TACO (Trash Annotations in Context,
http://tacodataset.org — 1500 photos, 4784 litter boxes, CC BY 4.0) so the
Stage-2 crop classifier trains BEFORE the field flywheel has accumulated
much data. Field crops from build_litter_dataset.py land in the same class
dirs and dominate over time — TACO is the cold-start, not the destination.

For every TACO annotation: a context-padded crop of the litter box is saved
under the mapped GCC canonical class dir. For every image, up to
--negatives-per-image random crops that DON'T intersect any litter box are
saved under background/ — the model's negative class (the app treats a
confident "background" verdict as backdrop-soft, GroundExclusionRules).

Run (DGX, same venv as train_litter_classifier.py):
  .venv/bin/python fetch_taco.py [--limit 200] [--negatives-per-image 2]

Incremental: images already fetched are recorded in .taco-ingested and
skipped. Downloads come from the TACO annotation URLs (Flickr originals) —
flaky links are skipped with a count, rerun to retry.
"""
from __future__ import annotations

import argparse
import io
import json
import pathlib
import random
import urllib.request

from PIL import Image

DATASET = pathlib.Path("~/models/gcc/litter-dataset").expanduser()
INGESTED = DATASET / ".taco-ingested"
ANNOTATIONS_URL = (
    "https://raw.githubusercontent.com/pedropro/TACO/master/data/annotations.json"
)

# TACO supercategory / category → GCC canonical type (TrashItemCatalog ids).
# Anything unmapped but still litter lands in other_litter — its tokens
# ("other", "litter") still type as litter in GroundExclusionRules.
CATEGORY_MAP = {
    "clear plastic bottle": "plastic_bottle",
    "other plastic bottle": "plastic_bottle",
    "plastic bottle cap": "plastic_bottle",
    "glass bottle": "glass_bottle",
    "broken glass": "glass_bottle",
    "drink can": "can",
    "food can": "can",
    "aerosol": "can",
    "single-use carrier bag": "plastic_bag",
    "polypropylene bag": "plastic_bag",
    "plastic film": "plastic_bag",
    "garbage bag": "trash_bag",
    "paper cup": "cup",
    "disposable plastic cup": "cup",
    "foam cup": "cup",
    "other plastic cup": "cup",
    "normal paper": "paper",
    "tissues": "paper",
    "wrapping paper": "paper",
    "magazine paper": "paper",
    "plastic film wrapper": "wrapper",
    "single-use plastic wrapper": "wrapper",
    "crisp packet": "chip_bag",
    "other plastic wrapper": "wrapper",
    "drink carton": "carton",
    "egg carton": "carton",
    "toilet tube": "carton",
    "other carton": "carton",
    "corrugated carton": "carton",
    "meal carton": "carton",
    "pizza box": "carton",
    "cigarette": "cigarette",
    "food waste": "food_waste",
}

CONTEXT_PAD = 0.25  # fraction of box size added on each side — the app's crops carry context too
MIN_CROP_PX = 48


def canonical_class(category_name: str) -> str:
    return CATEGORY_MAP.get(category_name.lower().strip(), "other_litter")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=0, help="max images this run (0 = all)")
    parser.add_argument("--negatives-per-image", type=int, default=2)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    rng = random.Random(args.seed)

    DATASET.mkdir(parents=True, exist_ok=True)
    done = set(INGESTED.read_text().split()) if INGESTED.exists() else set()

    print("fetching TACO annotations…")
    with urllib.request.urlopen(ANNOTATIONS_URL, timeout=60) as r:
        coco = json.load(r)
    categories = {c["id"]: c["name"] for c in coco["categories"]}
    boxes_by_image: dict[int, list[dict]] = {}
    for ann in coco["annotations"]:
        boxes_by_image.setdefault(ann["image_id"], []).append(ann)

    fetched = crops = negatives = failures = 0
    for img_meta in coco["images"]:
        image_id = img_meta["id"]
        key = str(image_id)
        if key in done:
            continue
        if args.limit and fetched >= args.limit:
            break
        url = img_meta.get("flickr_url") or img_meta.get("coco_url") or ""
        if not url:
            done.add(key)
            continue
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                image = Image.open(io.BytesIO(r.read())).convert("RGB")
        except Exception:
            failures += 1
            continue  # NOT marked done — rerun retries flaky links
        fetched += 1
        width, height = image.size
        anns = boxes_by_image.get(image_id, [])

        for idx, ann in enumerate(anns):
            x, y, w, h = ann["bbox"]
            pad_w, pad_h = w * CONTEXT_PAD, h * CONTEXT_PAD
            left = max(0, int(x - pad_w))
            top = max(0, int(y - pad_h))
            right = min(width, int(x + w + pad_w))
            bottom = min(height, int(y + h + pad_h))
            if right - left < MIN_CROP_PX or bottom - top < MIN_CROP_PX:
                continue
            class_dir = DATASET / canonical_class(categories.get(ann["category_id"], ""))
            class_dir.mkdir(exist_ok=True)
            image.crop((left, top, right, bottom)).save(
                class_dir / f"taco-{image_id}-{idx}.jpg", quality=90
            )
            crops += 1

        # Negatives: random crops that don't intersect any litter box.
        neg_dir = DATASET / "background"
        neg_dir.mkdir(exist_ok=True)
        litter_rects = [
            (a["bbox"][0], a["bbox"][1], a["bbox"][0] + a["bbox"][2], a["bbox"][1] + a["bbox"][3])
            for a in anns
        ]
        made = 0
        for attempt in range(args.negatives_per_image * 8):
            if made >= args.negatives_per_image:
                break
            side = rng.randint(max(MIN_CROP_PX, min(width, height) // 8), max(MIN_CROP_PX + 1, min(width, height) // 3))
            if side >= min(width, height):
                continue
            left = rng.randint(0, width - side)
            top = rng.randint(0, height - side)
            right, bottom = left + side, top + side
            if any(not (right < lx or left > rx or bottom < ly or top > ry)
                   for (lx, ly, rx, ry) in litter_rects):
                continue
            image.crop((left, top, right, bottom)).save(
                neg_dir / f"taco-neg-{image_id}-{attempt}.jpg", quality=90
            )
            made += 1
        negatives += made
        done.add(key)

    INGESTED.write_text("\n".join(sorted(done)))
    counts = {d.name: len(list(d.glob("*.jpg"))) for d in DATASET.iterdir() if d.is_dir()}
    print(json.dumps({
        "imagesFetched": fetched, "litterCrops": crops,
        "backgroundCrops": negatives, "downloadFailures": failures,
        "datasetCounts": counts,
    }, indent=2))


if __name__ == "__main__":
    main()
