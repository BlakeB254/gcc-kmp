# GCC Neon Operational Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce Global Community Cleaners' Neon compute demand and monthly cost without weakening correctness or the shared API contract used by the website, iOS app, and forthcoming Android app.

**Architecture:** Keep Neon Postgres as the authoritative writable store and the `site/` Next.js application as the production API. Reduce repeated reads with bounded, tagged server caches; preserve authoritative cursor-based map deltas; stop mobile polling when the app is not active; make scheduled work query only due rows; and canary a smaller autoscaling envelope before considering any replica. Every mutation invalidates the smallest affected cache family, and every private or live response remains uncached.

**Tech Stack:** Next.js 16, TypeScript, Drizzle ORM, Neon serverless Postgres, Kotlin Multiplatform/Compose, Node test runner through `tsx`, Gradle, Vercel, Render scheduler.

## Global Constraints

- Treat `site/` as the only production web/API implementation. Do not modify or deploy the legacy Ktor `backend/` as part of this work.
- Preserve `https://api.globalcommunitycleaners.org` response shapes for web, iOS, and Android. Additive response metadata is allowed; removals, renames, and type changes are not.
- Keep writes on the primary Neon endpoint. Do not add a read replica until the seven-day evidence gate demonstrates that caching, polling, and query work were insufficient.
- Never cache authenticated, worker, sponsor-admin, payment, webhook, or mutation responses.
- Cache keys must include every scope that changes the result. Tenant/service-area/user boundaries must never rely on a caller-side filter.
- Do not commit database contents, Neon credentials, `.env` files, exports, or backups to Git. Migrations, schemas, seed programs, and non-sensitive fixtures belong in Git.
- Do not touch the pre-existing untracked `backend/deploy/.env.bak-20260724-premigration` or `site/.env.local.bak-20260724-premigration` files.
- Neon configuration changes and production deployments require an explicit operator confirmation at execution time. Record before/after metrics and retain the rollback values.
- Use test-driven development for every code task: add a failing focused test, prove the failure, implement the smallest change, prove the focused test, then run the affected suite.

---

## Task 1: Establish the measurement contract and safe response policies

**Files:**

- Create: `docs/runbooks/neon-operational-efficiency.md`
- Create: `site/src/lib/http/cache-policy.ts`
- Create: `site/src/lib/http/cache-policy.test.ts`
- Modify: `site/package.json`

**Interfaces:**

```ts
export type PublicCachePolicy = {
  browserMaxAgeSeconds: number;
  sharedMaxAgeSeconds: number;
  staleWhileRevalidateSeconds: number;
};

export function publicCacheControl(policy: PublicCachePolicy): string;
export const PRIVATE_NO_STORE = "private, no-store";
```

- [ ] Record the current Neon baseline in the runbook: project `icy-sun-66022287`, branch `br-lively-recipe-aefttxnd`, endpoint `ep-long-feather-aebr43g4`, 1–4 CU, 300-second suspend, approximately 181 CU-hours and $19.18 month-to-date at planning time, and a projected baseline near $74/month. Label these as dated observations, not guarantees.
- [ ] Define the seven-day scorecard in the runbook: Neon CU-hours/day, active compute hours/day, data-transfer/egress, API p50/p95 latency, error rate, cron duration/rows scanned/rows processed, affected-area request rate, and mobile polling request rate.
- [ ] Define release gates: no API contract regression; no increase greater than 10% in p95 for uncached writes; no stale safety-critical map delta beyond its documented cursor flow; no missed scheduled release; and target GCC Neon cost of $15–25/month under comparable traffic.
- [ ] Add a `test:unit` script that runs `tsx --test`, while retaining the ability to invoke a single file exactly.
- [ ] Write failing tests for exact public and private `Cache-Control` strings, including rejection of negative or non-integer durations.
- [ ] Run `cd site && DATABASE_URL=postgres://test:test@localhost/test npx tsx --test src/lib/http/cache-policy.test.ts` and confirm the missing module or assertion failure.
- [ ] Implement the typed policy formatter with validation and `PRIVATE_NO_STORE`.
- [ ] Re-run the focused test and confirm it passes.
- [ ] Run `cd site && npm run lint`.
- [ ] Commit only this task: `git add docs/runbooks/neon-operational-efficiency.md site/src/lib/http/cache-policy.ts site/src/lib/http/cache-policy.test.ts site/package.json package-lock.json && git commit -m "ops: define GCC Neon efficiency baseline"`.

## Task 2: Cache slow-changing public summaries behind tagged loaders

**Files:**

