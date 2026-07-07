# Pillar 2 — Live Map Delta Polling: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Other users see fresh scan coverage/observations within ~30-50s without navigation tricks. Write side is already near-live (30s event flush); this adds the read side. Spec: world-scanning v2 Pillar 2.

## Global Constraints
- Vercel-Hobby-safe: polling only (NO SSE/WebSocket). Poll cadence 20s, foreground-only, paused when the map screen isn't visible.
- Gates: server `cd site && npx tsc --noEmit`; client `./gradlew :composeApp:desktopTest :composeApp:linkDebugFrameworkIosSimulatorArm64`.
- Wire compat: new query params optional; response shapes unchanged (full-row cells/observations — the delta is WHICH rows return, not their shape). New client fields defaulted.
- Merge semantics client-side: upsert by key (coverage: locationCellId; observations: id), never wholesale replace (a delta response must not erase the map).
- Known deferred item this plan CLOSES PARTLY: "map refresh relies on nav recreation" (audit D10) — the poll replaces that reliance on the map screen; other screens unchanged.

### Task 1: Server delta params
**Files:** `site/src/app/api/map/coverage/route.ts`, `site/src/app/api/map/observations/route.ts` (find exact filename)
- [ ] Both GETs accept optional `since` (ISO string): coverage filters `lastScannedAt >= since` (post-bbox filter, same in-memory path it uses today); observations filters its recency column (read the route to find it — capturedAt/createdAt; use what indexes support). Validate: unparseable since → ignore param (full response), never 4xx (old/broken clients must keep working).
- [ ] Response unchanged in shape. tsc clean. Commit: `feat(live-map): since-param deltas on coverage + observations`

### Task 2: Client poll + merge
**Files:** `composeApp/.../ui/screens/MapFullScreen.kt`, `shared/.../api/GccApiClient.kt` (add optional since args to getMapCoverage/getMapObservations)
- [ ] `getMapCoverage(bbox, since: String? = null)` / observations equivalent — parameter appended, defaulted (no call-site churn).
- [ ] MapFullScreen: a `LaunchedEffect` poll loop — while the screen is composed: delay 20s, fetch deltas with `since = lastFetchIso` (server clock authority: use the max lastScannedAt/capturedAt seen in responses, NOT device clock), merge upsert-by-key into the existing state lists, update lastFetchIso. First load stays the existing full fetch. Errors: log + keep polling (no user-facing noise). The poll must not fight the existing initial-load effect — read how coverage/observation state is held today and merge into the same state vars.
- [ ] Scanning user's own map: SpotScreen already shows session state live; NO change there (out of scope).
- [ ] Test: pure merge function extracted (`mergeCoverageCells(existing, delta)`, `mergeObservations(existing, delta)`) in a map util file + commonTest (upsert replaces matching key, keeps others, handles empty delta).
- [ ] Gates. Commit: `feat(live-map): 20s foreground delta polling with upsert merge`

### Task 3: Sweep + PR
- [ ] Full gates incl. xcodebuild sim; quick /simplify pass; PR (merge = Blake's word). Body: cadence/cost note (2 tiny requests per 20s per open map; Hobby-plan headroom), deferred (push/SSE, per-user channels, observation deletes-don't-propagate note — resolved rows only vanish on full refetch; document).
