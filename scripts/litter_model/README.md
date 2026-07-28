# Stage-2 litter crop classifier (DGX runbook)

Purpose-trained "is this litter, and what kind" model for the env-scan's
candidate crops — replaces Apple's generic scene taxonomy at every decision
seam (ground candidates, taps, saliency crops). App-side slot:
`iosApp/iosApp/LitterCropClassifier.swift` (inert until the model ships in
the bundle).

## One-time setup (DGX, Blackwell sm_121 needs PyTorch nightly)

```bash
cd scripts/litter_model
uv venv --python 3.12 .venv && source .venv/bin/activate
uv pip install --pre torch torchvision --index-url https://download.pytorch.org/whl/nightly/cu128
uv pip install ultralytics pillow
```

## The loop

```bash
# 1. Cold-start data: TACO litter crops + background negatives (~1500 photos, CC BY 4.0)
.venv/bin/python fetch_taco.py                 # rerun to retry flaky downloads

# 2. Field data: labelled crops from every scan session in R2 (incremental).
#    Items observed on build >= 168 carry image boxes -> real crops;
#    user-labelled items (chips / "Other...") are marked -gold.
cd ../reconstruction && .venv/bin/python ../litter_model/build_litter_dataset.py && cd ../litter_model

# 3. Train (resumes from previous best -> the model improves every cycle)
.venv/bin/python train_litter_classifier.py --epochs 50

# 4. Export for the app
.venv/bin/python export_coreml.py
# -> ~/models/gcc/litter-cls/LitterCropClassifier.mlpackage
```

Ship: copy the `.mlpackage` into `iosApp/iosApp/`, add to the Xcode target,
rebuild. The app detects it at launch; no code changes needed.

## Class contract (do not rename)

Dataset class dirs == model labels == what the app's decision rules parse:
canonical `TrashItemCatalog` ids (`plastic_bottle`, `glass_bottle`, `can`,
`plastic_bag`, `trash_bag`, `cup`, `paper`, `wrapper`, `chip_bag`, `carton`,
`cigarette`, `food_waste`, `other_litter`) + `background`. A confident
`background` goes backdrop-soft in the app (quiet, tappable — never
terminal); any litter class types the candidate via the existing trash-token
rules.

## Data flywheel

Every field confirmation is a training sample: the app ships each observed
item's normalized image box (`imageCx/imageCy/imageHalfW/imageHalfH`) and
label provenance (`labelSource`: `user_chip` / `user_text` = gold) in the
scan manifest; `build_litter_dataset.py` turns them into labelled crops.
More scanning -> better model -> fewer manual labels -> repeat.
