# Hardening Retrospective — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Re-implement the taped-together seams the 2026-07-06 audit ranked worth fixing now that the v2 pillars are in. Owner's framing: "harden anything that could be better implemented now that we got it taped together."

**Source:** audit §B/§C/§D (full findings in round-2 ledger `.superpowers/sdd/progress.md`) + review-round backlog accumulated since.

## Global Constraints
- Gates per task: `./gradlew :shared:jvmTest :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64`; server tasks `cd site && npx tsc --noEmit`; Swift-touching or framework-export-heavy tasks add the full xcodebuild sim build.
- Branch from CURRENT origin/main (all six PRs). Merge origin/main again before the PR.
- Behavior-preserving refactors: every consolidation keeps existing tests green UNCHANGED (test edits only for moved imports/names). Anything that would change behavior: stop, report.
- No prod DB commands (journal repair is FILES ONLY — snapshots/journal metadata; verify with drizzle-kit output, never against prod).
- Package moves: mechanical, one commit per package, expect+ALL actuals move together.

### Task 1: Migration journal repair (audit D2 — every future schema change is hostage)
**Files:** `site/src/db/migrations/meta/_journal.json` + snapshots
- [ ] Reconstruct: journal ends idx13=0016 while SQL files run to 0027 (0010 missing, 0011/0012 unjournaled, 0007 name mismatch). Strategy: `npx drizzle-kit pull` against... NOT prod (no db commands) — instead: hand-append journal entries idx14..N for 0017..0027 with correct tags/whens matching filenames, and regenerate the latest snapshot from schema.ts via `npx drizzle-kit generate --custom` dry mechanics — READ drizzle-kit 0.31 docs/behavior first (node_modules present); if a snapshot cannot be produced without a DB connection, produce the journal repair + a documented runbook step for Blake to run `drizzle-kit pull` once locally. Acceptance: `npx drizzle-kit generate` on a THROWAWAY schema tweak produces a sane incremental diff (then revert the tweak + generated file). Document in the commit body exactly what future generates will do.
- [ ] Commit: `fix(hardening): repair drizzle migration journal (0017-0027)`

### Task 2: checkUnauthorized policy (audit D4 — 403s force-logout users)
**Files:** `shared/.../api/GccApiClient.kt` (~:51 checkUnauthorized + call sites), `composeApp/.../di/AppModule.kt` (~:81 SessionExpiredSignal wiring)
- [ ] Only 401 triggers the logout signal; 403 becomes a normal error (thrown with message, no token clear). Grep the site routes that deliberately return 400-instead-of-403 to dodge the old behavior (comment near GccApiClient:296 names the pattern) — LIST them in the report (reverting them to proper 403s is a follow-up, not this task). Verify the re-auth flows (account settings) still behave (they relied on 400s; unchanged).
- [ ] Commit: `fix(hardening): 403 no longer force-logs-out; logout only on 401`

### Task 3: ScanAreaCacheStore missing-table exposure (found during P1)
**Files:** `shared/.../cache/ScanAreaCacheStore.kt`, `SpotScreen.kt` (uncaught .put()), test
- [ ] Same ensureTable pattern as CaptureChunkStore (idempotent CREATE TABLE IF NOT EXISTS at init, keep-in-sync comments); wrap SpotScreen's unguarded `.put()` in try/catch + DiagnosticsLog; test: store works against a driver without Schema.create.
- [ ] Commit: `fix(hardening): scanAreaCache self-creates on upgraded installs`

### Task 4: PersistedRetryQueue<T> (audit B1 — 4 hand-rolled queues)
**Files:** new `composeApp/.../platform/PersistedRetryQueue.kt` (+ tests), migrate `ArMarkerQueue` + `EnvScanSessionUploader`'s queue + `CoverageRetractionQueue` onto it; `CoverageEventQueue` KEEPS its bespoke purge/shield logic but reuses the generic core if clean — else leave it and document (do NOT destabilize the reviewed purge machinery).
- [ ] Generic: Settings-persisted JSON list, Mutex, single-flight, `submitFn: suspend (List<T>) -> SubmitOutcome {DeliveredAll, RetryLater, DropHead}` + maxBatch/maxQueued knobs. Migrate the three simple queues; ALL existing queue tests stay green (adapt imports only); persisted-state compat: SAME Settings keys + JSON shapes as before (old queued data must load).
- [ ] Commit: `refactor(hardening): one persisted retry queue behind ArMarker/EnvSession/Retraction queues`

