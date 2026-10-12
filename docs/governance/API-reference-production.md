# GCC — Production API Reference (Next.js on Vercel)

Base URL: `https://api.globalcommunitycleaners.org` (same deployment as the website). Auth: worker/sponsor JWT (Bearer for mobile, HTTP-only cookie for web); admin = JWT whose email ∈ `ADMIN_EMAILS`. **This is the contract.** Endpoints the mobile app calls that are NOT listed here are the parity gap (see ARCHITECTURE doc) and will 404 in prod. Audited 2026-06-24 from `site/src/app/api/**`.

## Auth
`POST /api/auth/register` · `POST /api/auth/login` · `POST /api/auth/magic-link` · `POST /api/auth/verify-magic-link` · `GET /api/auth/session` · `POST /api/auth/logout` · `POST /api/auth/refresh` · `POST /api/auth/verify-2fa` (stub). Apple/Google sign-in via web OAuth callback → mobile token.

## Workers
Public: `apply`, `verify-email`, `register-phone`, `verify-phone`, `resend-verification`, `forgot-password`, `reset-password`, `set-password`, `check-duplicate`.
Authed (`/api/workers/me/...`): `device-token`, `handle`, `verification` (GET/POST), plus `address-change`, `upload-document`. Worker self profile/jobs are read via workers/jobs endpoints.

