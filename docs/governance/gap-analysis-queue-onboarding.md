# Gap Analysis — Community Queue / "Load Next in Line" Onboarding

**Decision owner:** Blake · **Status:** ✅ RESOLVED — Option B built & deployed to prod 2026-06-24 · **Audited:** 2026-06-24

> **UPDATE 2026-06-24:** Option B was chosen and **shipped to production**. The queue endpoints are live on `api.globalcommunitycleaners.org`, validated end-to-end (create → wave → ordered offer → accept → claimed job) with full cleanup, and a Vercel Cron advances waves. The analysis below is retained for context. Remaining before a full 10-block run: onboard more verified North Lawndale workers (only **5 eligible** today vs 10 blocks/session — the engine leaves extra slots open) and confirm the admin scan pass/fail override. Signup-ranking backfill is **not** needed (all 5 eligible workers already have rankings).

## The gap

You want: *select verified users by community in signup order, and onboard the next N as N blocks are scheduled* (two upcoming sessions of 10 blocks), plus admin "load next in line" and manual route assignment.

What exists today:
- **Production (Next.js API):** **direct admin assignment** (`POST /api/admin/routes/{id}/assign`, with `isTest`) and **worker self-claim** (`POST /api/jobs/claim`). **No queue, no batch release, no ordered offers, no waves.** The DB tables for it exist (migration `0018`) but have **no endpoints**.
- **Ktor branch (`server/`, not deployed):** a full `RouteQueueService` — community/zip eligibility, signup-rank ordering, carryover, timed waves, offer windows. **Not in production.**
- **Mobile app:** already has the client UI/calls for route offers (`/api/workers/me/route-offers`, accept/decline) and admin batch release (`/api/admin/route-releases`) — these **404 against prod** because the endpoints aren't there.

So the *queue brain* is written (Ktor) and the *queue UI* is written (app), but the *deployed backend* doesn't expose it.

## Options to run the two 10-block sessions

### Option A — Manual ordered onboarding on current prod (fastest, no code)
Use what's live: an admin pulls the verified North Lawndale workers in signup order (the data exists — `signupRankings*`, `verificationStatus`, `addressZip`) and **direct-assigns** each of the 10 routes to the next 10 in line via the existing assign endpoint/admin UI.
- **Pros:** works today, zero new code, full control, fine for a pilot of 2 sessions.
- **Cons:** manual; no automated offer/accept/decline/carryover; admin does the ordering by hand.
- **Effort:** hours. **Recommended for the immediate two sessions.**

### Option B — Port the queue endpoints into the Next.js API (medium)
Implement the missing endpoints in `site/src/app/api` against the existing `0018` tables, porting the logic from `RouteQueueService.kt`: `POST/GET /api/admin/route-releases`, `POST /api/admin/route-releases/{id}/process`, `GET /api/workers/me/route-offers`, `accept`, `decline`, plus a Vercel Cron for wave processing.
- **Pros:** keeps the single Next.js backend; unlocks the app's existing offer UI; automated ordered onboarding + carryover.
- **Cons:** real backend work + a cron; must match the app's request/response contracts; needs testing.
- **Effort:** days. **Recommended path to make this a repeatable product capability.**

### Option C — Deploy the Ktor server and route the queue to it (heaviest)
Stand up `server/` (Docker + host), point `/api/admin/route-releases` etc. at it (or move the whole API), reconcile the divergent Exposed schema with the Neon DB.
- **Pros:** reuses the finished `RouteQueueService` as-is.
- **Cons:** new infra + ops; two backends to maintain or a full migration; schema reconciliation risk. Highest complexity.
- **Effort:** week+. Only worth it if you intend to migrate the backend to Ktor long-term.

## Recommendation

1. **Now (this week):** Option A — run the two 10-block sessions via **admin direct-assignment in signup order**. (The test route NL-TEST01 + your verified worker already prove the assign + scan path.)
2. **Next:** Option B — port the queue endpoints into the Next.js API so "load next in line" becomes a real, automated button that drives the app's existing offer UI. Use `RouteQueueService.kt` as the spec.
3. **Only if** you decide to consolidate on Ktor long-term: Option C.

## Pre-session checklist (independent of option)
- [ ] Backfill `signupRankings*` for all verified North Lawndale workers (null ranking → sorts last).
- [ ] Confirm the 10 production routes (ids 1–10) are the session set and are published.
- [ ] Confirm eligible zips (60623/60624) and each worker's `verificationStatus=approved` + `proofOfAddressStatus=approved`.
- [ ] Decide clean pass/fail adjudication: today it's photo/scan capture; an **admin override** review step should be confirmed before payouts (hybrid).
- [ ] Remove the NL-TEST01 test route (id 12) before the real run.
