"""Pure resolution of why a cell's fusion run did or didn't produce a splat
asset -- factored out of fuse_cell.py's splat block specifically so this
logic is testable without pulling in open3d/boto3/requests (fuse_cell.py's
module-level deps) or av/liblzfse/torch (the trainer engines' deps). Used by
fuse_cell.py; the result rides on FusionResult.splat_skip_reason and
recon_worker.py's complete_cell() writes it into
reconstruction_jobs.splat_skip_reason (varchar(32), migration 0039).

Reason vocabulary (exact strings; keep in sync with gsplat_runner.py and
splat_train.py's trainer contracts):
  "no-video-frames"  trainer found zero posed frames to train from
  "too-few-frames"   trainer found more than zero but fewer than 8 posed
                     frames
  "insufficient-parallax" camera path spans less than 2m horizontally;
                     evidence is retained but no fuzzy splat is published
  "implausible-pose-span" camera path spans more than 40m inside one cell,
                     indicating a cross-session registration failure
  "training-failed"  venv missing / empty merged points / training
                     subprocess rc != 0 / missing output file
  "evidence-missing" no serialized captured-evidence artifact exists
  "evidence-malformed" artifact cannot be decoded or recomputed by Task 3
  "evidence-incomplete" artifact lacks confidence or required provenance
  "evidence-source-mismatch" artifact does not match raw PLY / vertex order
  "evidence-generation-mismatch" artifact belongs to another durable retry
  "cleanup-failed"  verified cleanup subprocess returned nonzero
  "cleanup-incomplete" three variants/report/candidate validation incomplete
  "disabled"         splat_wanted was falsy (train_splat off, no accepted
                     sessions, or the activation gate held it back) -- no
                     trainer ran at all
  None               a splat asset was produced
"""
from __future__ import annotations


def resolve_splat_outcome(
    splat_wanted: bool,
    splat_bytes: bytes | None,
    trainer_reason: str | None,
) -> str | None:
    """None <=> a splat asset was produced. Mirrors fuse_cell's splat block:
    splat_wanted=False short-circuits to "disabled" before any trainer runs
    (trainer_reason is ignored in that case -- there's nothing to report on
    a trainer that never ran); otherwise the trainer's own (bytes, reason)
    tuple decides."""
    if not splat_wanted:
        return "disabled"
    if splat_bytes is not None:
        return None
    return trainer_reason
