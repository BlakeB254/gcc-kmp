#!/bin/bash
# gsplat environment for the GB10 (Blackwell sm_121) — per the cdx-dgx-spark
# skill: stable torch wheels lack sm_121 kernels; nightly cu128+ carries them.
# gsplat compiles its CUDA extension on first import (needs nvcc + arch list).
set -e
cd "$(dirname "$0")"
uv venv --python 3.12 .venv
VIRTUAL_ENV=$PWD/.venv uv pip install --pre torch torchvision --index-url https://download.pytorch.org/whl/nightly/cu130
VIRTUAL_ENV=$PWD/.venv uv pip install numpy plyfile pillow av pyliblzfse boto3 tqdm
export TORCH_CUDA_ARCH_LIST="12.0;12.1"
VIRTUAL_ENV=$PWD/.venv uv pip install gsplat --no-build-isolation || \
  VIRTUAL_ENV=$PWD/.venv uv pip install gsplat
.venv/bin/python -c "import torch; print('torch', torch.__version__, 'cuda', torch.cuda.is_available(), torch.cuda.get_device_capability(0) if torch.cuda.is_available() else '')"