### Task 5: GeoMath consolidation (audit B3 — 111320 at 28 sites)
**Files:** promote shared `GeoMath` (base: BoundaryEditorGeometry's shape + ArGeoMath constants) in shared/geo; replace the 28 inline sites (audit lists them) with calls; keep ArGeoMath as the AR-frame layer delegating to it.
- [ ] Frozen-value test (111_320.0 etc. — byte-identical results; spot-check a projection round-trip per replaced site family via existing tests staying green).
- [ ] Commit: `refactor(hardening): single GeoMath for meters/degrees conversions`

### Task 6: MinimapState param object (audit B4 — 20+-param forwarding chains)
**Files:** `ScanMinimapOverlay.kt`, `CameraCapture.kt`, `SpotScanCapture.kt`, `SpotScreen.kt`, `ArRouteScreen.kt`
- [ ] `data class MinimapState(...)` bundling the cell-list/boundary/guidance cluster (leave callbacks + modifier separate); wrappers pass ONE object. Defaults preserved; both call sites migrated; compile-time equivalence (no behavior change).
- [ ] Commit: `refactor(hardening): MinimapState bundles the minimap forwarding chain`

### Task 7: SpotViewModel singleton→factory + queue singletons (audit D5)
**Files:** `AppModule.kt`, `OfflineAppModule.kt`, `SpotViewModel.kt`, `SpotScreen.kt`
- [ ] `CoverageEventQueue`/`CoverageRetractionQueue` become Koin singles injected everywhere (kills the shared-Settings-key dual-instance clobber); SpotViewModel becomes `factory` — AUDIT first: cross-session state it must NOT lose (arMarkerQueue → make it a single too; scanFinishedWithoutReport semantics; anything else its reset() protects). If factory conversion shows risk beyond the audit's notes, do the queue-singleton half only and report.
- [ ] Commit: `refactor(hardening): per-session SpotViewModel; process-wide queues`

### Task 8: server/ deletion + org moves (pending Blake's standing ask to organize)
- [ ] DELETE `server/` (unbuildable, undeployed, broken by DTO removals) + `run-server.sh` + remaining references — Blake pre-authorized cleanup direction; note prominently in PR body for final confirmation at merge.
- [ ] `composeApp platform/` → subpackages per audit C2 (ar/, coverage/, envscan/, camera/, device/, location/) — one commit per subpackage, expect+actuals together, imports mechanical.
- [ ] `site/src/lib` → auth/, geo/, sponsor/, scan/, mobile/ per audit C3 (imports via IDE-free sed + tsc verification).
- [ ] Commits: `chore(hardening): delete undeployed server module` + one per package move.

### Task 8b: Missing-endpoint resolution (owner decision 2026-07-07)
- [ ] IMPLEMENT: feed comments (POST /api/feed/[id]/comments — small table `feed_comments` via hand-written migration file, GET included), feed profile opt-in (POST /api/feed/[id]/opt-in-profile or the shape the client calls — read FeedViewModel first), admin analytics activity+daily (read-only aggregates over existing tables matching the admin tabs' parsing), admin bulk-verify (batch update, admin-auth). Match each client call shape EXACTLY (the deployed app can't change).
- [ ] REMOVE: the "already clean" spot-volunteer UI path + its client method (superseded by scan rectify; spots are being removed in P8).
- [ ] Commits: `feat(hardening): implement feed comments/opt-in + admin analytics/bulk-verify` and `chore(hardening): remove superseded already-clean volunteer path`.

### Task 9: Sweep + PR
- [ ] Merge origin/main, full gates incl. xcodebuild, /simplify 2-angle pass, PR. Body: per-task summary, the 400-instead-of-403 route list (follow-up), deferred items (file splits for SpotScreen/GccMap.ios — NEXT round; ARGeoAnchor v3; CommunityAreaData codegen; missing-endpoints product decision for Blake: feed comments/opt-in + already-clean + admin analytics/bulk-verify — implement or remove?).
