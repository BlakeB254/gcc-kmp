# GCC Neon Operational Efficiency Design

**Date:** 2026-08-08  
**Status:** Approved for implementation planning  
**Repository:** `BlakeB254/gcc`

## Objective

Reduce Global Community Cleaners' Neon compute cost without weakening the operational behavior shared by the website, iOS app, and forthcoming Android app. The target is to reduce projected Neon compute cost from roughly $74 per month to $15–$25 per month while preserving scan, assignment, route-offer, payout, upload, and live-map correctness.

## Current Baseline

The August 2026 Neon consumption audit found:

- Project: `Global Community Cleaners`
- Neon project ID: `icy-sun-66022287`
- Plan: Launch
- Primary compute: autoscaling `1–4 CU`
- Scale-to-zero timeout: 300 seconds
- Month-to-date compute through 2026-08-08: approximately 181 CU-hours
- Month-to-date compute cost at $0.106/CU-hour: approximately $19.18
- Current monthly compute projection: approximately $74
- Storage: approximately 89 MB; storage is not a material cost driver
- Public network transfer: approximately 155 MB month-to-date; egress is not a material cost driver
- Read replicas: none

The production topology is one shared system:

```text
Website ─────┐
iOS app ─────┼──> Next.js API on Vercel ──> Neon PostgreSQL
Android app ─┘

Render scheduler ──> authenticated Next.js cron routes ──> Neon PostgreSQL
```

The Next.js API and Drizzle schema remain authoritative. The unused Ktor implementation is outside this optimization effort.

## Design Principles

1. Neon remains the canonical cloud system of record for all three clients.
2. Writes, financial state, assignments, scans, uploads, route offers, and active work are never served from a shared public cache.
3. Display-only reads are cacheable only when their maximum staleness is explicit and safe.
4. A cache invalidation failure cannot create unbounded staleness; every cache policy has a finite expiry.
5. iOS and Android must observe the same resource versions and pagination semantics as the website.
6. Mobile polling must be bound to screen and application lifecycle, not merely object lifetime.
7. Compute changes are canaried and reversible without a database migration.
8. Read replicas are deferred until measurements show primary read saturation after caching.

## Data Classification and Cache Contract

### Public, slow-changing data

Examples include articles, sponsor-wall presentation data, public impact summaries, public support-wall data, affected-area summaries, and scan configuration.

- Cache location: Vercel/CDN response cache and Next.js server cache where supported
- Browser policy: `public`
- Freshness: 5 minutes unless an endpoint already has a stricter product requirement
- Stale-while-revalidate: up to 30 minutes
- Invalidation: resource-specific tag from the corresponding mutation or administrative publishing flow
- Failure behavior: serve bounded stale content while one revalidation attempt runs

### Public, location-sensitive data

Examples include map observations, coverage summaries, spots, building footprints, and nearby civic data.

- Initial map payloads may use short shared caching keyed by normalized viewport tile and server-side cursor.
- Delta endpoints remain authoritative and accept a server-derived `since` cursor.
- Cache keys include every scope that changes the result: viewport/tile, community, visibility state, and cursor.
- Responses expose canonical `updatedAt` or equivalent server timestamps so web, iOS, and Android can merge deterministically.
- No client may advance a delta cursor from its local clock.

### Private or user-specific reads

Examples include worker dashboards, jobs, route offers, wallet balances, payout state, verification documents, and sponsor receipts.

- Response policy: `private, no-store`
- Shared caching: prohibited
- Short request-local memoization is allowed only within one server request and one authenticated identity.

### Transactional and operational writes

Examples include scan creation/completion, hotspot updates, route offer acceptance, assignments, payouts, evidence, uploads, and administrative state changes.

- Always execute against the Neon primary.
- Preserve existing atomicity, leases, idempotency, and authorization behavior.
- Successful writes invalidate only related public read tags.
- Failed invalidation is logged and retried where practical; finite cache expiry remains the correctness backstop.

## Compute Configuration

The first canary changes the production primary from `1–4 CU` to `0.25–1 CU`, retaining the 300-second scale-to-zero timeout.

The canary runs for 48 hours before application caching changes. It is accepted when:

- no material increase occurs in 5xx responses or transaction failures;
- critical write p95 remains within 20% of baseline;
- autoscaling does not remain pinned at 1 CU for more than 30 continuous minutes under ordinary load; and
- scan, route-offer, payout, and upload flows pass smoke tests on the website and iOS client.

The maximum may be raised from 1 CU only with saturation evidence. The minimum returns to 1 CU immediately if critical operational latency or error thresholds fail.

## Polling and Client Lifecycle

