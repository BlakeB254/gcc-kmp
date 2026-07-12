#!/usr/bin/env bash
# GB10 (sm_121): stable torch wheels lack kernels; nightly cu130 carries them
# (same recipe as ../gsplat_train/setup.sh — see the cdx-dgx-spark skill).
set -euo pipefail
cd "$(dirname "$0")"
uv venv --python 3.12 .venv   # ultralytics/torch-nightly are well-trodden on 3.12; 3.14 is not
VIRTUAL_ENV=$PWD/.venv uv pip install --pre torch torchvision --index-url https://download.pytorch.org/whl/nightly/cu130
VIRTUAL_ENV=$PWD/.venv uv pip install -r requirements.txt

# Weights live in the central model repo, never in the project tree.
# ~/models/ultralytics is root-owned (read-only for us; the face model lives
# there) — new detection weights go in the writable ~/models/specialty/detection.
MODELS_DIR=/home/codex450/models/specialty/detection
mkdir -p "$MODELS_DIR"
if [ ! -f "$MODELS_DIR/yolo11m.pt" ]; then
  curl -sL -o "$MODELS_DIR/yolo11m.pt" \
    https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo11m.pt
fi

.venv/bin/python - <<'EOF'
import torch
assert torch.cuda.is_available(), "CUDA not available — wrong torch wheel?"
print("torch", torch.__version__, "cap", torch.cuda.get_device_capability(0))
EOF
