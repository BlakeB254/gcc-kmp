# Wallet ledger + worker-initiated cash out

**Date:** 2026-07-13
**Supersedes the payment-delivery parts of:** `2026-07-06-admin-payouts-design.md`
(the payouts table, checks-to-mail queue, and payout-method preference survive;
what changed is *when money moves* and *who initiates it*).

## Problem

Early-launch payouts were blocked end-to-end on Stripe Connect onboarding:

1. `POST /api/admin/jobs/[id]/approve` and `POST /api/admin/workers/[id]/payout`
   both 409'd unless `workers.stripe_onboarding_status = 'completed'`.
2. That status was written ONLY by the `account.updated` webhook. Connect
   (Express) account events require a **Connect-scoped webhook endpoint**; with
   only an account-scoped endpoint configured, the DB status never left
   `not_started` — so even genuinely onboarded workers were reported as
   "hasn't set up Stripe".
3. The old readiness check also required `charges_enabled`, which an Express
   account created with only the `transfers` capability never enables.
4. Separately, the admin Operations job list rendered `payoutAmount` (cents)
   as dollars ×100 — an approved $100 route showed as "$10,000".

## Design

**Earning and cashing out are now decoupled.**

```
job approved ──► wallet credit (ledger + balance)   [no Stripe involved]
admin bonus  ──► wallet credit (ledger + balance)   [no Stripe involved]
                        │
                        ▼
        worker taps Cash Out in the app
                        │
        ┌───────────────┴────────────────┐
        ▼                                ▼
  Stripe Connect                    paper check
  (transfer + payout,               (checks-to-mail queue,
   requires onboarding)              requires mailing address)
```

### Where the money "is"

Crediting a wallet does **not** move money in Stripe. Stripe Connect cannot
pre-allocate funds to an account that doesn't exist yet, so the platform
balance keeps holding the cash and `worker_wallet_entries` is the internal
earmark of the liability (`SUM(amount_cents)` per worker = what we owe them).
This is the standard marketplace ledger pattern. Money physically leaves the
platform Stripe balance only at cash-out time (transfer → connected account →
payout to bank) or when a check is mailed.

### Schema

- `workers.wallet_balance_cents` — authoritative available balance. All debits
  are single conditional UPDATEs (`WHERE wallet_balance_cents >= amount`),
  which is what makes concurrent cash-outs safe on the neon-http driver
  (no interactive transactions).
- `worker_wallet_entries` — signed audit ledger. Kinds: `job_earning`,
  `admin_credit`, `withdrawal`, `withdrawal_reversal`, `adjustment`.
  Partial unique indexes: one `job_earning` per job; one
  `withdrawal_reversal` per payout; unique nullable `idempotency_key`.
- `payouts` — now the cash-out table. `initiated_by_admin_id` is nullable;
  `initiated_by_worker_id` added. Statuses/methods unchanged.
- Migration: `site/src/db/migrations/0038_worker_wallet_ledger.sql`
  (guarded, hand-applied per repo migration policy).

Drift note: a crash between "ledger insert" and "balance bump" (credits) can
leave the column lower than `SUM(ledger)`. Never higher. Reconcile by
comparing the two if a worker reports a missing credit.

### API surface

| Route | Change |
|---|---|
| `POST /api/admin/jobs/[id]/approve` | Credits wallet; no Stripe call, no Connect guard. Bumps stats at credit time. |
| `POST /api/admin/workers/[id]/payout` | Credits wallet (`admin_credit`); legacy-compatible response with `payout.method='wallet'`. |
| `POST /api/admin/jobs/[id]/reassign` | NEW. Moves a job (any status) to the worker who really did it; moves the wallet credit + stats when already paid. |
| `GET /api/workers/me/wallet` | NEW. Balance + recent ledger entries. |
| `POST /api/workers/me/withdraw` | NEW. `{amountCents, method: stripe\|check, idempotencyKey}`. Stripe path live-checks Connect via `syncConnectStatus`; check path snapshots the mailing address and lands in the checks-to-mail queue. $25 check minimum, $1 Stripe minimum. |
| `GET /api/workers/me/connect/status` | Now persists the live Stripe status (self-healing) and no longer requires `charges_enabled`. |
| Stripe webhook | `payout.paid`: skips the earnings bump for wallet withdrawals (already counted at credit). `payout.failed`: refunds the held funds via `withdrawal_reversal` and notifies the worker. |

### Mobile app

- **Wallet screen:** balance card + **Cash Out** sheet (amount, Max, method
  picker). Direct deposit and paper check are live; Venmo / PayPal / Cash App
  are shown disabled as "Coming soon". Wallet activity feed from the ledger.
  Stripe Connect card is reframed as *optional* ("cash outs arrive in minutes
  instead of a mailed check").
- **Admin worker detail:** shows wallet balance; Send Payout no longer blocks
  on Stripe and explains funds land in the wallet.
- **Admin Operations job list:** `$10,000` display bug fixed
  (`GccFormatters.formatCentsLong`); every completed row with a worker gets a
  **Reassign** action with worker search.

### Failure semantics (unchanged philosophy)

Stripe cash-out failure after the transfer may have partially completed keeps
the payouts row `processing` and the wallet debit held — never auto-retry,
never auto-refund in the request path. `payout.failed` webhook (idempotent via
the partial unique index) is the refund path; manual reconciliation remains
the backstop.

## Follow-ups

- Add Venmo/PayPal/Cash App rails behind the coming-soon rows (likely via
  PayPal Payouts API / Stripe Connect external accounts).
- Configure a **Connect-scoped webhook endpoint** in the Stripe dashboard so
  `account.updated` self-heal isn't load-bearing.
- Nightly drift check: `workers.wallet_balance_cents` vs `SUM(ledger)`.
