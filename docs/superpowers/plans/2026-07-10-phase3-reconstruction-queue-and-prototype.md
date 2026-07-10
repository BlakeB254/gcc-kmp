# Phase-3 Reconstruction Queue + One-Cell Prototype — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A durable Neon `reconstruction_jobs` queue fed by the Ktor backend, consumed by a DGX GPU worker that turns one real cell's raw R2 captures into web-servable 3D assets with provenance.

**Architecture:** Port the tested fusion pipeline from branch `worktree-scan-capture-and-fusion-pipeline` into `scripts/reconstruction/` on this branch; add a queue table (Drizzle + hand-written migration), a small enqueue step to the Ktor `ScanIndexer` sweep, and a new `recon_worker.py` that leases jobs with `FOR UPDATE SKIP LOCKED`, runs fusion + world-mirror splat, compresses for web, publishes to `reconstructed/{cell}/{version}/`, and records `reconstruction_assets` + `scan_sessions.reconstruction_version` directly in Neon.

**Tech Stack:** Python 3.11 (`uv` venv; numpy/scipy/open3d-io/pyav/pyliblzfse/boto3/psycopg), Kotlin/Ktor (plain JDBC), Drizzle schema + psql-applied SQL migration, `cdx-world-mirror` on `:7027`, `@playcanvas/splat-transform` for web compression.

## Global Constraints (from the spec)

- Spec: `docs/superpowers/specs/2026-07-10-phase3-reconstruction-queue-and-prototype-design.md`
- Drizzle `site/src/db/schema.ts` is authoritative; migrations are hand-written `CREATE … IF NOT EXISTS`, applied via psql with a `drizzle.__drizzle_migrations` tracking row. NEVER trust `drizzle-kit generate`.
- NEVER modify or delete `raw/…` R2 objects. All writes go under `reconstructed/{cellId}/{version}/`.
- One version number per worker run per cell, shared by every asset in that run; versions are append-only.
- Depth chunks in R2 use extension **`.bin`** (recorder docs say `.depth` — accept both).
- world-mirror base URL default `http://127.0.0.1:7027`; worker checks `/health` and requeues on failure, never manages GPU services.
- Prototype cell: `464650:-721828` (sessions `env-1783640498732-0cgku8`, `env-1783470359293-i3tt1m`).
- DB access for the worker: `DATABASE_URL` (the backend deploy env / `DATABASE_URL_UNPOOLED` in `site/.env.local`) via psycopg — real transactions.
- Commit after every task; do not merge to main; do not open PRs.

---

### Task 1: Port the fusion pipeline + fresh venv + tests green

**Files:**
- Replace: `scripts/reconstruction/**` (from branch `worktree-scan-capture-and-fusion-pipeline`)

**Steps:**
- [ ] `git checkout worktree-scan-capture-and-fusion-pipeline -- scripts/reconstruction` (brings chunk_io.py, fuse_cell.py, gaussian_ply.py, splat_train.py, world_mirror_client.py, r2_store.py, frame_io.py, registration.py, requirements.txt, tests/, README.md)
- [ ] `cd scripts/reconstruction && uv venv --python 3.11 .venv && source .venv/bin/activate && uv pip install -r requirements.txt`
- [ ] `python -m pytest tests/ -q` → expect **31 passed, 1 xfailed**
- [ ] Commit: `feat(reconstruction): port tested fusion pipeline from worktree-scan-capture-and-fusion-pipeline`

### Task 2: Accept the real `.bin` depth extension (TDD)

**Files:**
- Modify: `scripts/reconstruction/fuse_cell.py` (`_CHUNK_KEY_PATTERN`, `_group_chunks_by_index`)
- Test: `scripts/reconstruction/tests/test_fuse_cell.py`

**Interfaces:** `_group_chunks_by_index(keys) -> {idx: {"mov"|"jsonl"|"depth": key}}` — `.bin` keys must land under kind `"depth"`.

- [ ] Add failing test: keys `[".../chunk-0.mov", ".../chunk-0.jsonl", ".../chunk-0.bin"]` group to `{0: {"mov":…, "jsonl":…, "depth":…}}`; run to see it fail
- [ ] Change pattern to `chunk-(\d+)\.(mov|jsonl|depth|bin)$` and normalize `bin` → `depth` kind in `_group_chunks_by_index`
- [ ] `python -m pytest tests/test_fuse_cell.py -q` → pass; full suite still green
- [ ] Commit: `fix(reconstruction): depth chunks upload as .bin, not .depth`

### Task 3: Publish refactor — explicit run version, produced-assets list, provenance optional (TDD)

**Files:**
- Modify: `scripts/reconstruction/fuse_cell.py`, `scripts/reconstruction/r2_store.py`
- Test: `scripts/reconstruction/tests/test_fuse_cell.py`

