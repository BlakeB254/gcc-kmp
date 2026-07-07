# Cleanup + Urgent Hardening Round — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Remove dead code, fix two live production bugs (crash telemetry 404, broken admin transactions), and land the audit's quick wins — one reviewable PR that tidies the base before the v2 pillars build on it.

**Source of truth:** the 2026-07-06 architecture audit (in the session progress ledger `.superpowers/sdd/progress.md` + audit report). Every item below carries the audit's file:line evidence; implementers MUST re-verify each anchor before acting (lines drift).

**Architecture:** deletions + small fixes only. NO restructures (no generic queue, no AR base class, no package moves, no file splits) — those are the hardening round. Anything that turns out to require restructuring: report DONE_WITH_CONCERNS and skip it.

**Tech Stack:** Kotlin Multiplatform, Next.js/Drizzle, GitHub CI yaml.

## Global Constraints

- Verification gate for every Kotlin task: `./gradlew :composeApp:desktopTest` green AND `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` links. Site tasks: `cd site && npx tsc --noEmit` clean.
- Deletions must be grep-verified: after deleting symbol X, `grep -rn "X" composeApp/src shared/src iosApp site/src` returns nothing (or only intended survivors, listed in the report).
- Never touch `gridCell()`/`gridCell10m` constants; never run db commands; schema/journal issues are OUT OF SCOPE (hardening round).
- Navigation is a sealed class (`navigation/Screen.kt`) — no reflection/string routing exists; grep is conclusive for dead screens.
- Commit per task, prefix `chore(cleanup):` or `fix(cleanup):`.

---

### Task 1: Delete the SpotArScreen dead cluster

**Files (delete entirely):**
- `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/SpotArScreen.kt`
- `composeApp/src/commonMain/kotlin/com/gcc/app/platform/SpotArOverlay.kt` + iosMain/androidMain/desktopMain actuals
- `composeApp/src/commonMain/kotlin/com/gcc/app/platform/ObjectCoverageTracker.kt`
- `composeApp/src/commonTest/kotlin/com/gcc/app/platform/ObjectCoverageTrackerTest.kt`

- [ ] Verify zero external refs first: `grep -rn "SpotArScreen\|SpotArOverlay\|ObjectCoverageTracker" composeApp/src shared/src iosApp --include="*.kt" --include="*.swift" | grep -v "ui/screens/SpotArScreen.kt\|platform/SpotArOverlay\|ObjectCoverageTracker"` → expect empty.
- [ ] NOTE: the scan-end crash fix (commit 4022116, this branch) may have touched SpotArOverlay's stop() — deletion supersedes; if ArNativeSurface.teardown() has a SpotArOverlay conformer call site, it dies with the file.
- [ ] Delete files; run verification gate. Do NOT delete `ArSessionHealth`, `SessionPointsEstimator`, `DetectionReticleOverlay`, `GoOutsideOverlay`, `OutdoorGate` (all have other users).
- [ ] Commit: `chore(cleanup): delete dead SpotArScreen cluster (~990 lines, zero call sites)`

### Task 2: Delete orphaned GccApiClient methods + DTOs

**Files:** `shared/src/commonMain/kotlin/com/gcc/shared/api/GccApiClient.kt`, `shared/src/commonMain/kotlin/com/gcc/shared/model/AdminModels.kt` (and siblings)

- [ ] Delete these methods (audit-verified: endpoint missing in site/ AND zero composeApp call sites — re-verify BOTH conditions per method before deleting): `getRevenueByArea`, `getWorkerMetrics`, `getSponsorMetrics`, `updateWorkerRole`, `getAdminSponsors`, `getAdminJobs`, `getBountyAnalyticsStats`, `getBountyTimeseries`, `getBountyByLocation`, `getBountyLeaderboard`, `forceReassignBounty`, `getAdminDisputes`, `resolveDispute`, `getAdminServiceAreas`, `createServiceArea`, `getAdminFeedback`, `getAddressChanges`, `sponsorBlock`, `updateBlock`, `createBlock`, `getAdminBlocks` (~GccApiClient.kt:680-990).
- [ ] Do NOT delete (live call sites, missing endpoints — hardening round decides implement-vs-remove): `getAdminActivity`, `getAdminDailyStats`, `bulkWorkerAction`, `reportSpotAlreadyClean`, `reportCrash`, feed caption/comments/opt-in methods.
- [ ] Sweep DTOs: for each deleted method's request/response types, grep for other users; delete only if orphaned.
- [ ] Verification gate + commit: `chore(cleanup): remove 21 Ktor-era API methods with no production endpoint`

