# Pillar 9 — Block Completion Reports: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** When a block cleaning completes (two-lap scan), auto-generate a structured completion report — time to complete, GPS route taken, key areas cleaned, before/after image pairs aligned to the same issues, estimated total trash weight, counts — displayed to the worker (completion + history) and admin (review + stats). Spec: v2 Pillar 9 item 2.

## Global Constraints
- Gates: site `npx tsc --noEmit`; `./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64`; xcodebuild at sweep.
- NO prod db commands. New table via hand-written migration file 0031 (MERGE ACTION note; owner-word pattern).
- CONCURRENT P8 implementer owns: site spots route, world-observations lib internals, GccMap litter layers, web CombinedMap, ArEnvScan. DO NOT edit those. Your "areas cleaned" data source v1 = world_observations state transitions (dirty→clean/superseded within the session window + circle/boundary) — read-only queries; at sweep, merge origin/main and if P8's supersede/diagnostic fields have landed, upgrade the query to use them (isolated in one lib function so the upgrade is one-file).
- The two-lap flow's data sources (all existing): env_scan_sessions manifests (gpsTrack path, startedAt/lap timestamps), scan_hotspots (lap-1 documentation + estimatedWeightLbs + photos), world_observations (weights/photos/positions/mediaUrl), completeJobId job linkage (PR #109), Lap2WeightMath stats (client).
- Before/after pairing v1: same 10m cell + same/compatible item type; BEFORE = lap-1 hotspot/observation photo, AFTER = lap-2 confirm/rectify photo; nearest-position tiebreak. Pure function, unit-tested server-side.

### Task 1: Report generation (server)
**Files:** new `site/src/lib/block-completion-report.ts` (+ colocated unit tests if a test rig exists — else pure-function file + tsc), new `site/src/db/migrations/0031_add_block_completion_reports.sql` + schema.ts table `block_completion_reports` (id, job_id, route_id, worker_id, session_id, started_at, completed_at, duration_seconds, gps_track jsonb, areas_cleaned jsonb, image_pairs jsonb, total_estimated_weight_lbs, cleaned_estimated_weight_lbs, counts_by_type jsonb, created_at), generation hook in the job-completion path (the endpoint completeJobId hits — extend AFTER the completion write, non-fatal on failure).
- [ ] Generator assembles: duration (session timestamps), gpsTrack (manifest), areas cleaned (observation transitions clustered by cell), image pairs (pairing function), weights (sum identified vs cleaned), counts by type/severity. Non-fatal: report failure never fails job completion (log + admin can regenerate).
- [ ] `GET /api/jobs/[id]/completion-report` (worker: own jobs; admin: any) + `POST /api/admin/reports/[id]/regenerate`.
- [ ] Commit: `feat(reports): block completion report generation + endpoints`

### Task 2: Worker display (mobile)
**Files:** shared DTO `BlockCompletionReport.kt`, GccApiClient method, new `CompletionReportCard.kt` composable, RouteScanScreen completion state + job history entry point (read current nav — completion panel from #109)
- [ ] After job-completing scan submission: fetch + render the report (duration, mini route path, weight cleaned/identified, before/after pairs as photo rows, counts). Graceful "report generating…" fallback if fetch 404s (generation is async-ish).
- [ ] Commit: `feat(reports): worker completion report view`

### Task 3: Admin display (web)
**Files:** new `site/src/app/admin/reports/page.tsx` (list + detail), link from the jobs/admin nav
- [ ] List completed-block reports (date, worker, duration, weight, thumbnail pair count); detail view renders the full report incl. GPS track on a small map (existing map components) + image pairs side-by-side. Admin-auth like sibling pages.
- [ ] Commit: `feat(reports): admin completion reports review + stats`

### Task 4: Sweep + PR
- [ ] Merge origin/main (P8 may have landed — upgrade the areas-cleaned query to supersede/diagnostic fields if present; resolve any RouteScanScreen overlap keeping both), full gates + xcodebuild, /simplify pass, push, `gh pr create` (NOT draft; body: MERGE ACTION migration 0031, data-source table, pairing algorithm notes, deferred: DGX best-frame extraction upgrade path per spec addendum).