**Interfaces (produced for Task 6):**
```python
@dataclass
class ProducedAsset:
    asset_type: str   # 'pointcloud' | 'splat' | 'splat-web'
    fmt: str          # 'ply' | 'spz' | …
    r2_key: str
    version: int
    data: bytes | None   # kept in-memory for post-processing (splat-web compression)

FusionResult.assets: list[ProducedAsset]
r2_store.next_run_version_for_cell(cell_id) -> int     # max version dir under reconstructed/{cell}/ + 1, across ALL asset types
fuse_cell(cell_id, api_base_url=None, train_splat=True, require_activation_for_splat=True) -> FusionResult
```
- [ ] Failing tests: (a) `next_run_version_for_cell` returns max+1 across asset types (monkeypatched listing); (b) `fuse_cell` with mocked R2/sessions records `ProducedAsset(version==run_version)` for the pointcloud; (c) `api_base_url=None` skips HTTP provenance entirely (no `requests.post` call)
- [ ] Implement: compute run version once in `fuse_cell`; thread `version` through `_write_ply_to_r2`/`_write_bytes_to_r2` (signature gains `version: int`); append every publish to `result.assets` keeping `data`; gate splat on `require_activation_for_splat`; `_record_provenance` only when `api_base_url` is truthy
- [ ] Full suite green; commit: `refactor(reconstruction): run-versioned publishes, produced-asset provenance, HTTP write optional`

### Task 4: `reconstruction_jobs` table — schema.ts + migration 0034, applied

**Files:**
- Modify: `site/src/db/schema.ts` (after `captureChunks`, mirroring its style)
- Create: `site/src/db/migrations/0034_add_reconstruction_jobs.sql`

Migration content = spec §4 DDL **plus** `CREATE UNIQUE INDEX IF NOT EXISTS reconstruction_assets_r2_key_key ON reconstruction_assets (r2_key);` (two writers, idempotent on r2_key — indexer already inserts with `WHERE NOT EXISTS`).

- [ ] Add `reconstructionJobs` pgTable to schema.ts (status/attempts/leased_until/last_error/timestamps, unique on `session_id`, indexes `(status, created_at)` and `(location_cell_id)`)
- [ ] `cd site && npx tsc --noEmit` → clean
- [ ] Apply via `psql "$DATABASE_URL_UNPOOLED" -f …0034….sql`; verify `\d reconstruction_jobs`; insert tracking row into `drizzle.__drizzle_migrations`
- [ ] Commit: `feat(db): reconstruction_jobs queue + unique r2_key on reconstruction_assets (migration 0034)`

### Task 5: Ktor enqueuer — jobs appear after every index sweep

**Files:**
- Modify: `backend/src/main/kotlin/com/gcc/backend/ScanIndexRepository.kt` (new `enqueueReconstructionJobs(): Int`)
- Modify: `backend/src/main/kotlin/com/gcc/backend/ScanIndexer.kt` (call it at the end of `runOnce()`, count into `Result`)

