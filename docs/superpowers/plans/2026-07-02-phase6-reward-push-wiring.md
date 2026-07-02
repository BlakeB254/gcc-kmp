# Phase 6 — Reward Push Notification Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the two currently-dead push notification templates (`jobApproved`, `payoutSent`) to their real trigger points, per design spec §6 ("Phase 6 — Reward feedback UX") and the gap the same-day-payouts spec explicitly left open ("push half-built... never called").

**Architecture:** Two call sites, both using the existing `notifyWorker()` helper (`site/src/lib/notifications/index.ts`) and existing `notificationTemplates.jobApproved`/`payoutSent` — no new notification infrastructure. `jobApproved` fires in `POST /api/admin/jobs/[id]/approve` after the Stripe transfer genuinely succeeds (not just after the atomic DB claim, since a Stripe failure after the claim must not tell the worker they're paid). `payoutSent` fires in the `payout.paid` Stripe webhook handler, where a `worker` row is already being fetched for stats updates.

**Tech Stack:** Next.js App Router, Drizzle ORM, existing `notifyWorker`/`notificationTemplates` (no new dependencies).

## Global Constraints

- No unit-test framework exists in `site/` — verification is TypeScript typecheck plus manual reasoning/curl-level checks (this touches internal server logic triggered by Stripe events and admin actions, not a directly curl-able public endpoint change — verification here is closer reading + typecheck than the Phase 0 curl pattern).
- `notifyWorker(worker, notification)` expects `{ phone?, pushSubscription?, preferences?: { smsEnabled?, pushEnabled? } }` — the `workers` table's actual columns are flat (`phone`, `notificationsPushEnabled`, `notificationsPushSubscription`, `notificationsSmsEnabled`), so both call sites need a small mapping shim, not a direct pass-through.
- `payoutAmount` is stored in **cents** as a numeric string (confirmed via the approve route's own comment: `'10000' = $100`) — format as dollars for the notification body, don't pass the raw cents string.
- Don't touch `notifyWorker`, `notificationTemplates`, or any other existing exported function's signature — this plan only adds call sites.

---

### Task 1: Wire `jobApproved` and `payoutSent` to their real trigger points

**Files:**
- Modify: `site/src/app/api/admin/jobs/[id]/approve/route.ts`
- Modify: `site/src/app/api/stripe/webhooks/route.ts`

**Interfaces:**
- Consumes: `notifyWorker(worker, notification): Promise<void>` and `notificationTemplates.jobApproved(blockCode: string)` / `notificationTemplates.payoutSent(amount: string)`, both already exported from `site/src/lib/notifications/index.ts` (no changes to either).

- [ ] **Step 1: Read the exact current worker-row shape needed for the mapping shim**

```bash
grep -n "phone\|notificationsPushEnabled\|notificationsPushSubscription\|notificationsSmsEnabled" site/src/db/schema.ts | sed -n '1,10p'
```
Confirm these four column names exist exactly as assumed above (they were confirmed during this session's earlier grounding, but re-confirm before writing code — schema drift between sessions is possible).

- [ ] **Step 2: Wire `jobApproved` in the approve route**

In `site/src/app/api/admin/jobs/[id]/approve/route.ts`, add the import:

```typescript
import { notifyWorker, notificationTemplates } from '@/lib/notifications'
```

The current SELECT (around line 55-70) doesn't fetch the block code or the worker's notification fields — extend it. Find the existing query:

```typescript
  const [row] = await db
    .select({
      // job fields
      jobId: jobs.id,
      jobStatus: jobs.status,
      reviewStatus: jobs.reviewStatus,
      payoutStatus: jobs.payoutStatus,
      payoutAmount: jobs.payoutAmount,
      workerId: jobs.workerId,
      // worker fields
      stripeAccountId: workers.stripeAccountId,
      stripeOnboardingStatus: workers.stripeOnboardingStatus,
      stripeInstantPayoutsEnabled: workers.stripeInstantPayoutsEnabled,
    })
    .from(jobs)
    .leftJoin(workers, eq(jobs.workerId, workers.id))
    .where(eq(jobs.id, jobId))
    .limit(1)
```

Add a join to `blocks` for the code, and the four worker notification fields. Check `blocks` is already imported from `@/db/schema` at the top of the file — if not, add it to the existing schema import line:

```typescript
  const [row] = await db
    .select({
      // job fields
      jobId: jobs.id,
      jobStatus: jobs.status,
      reviewStatus: jobs.reviewStatus,
      payoutStatus: jobs.payoutStatus,
      payoutAmount: jobs.payoutAmount,
      workerId: jobs.workerId,
      // worker fields
      stripeAccountId: workers.stripeAccountId,
      stripeOnboardingStatus: workers.stripeOnboardingStatus,
      stripeInstantPayoutsEnabled: workers.stripeInstantPayoutsEnabled,
      workerPhone: workers.phone,
      workerNotificationsPushEnabled: workers.notificationsPushEnabled,
      workerNotificationsPushSubscription: workers.notificationsPushSubscription,
      workerNotificationsSmsEnabled: workers.notificationsSmsEnabled,
      // block field
      blockCode: blocks.code,
    })
    .from(jobs)
    .leftJoin(workers, eq(jobs.workerId, workers.id))
    .leftJoin(blocks, eq(jobs.blockId, blocks.id))
    .where(eq(jobs.id, jobId))
    .limit(1)
```

Then, immediately after the successful Stripe payout (after `transferId`/`payoutMethod` are set — i.e. right after the existing `try { const result = await processJobPayout(...) ... } catch (err) { ... }` block, before the "Persist transfer ID" step, so a Stripe failure never triggers this), add the notification call. Wrap it so a notification failure never breaks the approval response (the payout already succeeded — don't let a push-notification bug turn a successful payout into a 500):

```typescript
  // ── Notify worker: job approved, payout processing ──────────────────────
  // Fire-and-forget-with-logging: the payout already succeeded above: a
  // notification failure must never mask that success or block the response.
  try {
    await notifyWorker(
      {
        phone: row.workerPhone ?? undefined,
        pushSubscription: (row.workerNotificationsPushSubscription as any) ?? undefined,
        preferences: {
          pushEnabled: row.workerNotificationsPushEnabled ?? undefined,
          smsEnabled: row.workerNotificationsSmsEnabled ?? undefined,
        },
      },
      notificationTemplates.jobApproved(row.blockCode ?? `job #${jobId}`)
    )
  } catch (notifyErr) {
    console.error('[approve] Failed to send jobApproved notification for job ' + jobId, notifyErr)
  }
```

Place this block after the Stripe `try/catch` succeeds and before the "Persist transfer ID" section — read the surrounding code first to confirm the exact insertion point relative to the existing comments (`// ── Persist transfer ID ──...`).

- [ ] **Step 3: Wire `payoutSent` in the Stripe webhook**

In `site/src/app/api/stripe/webhooks/route.ts`, add the same import at the top:

```typescript
import { notifyWorker, notificationTemplates } from '@/lib/notifications'
```

Inside the existing `case 'payout.paid':` block, the code already does:

```typescript
            if (worker && job) {
              await db
                .update(workers)
                .set({ ... })
                .where(eq(workers.id, Number(workerId)))
            }
```

Immediately after that `db.update(workers)...` call succeeds (still inside the `if (worker && job)` block), add:

```typescript
              // ── Notify worker: payout landed ────────────────────────────
              // Same fire-and-forget-with-logging pattern as the approve
              // route: the payout is already confirmed by Stripe at this
              // point (this IS the payout.paid webhook) — a notification
              // failure must not affect webhook processing or ack status.
              try {
                const amountDollars = (Number(job.payoutAmount || 10000) / 100).toFixed(2)
                await notifyWorker(
                  {
                    phone: worker.phone ?? undefined,
                    pushSubscription: (worker.notificationsPushSubscription as any) ?? undefined,
                    preferences: {
                      pushEnabled: worker.notificationsPushEnabled ?? undefined,
                      smsEnabled: worker.notificationsSmsEnabled ?? undefined,
                    },
                  },
                  notificationTemplates.payoutSent(`$${amountDollars}`)
                )
              } catch (notifyErr) {
                console.error('[webhook] Failed to send payoutSent notification for worker ' + workerId, notifyErr)
              }
```

Read the surrounding 20 lines first (`sed -n '380,432p' site/src/app/api/stripe/webhooks/route.ts`) to confirm the exact variable names (`worker`, `job`, `workerId`) match what's actually there — this session's earlier grounding read this file, but re-confirm before editing, since the file may have changed.

- [ ] **Step 4: Typecheck**

```bash
cd site && npx tsc --noEmit
```
Expected: no new errors. If `pushSubscription: (row.workerNotificationsPushSubscription as any)` or the webhook's equivalent produces a type error because `notifyWorker`'s `PushSubscription` interface doesn't match the `jsonb` column's inferred `unknown`/`any` type, the `as any` cast is intentional here (the column is untyped JSON at the DB layer) — don't fight the type system with a broader refactor, this is consistent with how JSON columns are used elsewhere in this codebase already (spot-check one nearby `jsonb(...)` column's usage if genuinely unsure whether a cast is the established pattern).

- [ ] **Step 5: Manual verification — confirm the notification would fire correctly for a realistic case**

This can't be curl-tested like Phase 0's endpoints (no isolated dev-server payload to POST — this logic is embedded inside two existing, complex, side-effecting routes). Instead, write a short read-only verification script that simulates the exact data shape both call sites would see, without touching the database or calling Stripe:

```bash
cd site && npx tsx -e "
import { notificationTemplates } from './src/lib/notifications'
const jobApproved = notificationTemplates.jobApproved('NL-N01')
console.log('jobApproved template:', JSON.stringify(jobApproved))
const payoutSent = notificationTemplates.payoutSent('\$100.00')
console.log('payoutSent template:', JSON.stringify(payoutSent))
"
```
Expected: both print a `{title, body, url}` object with the block code / dollar amount correctly interpolated into the body text — confirms the template functions themselves work as expected with realistic inputs, even though the full end-to-end call sites (requiring a real job/worker/Stripe event) aren't independently exercisable here.

- [ ] **Step 6: Commit**

```bash
git add site/src/app/api/admin/jobs/[id]/approve/route.ts site/src/app/api/stripe/webhooks/route.ts
git commit -m "feat(notifications): wire jobApproved + payoutSent push templates to real trigger points"
```

---

## Self-Review Notes

- **Spec coverage:** implements design spec §6's two "existing push template — currently unused" rows exactly, closing the gap the same-day-payouts spec explicitly left open. Does not implement the other Phase 6 rows (scan-coverage haptics, session-complete count-up, rectification framing) — those depend on Phase 1's mobile screens existing, which they don't yet; out of scope for this DGX-only slice.
- **Placeholder scan:** no TBD/TODO. The one thing genuinely deferred (full end-to-end verification with a real Stripe event and a real device push) is explicitly named as unverifiable in this environment, not glossed over.
- **Type consistency:** both call sites use the exact same `notifyWorker`/`notificationTemplates` signatures already exported — no new types introduced, so nothing to check for drift between tasks (this is a single-task plan).