- Create: `site/src/lib/public-data/cache-tags.ts`
- Create: `site/src/lib/public-data/cached-loaders.ts`
- Create: `site/src/lib/public-data/cached-loaders.test.ts`
- Modify: `site/src/app/api/public/stats/route.ts`
- Modify: `site/src/app/api/public/impact-stats/route.ts`
- Modify: `site/src/app/api/public/sponsor-wall/route.ts`
- Modify: `site/src/app/api/public/support-wall/route.ts`
- Modify: `site/src/app/api/articles/route.ts`

**Interfaces:**

```ts
export const GCC_CACHE_TAGS = {
  publicStats: "gcc:public-stats",
  impactStats: "gcc:impact-stats",
  sponsorWall: "gcc:sponsor-wall",
  supportWall: "gcc:support-wall",
} as const;

export async function loadPublicStats(): Promise<PublicStatsResponse>;
export async function loadImpactStats(): Promise<ImpactStatsResponse>;
export async function loadSponsorWall(): Promise<SponsorWallResponse>;
export async function loadSupportWall(input: { view: string; limit: number }): Promise<SupportWallResponse>;
```

- [ ] Extract the existing route query logic into uncached loader functions without changing serialized response types.
- [ ] Write failing tests with injected query functions proving that identical public scopes compute once inside the configured window, while different `support-wall` `view` and `limit` values never share a result.
- [ ] Run the focused test and confirm duplicate calls are still observed.
- [ ] Wrap loaders with Next's server cache primitive using finite TTLs and tags: 5 minutes for public/impact stats and 30 minutes for sponsor/support wall.
- [ ] Make `articles` static or long-cacheable because its source is repository content rather than Neon; do not retain `force-dynamic`.
- [ ] Set explicit response policy: browser max-age 60 seconds, shared max-age matching loader TTL, and stale-while-revalidate equal to the TTL.
- [ ] Preserve query validation, response bodies, status codes, and error handling in all routes.
- [ ] Re-run the focused test, then run the existing public API tests discovered by `rg -l "public/stats|impact-stats|sponsor-wall|support-wall" site/src site/tests`.
- [ ] Run `cd site && npm run build` to catch Next cache boundary and serialization errors.
- [ ] Commit: `git add site/src/lib/public-data site/src/app/api/public site/src/app/api/articles/route.ts && git commit -m "perf: cache GCC public summary reads"`.

## Task 3: Add scope-safe affected-area caching without weakening deltas

**Files:**

- Create: `site/src/lib/affected-areas/cached-initial-load.ts`
- Create: `site/src/lib/affected-areas/cached-initial-load.test.ts`
- Modify: `site/src/app/api/affected-areas/route.ts`
- Modify: `composeApp/src/commonTest/kotlin/com/gcc/app/MapDeltaMergeTest.kt`

**Interfaces:**

```ts
export type AffectedAreaScope = {
  serviceAreaId: string | null;
  minStatus: number | null;
};

export function affectedAreaCacheKey(scope: AffectedAreaScope): readonly string[];
export async function loadInitialAffectedAreas(scope: AffectedAreaScope): Promise<AffectedAreasGeoJson>;
```

- [ ] Document the existing initial-load and delta-query parameters directly above the route parser. Identify the existing cursor/timestamp field consumed by mobile and preserve it exactly.
- [ ] Write failing TypeScript tests proving that service area, minimum status, and initial-versus-delta mode produce distinct cache identities.
- [ ] Write or extend Kotlin contract tests proving an additive server timestamp/cursor is accepted by the shared mobile parser and that later deltas still win during merge.
- [ ] Run both focused tests and confirm the new expectations fail.
- [ ] Cache only initial full-map reads for 30–60 seconds. Keep cursor/delta requests authoritative and uncached.
- [ ] Return `private, no-store` if a request becomes user-specific; otherwise return a short public shared-cache header that varies by the complete server-side key.
- [ ] Ensure the cache stores the database result/GeoJSON value, not a mutable `Response` object.
- [ ] Run `cd site && DATABASE_URL=postgres://test:test@localhost/test npx tsx --test src/lib/affected-areas/cached-initial-load.test.ts`.
- [ ] Run `./gradlew :composeApp:desktopTest --tests '*MapDeltaMergeTest*'` from the repository root.
- [ ] Run `cd site && npm run build`.
- [ ] Commit: `git add site/src/lib/affected-areas site/src/app/api/affected-areas/route.ts composeApp/src/commonTest && git commit -m "perf: bound GCC affected-area reads"`.

## Task 4: Invalidate public cache families after authoritative writes

**Files:**

