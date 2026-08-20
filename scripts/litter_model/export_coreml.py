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


def _manual_mlpackage(mlmodel_path: pathlib.Path, package_path: pathlib.Path) -> None:
    """Wrap a flat .mlmodel in the .mlpackage directory structure by hand.

    coremltools on linux-aarch64 ships no compiled libmodelpackage, so
    MLModel.save("*.mlpackage") fails — but an mlpackage is just a folder:
    Manifest.json + Data/com.apple.CoreML/model.mlmodel. Xcode compiles it
    identically.
    """
    import json
    import uuid

    root_id = str(uuid.uuid4()).upper()
    data_dir = package_path / "Data" / "com.apple.CoreML"
    data_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(mlmodel_path, data_dir / "model.mlmodel")
    manifest = {
        "fileFormatVersion": "1.0.0",
        "itemInfoEntries": {
            root_id: {
                "author": "com.apple.CoreML",
                "description": "CoreML Model Specification",
                "name": "model.mlmodel",
                "path": "com.apple.CoreML/model.mlmodel",
            }
        },
        "rootItemIdentifier": root_id,
    }
    (package_path / "Manifest.json").write_text(json.dumps(manifest, indent=4))


def _export_neuralnetwork_fallback(best: pathlib.Path, target: pathlib.Path) -> None:
    """linux-aarch64 coremltools has no BlobWriter/libmodelpackage, so the
    default mlprogram export cannot save. Convert to the neuralnetwork
    backend instead (weights embedded in the proto — no blob writer needed),
    save a flat .mlmodel, then wrap it as .mlpackage manually. The app only
    needs an image-in / class-label-out classifier, which the NN backend
    delivers on every supported iOS version.
    """
    import coremltools as ct
    import torch
    from ultralytics import YOLO

    model = YOLO(str(best))
    im = torch.zeros(1, 3, 224, 224)
    traced = torch.jit.trace(model.model.eval().cpu().float(), im)
    ct_model = ct.convert(
        traced,
        inputs=[ct.ImageType(name="image", shape=im.shape, scale=1 / 255.0)],
        classifier_config=ct.ClassifierConfig(list(model.names.values())),
        convert_to="neuralnetwork",
        minimum_deployment_target=ct.target.iOS13,
    )
    flat = target.with_suffix(".mlmodel")
    ct_model.save(str(flat))
    if target.exists():
        shutil.rmtree(target)
    _manual_mlpackage(flat, target)
    print(f"exported (neuralnetwork fallback): {target}")


def main() -> None:
    from ultralytics import YOLO

    best = MODELS / "best.pt"
    if not best.exists():
        raise SystemExit(f"{best} not found — run train_litter_classifier.py first")

    target = MODELS / "LitterCropClassifier.mlpackage"
    try:
        model = YOLO(str(best))
        exported = pathlib.Path(model.export(format="coreml", imgsz=224, nms=False))
        if target.exists():
            shutil.rmtree(target)
        shutil.move(str(exported), str(target))
        print(f"exported: {target}")
    except Exception as e:
        print(f"mlprogram export unavailable ({e}) — using neuralnetwork fallback")
        _export_neuralnetwork_fallback(best, target)
    print("next: copy into iosApp/iosApp/, add to the Xcode target, rebuild — see module doc.")


if __name__ == "__main__":
    main()
