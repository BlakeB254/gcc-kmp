"""Exports the trained litter crop classifier to Core ML for the iOS app's
Stage-2 slot (iosApp/iosApp/LitterCropClassifier.swift).

Run (DGX, after train_litter_classifier.py):
  .venv/bin/python export_coreml.py

Produces ~/models/gcc/litter-cls/LitterCropClassifier.mlpackage.

Ship it:
  1. Copy the .mlpackage into iosApp/iosApp/ on the Mac.
  2. Add it to the Xcode project (target iosApp) — Xcode compiles it to
     LitterCropClassifier.mlmodelc inside the bundle at build time.
  3. Nothing else: LitterCropClassifier.shared detects the compiled model at
     launch and starts prepending its verdicts to every candidate/tap crop
     classification. No model in the bundle = the slot stays inert.

Class-label contract the app depends on: canonical GCC item types
(plastic_bottle, can, trash_bag, …, other_litter) + "background" — exactly
the dataset's class dirs, which ultralytics bakes into the model.
"""
from __future__ import annotations

import pathlib
import shutil

MODELS = pathlib.Path("~/models/gcc/litter-cls").expanduser()


def main() -> None:
    from ultralytics import YOLO

    best = MODELS / "best.pt"
    if not best.exists():
        raise SystemExit(f"{best} not found — run train_litter_classifier.py first")

    model = YOLO(str(best))
    exported = pathlib.Path(model.export(format="coreml", imgsz=224, nms=False))
    target = MODELS / "LitterCropClassifier.mlpackage"
    if target.exists():
        shutil.rmtree(target)
    shutil.move(str(exported), str(target))
    print(f"exported: {target}")
    print("next: copy into iosApp/iosApp/, add to the Xcode target, rebuild — see module doc.")


if __name__ == "__main__":
    main()