### Task 3: CI + build hygiene

**Files:** `.github/workflows/ci.yml`, `settings.gradle.kts`, `shared/src/commonTest/.../RouteCacheStoreTest.kt`, `ScanAreaCacheStoreTest.kt`

- [ ] Remove `./gradlew :server:build` from ci.yml (~:108-109); remove `include(":server")` from settings.gradle.kts. Leave the `server/` directory in place (archived; delete in hardening round after one more Blake confirmation).
- [ ] `git mv` the two JDBC-driver tests from `shared/src/commonTest/` to `shared/src/jvmTest/` (package path preserved). Run `./gradlew :shared:jvmTest` → green.
- [ ] Verification gate + commit: `chore(cleanup): stop building undeployed :server in CI; move JVM-only tests to jvmTest`

### Task 4: Small dead-code deletions

- [ ] `ArScanner.kt`: delete `placeAnchor` + `getCameraFovDegrees` from expect (:29,:32) + all actuals (grep confirms zero callers; only mention is a comment in ArEnvScan.kt:750 — update the comment).
- [ ] `CheckpointThumbnails.kt`: delete `CheckpointThumbnail` (:28-57); KEEP `RouteThumbnail` (used by DashboardScreen).
- [ ] `fogCellCenters` plumbing: FIRST read ScanMinimapOverlay's draw body — confirm the param is never read. Then delete the param from `ScanMinimapOverlay.kt:54`, `CameraCapture.kt:75`, `SpotScanCapture.kt:62,223`, call sites `SpotScreen.kt:804,838`, `ArRouteScreen.kt:96,462`, and the producing projection `RouteScanViewModel.kt:281-290` (`_fogCellCenters`).
- [ ] `ArRouteOverlay.kt:69`: replace private `METERS_PER_DEG_LAT` with the `ArGeoMath.kt:6` shared constant (import).
- [ ] Verification gate + commit: `chore(cleanup): dead members, fog-dots plumbing, duplicated geo constant`

### Task 5: GccMap onClick dedupe + web icon layer

- [ ] `GccMap.ios.kt`: the four byte-identical coverage-cell onClick closures (~:802,:818,:834,:852 — post-crash-fix lines will differ; anchor on `onCoverageCellTap`) → extract one private helper `fun cellIdClickHandler(onTap: ((String) -> Unit)?): (...)` returning the closure (match the exact FeaturesClickHandler type the layers expect); apply to all four. Check the route-tap closures (:688,:751) — dedupe only if byte-identical.
- [ ] `site/src/components/map/CombinedMap.tsx:760-770`: remove the `icon-image`/`icon-*` layout keys from `combined-spot-icons` (sprites were never registered; text labels already render and stay). Leave a one-line comment pointing at the audit decision.
- [ ] Verification gate (both sides) + commit: `chore(cleanup): dedupe map click handlers; drop never-registered web icon keys`

### Task 6: fix(cleanup) — crash telemetry route (prod bug: every crash report 404s)

**Files:** Create `site/src/app/api/crashes/route.ts`

- [ ] Read `site/src/app/api/admin/crashes/route.ts` (the viewer) to learn the table/columns it SELECTs (drizzle schema table for crash reports), and `GccApiClient.reportCrash` (:1131) + `CrashReporting.kt` (iosMain :88) for the exact request body the app sends.
- [ ] Implement POST: `requireMobileAuth` optional? — crashes can happen logged-out; accept EITHER auth or none (crash telemetry must not be dropped for auth reasons) but validate body shape strictly (bounded string lengths — stack traces can be huge: truncate to e.g. 64KB server-side) and insert into the same table the admin viewer reads. Return `{success: true}` matching what `reportCrash` parses.
- [ ] `cd site && npx tsc --noEmit` clean.
- [ ] Commit: `fix(cleanup): add POST /api/crashes — mobile crash reports were 404ing since launch`

