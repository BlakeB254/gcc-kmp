# Admin-Configured Payouts, Worker Payout Methods, and Paper Checks

Date: 2026-07-06
Status: Approved (Blake, via design Q&A: iOS Admin tab, one-off any-amount payouts, method-preference check routing)

## Problem

Today money moves to a worker in exactly one way: an admin approves a `pending_review` job
(`POST /api/admin/jobs/[id]/approve` → Stripe transfer + payout, fixed `jobs.payoutAmount`).
There is no way for an admin to send an arbitrary payout, no standalone payout ledger
(payout state lives on the `jobs` row), and no alternative to Stripe for workers who
can't or won't complete Connect onboarding.

Requirements (Blake):
1. Admin can configure a payout to any worker from the admin portal (iOS Admin tab).
2. Workers have a clear way to connect how they get paid — Stripe Connect (existing) stays
   the primary rail (bank account or debit card via Express onboarding).
3. Workers can opt to be paid by mailed paper check instead; admins see those as a queue
   ("checks to mail") with a push alert. Stripe cannot mail checks, so fulfillment is manual.

## Design

### Data model (Drizzle, `site/src/db/schema.ts`, migration 0027)

New `payouts` table — the standalone payout ledger:

| column | type | notes |
|---|---|---|
| `id` | serial PK | |
| `worker_id` | integer, not null | FK-by-convention to workers.id (no inbound FK constraints on workers, matching prod) |
| `amount_cents` | integer, not null | cents, matches wallet tables' integer convention |
| `note` | varchar | admin-entered reason, shown to worker |
| `method` | enum `payout_method` (`stripe`, `check`) | snapshot of the worker's method at send time |
| `status` | enum `payout_status` (`processing`, `completed`, `failed`, `check_pending`, `check_mailed`) | Stripe path: processing → completed/failed (webhook-reconciled). Check path: check_pending → check_mailed |
| `stripe_transfer_id` | varchar | |
| `stripe_payout_id` | varchar | |
| `initiated_by_admin_id` | integer, not null | |
| `mailing_address` | jsonb | snapshot at creation for check payouts (name, street, city, state, zip) |
| `check_mailed_at` | timestamptz | |
| `check_mailed_by_admin_id` | integer | |
| `created_at` / `updated_at` | timestamptz | |

Workers table additions:
- `payout_method` enum default `'stripe'` — how the worker wants to be paid.
- `payout_mailing_address` jsonb, nullable — optional override; when null, checks use the
  worker's existing home address (`address_street/city/state/zip`).

Job payouts are untouched — they keep living on the `jobs` row. The new table is only for
admin-initiated ad-hoc payouts. (Unifying job payouts into this ledger is future work.)

### Stripe layer (`site/src/lib/stripe/connect.ts`)

Generalize the metadata reference: `createTransferToWorker` / `createInstantPayout` /
`createStandardPayout` / `processJobPayout` gain a reference param
`{ kind: 'job' | 'admin_payout', id: string, workerId?: string }` replacing the bare `jobId`.
- metadata carries `jobId` (kind=job) or `payoutId` (kind=admin_payout) + `workerId`.
- `processJobPayout(accountId, amountCents, jobId, useInstant)` keeps its exact signature as
  a thin wrapper so the approve route is behavior-identical; new `processAdminPayout(...)`
  wraps the same flow with `payoutId` metadata.

### Webhook reconciliation (`site/src/app/api/stripe/webhooks/route.ts`)