- Create: `site/src/lib/public-data/invalidation.ts`
- Create: `site/src/lib/public-data/invalidation.test.ts`
- Modify: `site/src/app/api/stripe/webhooks/route.ts`
- Modify: `site/src/app/api/admin/workers/[id]/verify/route.ts`
- Modify: `site/src/app/api/jobs/[id]/complete/route.ts`
- Modify: `site/src/app/api/community-reports/route.ts`
- Modify: `site/src/app/api/community-reports/[id]/thank/route.ts`
- Modify: `site/src/app/api/waitlist/join/route.ts`
- Modify: `site/src/app/api/waitlist/profile/route.ts`
- Modify: `site/src/app/api/waitlist/update/route.ts`
- Modify: only the sponsor and block-admin mutation routes found with `rg -l "(insert|update|delete)" site/src/app/api/sponsors site/src/app/api/admin` that affect cached families

**Interfaces:**

```ts
export type PublicDataChange =
  | "cleanup-impact"
  | "worker-verification"
  | "sponsorship"
  | "supporter"
  | "affected-area";

export function tagsForPublicDataChange(change: PublicDataChange): readonly string[];
export function invalidatePublicData(change: PublicDataChange): void;
```

- [ ] Write a route-to-cache-family matrix in the runbook before editing routes.
- [ ] Write failing unit tests proving each change kind maps only to the required tags and cannot accept an arbitrary tag string.
- [ ] Implement one centralized invalidator using `revalidateTag` and typed change kinds.
- [ ] Call invalidation only after a successful committed mutation. Never invalidate on validation errors, rejected payments, or rolled-back transactions.
- [ ] Add route-level tests for at least job completion, worker verification, sponsor webhook, and affected-area mutation proving success invalidates and failure does not.
- [ ] Run the focused tests and the affected route tests.
- [ ] Run `cd site && npm run build`.
- [ ] Commit: `git add site/src/lib/public-data site/src/app/api && git commit -m "perf: invalidate GCC public caches on writes"`.

## Task 5: Gate mobile polling on lifecycle and preserve one shared API contract

**Files:**

- Create: `composeApp/src/commonMain/kotlin/com/gcc/app/polling/PollingGate.kt`
- Create: `composeApp/src/commonTest/kotlin/com/gcc/app/polling/PollingGateTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/ui/screens/MapFullScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/app/viewmodel/AffectedBlocksViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/api/AffectedAreasRepository.kt`

**Interfaces:**

```kotlin
enum class AppVisibility { Foreground, Background }

class PollingGate(
    private val baseIntervalMillis: Long,
    private val maxIntervalMillis: Long,
) {
    fun nextDelayMillis(visibility: AppVisibility, consecutiveUnchanged: Int): Long?
}
```

- [ ] Write failing common tests for: no polling in background; immediate refresh on foreground transition; 20–30 second active base cadence; capped backoff after unchanged responses; reset after a changed response; and cancellation preventing duplicate jobs.
- [ ] Run `./gradlew :composeApp:desktopTest --tests '*PollingGateTest*'` and confirm failure.
- [ ] Implement the platform-neutral gate in common code so iOS and Android receive identical behavior.
- [ ] Integrate it into `MapFullScreen` and `AffectedBlocksViewModel`; enforce one active polling job per screen/view model and retain server-provided cursors.
- [ ] Update repository polling to accept cancellation/lifecycle state instead of owning an unstoppable `pollForever` loop. Do not create a second timer around the existing loop.
- [ ] Preserve manual refresh and perform one catch-up delta fetch on foreground entry.
- [ ] Add tests proving stop/start does not duplicate requests and the latest server cursor is reused.
- [ ] Run `./gradlew :composeApp:desktopTest` and `./gradlew :composeApp:allTests`.
- [ ] Commit: `git add composeApp/src/commonMain composeApp/src/commonTest shared/src/commonMain && git commit -m "perf: suspend GCC mobile polling offscreen"`.

## Task 6: Make route-release cron work proportional to due rows

**Files:**

- Modify: `site/src/lib/route-queue.ts`
- Create or modify: the focused route-queue test located with `rg -l "processDueBatches" site`
- Modify: `site/src/app/api/cron/route-releases/route.ts`
- Create: `site/src/lib/cron/metrics.ts`
- Create: `site/src/lib/cron/metrics.test.ts`

**Interfaces:**

```ts
export type RouteReleaseMetrics = {
  examined: number;
  processed: number;
  released: number;
  durationMs: number;
};

export function logRouteReleaseMetrics(metrics: RouteReleaseMetrics): void;
```

