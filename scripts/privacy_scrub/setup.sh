#!/usr/bin/env bash
# Installs ONE detector backend, chosen by SCRUB_DETECTOR (default yolo):
#   yolo   -> requirements-yolo.txt   (ultralytics, AGPL-3.0: internal only)
#   rfdetr -> requirements-rfdetr.txt (no AGPL)
# SCRUB_SKIP_VENV=1 fetches weights only (e.g. a container that already has
# CUDA torch: pip install -r requirements-rfdetr.txt there instead).
# GB10 (sm_121): stable torch wheels lacked kernels; nightly cu130 carries them
# (same recipe as ../gsplat_train/setup.sh — see the cdx-dgx-spark skill).
set -euo pipefail
cd "$(dirname "$0")"
DETECTOR=${SCRUB_DETECTOR:-yolo}
case "$DETECTOR" in
  yolo|rfdetr) ;;
  *) echo "SCRUB_DETECTOR must be yolo or rfdetr, got '$DETECTOR'" >&2; exit 2 ;;
esac

if [ "${SCRUB_SKIP_VENV:-0}" != 1 ]; then
  uv venv --python 3.12 .venv
  VIRTUAL_ENV=$PWD/.venv uv pip install --pre torch torchvision --index-url https://download.pytorch.org/whl/nightly/cu130
  VIRTUAL_ENV=$PWD/.venv uv pip install -r "requirements-$DETECTOR.txt"
fi

# Weights live in the central model repo, never in the project tree.
MODELS_DIR=${SCRUB_MODELS_DIR:-/home/codex450/models/specialty/detection}
mkdir -p "$MODELS_DIR"
fetch() {  # fetch FILE URL ALGO SUM
  local f="$MODELS_DIR/$1"
  if [ ! -f "$f" ]; then
    curl -fsSL -o "$f.part" "$2" && mv "$f.part" "$f"
  fi
  echo "$4  $f" | "$3sum" -c --quiet
}
if [ "$DETECTOR" = rfdetr ]; then
  # Apache-2.0 RF-DETR COCO weights (Nano–Large only; XL/2XL are PML) and the
  # opencv_zoo YuNet face model (MIT). Checksums are the ones rfdetr itself
  # pins (md5) and the 2023mar release (sha256).
  fetch rf-detr-large-2026.pth https://storage.googleapis.com/rfdetr/rf-detr-large-2026.pth \
    md5 5cb72153541cbcb9aa6efa26222acc75
  fetch rf-detr-medium.pth https://storage.googleapis.com/rfdetr/medium_coco/checkpoint_best_regular.pth \
    md5 7223f764a87b863f02eb8d52bf0ce2ee
  fetch face_detection_yunet_2023mar.onnx \
    https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx \
    sha256 8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
else
  # AGPL path, Spark-internal only. The face model lives in the root-owned
  # ~/models/ultralytics/bbox (pre-existing).
  if [ ! -f "$MODELS_DIR/yolo11m.pt" ]; then
    curl -fsSL -o "$MODELS_DIR/yolo11m.pt" \
      https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo11m.pt
  fi
fi

if [ "${SCRUB_SKIP_VENV:-0}" != 1 ]; then
  .venv/bin/python - <<'EOF'
import torch
assert torch.cuda.is_available(), "CUDA not available — wrong torch wheel?"
print("torch", torch.__version__, "cap", torch.cuda.get_device_capability(0))
EOF
fi