## Jobs & assignment (NO queue in prod)
- `POST /api/jobs/claim` — worker self-claims an available block (first-come via a conditional UPDATE; requires approved verification; payout = the block's route `payout_amount`, a block with no route is refused 409).
- **Standard money flow (the only way a job is paid):** `POST /api/jobs/{id}/claim` (only from `available`) → `PATCH /api/jobs/{id}/start` (only from `claimed`) → `PATCH /api/jobs/{id}/complete` → `pending_review` for a paid job (`completed` for a $0 test job) → `POST /api/admin/jobs/{id}/approve` (a different admin; the only path that credits a `job_earning`). Every transition is a conditional UPDATE; a lost race answers 409. `POST /api/admin/workers/{id}/payout` is for non-job credits only (reason required, never your own wallet) and is volume-capped: per-admin and per-target daily aggregates counted in Postgres in one upsert (`ADMIN_CREDIT_DAILY_CAP_PER_ADMIN_CENTS` default $500, `ADMIN_CREDIT_DAILY_CAP_PER_TARGET_CENTS` default $250; a crossing credit answers 409 and writes nothing), and a credit above `ADMIN_CREDIT_SECOND_APPROVAL_THRESHOLD_CENTS` (default $100) answers 202 as a `pending_admin_credits` request that a *different* admin (not the requester, not the target) approves with `POST /api/admin/wallet-credits/{id}/approve` (conditional UPDATE on `pending`; only the winner writes the `admin_credit` ledger row) or withdraws with `POST /api/admin/wallet-credits/{id}/reject`; `GET /api/admin/wallet-credits[?status=pending|approved|rejected|all]` lists them. All three limits fail closed to their defaults on a bad value. Cash out: `POST /api/workers/me/withdraw` (approved + verified email, 24h hold after a payout-destination change, refused with 409 while the wallet column ≠ ledger sum). `GET /api/admin/wallets/reconcile` lists wallet/ledger mismatches plus every `admin_credit` ledger line and every second-approval request in the window (`?days=`, default 30).
- `GET /api/workers/me/jobs` — worker's jobs.
- `GET|POST|DELETE /api/admin/routes/{routeId}/assign` — **admin direct assign** a worker (`isTest` → unpaid), list, cancel.

**Queue / batch "next-in-line" onboarding (LIVE 2026-06-24, Option B):**
- `POST /api/admin/route-releases` — create a release batch (turns N routes into N offerable slots); body: `{name, serviceAreaId, routeIds[], releaseAt, offerWindowMinutes?, eligibleZipCodes[], communityName?, timezone?, daytimeWindowStart?, daytimeWindowEnd?}`.
- `GET /api/admin/route-releases?limit=` — list batches.
- `POST /api/admin/route-releases/{id}/process` — run the next wave (expire stale offers, offer open slots to next eligible workers in order). Returns `RouteReleaseRunResponse`.
- `POST /api/admin/route-releases/process-notifications` — process all due batches (cron-style).
- `GET /api/workers/me/route-offers` — worker's open offers (with batch + available routes).
- `POST /api/workers/me/route-offers/{offerId}/accept` — body `{routeId}`; claims that route's job.
- `PATCH /api/workers/me/route-offers/{offerId}/decline` — body `{reason?}`; carryover applies.
- `GET /api/cron/route-releases` — CRON_SECRET-guarded; Vercel Cron advances waves automatically.

Ordering (mirrors the Ktor `RouteQueueService`): eligibility = `verificationStatus=approved` AND `proofOfAddressStatus=approved` AND `addressZip ∈ batch.eligibleZipCodes` AND no active job AND not already offered; sort = carryover → never-offered → signup ranking (neighborhood→city→global) → workerId.

## Routes & blocks
`GET|POST /api/admin/routes` · `GET|PUT|DELETE /api/admin/routes/{routeId}` (PUT accepts `status` → publish, `payoutAmount` etc.) · `GET|POST /api/admin/routes/{routeId}/checkpoints` · `POST /api/admin/routes/accept` · `POST /api/admin/routes/generate` (grid generator) · `GET /api/routes`, `GET /api/routes/{id}` (worker-facing) · `GET /api/admin/blocks` · `GET /api/affected-areas` (public GeoJSON heatmap).

## Scans / cleaning flow (LIVE in prod)
`POST /api/scans` (start scout) · `GET /api/scans/{id}` · `PATCH /api/scans/{id}/phase` (scout→review→clean→complete) · `POST /api/scans/{id}/hotspots` · `PATCH /api/scans/{id}/hotspots/{hid}` (after-photo, verification score, status) · `PATCH /api/scans/{id}/coverage` · `POST /api/scans/{id}/complete` (points). Persists depth, scout/clean GeoJSON, photos, attestation. No dedicated admin scan-override endpoint yet (adjudication via status/peer; confirm hybrid override before payout).

## Spots / community reports & verification
`POST|GET /api/community-reports` · `my-reports` · `my-stats` · `[id]/thank` · `map`. `POST /api/spots/upload` (photos) · `POST /api/spots/{id}/verify` (still_dirty/already_clean, GPS+photo anti-gaming) · `GET /api/spots/{id}/verifications` · `GET /api/spots/needs-verification` · `PATCH|DELETE /api/spots/{id}` (admin) · volunteer cleanup sub-routes.

## Bounties
`GET|POST /api/bounties` (public list ≥ $10; worker posts on a report) · `GET /api/admin/bounties`.

## Sponsors & payments (Stripe)
Sponsor auth/profile: `register`, `verify-email`, `forgot/reset-password`, `check-duplicate`, `me`, `logout`, `settings`, `sponsorships`. Checkout: `POST /api/sponsors/checkout` (single block), `POST /api/sponsor-packages/checkout` (multi-block + add-ons), `GET /api/sponsor-packages[/walkthrough]`, `POST /api/contribute/checkout` (donation). Webhook: `POST /api/stripe/webhooks` (checkout.session.completed → transactions/funding; payout.paid → payout completed; payout.failed → payout `failed`, funds stay in the worker's connected account, wallet NOT re-credited; transfer.reversed (full) → wallet `withdrawal_reversal`; Connect account.updated. A handler error releases the event claim so Stripe's retry is processed). Deliverables (admin): `/api/admin/sponsor-deliverables[...]` auto-triggered ≥ $2k.

## Admin (web + in-app)
Workers: `GET /api/admin/workers`, `GET|PATCH /api/admin/workers/{id}`, `/verify`, `/documents`, `/points`. Spots/blocks: `/api/admin/spots`, `/api/admin/blocks`. Analytics: `/api/admin/analytics/stats`. Ops utilities: `/api/admin/backfill-verification`, `/api/admin/reprocess-images`, `/api/admin/newsletter/{audience,preview,send}`.
⚠️ App also calls admin endpoints that may NOT exist in prod (parity gap): `disputes`, `workers/{id}/role`, `workers/bulk-verify`, `service-areas` (POST), several `analytics/*` variants, `bounties/{id}/close|force-reassign`. (`route-releases` is now live — see Queue section above.) Verify before relying on the rest.

## Public, community, misc
`/api/public/{stats,impact-stats,contributions,sponsor-wall,support-wall,sponsor/[id],data-export[/stats]}` · `/api/leaderboard` · `/api/gamification/me/badges` · `/api/movement/stats` · `/api/feed` · `/api/forum/*` · `/api/feedback/tickets[...]` · `/api/material-donations` · `/api/waitlist/{join,check-area,profile,update}` · `/api/articles[/{slug}]` · `/api/health` · `/api/unsubscribe` · `/api/analytics/cleanliness[/{area}]`. Wallet (`/api/wallet*`) is stubbed/incomplete.

> Counts ~115 endpoints. For exact request/response shapes, read the corresponding `site/src/app/api/**/route.ts`.
