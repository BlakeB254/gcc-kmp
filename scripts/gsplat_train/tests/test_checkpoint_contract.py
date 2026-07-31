from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import types

import pytest


TRAINER_PATH = Path(__file__).resolve().parents[1] / "train_gsplat.py"
RECONSTRUCTION_DIR = Path(__file__).resolve().parents[2] / "reconstruction"


def _load_trainer_without_torch():
    """Load parser/state contracts without importing the GPU stack."""
    sentinel = object()
    previous = sys.modules.get("torch", sentinel)
    sys.modules["torch"] = types.ModuleType("torch")
    try:
        spec = importlib.util.spec_from_file_location("checkpoint_contract_trainer", TRAINER_PATH)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        try:
            spec.loader.exec_module(module)
            return module
        finally:
            sys.modules.pop(spec.name, None)
    finally:
        if previous is sentinel:
            sys.modules.pop("torch", None)
        else:
            sys.modules["torch"] = previous


def test_verified_defaults_freeze_poses_and_enable_checkpoints():
    """Catches verified training silently enabling pose drift or losing recovery."""
    trainer = _load_trainer_without_torch()

    args = trainer.parse_args(["--data", "/tmp/data"])

    assert args.pose_mode == "frozen"
    assert args.checkpoint_every == 1000


def test_resume_rejects_dataset_fingerprint_mismatch(tmp_path):
    """Catches recovery applying optimizer state to a different captured dataset."""
    trainer = _load_trainer_without_torch()
    (tmp_path / "checkpoint_latest.pt").write_bytes(b"not-read-by-validation")
    (tmp_path / "training-state.json").write_text(json.dumps({
        "checkpoint": "checkpoint_latest.pt",
        "datasetSha256": "old",
        "frozenPosesSha256": "poses",
    }))

    with pytest.raises(ValueError, match="dataset fingerprint"):
        trainer.validate_resume(tmp_path, dataset_sha256="new")


def test_trainer_contract_import_does_not_import_torch():
    """Catches CPU orchestration contracts importing the GPU training stack."""
    code = f"""
import importlib.abc
import importlib.util
import sys

class ForbidTorch(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname == 'torch' or fullname.startswith('torch.'):
            raise AssertionError('trainer contract imported torch')
        return None

sys.meta_path.insert(0, ForbidTorch())
spec = importlib.util.spec_from_file_location('lazy_trainer', {str(TRAINER_PATH)!r})
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
assert 'torch' not in sys.modules
print('torch_imported=False')
"""
    proc = subprocess.run(
        [sys.executable, "-c", code], text=True, capture_output=True, check=False
    )

    assert proc.returncode == 0, proc.stderr
    assert proc.stdout.strip() == "torch_imported=False"


def test_smooth_trajectory_is_rejected_until_an_audited_representation_exists(capsys):
    """Catches verified publication accepting unsupported camera adjustments."""
    trainer = _load_trainer_without_torch()

    with pytest.raises(SystemExit):
        trainer.parse_args(["--data", "/tmp/data", "--pose-mode", "smooth-trajectory"])

    assert "audited trajectory representation" in capsys.readouterr().err


def test_independent_per_frame_pose_optimizer_option_is_prohibited():
    """Catches the removed pose-delta path becoming reachable through the CLI."""
    trainer = _load_trainer_without_torch()

    with pytest.raises(SystemExit):
        trainer.parse_args(["--data", "/tmp/data", "--pose-lr", "0.0001"])


def test_dataset_fingerprint_covers_frozen_poses_and_captured_inputs(tmp_path):
    """Catches resume validation overlooking a changed verified input artifact."""
    trainer = _load_trainer_without_torch()
    (tmp_path / "frames").mkdir()
    for name, content in {
        "meta.json": b"{}",
        "poses.npy": b"poses-v1",
        "intrinsics.npy": b"intrinsics",
        "init_points.npy": b"lidar",
        "init_colors.npy": b"captured-colors",
        "frame-audit.json": b"audit",
        "frames/0000.jpg": b"captured-rgb",
    }.items():
        path = tmp_path / name
        path.write_bytes(content)

    first = trainer.dataset_fingerprints(tmp_path)
    (tmp_path / "poses.npy").write_bytes(b"poses-v2")
    second = trainer.dataset_fingerprints(tmp_path)

    assert first.dataset_sha256 != second.dataset_sha256
    assert first.frozen_poses_sha256 != second.frozen_poses_sha256


