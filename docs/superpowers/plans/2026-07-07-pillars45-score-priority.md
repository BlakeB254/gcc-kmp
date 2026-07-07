# Pillars 4+5 — Scan Score + Rescan Priority: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** (P4) every completed scan gets a legible A–F score with one actionable advice line; (P5) the map can answer "what needs scanning most" via a per-cell priority the Explorer lens renders. Spec: world-scanning v2 Pillars 4-5.

## Global Constraints
- Gates: `./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64`; server `cd site && npx tsc --noEmit`. No schema migration (priority is computed at read time from existing summary columns).
- Score inputs already exist client-side: `CoverageTracker.coveragePercent` + `closePercent`, `ArSessionHealth.trackingState` history (steadiness = fraction of session ticks in NORMAL — if no history exists, track a simple counter pair in the session; keep it cheap).
- Priority computed in `/api/map/coverage` at read time from `cell_coverage_summary` columns (lastScannedAt age, bestQuality, scanCount) + nearby-dirty weighting ONLY if the observations join is cheap (it is not — SKIP dirty weighting v1, document).
- All new wire fields defaulted; UI strings short and plain.

### Task 1 (P4): Scan score on the summary card
**Files:** new `composeApp/.../platform/ScanScore.kt` (+ commonTest), `SpotScreen.kt`/`ScanSessionSummaryCard.kt`, RouteScanViewModel (lap summary if it has one — check; else spot-only v1, note)
- [ ] `ScanScore.compute(coveragePercent, closePercent, steadinessFraction): ScanScoreResult(grade: Char /*A-F*/, headline: String, advice: String?)` — weights: coverage 0.45, close 0.35, steadiness 0.20; grade bands 90/80/65/50/else→A/B/C/D/F; advice = the LOWEST component's canned line ("Walk closer — most of your area was sweep-only" / "Cover more of the area next time" / "Hold the phone steadier while scanning"). Pure + tested (bands, advice selection, edge 0/100).
- [ ] Steadiness source: count NORMAL vs total tracking ticks during the session (SpotScreen already collects trackingState — accumulate two ints in the session scope, pass fractions at completion).
- [ ] Render grade + advice on ScanSessionSummaryCard (big grade letter beside the existing stats row; advice as one caption line). Thread params explicitly through any wrapper (project gotcha).
- [ ] Commit: `feat(score): A-F scan score with advice on the session summary`

### Task 2 (P5): Rescan priority + Explorer lens
**Files:** `site/src/app/api/map/coverage/route.ts`, `shared/.../model/ScanCoverage.kt` (MapCoverageCell), `MapFullScreen.kt` + `GccMap` (Explorer lens layer)
- [ ] Server: per-cell `priority: Double 0..1` = ageFactor (min(1, ageMs/7d)) × qualityBoost (grazed=1.0, close=0.6) — computed in the existing read loop; returned on each cell (defaulted 0 client-side).
- [ ] `MapCoverageCell` += `priority: Double = 0.0` (last param).
- [ ] Explorer lens: in EXPLORER map mode, a "Needs scanning" toggle chip (find Explorer mode's existing chrome in MapFullScreen) that when ON renders a priority choropleth layer (reuse the coverage-tile rendering path with a priority→opacity/color ramp — amber→red, gated on the toggle + non-empty) instead of/atop the standard coverage tint. Keep it one FillLayer; z-order beside the existing coverage layers.
- [ ] Wire-compat test (legacy cell JSON → priority 0.0).
- [ ] Commit: `feat(priority): rescan-priority per cell + Explorer needs-scanning lens`

### Task 3: Sweep + PR
- [ ] Merge origin/main first (other PRs land continuously), full gates incl. xcodebuild sim, quick /simplify, PR (merge = Blake's word). Deferred notes: dirty-observation weighting in priority; route-lap score surface; score persistence/leaderboards.
