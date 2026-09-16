# Indoor Gate v4 classify API — engineering report

Date: 2026-08-18
Card: cdx-programming / t_7fb940cf
PR: https://github.com/BlakeB254/gcc/pull/259
Commit: 4aa23fa0

## Fields

- project: GCC (core project 122, entity 92 / research org 1672)
- endpoint_or_model: indoor_gate_v4_classification
- acceptance: indoor residence packets classify INDOOR and block capture within 5s
- tests: scripts/indoor_gate_v4/tests (pytest)

## What changed

New stateless FastAPI slice at scripts/indoor_gate_v4/. Ports the shipped
client OutdoorGate v4 + TrashDetectionRules policy so reconstruction/audit
can classify label packets without ingesting pixels.

No SQL migration. Policy is stateless; gate events already live on the
scan manifest. Drizzle remains schema owner.

## Tests

Command: cd scripts/indoor_gate_v4 && .venv/bin/python -m pytest tests -q
Result: 37 passed
Validation set: 25/25 (100% ≥ 80%)
Latency: each classify elapsed_ms < 5000

## Side effects not taken

- no deploy
- no DB migration
- no publishing / Postiz / Omniscribe
- no account mutation
- live gcc checkout not edited (worktree only)

## Remaining risk

- GCC CI does not run this Python suite yet
- On-device gate remains capture authority; this API is not wired into
  the reconstruction worker in this PR
- Reviewer should confirm no pixel-model / Neon table is wanted
