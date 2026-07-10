# Phase 3 — Reconstruction Queue + One-Cell Prototype — Design

**Date:** 2026-07-10 · **Status:** Approved for prototype (per DGX handoff brief) · **Author:** Claude (DGX session)

**Relationship to canon:** implements the Phase-3 roadmap item in `backend/README.md` and
`docs/governance/production-readiness-and-scaling.md` §3, building directly on:
- `2026-07-03-phase3-reconstruction-fusion-research.md` — engine choice research (Open3D-class
  registration + splat production; its conclusions are implemented, not revisited).
- The **`worktree-scan-capture-and-fusion-pipeline` branch** (pushed, head `e99f0546`) — a prior
  session's full fusion pipeline: `scripts/reconstruction/` parsing the REAL `ArSessionRecorder`
  capture format, pure-NumPy/SciPy registration (Open3D's compute core segfaults on this
  aarch64/GB10 box — see that branch's README), per-session Gaussian-splat production via the
  local `cdx-world-mirror` (HY-WorldMirror-2.0) service, 31 passing tests. That work predates the
  Ktor backend merge (PR #147) and was never run on real captured data because none existed then.
- `2026-07-07-raw-area-capture-and-fusion-pipeline-design.md` — the capture producer, since
  shipped: real sessions with HEVC video + LZFSE LiDAR depth + pose sidecar chunks now exist in R2.

## 0. Verified current state (2026-07-10, this session)

- `main` @ `a713f90a` — Ktor scan control-plane merged; `deploy-gcc-backend-1` healthy on
  `127.0.0.1:8080`, R2 indexer enabled (30-min sweeps), quick tunnel up.
- Neon: `scan_sessions` 34 rows, `capture_chunks` 59, `reconstruction_assets` 0,
  `scan_coverage_events` 2 269, `cell_coverage_summary` 174, `world_observations` 295.
  Partial index `scan_sessions_unreconstructed_idx` (WHERE `reconstruction_version IS NULL`) exists.
- **Real capture data exists**: session `env-1783640498732-0cgku8` at cell `464650:-721828`
  (2026-07-09, 125 MB): 3 chunks × (`.mov` HEVC video + `.bin` LZFSE depth + `.jsonl` pose sidecar)
  + manifest + photo. A second session at the SAME cell (`env-1783470359293-i3tt1m`, `recovered`,
  42 MB, 1 chunk-set) enables a first real cross-session registration test.
- `cdx-world-mirror` FastAPI is **live on :7027** (`pipeline_loaded: true`), with the
  `result_dir` artifact-path fix already applied in `~/cdx-platform/services/cdx-world-mirror/`.
  (:7026 is occupied by an unrelated Gradio app; the service's old systemd unit remains archived.)
- COLMAP 3.9.1 is installed but **built without CUDA** — reinforces the research doc's conclusion
  not to build the pipeline around SfM on this box.
- Chunk-key reality check: depth chunks upload with extension **`.bin`**, while
  `ArSessionRecorder.swift`'s doc-comment says `.depth`. The pipeline's chunk grouping must accept
  both (verify against real keys during the prototype).

## 1. Goal

Turn a captured session's raw R2 inputs into a **viewable, web-servable 3D asset** with full
provenance, driven by a **durable Neon job queue**, prototyped end-to-end on ONE real cell before
any batch processing.

Non-goals for this increment: batch/backfill across all cells; raw-data deletion rules; the
world-model query surface (separate spec, same date); admin review UI; auto-scaling or cloud-GPU
burst; improving ICP's known flat-ground degeneracy beyond what the ported code already does.

## 2. Approaches considered

| Approach | Verdict |
|---|---|
| **A. Port the tested fusion pipeline from `worktree-scan-capture-and-fusion-pipeline`** (depth-fused point cloud + per-session world-mirror splat, ICP cross-session alignment) | **Chosen.** ~2 000 lines of working, tested code that already parses the real capture format and already round-tripped against a live world-mirror instance. The only missing piece is running it on real data — which now exists. |
| B. COLMAP SfM + gsplat/nerfstudio training | Rejected: COLMAP here is CPU-only; gsplat's aarch64/Blackwell wheel fragility is documented (research doc, GitHub #595/#855); duplicates work A already finished. Remains the right *evaluation* path if A's quality proves insufficient on real data — record that as an explicit follow-up trigger, not a parallel build. |
| C. World-mirror only (skip depth fusion + registration) | Rejected as the *architecture*, but it IS the prototype's minimum viable output: a single-session cell needs no cross-session registration. A keeps the fused-point-cloud path because LiDAR depth is the ground-truth geometry and the activation gate needs cross-session agreement. |

## 3. Architecture

```
Ktor backend (always-on, deploy-gcc-backend-1)
  └─ ScanIndexer sweep (existing, 30 min)
       └─ NEW ReconstructionJobEnqueuer: INSERT queued job per completed,
          unreconstructed, chunk-bearing scan_session (ON CONFLICT DO NOTHING)

Neon: reconstruction_jobs  (durable queue, survives worker/box restarts)

DGX GPU worker (separate process: scripts/reconstruction/recon_worker.py)
  loop:
    lease job        UPDATE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED) RETURNING
    resolve cell     job names a session; the WORK UNIT is the session's cell
    pull raw         R2 → local scratch (manifest + capture chunks for ALL
                     completed sessions at that cell)
    fuse             chunk_io → frame_io RGBD projection → registration (ICP)
                     → fused point cloud  (ported code, unchanged math)
    splat            per-session frames → cdx-world-mirror :7027 → gaussians.ply
                     → transform into cell frame → concatenate  (ported code)
    compress         splat-transform → compressed web format (§6)
    publish          R2 reconstructed/{cellId}/{version}/{assetType}.{format}
    record           reconstruction_assets rows + scan_sessions.reconstruction_version
                     + reconstructed_at + close sibling jobs for the cell  (one tx)
```

The always-on Ktor backend stays orchestrator + I/O + light CPU (per brief); the worker is a
separate process the box runs on demand (prototype: manually; later: systemd user unit
`gcc-recon-worker.service`, started by timer or kept always-on with an idle poll).

## 4. The queue

Drizzle is authoritative — add to `site/src/db/schema.ts` AND hand-write
`CREATE … IF NOT EXISTS` SQL applied via psql (per CLAUDE.md; `drizzle-kit generate` untrusted).

```sql
CREATE TABLE IF NOT EXISTS reconstruction_jobs (
  id               serial PRIMARY KEY,
  session_id       varchar(64) NOT NULL UNIQUE,   -- job unit: one per scan session
  location_cell_id varchar(32) NOT NULL,
  status           varchar(16) NOT NULL DEFAULT 'queued',  -- queued|leased|done|failed
  attempts         integer NOT NULL DEFAULT 0,
  leased_until     timestamptz,
  last_error       text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  completed_at     timestamptz
);
CREATE INDEX IF NOT EXISTS reconstruction_jobs_claim_idx
  ON reconstruction_jobs (status, created_at);
CREATE INDEX IF NOT EXISTS reconstruction_jobs_cell_idx
  ON reconstruction_jobs (location_cell_id);
```

- **Job unit = session; work unit = cell.** A leased job reconstructs its whole cell (all
  completed chunk-bearing sessions there). On success the worker closes, in the same
  transaction, every queued/leased job whose `location_cell_id` matches — a cheap dedupe that
  keeps enqueue logic trivial and matches the brief ("a job per unreconstructed scan_session").
- **Lease, not delete**: `status='leased', leased_until = now() + 30 min, attempts += 1`.
  A crashed worker's job is re-claimable once `leased_until` passes. `attempts >= 3` → `failed`
  with `last_error`; failed jobs need manual re-queue (no automatic retry storm on a
  deterministic crash).
- Claim SQL (worker, psycopg, real transaction — the neon-http/no-transaction caveat applies to
  the Vercel side only, which never consumes this queue):

```sql
UPDATE reconstruction_jobs SET status='leased', leased_until=now()+interval '30 minutes',
       attempts=attempts+1, updated_at=now()
WHERE id = (
  SELECT id FROM reconstruction_jobs
  WHERE status='queued' OR (status='leased' AND leased_until < now())
  ORDER BY created_at
  LIMIT 1 FOR UPDATE SKIP LOCKED)
RETURNING id, session_id, location_cell_id, attempts;
```

- **Enqueuer (Ktor)**: after each indexer sweep, one INSERT…SELECT over
  `scan_sessions` WHERE `reconstruction_version IS NULL AND completed_at IS NOT NULL AND
  chunk_count > 0` (uses the existing partial index), `ON CONFLICT (session_id) DO NOTHING`.
  Sessions with no capture chunks (GPS-only walks — most of the 34) are permanently skipped:
  nothing to reconstruct. Plain JDBC in `backend/`, same pattern as `ScanIndexRepository`.

## 5. Engine (ported, not rewritten)

`scripts/reconstruction/` is copied wholesale from `worktree-scan-capture-and-fusion-pipeline`
(`git checkout <branch> -- scripts/reconstruction`), keeping its README, tests, and the two
documented limitations (Open3D compute-core segfaults → NumPy/SciPy math; ICP flat-ground
degeneracy → the ≥3-session activation gate is the safety net, single-session cells produce
assets but `activated_at` stays NULL). Deltas in this increment:

1. **`recon_worker.py`** (new) — queue loop above. DB writes via `psycopg` against
   `DATABASE_URL` directly; the branch's secret-guarded Next.js writer route
   (`/api/admin/reconstruction-assets`) is NOT ported — it existed because that session assumed
   the pipeline might run remote from the DB; this worker is co-located with credentials and a
   real Postgres driver. (If a remote/burst worker ever appears, resurrect the route then.)
2. **Chunk-extension fix** — `_group_chunks_by_index` must accept the real `.bin` depth
   extension (recorder docs say `.depth`; actual R2 keys say `.bin`).
3. **Real-data validation** — the branch README's own "next concrete steps": confirm HEVC `.mov`
   decode via PyAV on a real chunk, and empirically validate `project_rgbd_to_points`'s
   depth-buffer axis/origin assumption against the real scene (eyeball in SuperSplat :7051).
4. **Version source of truth** = `reconstruction_assets` MAX(version)+1 per (cell), computed in
   the worker's transaction (no server-side race — single worker today, `FOR UPDATE` on the max
   query if that ever changes).

## 6. Serving formats (what actually lands in R2)

Per `site/src/lib/r2.ts::reconstructedAssetKey`: `reconstructed/{cellId}/{version}/{assetType}.{format}`.

| assetType | format | producer | purpose |
|---|---|---|---|
| `pointcloud` | `ply` | fused LiDAR point cloud (voxel-downsampled) | ground-truth geometry, DGX/debug use |
| `splat` | `ply` | world-mirror gaussians, cell-frame aligned + concatenated | archival full-fidelity splat |
| `splat-web` | `spz` — fallback `compressed.ply` if the converter can't emit spz on aarch64 | `@playcanvas/splat-transform` over the aligned splat | the web/app streaming asset, target ≤ ~15 MB (decimate before compress if needed) |

Never raw multi-hundred-MB `.ply` to clients; never touch `raw/` objects (read-only input —
lifecycle rules already encode this).

`reconstruction_assets` rows: one per artifact, `source_scan_coverage_event_ids` carries the
contributing **session ids** under key `sessions` plus chunk r2_keys (the jsonb column predates
this design; storing `{"sessions": […], "chunks": […]}` is honest provenance today — resolving
actual coverage-event row ids is the follow-up the branch README already flagged).
`scan_sessions.reconstruction_version` + `reconstructed_at` are set for every session whose data
entered the accepted fusion (rejected-registration sessions stay NULL — they'll be retried when
the cell next reconstructs, and their rejection is logged in the job's `last_error` context).

## 7. Error handling

- Any exception in a job → `status='failed'` at `attempts>=3` else back to `queued` with
  `leased_until=NULL`, `last_error` = truncated traceback. The raw data is untouched; re-running
  is always safe (R2 writes are keyed by version; a re-run makes version N+1, never overwrites).
- GPU contention: world-mirror is the only GPU stage (~5 GB resident, already loaded). The worker
  checks `/health` before submitting and fails fast (requeue) if the service is down — it never
  starts/stops other GPU services itself.
- Partial publishes: R2 uploads happen before the DB transaction, so a crash between upload and
  commit leaves R2 objects at a version number with no DB row. That is harmless in itself (the
  next run computes version+1 from the DB and never overwrites), BUT the Ktor indexer also
  mirrors `reconstructed/` into `reconstruction_assets` on its sweep (backend/README), so those
  orphans WILL get rows eventually. Two writers, one table → both must be idempotent on
  `r2_key`: a unique index on `reconstruction_assets.r2_key` is added in this migration, and the
  worker inserts with `ON CONFLICT (r2_key) DO NOTHING` (verify during implementation that the
  indexer's insert survives the new unique index; make it `ON CONFLICT DO NOTHING` if not).

## 8. Testing

- Ported tests keep running (`pytest`, 31 pass + 1 xfail) in a fresh `.venv` (uv, Python 3.11 —
  Open3D's only aarch64 wheel).
- New unit tests: chunk grouping accepts `.bin`; worker's cell-close SQL; provenance payload shape.
- Queue-claim SQL exercised against the LOCAL `data-postgres` (:5432 scratch database), not Neon —
  concurrency semantics (`SKIP LOCKED`, lease expiry) are Postgres-generic.
- The prototype itself is the integration test (§9).

## 9. Prototype acceptance (deliverable 2)

Cell `464650:-721828` (two real chunk-bearing sessions):
1. Jobs enqueued by the real enqueuer path (or identical SQL run manually if the backend image
   isn't rebuilt yet — note which in the summary).
2. Worker leases, pulls raw from R2, fuses, splats, compresses.
3. `reconstructed/464650:-721828/1/…` objects exist in R2 (pointcloud.ply, splat.ply,
   splat-web.spz|ply ≤ ~15 MB).
4. `reconstruction_assets` rows present with provenance; both sessions'
   `reconstruction_version=1`, `reconstructed_at` set (if session 2's registration is rejected,
   version applies to session 1 only and the rejection is reported, not hidden).
5. Splat visually sanity-checked in SuperSplat (:7051) — screenshot in the summary.
6. No `raw/` object modified or deleted (verify by listing before/after).

## 10. Risks

| Risk | Mitigation |
|---|---|
| HEVC decode fails in PyAV on real `.mov` | H.264 fallback exists device-side; if HEVC-only, `pip install av` builds against system ffmpeg (present, aarch64) — verify first thing in the prototype |
| Depth-buffer axis/origin assumption wrong | Visible immediately as garbled geometry in SuperSplat; fix orientation in `frame_io` (the cdx splat skill documents the same class of fix) |
| ICP wrong-but-confident registration on flat ground | Known, documented; activation gate ≥3 sessions; prototype reports fitness numbers rather than trusting them |
| `splat-transform` unavailable/broken on aarch64 | fallback `compressed.ply`; format column records what was actually produced |
| world-mirror :7027 is a manually-started process (archived unit) | prototype uses it as-is; productionizing the unit (port move off 7025/7026 conflicts) is listed as follow-up, not blocking |
| Two prior worktree branches overlap (`r2-reconstruction-pipeline`, `scan-viz-and-conversion-pipeline` route collision) | Neither is merged; this design ports code from the fusion branch only and does NOT port the colliding Next.js route at all |