```sql
INSERT INTO reconstruction_jobs (session_id, location_cell_id)
SELECT s.session_id, s.location_cell_id
FROM scan_sessions s
WHERE s.reconstruction_version IS NULL
  AND s.completed_at IS NOT NULL
  AND s.location_cell_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM capture_chunks c WHERE c.session_id = s.session_id AND c.format = 'jsonl')
  AND EXISTS (SELECT 1 FROM capture_chunks c WHERE c.session_id = s.session_id AND c.format = 'bin')
  AND EXISTS (SELECT 1 FROM capture_chunks c WHERE c.session_id = s.session_id AND c.format = 'mov')
ON CONFLICT (session_id) DO NOTHING
```
- [ ] Implement repository method (plain JDBC, same style as siblings) + wire into `runOnce()`; log enqueued count
- [ ] `./gradlew :backend:build` → green (backend has no test infra yet; the SQL itself is exercised in Task 6's local-PG tests and the prototype)
- [ ] Commit: `feat(backend): enqueue reconstruction jobs for unreconstructed chunk-bearing sessions`

### Task 6: `recon_worker.py` — lease → fuse → publish → record (TDD on local Postgres)

**Files:**
- Create: `scripts/reconstruction/recon_worker.py`
- Create: `scripts/reconstruction/tests/test_recon_worker.py`

**Interfaces:**
```python
claim_job(conn) -> Job | None                      # Job(id, session_id, location_cell_id, attempts)
complete_cell(conn, cell, result, assets) -> None  # one tx: assets rows, session versions, close sibling jobs
fail_job(conn, job, err) -> None                   # attempts>=3 → failed, else requeue
run_once(conn, …) -> bool                          # one claim/process cycle; True if a job was processed
```
Claim SQL = spec §4 verbatim (30-min lease). `complete_cell` in ONE transaction:
1. `INSERT INTO reconstruction_assets (location_cell_id, asset_type, format, r2_key, version, source_scan_coverage_event_ids, activated_at) VALUES … ON CONFLICT (r2_key) DO NOTHING` per asset — provenance jsonb `{"sessions": accepted_ids, "rejectedSessions": rejected_ids}`; `activated_at = now()` only when `result.activated`
2. `UPDATE scan_sessions SET reconstruction_version = %(v)s, reconstructed_at = now() WHERE session_id = ANY(%(accepted)s)`
3. `UPDATE reconstruction_jobs SET status='done', completed_at=now(), updated_at=now() WHERE location_cell_id = %(cell)s AND status IN ('queued','leased')`

Tests run against the LOCAL scratch Postgres (`postgresql://…@localhost:5432/recon_worker_test`, created by the test via the `data-postgres` container's superuser; skip if unreachable): lease claim + skip-locked under two connections, lease expiry reclaim, attempts→failed at 3, `complete_cell` closes sibling jobs and stamps sessions.

- [ ] Write failing tests; run (fail: module missing)
- [ ] Implement worker (`--once` flag for the prototype; `--loop [interval]` for later; env: `DATABASE_URL`, `WORLD_MIRROR_URL`, R2 vars). world-mirror `/health` checked before splat work; failure → `fail_job` requeue path
- [ ] `python -m pytest tests/ -q` → all green
- [ ] Commit: `feat(reconstruction): queue worker — lease, fuse, publish, record provenance`

### Task 7: `splat-web` compression step

**Files:**
- Modify: `scripts/reconstruction/recon_worker.py` (post-process the `splat` ProducedAsset)
- Test: `scripts/reconstruction/tests/test_recon_worker.py` (subprocess mocked; format-fallback logic unit-tested)

- [ ] Probe tooling once at startup: `npx -y @playcanvas/splat-transform --version` (aarch64 Node present). Compress: `splat-transform in.ply out.spz` → fallback `out.compressed.ply` → if both fail, record no `splat-web` asset and log loudly (spec: format column records what was actually produced)
- [ ] Upload as `splat-web.{spz|ply}` under the SAME run version; append `ProducedAsset` so `complete_cell` records it
- [ ] Tests green; commit: `feat(reconstruction): compress splat to web-servable spz (compressed.ply fallback)`

### Task 8: PROTOTYPE — cell `464650:-721828` end-to-end (deliverable 2)

- [ ] Snapshot `raw/` listing for the cell (rclone) to a scratch file (for the no-mutation check)
- [ ] Enqueue via the real path: `curl -X POST localhost:8080/admin/index-scans -H "Authorization: Bearer $INDEX_SECRET"` after rebuilding the backend container (`cd backend/deploy && docker compose up -d --build`); verify 2 queued jobs in Neon. (If the rebuild is deferred, run Task 5's SQL manually via psql and SAY SO in the summary.)
- [ ] `WORLD_MIRROR_URL=http://127.0.0.1:7027 python recon_worker.py --once` (worker env from `backend/deploy/.env` + `DATABASE_URL_UNPOOLED`)
- [ ] First-run validation gates (spec §5.3): HEVC decode works on a real chunk; depth-axis assumption eyeballed — load the splat/pointcloud in SuperSplat (`cp` to `~/3d/supersplat/dist/`, open `http://localhost:7051/?load=…`), screenshot
- [ ] Verify: `reconstructed/464650:-721828/1/…` objects (pointcloud.ply, splat.ply, splat-web.*); `reconstruction_assets` rows + provenance jsonb; `scan_sessions.reconstruction_version=1` + `reconstructed_at` for accepted sessions; jobs `done`; `raw/` listing byte-identical to the snapshot
- [ ] Commit any fixes surfaced by real data (each as its own focused commit)

### Task 9: Docs + handoff

- [ ] Update `scripts/reconstruction/README.md`: status section (real-data run results, .bin fix, worker + queue usage), setup for the worker
- [ ] Update `backend/README.md` roadmap: Phase 3 queue now exists (enqueue on sweep); worker invocation
- [ ] Commit: `docs(reconstruction): real-data prototype results + queue/worker usage`
- [ ] Write the session summary (verified / built / next) — deliverable 3

## Self-review notes

- Spec §4 queue semantics → Tasks 4–6; §5 engine deltas → Tasks 1–3; §6 formats → Tasks 3, 7; §7 error handling → Task 6 (fail/requeue) + §7's r2_key idempotency → Task 4; §8 testing → Tasks 2, 3, 6, 7; §9 acceptance → Task 8; §10 risks: HEVC/depth-axis checks live in Task 8, splat-transform fallback in Task 7.
- Type check: `ProducedAsset`/`FusionResult.assets` defined in Task 3, consumed by Tasks 6–7 with the same names. `require_activation_for_splat=False` is what the prototype passes (worker default from env `SPLAT_REQUIRE_ACTIVATION=0` for now — flip to activation-gated before any batch run).