### Website

- Dashboard and sponsor polling runs only while the document is visible.
- Multiple components requesting the same resource share one in-flight request.
- Polling stops when a resource reaches a terminal state.
- Unchanged responses trigger bounded exponential backoff; an actual state change resets the normal cadence.
- Navigation and component unmount cancel timers and in-flight requests.

### iOS and Android

- Shared Kotlin code owns server polling policies so platforms do not drift.
- Map delta polling runs only while the map screen is composed and the app is foregrounded.
- Affected-area polling stops when its owning screen/view model is inactive.
- Active scan-local timers and native camera/AR polling are not classified as Neon polling unless they call the shared API.
- Background/resume behavior performs one cursor-based catch-up request rather than replaying missed intervals.
- iOS and Android use server timestamps and the same merge functions for delta responses.

The existing 20-second live-map delta cadence may remain during a visible, foreground map session. Optimization comes from lifecycle suspension, delta queries, viewport scoping, and request sharing—not from making active field work feel stale.

## Scheduled Work

The Render route-release scheduler retains its 15-minute cadence because offer windows and worker notification timing are operational requirements.

Each tick must:

1. acquire the existing lease;
2. perform one indexed due-work lookup;
3. return immediately when no batch or notification is due;
4. emit whether the tick was empty, lease-skipped, or productive;
5. report duration and affected-row counts without logging private records.

Building-footprint ingestion remains hourly. Its database work is skipped when all target tiles are fresh. The redundant daily Vercel backstop may remain until the Render schedule has a documented reliability record, after which removal is a separate operational decision.

## Observability

Measurements are aggregated and contain no connection strings, authentication tokens, coordinates tied to a person, financial details, or uploaded evidence.

Required measurements:

- Neon CU-hours by project and hour
- compute start/suspend frequency
- route request count, p50, p95, and error rate
- database-call count per API route
- cache hit, miss, stale-hit, and revalidation counts
- cron empty/productive/lease-skipped counts and duration
- mobile poll count by endpoint and application lifecycle state
- critical-flow success rates for scans, assignments, offers, payouts, and uploads

The Neon Consumption API is used for billing measurements because it does not wake suspended computes.

## Rollout

1. Capture seven days of hourly baseline consumption and route behavior.
2. Canary `0.25–1 CU` for 48 hours with existing application behavior.
3. Introduce cache-policy tests and explicit response policies before adding shared caching.
4. Cache public slow-changing routes and wire mutation invalidation one resource family at a time.
5. Add web visibility-aware request sharing and backoff.
6. Add shared Kotlin lifecycle-aware polling for iOS and Android.
7. Reduce empty cron work while retaining cadence and lease semantics.
8. Compare seven post-change days to baseline.
9. Keep, revise, or roll back each independently deployable change based on its acceptance gate.

## Failure and Rollback

- Compute rollback: restore the previous `1–4 CU` limits.
- Cache rollback: disable the affected cache policy or reduce its TTL without reverting database changes.
- Client rollback: disable lifecycle/backoff behavior through a bounded configuration flag only if it prevents current work from refreshing.
- Cron rollback: restore the prior due-work path while retaining the existing lease.
- No step deletes data, changes canonical ownership, introduces a replica, or migrates the database.

## Verification

Automated coverage must prove:

- public cache keys include all relevant scope dimensions;
- private responses never carry public cache directives;
- each cached resource has a mutation invalidation test;
- expired cache entries cannot remain indefinitely stale;
- web polling pauses when hidden and stops at terminal states;
- shared Kotlin polling starts and stops with foreground/screen lifecycle;
- iOS and Android clients merge the same delta sequence identically;
- cron empty ticks execute the bounded indexed lookup only;
- critical writes remain uncached and preserve existing idempotency behavior.

Browser smoke tests cover the public site and authenticated web flows. Shared Kotlin tests cover common behavior, with platform smoke tests on iOS and Android before release.

## Acceptance Criteria

- At least 60% lower seven-day CU-hour consumption relative to baseline
- Projected GCC compute cost between $15 and $25 per month under comparable traffic
- No cross-user or cross-role cache leakage
- No material increase in critical transaction failures
- Critical write p95 no more than 20% slower than baseline
- Public cached p95 no worse than baseline
- Public data remains within its declared freshness window
- Website, iOS, and Android retain compatible API and merge semantics
- Rollback procedures are executed successfully in a non-production environment or safe canary

## Non-Goals

- Migrating GCC away from Neon
- Deploying the Ktor backend
- Adding read replicas
- Changing product behavior, offer timing, financial rules, scan evidence, or data ownership
- Caching private or transactional responses

