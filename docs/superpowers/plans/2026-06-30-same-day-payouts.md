# Same-Day Stripe Payouts (Admin-Approved) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pay workers same-day via Stripe Connect when an admin approves a completed job — after reviewing before/after photos & scans — wiring up the already-written-but-uncalled Connect library.

**Architecture:** The Connect helpers already exist in `site/src/lib/stripe/connect.ts` (createConnectAccount, createOnboardingLink, checkAccountStatus, processJobPayout, createInstantPayout, createStandardPayout) and the webhooks (`account.updated`, `payout.paid/failed`) already reconcile status. This plan adds the API endpoints + app screens that CALL them: worker onboarding, an admin approval queue, an approve/deny → payout path, and admin push. Every payout is admin-approved (no auto-pay).

**Tech Stack:** Next.js `site/` (App Router API routes, Drizzle, Stripe SDK) = live backend; KMP `composeApp/` (Compose) + `shared/` (`GccApiClient`).

## Global Constraints

- **STRIPE TEST MODE ONLY during build.** Use test keys (`sk_test_`/`rk_test_`) + a restricted key. Do NOT touch live keys. All verification is against Stripe test-mode Connect accounts. Live cutover is a separate, user-approved step.
- **Live backend = `site/src/app/api/*`.** Not the Ktor `server/`.
- **Admin-gated:** `requireAdmin` (`site/src/lib/admin-auth.ts`) on every admin endpoint. Verified worker = `verificationStatus === 'approved'`.
- **Idempotency:** payout guarded by `jobs.payoutStatus` (never pay if `completed`/`processing`) AND a Stripe idempotency key `job-payout-{jobId}`. A double-tap or retry must never double-pay.
- **Onboarding gate:** approve MUST hard-fail (no money moved) if the worker isn't payouts-ready; return a clear message + the "send onboarding link" affordance.
- **Instant with standard fallback:** use Instant Payout when `workers.stripeInstantPayoutsEnabled`, else standard; reflect the method used in the response.
- **Audit:** record `approvedByAdminId` / `deniedByAdminId` + timestamps (pairs with feature B's `performedByAdminId`).
- **No unit-test runner in `site/`** — verify with `npx tsc --noEmit` + Stripe test-mode manual/e2e; app with gradle compile. Local homebrew Postgres `gcc` DB is available; `.env.local` points at PROD Neon so use the localhost override for any `drizzle-kit push`.
- Reuse the existing `connect.ts` helpers — do NOT reimplement Stripe calls.
- Commit after every task.

---

### Task 1: Worker payout schema + review/approval columns

**Files:**
- Modify: `site/src/db/schema.ts` (`jobs`: add review/approval columns)
- Modify: `shared/src/commonMain/kotlin/com/gcc/shared/model/Job.kt` (surface new fields)

**Interfaces:**
- Produces: `jobs.reviewStatus` (varchar, nullable: `'approved'|'denied'|null`), `jobs.approvedByAdminId` (int FK→workers, nullable), `jobs.deniedByAdminId` (int FK→workers, nullable), `jobs.denyReason` (text, nullable), `jobs.reviewedAt` (timestamp, nullable). (The `stripeAccountId` etc. on workers and `payoutStatus`/`payoutStripeTransferId` on jobs already exist.)

- [ ] **Step 1: Read** `site/src/db/schema.ts` `jobs` table + `workers` (confirm `stripeAccountId`, `stripeOnboardingStatus`, `stripeInstantPayoutsEnabled` already exist) and `Job.kt`.
- [ ] **Step 2: Add the columns** to `jobs` (match the file's column style): `reviewStatus: varchar('review_status', { length: 20 })`, `approvedByAdminId: integer('approved_by_admin_id').references(() => workers.id)`, `deniedByAdminId: integer('denied_by_admin_id').references(() => workers.id)`, `denyReason: text('deny_reason')`, `reviewedAt: timestamp('reviewed_at')`.
- [ ] **Step 3: Surface** on `Job.kt` (nullable, defaulted): `val reviewStatus: String? = null`, `val approvedByAdminId: Int? = null`, `val deniedByAdminId: Int? = null`, `val denyReason: String? = null`, `val reviewedAt: String? = null` (match the model's timestamp type).
- [ ] **Step 4: Push to LOCAL dev DB** (never bare): `cd site && DATABASE_URL="postgresql://codexmetatron@localhost:5432/gcc" DATABASE_URI="$DATABASE_URL" npx drizzle-kit push` → "Changes applied". If it targets a neon.tech host, STOP.
- [ ] **Step 5: Verify** `cd site && npx tsc --noEmit` (clean) + `./gradlew :composeApp:compileKotlinMetadata` (SUCCESSFUL).
- [ ] **Step 6: Commit** `feat(db): job review/approval + payout audit columns`.

---

### Task 2: Worker Connect onboarding endpoints

**Files:**
- Create: `site/src/app/api/workers/me/connect/onboard/route.ts`
- Create: `site/src/app/api/workers/me/connect/status/route.ts`
- Create: `site/src/app/api/admin/workers/[id]/connect/onboard/route.ts`

**Interfaces:**
- Consumes: `connect.ts` — `createConnectAccount(worker)`, `createOnboardingLink(accountId, returnUrl, refreshUrl)`, `checkAccountStatus(accountId)`.
- Produces: `POST /api/workers/me/connect/onboard → { url }`; `GET /api/workers/me/connect/status → { onboardingStatus, instantPayoutsEnabled, payoutsReady }`; `POST /api/admin/workers/[id]/connect/onboard → { url }` (admin, verified target).

- [ ] **Step 1: Read** `connect.ts` signatures (exact params/returns of the three helpers) + an existing authed worker route (`site/src/app/api/workers/me/*`) for the auth pattern (`verifyMobileToken`), and an admin route for `requireAdmin`.
- [ ] **Step 2: `POST /workers/me/connect/onboard`** — auth worker; load worker; if `stripeAccountId` null → `createConnectAccount(worker)` and persist `stripeAccountId`; return `createOnboardingLink(accountId, returnUrl=<app deep link or site success page>, refreshUrl=<retry>)` as `{ url }`. (Use the app URL env `NEXT_PUBLIC_APP_URL` for return/refresh.)
- [ ] **Step 3: `GET /workers/me/connect/status`** — auth worker; if no `stripeAccountId` return `{ payoutsReady:false, onboardingStatus:'not_started' }`; else `checkAccountStatus(accountId)` → `{ onboardingStatus, instantPayoutsEnabled, payoutsReady }` (payoutsReady = onboarding complete).
- [ ] **Step 4: `POST /admin/workers/[id]/connect/onboard`** — `requireAdmin`; load target worker; require `verificationStatus === 'approved'` (else 422); same create-account-if-missing + return `{ url }` so the admin can share it.
- [ ] **Step 5: Verify** `cd site && npx tsc --noEmit` clean. Stripe test keys must be configured in `.env.local` (test mode) to exercise; if not runnable here, verify by inspection + note it.
- [ ] **Step 6: Commit** `feat(api): worker Stripe Connect onboarding endpoints`.

---

### Task 3: Worker "Set up payouts" app UI

**Files:**
- Modify: `shared/.../api/GccApiClient.kt` (add `connectOnboard()`, `connectStatus()`)
- Modify: a worker profile/settings screen (find via grep: `ProfileScreen`/`SettingsScreen`/account area) + its viewmodel

**Interfaces:**
- Consumes: Task 2 endpoints.
- Produces: a "Set up payouts" button → opens the onboarding URL in a platform browser; a status line from `/connect/status`.

- [ ] **Step 1: Grep** the worker profile/settings screen + how the app opens external URLs (`UriHandler`/Custom Tab/`openUrl` expect-actual): `grep -rn "ProfileScreen\|SettingsScreen\|UriHandler\|openUrl\|LocalUriHandler" composeApp/src/commonMain`.
- [ ] **Step 2: API client** — add `suspend fun connectOnboard(): String` (POST, returns `url`) and `suspend fun connectStatus(): ConnectStatus` (GET) with a `@Serializable data class ConnectStatus(val onboardingStatus: String, val instantPayoutsEnabled: Boolean, val payoutsReady: Boolean)` in `shared/`.
- [ ] **Step 3: UI** — add a "Set up payouts" row to the profile/settings screen: shows status ("Payouts ready ✓" when `payoutsReady`, else "Set up / Finish setup"); tapping calls `connectOnboard()` and opens the returned URL via the app's URL handler. Load status via the viewmodel on screen open.
- [ ] **Step 4: Verify** `./gradlew :composeApp:compileDebugKotlinAndroid` SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(app): worker Set up payouts (Connect onboarding)`.

---

### Task 4: Admin approval queue endpoint

**Files:**
- Create: `site/src/app/api/admin/jobs/pending-review/route.ts`

**Interfaces:**
- Produces: `GET /api/admin/jobs/pending-review → [{ job, worker:{id,name}, payoutAmount, beforePhotos:[url], afterPhotos:[url], hasScan:boolean, scanDepthAvailable:boolean }]` for jobs with `status='pending_review'` and `reviewStatus` null. Admin-gated.

- [ ] **Step 1: Read** how `jobs.beforePhotos`/`afterPhotos` are stored (media IDs vs {url}) and resolved to URLs elsewhere (`site/src/app/api/jobs/submit/route.ts` + any `toMobileJob`), and how a `RouteScan`/`scan_hotspots` links to a job/route (for scan/depth presence).
- [ ] **Step 2: Implement** the query: jobs where `status='pending_review'` and `reviewStatus IS NULL`, joined to worker (id+name); resolve before/after photo URLs (via `media` if ids); set `hasScan`/`scanDepthAvailable` from the linked scan hotspots (`depthData`/`arAnchorData` present). Return the list.
- [ ] **Step 3: Verify** `cd site && npx tsc --noEmit` clean. (Inspection if dev server not runnable.)
- [ ] **Step 4: Commit** `feat(api): admin pending-review payout queue`.

---

### Task 5: Approve/deny endpoints → payout

**Files:**
- Create: `site/src/app/api/admin/jobs/[id]/approve/route.ts`
- Create: `site/src/app/api/admin/jobs/[id]/deny/route.ts`

**Interfaces:**
- Consumes: `connect.ts` `processJobPayout(job)` (transfer → instant if enabled else standard), `checkAccountStatus`; Task 1 columns.
- Produces: `POST /approve → { ok, payoutMethod }` (money moved via Stripe test mode); `POST /deny { reason } → { ok }`.

- [ ] **Step 1: Read** `processJobPayout` in `connect.ts` (exact signature, what it needs from the job/worker, what it returns, how it picks instant vs standard, whether it sets `payoutStripeTransferId`).
- [ ] **Step 2: `POST /approve`** — `requireAdmin`. Guards (all before any Stripe call): job exists + `status='pending_review'`; `payoutStatus !== 'completed' && !== 'processing'` (idempotent); worker `payoutsReady` (via `checkAccountStatus` / stored `stripeOnboardingStatus === 'completed'`) — else **409** `{ error: 'Worker has not completed payout setup' }` with no state change. On pass: set `jobs.status='completed'`, `reviewStatus='approved'`, `approvedByAdminId=auth.workerId`, `reviewedAt=now`, `payoutStatus='processing'`; then `processJobPayout(job)` with Stripe idempotency key `job-payout-{jobId}`; persist `payoutStripeTransferId` + the payout method; return it. The `payout.paid` webhook later flips `payoutStatus='completed'`.
- [ ] **Step 3: `POST /deny`** — `requireAdmin`; require body `{ reason }`; set `reviewStatus='denied'`, `deniedByAdminId=auth.workerId`, `denyReason=reason`, `reviewedAt=now`; NO payout. (Leave `status` as-is or set a terminal non-paying state per the job model.)
- [ ] **Step 4: Verify** `cd site && npx tsc --noEmit` clean. **Stripe TEST-MODE e2e** (if runnable): onboard a test worker, fund+complete a job, approve → confirm a test transfer + payout is created and `payoutStripeTransferId` stored; approve a not-onboarded worker → 409, no money; double-approve → no second payout; deny → no payout. If not runnable here, verify by inspection + note the exact test steps.
- [ ] **Step 5: Commit** `feat(api): admin approve/deny job -> Stripe payout (test mode)`.

---

### Task 6: Admin approval app screen + push wiring

**Files:**
- Modify: `shared/.../api/GccApiClient.kt` (`getPendingPayoutReviews()`, `approveJob(id)`, `denyJob(id, reason)`, `registerDeviceToken(token, platform)`)
- Create/Modify: an admin "Payout Approvals" Compose screen + AdminPanel entry
- Modify: `site/src/app/api/workers/me/device-token/route.ts` (replace stub → persist token)
- Modify: the pending-review trigger to notify admins (`site/src/lib/notifications` or the working push path)

**Interfaces:**
- Consumes: Tasks 4–5 endpoints; existing `notificationTemplates`.
- Produces: an in-app approvals screen (review photos/scan → Approve/Deny) + best-effort admin push when a job enters `pending_review`.

- [ ] **Step 1: Read** the AdminPanel tab structure (`AdminPanelScreen.kt`), the `notifications` module (`notifyWorker`/templates), the `device-token` stub, and where a job transitions to `pending_review` (`site/src/app/api/jobs/submit/route.ts`).
- [ ] **Step 2: API client** — add the four methods (typed DTOs for the queue item + approve/deny responses).
- [ ] **Step 3: Admin screen** — a "Payout Approvals" section/tab: list from `getPendingPayoutReviews()`; tap → review (before/after photos side-by-side + scan/depth indicator + payout amount + worker); Approve (→ `approveJob`) / Deny (reason → `denyJob`); optimistic refresh.
- [ ] **Step 4: Device token** — implement `POST /workers/me/device-token` to persist `{ token, platform }` into `workers.notifications.pushSubscription` + `pushEnabled=true`; add app-side registration + `registerDeviceToken` call on admin login.
- [ ] **Step 5: Notify admins** — when a job enters `pending_review` (in `jobs/submit` completion), send a `payoutNeedsApproval` push to admin(s) via the reachable push path (add the template). The in-app queue is the guaranteed surface; push is best-effort — do not fail the completion if push fails.
- [ ] **Step 6: Verify** `cd site && npx tsc --noEmit` + `./gradlew :composeApp:compileDebugKotlinAndroid` + `:composeApp:compileKotlinMetadata` all clean/SUCCESSFUL.
- [ ] **Step 7: Commit** `feat(payouts): admin approval screen + push-to-admin`.

---

## Self-Review

**Spec coverage:** C1 onboarding → Tasks 2–3; C2 approval queue → Tasks 4 + 6; C3 payout-on-approval → Task 5; C4 push → Task 6; audit/review columns → Task 1; idempotency + onboarding-gate + instant/standard-fallback → Task 5 (Global Constraints). ✓

**Type consistency:** `reviewStatus/approvedByAdminId/deniedByAdminId/denyReason/reviewedAt` consistent across schema (Task 1), Job model (Task 1), and endpoints (Tasks 5). `ConnectStatus{onboardingStatus,instantPayoutsEnabled,payoutsReady}` consistent (Tasks 2–3). `processJobPayout`/`checkAccountStatus`/`createOnboardingLink` are existing `connect.ts` symbols (read in Tasks 2/5 Step 1 before use).

**Placeholder scan:** No TBD. Tasks include "read first" steps for the exact `connect.ts` signatures, the photo-URL resolution, and the navigation/notification wiring — the *behavior* (guards, idempotency, endpoints, screens) is fully specified; the implementer confirms surrounding code by reading.

**Money-safety callouts:** Test mode only; idempotency (payoutStatus guard + Stripe idempotency key); onboarding gate (409, no money); every payout admin-approved. Live-key cutover is a separate user-approved step, NOT in this plan.