- [ ] Write failing query-contract tests proving `processDueBatches(now)` excludes future `nextWaveAt` rows while including scheduled/active rows whose first or next wave is due.
- [ ] Verify the existing `idx_route_release_batches_status_next_wave` index in `site/src/db/migrations/0018_add_route_release_queue.sql`; do not add a duplicate index.
- [ ] Change the Drizzle predicate to select only due work and cap each invocation at a documented batch size. Preserve lease behavior and idempotency.
- [ ] Add structured metrics to the cron response/log for examined, processed, released, duration, and lease outcome. Do not log recipient PII or secrets.
- [ ] Add tests for no-due-work, due-work, lease-not-acquired, and bounded-batch cases.
- [ ] Run the focused route queue and cron tests.
- [ ] Run `cd site && npm run build`.
- [ ] Commit: `git add site/src/lib/route-queue.ts site/src/lib/route-queue*test* site/src/lib/cron site/src/app/api/cron/route-releases/route.ts && git commit -m "perf: bound GCC route release cron work"`.

## Task 7: Audit indexes with production-shaped query plans

**Files:**

- Create only if evidence requires it: `site/src/db/migrations/0041_neon_efficiency_indexes.sql`
- Modify: `docs/runbooks/neon-operational-efficiency.md`

- [ ] Capture `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for the exact affected-area initial query, affected-area delta query, due route batches, due offer notifications, and the two most expensive public aggregates against a sanitized production-like branch.
- [ ] Record plan shape, rows, execution time, buffers, and existing index used. Do not paste customer data or credentials into the runbook.
- [ ] Add an index only when the plan demonstrates a sequential scan or sort that is material at current cardinality. Prefer partial/compound indexes aligned to the full predicate.
- [ ] If adding an index, first add a failing schema-contract test that checks the required columns, order, and predicate; then create an additive migration using `CREATE INDEX CONCURRENTLY` where the migration runner permits it.
- [ ] Re-run each plan and record the before/after result. Remove any index that does not materially improve the measured query.
- [ ] Run `cd site && npm run build` and the schema/migration tests.
- [ ] Commit the runbook evidence and only evidence-backed migrations: `git add docs/runbooks/neon-operational-efficiency.md site/src/db/migrations && git commit -m "db: tune measured GCC read paths"`.

## Task 8: Canary the smaller Neon compute envelope

**Files:**

- Modify after the observation window: `docs/runbooks/neon-operational-efficiency.md`

- [ ] Before any setting change, export the endpoint configuration metadata (not credentials or data) into the runbook: endpoint ID, min/max CU, suspend timeout, timestamp, and operator.
- [ ] Obtain explicit operator confirmation for the production configuration mutation.
- [ ] Change only endpoint `ep-long-feather-aebr43g4` from 1–4 CU to 0.25–1 CU; retain the 300-second suspend timeout. Do not modify branches, databases, passwords, or connection strings.
- [ ] Observe for 48 hours across website traffic, iOS traffic, scheduled jobs, and an Android-compatible API smoke test.
- [ ] Check at least every four hours during active periods: p95 API latency, 5xx rate, connection errors, compute saturation, cold-start latency, cron completion, and mobile map freshness.
- [ ] Roll back immediately to 1–4 CU if p95 regresses by more than 20% for 15 minutes, error rate exceeds 1%, compute remains saturated for 10 minutes, a cron misses its release window, or mobile deltas violate the freshness gate.
- [ ] Record the exact result and rollback decision in the runbook; never record a Neon URI or password.
- [ ] Commit the observation record: `git add docs/runbooks/neon-operational-efficiency.md && git commit -m "ops: record GCC Neon compute canary"`.

## Task 9: Verify all clients, deploy, and make the seven-day decision

**Files:**

- Modify: `docs/runbooks/neon-operational-efficiency.md`

- [ ] Run `cd site && npm run lint`.
- [ ] Run `cd site && npm run test:unit`.
- [ ] Run `cd site && npm run build`.
- [ ] Run `./gradlew :composeApp:desktopTest` and `./gradlew :composeApp:allTests`.
- [ ] Execute contract smoke tests against the deployment for website, iOS user agent, and Android user agent: public stats, initial affected areas, delta affected areas, and an authenticated no-store endpoint. Confirm identical JSON contracts and correct cache headers.
- [ ] Verify cron invocations remain single-owner through the existing lease and report bounded work metrics.
- [ ] Deploy through the repository's normal reviewed pipeline. Do not hand-edit the production database.
- [ ] Compare seven complete days against the baseline using traffic-normalized CU-hours per 1,000 API requests and request counts by route/client.
- [ ] Mark one decision in the runbook: keep settings; adjust cache/poll intervals; roll back compute; or investigate a replica. A replica investigation is allowed only if primary CPU/read I/O remains the dominant cost after this plan and sustained read traffic justifies always-on replica compute.
- [ ] Confirm the projected monthly GCC Neon cost is within $15–25, or document the measured reason it is not.
- [ ] Run `git diff --check` and `git status --short`; verify no `.env`, backup, export, or database data file is staged.
- [ ] Commit the final evidence: `git add docs/runbooks/neon-operational-efficiency.md && git commit -m "ops: close GCC Neon efficiency rollout"`.