### Task 7: fix(cleanup) — the two broken admin transactions (neon-http)

**Files:** `site/src/app/api/admin/workers/[id]/points/route.ts` (~:153), `site/src/app/api/admin/spots/route.ts` (~:170)

- [ ] Verify the claim first: `grep -n "db.transaction" site/src -r` and read both routes + `site/src/db/index.ts` (neon-http driver). The neon-http drizzle session throws on `db.transaction` — these endpoints 500 when reached.
- [ ] Restructure each to the codebase's established non-transactional patterns (see `delete-worker.ts` and `scan-coverage.ts`): sequential awaited statements ordered so a mid-crash leaves recoverable state (insert ledger row BEFORE bumping denormalized totals; rate-limit check as plain select). Do NOT switch drivers.
- [ ] `npx tsc --noEmit` clean. If a preview-deploy curl is feasible without prod writes, note it for the reviewer; otherwise flag "verified by code-reading only" in the report.
- [ ] Commit: `fix(cleanup): remove db.transaction calls the neon-http driver cannot execute`

### Task 8: Queue hygiene — poison-drop + leaked scope

**Files:** `composeApp/.../platform/CoverageEventQueue.kt` (CoverageRetractionQueue), `SpotScreen.kt`, `RouteScanViewModel.kt`, test `CoverageQueuePurgeTest.kt`

- [ ] Retraction poison-drop: change `CoverageRetractionQueue.submitFn` contract from `suspend (RetractCoverageRequest) -> Boolean` to returning a small enum `RetractionOutcome { DELIVERED, RETRY, DROP }`; in `flush()`, DELIVERED → remove head, RETRY → return false (as today), DROP → remove head WITHOUT delivering (log via DiagnosticsLog). Update both submitFn call sites (`RouteScanViewModel.kt:183`, `SpotScreen.kt:441`): map HTTP success → DELIVERED; 4xx except 408/429 → DROP; everything else/exceptions → RETRY (the client method throws with status in message — catch `Exception` and inspect; if status isn't recoverable from the exception, add a tiny `retractCoverageEventsWithStatus` variant OR have the existing method return the parsed response and throw only on network errors — choose the smallest change that makes 400 distinguishable, and document it).
- [ ] Add test: DROP outcome removes head and continues to next item; RETRY keeps head.
- [ ] `SpotScreen.kt:437` flushScope leak: add `DisposableEffect(Unit) { onDispose { ... } }` that (after the existing final-flush launch) schedules `flushScope.cancel()` — preserve the exit-flush: launch the final flush, then cancel the scope from WITHIN that same launch's completion (`flushScope.launch { coverageQueue.flush(); retractQueue.flush() }.invokeOnCompletion { flushScope.cancel() }`).
- [ ] `OutdoorGate.kt:7` doc contradiction: fix the header comment to match :77-79 (UNCERTAIN blocks capture — deny-by-default).
- [ ] `site/src/app/api/admin/spots/route.ts:163` hardcoded `10` → export `OBSERVER_SPOT_REWARD = 10` from `points-config.ts` and import it.
- [ ] Verification gates (both sides) + commit: `fix(cleanup): retraction poison-drop, flush scope lifecycle, gate docs, points constant`

### Task 9: Final sweep + ship

- [ ] Full gates: desktopTest, iOS link, `xcodebuild` sim build (Swift untouched this round, but Task 1 deleted Kotlin the framework exports — sim build catches header staleness; purge `composeApp/build/xcode-frameworks` first), site tsc.
- [ ] Run `/simplify`-style pass over the branch diff: any newly-orphaned imports/DTOs the deletions exposed.
- [ ] Push branch, `gh pr create` (NOT draft — but do not merge; merge is Blake's call), PR body lists: lines deleted, the two prod bugs fixed, deferred-to-hardening list (generic queue, AR base class, GeoMath consolidation, MinimapState, package moves, file splits, migration journal, checkUnauthorized policy, missing feed/already-clean/admin-analytics endpoints decision, SpotViewModel singleton→factory, ARGeoAnchor, CommunityAreaData duplication).
