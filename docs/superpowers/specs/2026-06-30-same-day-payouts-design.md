# Same-Day Stripe Payouts (Admin-Approved) — Design Spec

**Date:** 2026-06-30
**Status:** Awaiting review
**Scope:** Pay workers same-day via Stripe Connect when a route/job completes, gated by admin approval (push alert → review before/after images & scans → approve/deny → payout). Wires up the already-written-but-dead Connect library.

---

## 1. Goal

When a job reaches `pending_review` (worker completed it, or an admin completed it on the worker's behalf — see feature B), the admin is alerted, reviews the before/after photos and any LiDAR/AR scan, and **approves or denies**. On approval, Stripe transfers the job's payout to the worker's Connect account and issues a **same-day Instant Payout** (standard fallback). No auto-payouts — every payout is admin-gated for now.

## 2. Decisions (locked with user)

| Decision | Choice |
|---|---|
| Worker onboarding | **In-app "Set up payouts"** (Stripe-hosted onboarding) **+ admin-assisted fallback** (admin generates the same onboarding link for phone-less alpha users). |
| Payout speed | **Instant (same-day) with standard fallback** — Instant when `stripeInstantPayoutsEnabled`, else standard (1–2 days). |
| Approval delivery | **In-app approval queue now + wire push** — a reliable admin "Payout Approvals" screen, plus a push alert when a job needs approval. |

## 3. Background (from codebase map)

- **Connect library already written, zero callers:** `site/src/lib/stripe/connect.ts` — `createConnectAccount`, `createOnboardingLink`, `checkAccountStatus`, `createDashboardLink`, `createTransferToWorker`, `createInstantPayout`, `createStandardPayout`, `processJobPayout` (Express, MCC 7349, `transfers` capability, 1% instant fee).
- **Webhooks exist:** `site/src/app/api/stripe/webhooks/route.ts` handles `account.updated` (→ `workers.stripeOnboardingStatus` / `stripeInstantPayoutsEnabled`), `payout.paid` (→ `jobs.payoutStatus='completed'` + `workers.statsTotalEarnings`), `payout.failed`.
- **Schema ready:** `workers.stripeAccountId / stripeOnboardingStatus / stripeInstantPayoutsEnabled / stripeDefaultPayoutMethod`; `jobs.payoutAmount / payoutStatus / payoutStripeTransferId`.
- **Earn→pay chain stops before payout:** sponsor pays (Checkout) → block funded → admin assigns → `Job(payoutAmount, payoutStatus='pending')` → worker completes (`POST /api/jobs/submit` stores `beforePhotos`/`afterPhotos`; `PATCH /api/jobs/{id}/complete` sets `completed`) → **nothing triggers a payout.**
- **Media/scans stored:** job photos in `jobs.beforePhotos/afterPhotos` (Vercel Blob + `media`); scan hotspots in `scan_hotspots.beforePhotoUrl/afterPhotoUrl/depthData/arAnchorData`.
- **Push half-built:** `site/src/lib/notifications` (web-push/VAPID, templates `jobApproved`/`payoutSent`) is never called; `POST /api/workers/me/device-token` is a stub; the only working push path is the Ktor `RouteNotificationService` via `PUSH_GATEWAY_URL` (route assignment only).
- **Live backend:** Next.js `site/src/app/api/*`. Use a **restricted Stripe key**; verify webhook signatures (already done).

## 4. Decomposition (build in this order)

- **C1 — Worker payout onboarding** (prerequisite): create Connect account + onboarding link; in-app "Set up payouts" + admin-assisted link.
- **C2 — Admin approval queue**: list `pending_review` jobs with media/scans; review UI; approve/deny.
- **C3 — Payout on approval**: approve → `processJobPayout` (transfer + instant/standard) → webhook reconciles status.
- **C4 — Push-to-admin**: alert admins when a job needs approval (in-app queue is primary; push is the nudge).

## 5. Backend changes (Next.js `site/`)

**C1 onboarding**
1. `POST /api/workers/me/connect/onboard` — if `stripeAccountId` null, `createConnectAccount(worker)` and persist it; return `createOnboardingLink(accountId, returnUrl, refreshUrl)`.
2. `GET /api/workers/me/connect/status` — `checkAccountStatus` → `{ onboardingStatus, instantPayoutsEnabled, payoutsReady }`.
3. `POST /api/admin/workers/[id]/connect/onboard` — admin-assisted: same as (1) for a target worker; returns a shareable onboarding link (admin can SMS/email it). Admin-gated, verified-worker only.

**C2 approval queue**
4. `GET /api/admin/jobs/pending-review` — list jobs with `status='pending_review'` joined to worker + before/after media URLs + linked `RouteScan`/hotspot scan data (`depthData`/`arAnchorData` presence flags). Admin-gated.

**C3 payout**
5. `POST /api/admin/jobs/[id]/approve` — admin-gated. Guard: job `pending_review`, worker `payoutsReady`, `payoutStatus !== 'completed'` (idempotent). Set `jobs.status='completed'`, `payoutStatus='processing'`, `approvedByAdminId`, then `processJobPayout(job)` (transfer to worker Connect acct → Instant if enabled else standard) storing `payoutStripeTransferId`. Use a Stripe **idempotency key** = `job-payout-{jobId}`. If worker not onboarded → `409` with a clear "worker hasn't set up payouts" message (no state change).
6. `POST /api/admin/jobs/[id]/deny` — admin-gated. Set a `denied` review state + `denyReason`; no payout. (Add `payoutStatus='denied'` or a `reviewStatus` field.)
7. Webhooks (`payout.paid/failed`) already finalize `payoutStatus` — keep.

**C4 push**
8. Replace the `device-token` stub: `POST /api/workers/me/device-token` persists `{ token, platform }` to `workers.notifications.pushSubscription` + `pushEnabled`.
9. When a job enters `pending_review`, notify all admins (new template `payoutNeedsApproval`). Deliver via the existing working push path (Ktor `PUSH_GATEWAY_URL`) or the site notification module — whichever is reachable from the Next.js backend; if neither is wired to admins yet, the in-app queue is the guaranteed surface and push is best-effort.

## 6. App changes (Compose `composeApp/`)

- **Worker — "Set up payouts"** (profile/settings): button → `POST /connect/onboard` → open the onboarding URL (in-app browser / Custom Tab / SFSafariViewController) → on return, show status from `/connect/status` ("Payouts ready ✓" / "Finish setup"). 
- **Admin — "Payout Approvals"** (new AdminPanel section/tab): list pending-review jobs; tap → review screen showing before/after photos side-by-side + scan info (LiDAR/AR depth if captured, else photo-only) + payout amount + worker; **Approve** (→ payout) / **Deny** (→ reason). Optimistic UI with server confirmation.
- **Admin push**: register admin device token; tapping the "needs approval" push deep-links to the approvals screen (once push is wired).

## 7. Out of scope

- Stripe **Identity** (automated KYC) — manual admin verification (feature exists) stays the identity gate.
- Direct APNs/FCM integration if the existing push gateway suffices.
- Auto-payouts / payout scheduling — every payout is admin-approved for now.
- Payout reconciliation reports / Connect dashboard embedding (future).

## 8. Risks / verification

- **Real money** — implement + test entirely in **Stripe test mode / sandbox** first (test Connect account, complete onboarding, fund a job, admin approve, verify transfer + payout + webhook). Only switch to live keys after sign-off. Use a **restricted API key**.
- **Idempotency** — payout guarded by `payoutStatus` + Stripe idempotency key so a double-tap or retry never double-pays.
- **Onboarding gating** — approve must hard-fail (no money) if the worker isn't `payoutsReady`; surface a clear admin message + the "send onboarding link" action.
- **Instant eligibility** varies per account/bank — fall back to standard automatically and reflect the chosen method in the response.
- **Audit** — record `approvedByAdminId` / `deniedByAdminId` + timestamps on the job (pairs with feature B's `performedByAdminId`).
- **Verify:** sandbox end-to-end happy path + denial path + not-onboarded path; webhook signature verification; TS typecheck; app compiles.