`payout.paid` / `payout.failed` gain a `payoutId` branch alongside the existing `jobId`
branch: mark the `payouts` row completed/failed, bump `workers.stats_total_earnings`
(NOT `stats_jobs_completed` — this isn't a job), notify the worker via the existing
`payoutSent` template.

### Server endpoints (`site/src/app/api/`)

All admin routes use `requireAdmin`; worker routes use the existing mobile-auth bearer pattern.

- `POST /api/admin/workers/[id]/payout` — body `{ amountCents, note?, idempotencyKey }`.
  Guards: worker exists; `0 < amountCents ≤ 100_000` ($1,000 cap); idempotencyKey unique
  (unique index; on conflict return the existing payout row → safe client retries).
  Routing by `worker.payout_method`:
  - `stripe`: require `stripeAccountId` + `stripeOnboardingStatus === 'completed'` → 409
    otherwise. Insert `payouts` row status `processing`, then Stripe transfer+payout
    (instant w/ standard fallback, same failure semantics as the approve route: on Stripe
    error leave `processing`, return 502, manual reconciliation; never auto-retry).
  - `check`: insert row status `check_pending` with mailing-address snapshot
    (override ?? home address; 409 if neither has a usable street+city+state+zip),
    then best-effort push-alert all admins ("New check to mail 📬 — $X for NAME").
- `GET /api/admin/payouts?status=&limit=&page=` — list with worker name/email joined;
  powers the checks queue (`status=check_pending`) and history.
- `POST /api/admin/payouts/[id]/mark-mailed` — atomic conditional update
  `check_pending → check_mailed` (409 if already mailed/not a check); records
  `check_mailed_at/by`; bumps `stats_total_earnings`; notifies the worker
  ("Your check for $X is in the mail 📬").
- `GET/PUT /api/workers/me/payout-method` — read/update `{ method, mailingAddress? }`;
  PUT validates method enum and requires a complete address when switching to `check`
  with no home address on file.
- `GET /api/workers/me/payouts` — the worker's ad-hoc payout history (amount, status,
  note, dates), newest first.

Admin alert delivery reuses both existing channels keyed off `notifications_push_subscription`
shape: web-push for VAPID-shaped subscriptions (jobs/submit pattern) and APNs via
`sendApns` for `{token, platform}` mobile tokens (route-queue-notifications pattern) —
mobile admins are the primary audience. New shared helper `notifyAdmins()` in
`site/src/lib/notifications/` so jobs/submit can adopt it later.

### Mobile (KMP)

Shared (`shared/`): `PayoutMethod` enum, `MailingAddress`, `AdminPayout`/`WorkerPayout` DTOs;
`GccApiClient` methods: `getPayoutMethod`, `updatePayoutMethod`, `getMyPayouts`,
`adminCreatePayout(workerId, amountCents, note, idempotencyKey)`, `getAdminPayouts(status, page)`,
`markPayoutMailed(payoutId)`.

WalletScreen — new "How you get paid" card between the earnings card and sponsor funds:
- Two options: **Direct deposit / debit card** (selects `stripe`; shows the existing
  Set up payouts / payout-ready status inline) and **Paper check by mail** (selects `check`;
  shows the address that will be used — home address by default — with an Edit form).
- Recent payouts list (from `getMyPayouts`): "$25.00 — Check mailed Jul 8" / "$40.00 — Sent
  to your bank".

Admin tab:
- `AdminWorkerDetailScreen`: "Send Payout" button beside Grant Points → dialog with dollar
  amount + note; shows how it will route ("via direct deposit" / "will join the checks-to-mail
  queue") and blocks with a clear message when the worker chose Stripe but isn't onboarded.
  Client generates a UUID idempotencyKey per dialog-open.
- `AdminPayoutsTab`: existing job-approval queue stays on top; below it a **Checks to mail**
  section (worker, amount, formatted mailing address, note, "Mark as mailed" with confirm)
  and a collapsed recent-payouts history. Tab badge count includes `check_pending` payouts.
- `AdminPanelViewModel`: state + actions for the above; check queue refreshes on tab open.

### Error handling

- Stripe failure mid-payout: identical semantics to the battle-tested approve route
  (row stays `processing`, 502, no auto-retry, webhook or manual reconciliation).
- Double-send protection: DB unique idempotencyKey + client-side single-flight on the dialog.
- Check queue race: conditional UPDATE on mark-mailed prevents double-mailing records.
- All notifications fire-and-forget-with-logging; never block or mask a money success.

### Testing

- `composeApp` commonTest: DTO serialization round-trip + payout-routing display logic.
- Site: `npx tsc --noEmit` (per repo convention; no server test harness exists).
- Sim verification: wallet card renders, admin dialog opens; live money paths verified
  against prod demo account read-only only (auto-mode blocks prod writes — TestFlight pass
  for the live send).

### Out of scope (deferred)

Recurring/scheduled payouts; web admin surface; worker-initiated "request $X" (no worker
balance ledger exists to validate against); automated check-mailing services (e.g. Lob);
migrating job payouts into the `payouts` ledger.
