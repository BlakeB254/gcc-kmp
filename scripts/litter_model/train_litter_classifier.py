"""Continual litter-classifier training on the DGX (GB10) — fine-tunes a
YOLO classification model on the growing field dataset and RESUMES from the
previous best weights every run, so the model improves as scans accumulate.

Setup (one-time, per the cdx-dgx-spark skill: Blackwell sm_121 needs
PyTorch nightly — stable wheels lack the kernels):

  cd scripts/litter_model
  uv venv --python 3.12 .venv && source .venv/bin/activate
  uv pip install --pre torch torchvision --index-url https://download.pytorch.org/whl/nightly/cu128
  uv pip install ultralytics

Run (after build_litter_dataset.py):
  .venv/bin/python train_litter_classifier.py [--epochs 50]

The dataset is tiny today (a handful of confirmed items) — training becomes
meaningful as field scanning grows; the harness is ready either way. The
trained model's next integration points: auto-labeling frames the humans
haven't confirmed (CV assist on observations), the 311 evidence matcher,
and on-device model distillation for the app's detector.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import shutil

DATASET = pathlib.Path(os.path.expanduser("~/models/gcc/litter-dataset"))
MODELS = pathlib.Path(os.path.expanduser("~/models/gcc/litter-cls"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--imgsz", type=int, default=224)
    args = parser.parse_args()

    from ultralytics import YOLO

    classes = [d for d in DATASET.iterdir() if d.is_dir()]
    n_images = sum(len(list(d.glob("*.jpg"))) for d in classes)
    if len(classes) < 2 or n_images < 20:
        raise SystemExit(
            f"dataset too small to train usefully ({len(classes)} classes, {n_images} images) "
            "— keep scanning; build_litter_dataset.py grows it automatically"
        )

    MODELS.mkdir(parents=True, exist_ok=True)
    prev_best = MODELS / "best.pt"
    base = str(prev_best) if prev_best.exists() else "yolov8n-cls.pt"
    print(f"training from: {base} ({n_images} images, {len(classes)} classes)")

    model = YOLO(base)
    results = model.train(data=str(DATASET), epochs=args.epochs, imgsz=args.imgsz,
                          project=str(MODELS / "runs"), name="litter", exist_ok=True)
    run_best = pathlib.Path(results.save_dir) / "weights" / "best.pt"
    if run_best.exists():
        shutil.copy(run_best, prev_best)
        print(f"updated {prev_best}")


if __name__ == "__main__":
    main()