def test_checkpoint_round_trip_is_atomic_and_keeps_cuda_uninitialized(tmp_path):
    """Catches incomplete recovery state, torn latest files, or CUDA initialization."""
    code = f"""
import importlib.util
from pathlib import Path
import torch
import random
import numpy as np

assert not torch.cuda.is_initialized()
spec = importlib.util.spec_from_file_location('checkpoint_trainer', {str(TRAINER_PATH)!r})
module = importlib.util.module_from_spec(spec)
__import__('sys').modules[spec.name] = module
spec.loader.exec_module(module)
root = Path({str(tmp_path)!r})
params = torch.nn.ParameterDict({{'means': torch.nn.Parameter(torch.tensor([[1.0, 2.0, 3.0]]))}})
optimizer = torch.optim.Adam([params['means']], lr=0.01)
scheduler = torch.optim.lr_scheduler.ExponentialLR(optimizer, gamma=0.9)
exposure = torch.nn.Parameter(torch.zeros(1, 12))
exposure_optimizer = torch.optim.Adam([exposure], lr=0.001)
random.seed(17)
np.random.seed(18)
torch.manual_seed(19)
expected_torch_rng = torch.get_rng_state().clone()
path = module.checkpoint_training(
    root,
    iteration=12,
    dataset_sha256='dataset-fingerprint',
    frozen_poses_sha256='poses-checksum',
    params=params,
    optimizers={{'means': optimizer}},
    scheduler=scheduler,
    strategy_state={{'state': {{'scene_scale': torch.tensor(2.0)}}, 'refine_stop_iter': 9}},
    auxiliary_parameters={{'exposure': exposure}},
    auxiliary_optimizers={{'exposure': exposure_optimizer}},
)
loaded = module.load_checkpoint(path)
assert loaded['iteration'] == 12
assert loaded['dataset_sha256'] == 'dataset-fingerprint'
assert loaded['frozen_poses_sha256'] == 'poses-checksum'
assert torch.equal(loaded['rng_state']['torch'], expected_torch_rng)
assert set(loaded['rng_state']) == {{'python', 'numpy', 'torch', 'cuda'}}
assert loaded['rng_state']['cuda'] is None
assert torch.equal(loaded['gaussian_parameters']['means'], torch.tensor([[1.0, 2.0, 3.0]]))
assert set(loaded['optimizer_states']) == {{'means', 'exposure'}}
assert loaded['scheduler_state']['last_epoch'] == scheduler.state_dict()['last_epoch']
assert loaded['strategy_state']['refine_stop_iter'] == 9
module.restore_rng_state(loaded['rng_state'])
assert torch.equal(torch.get_rng_state(), expected_torch_rng)
assert (root / 'checkpoint_12.pt').is_file()
assert (root / 'checkpoint_latest.pt').is_file()
assert not list(root.glob('*.tmp'))
state = __import__('json').loads((root / 'training-state.json').read_text())
assert state['iteration'] == 12
assert state['checkpoint'] == 'checkpoint_latest.pt'
assert not torch.cuda.is_initialized()
print('cuda_initialized_before=False after=False')
"""
    env = os.environ.copy()
    env["CUDA_VISIBLE_DEVICES"] = ""
    python = Path("/home/codex450/cdx/dev-projects/gcc/scripts/gsplat_train/.venv/bin/python")
    proc = subprocess.run(
        [str(python), "-c", code], env=env, text=True, capture_output=True, check=False
    )

    assert proc.returncode == 0, proc.stderr
    assert proc.stdout.strip() == "cuda_initialized_before=False after=False"
    print(proc.stdout.strip())


def test_checkpoint_schedule_is_periodic_and_always_includes_final_iteration():
    """Catches short or resumed runs finishing without a recoverable latest state."""
    trainer = _load_trainer_without_torch()

    assert trainer.checkpoint_iterations(0, 2500, 1000) == [1000, 2000, 2500]
    assert trainer.checkpoint_iterations(1500, 2500, 1000) == [2000, 2500]


def test_loaded_checkpoint_rejects_frozen_pose_checksum_mismatch():
    """Catches tampered checkpoint payloads bypassing lightweight resume metadata."""
    trainer = _load_trainer_without_torch()
    checkpoint = {
        "dataset_sha256": "dataset",
        "frozen_poses_sha256": "old-poses",
    }

    with pytest.raises(ValueError, match="frozen poses checksum"):
        trainer.validate_checkpoint_payload(
            checkpoint,
            dataset_sha256="dataset",
            frozen_poses_sha256="new-poses",
        )


def _load_runner_without_torch():
    sys.path.insert(0, str(RECONSTRUCTION_DIR))
    try:
        import gsplat_runner
        return gsplat_runner
    finally:
        sys.path.pop(0)


def test_runner_invokes_explicit_verified_training_defaults():
    """Catches orchestration omitting the publication-safe pose/checkpoint contract."""
    runner = _load_runner_without_torch()

    command = runner._build_train_command("/tmp/staging", "30000", "5000000")

    assert command[-4:] == ["--pose-mode", "frozen", "--checkpoint-every", "1000"]


def test_runner_stages_exact_trajectory_audit_and_training_state(tmp_path):
    """Catches candidate metadata omitting the evidence needed for verification."""
    runner = _load_runner_without_torch()
    dataset = tmp_path / "dataset"
    staging = tmp_path / "candidate"
    dataset.mkdir()
    expected = {
        "poses.npy": b"exact-frozen-trajectory",
        "frame-audit.json": b'{"audits": []}',
        "training-state.json": b'{"status": "completed"}',
    }
    for name, content in expected.items():
        (dataset / name).write_bytes(content)

    runner._stage_verified_training_metadata(dataset, staging)

    assert {path.name: path.read_bytes() for path in staging.iterdir()} == expected
    assert "torch" not in sys.modules
