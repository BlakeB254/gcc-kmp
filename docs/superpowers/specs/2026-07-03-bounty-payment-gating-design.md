# Bounty Payment Gating — Design Spec

**Date:** 2026-07-03
**Status:** Awaiting review
**Scope:** Gate bounty creation/pledging behind a real payment (Stripe Checkout or an existing wallet balance) instead of a self-reported dollar amount, plus give `POST /api/wallet/deposit` and `POST /api/wallet/allocate` real implementations (currently `501` stubs) since the wallet-funded path depends on them. Cleans up the specific unfunded bounty row currently live in prod.

**Explicitly out of scope** (deferred to a follow-up spec): `POST /api/bounties/[id]/claim` and `.../accept` (both currently `501` stubs — the full claim → verify → payout lifecycle doesn't exist yet). That follow-up should mirror the already-designed (status: "Awaiting review" as of this writing — verify it landed before assuming so) job-payout pattern in `docs/superpowers/specs/2026-06-30-same-day-payouts-design.md`, which wires the same `processJobPayout()` Connect-transfer helper this spec references but does not modify.

---

## 1. Background

`POST /api/bounties` (`site/src/app/api/bounties/route.ts`) currently inserts a publicly-visible, "open" bounty using an `amount` taken directly from the client request body — no Stripe charge, no wallet debit anywhere in the flow (`walletId: 0` hardcoded). The public `/bounties/how-it-works` page promises "paid out instantly," but there's no payment integration behind bounty creation at all today.

The schema already models a wallet-funded path that was never finished:
- `sponsor_wallets` (`totalBalance`, `generalFundBalance`, `routeFundBalance`, `bountyFundBalance`, `totalDeposited`, `totalSpent`) — real columns, all writes today happen through nothing (`POST /api/wallet/deposit` and `/api/wallet/allocate` are one-line `501` stubs).
- `bounty_pledges` (`bountyId`, `pledgerType`, `pledgerId`, `walletId`, `amount`, `status`) — defined in schema, referenced nowhere else in the codebase.

Meanwhile the codebase already has a working one-time-payment pattern used for donations/sponsorships: `stripe.checkout.sessions.create()` + `POST /api/stripe/webhooks` handling `checkout.session.completed`, with the mobile app already knowing how to launch a Stripe-hosted checkout URL in-browser (`WalletViewModel.startConnectOnboard()` / the block-sponsorship flow both call `openUrl(...)`).

## 2. Goal

Two ways to fund a bounty (create a new one, or add to an existing open one for the same spot), both flowing through **one shared resolver function** so there's a single source of truth for how a bounty got its money:

1. **Wallet-pay** — spend from an already-funded `bountyFundBalance`. Synchronous, no Stripe call at request time.
2. **Checkout-pay** — pay right now via a Stripe Checkout Session. Async — the bounty is created/incremented in the webhook once payment succeeds, not before.

`POST /api/wallet/deposit` (fund the wallet via Stripe) and `POST /api/wallet/allocate` (move cents between `generalFundBalance`/`routeFundBalance`/`bountyFundBalance`, no Stripe involved) get real implementations, since wallet-pay depends on a non-zero `bountyFundBalance` existing in the first place.

## 3. Schema changes

### Drizzle (`site/src/db/schema.ts`)

`bountyPledges`:
- `walletId`: `integer('wallet_id').notNull()` → `integer('wallet_id')` (nullable — null means "paid directly via Stripe Checkout, not from a wallet")
- add `stripeCheckoutSessionId: varchar('stripe_checkout_session_id', { length: 255 })` (nullable)
- add `stripePaymentIntentId: varchar('stripe_payment_intent_id', { length: 255 })` (nullable)

Every pledge row is one of exactly two shapes: `walletId` set + both Stripe columns null (wallet-pay), or `walletId` null + both Stripe columns set (checkout-pay). No other table changes. `bounties.walletId` (singular, defaults to 0) is **not** touched or newly populated — a bounty can accumulate pledges from multiple different sources over its life (the existing "top up an open bounty" behavior), so per-contribution funding provenance belongs on `bountyPledges`, not as a single column on `bounties`. Document this column as vestigial in a code comment; do not rename or drop it (out of scope, avoids unrelated migration churn).

### Kotlin mirror (`server/src/main/kotlin/com/gcc/server/db/tables/ServerOnlyTables.kt`)

`BountyPledges` object: `walletId` gains `.nullable()`; add `stripeCheckoutSessionId`/`stripePaymentIntentId` as `varchar(..., 255).nullable()`, matching `WalletTransactions.stripePaymentIntentId`'s existing style in the same file.

### Migration

`npm run db:push` required before either new endpoint can read/write the new columns — **not run as part of this task**; needs the same explicit sign-off as any other prod schema change.

## 4. Data flow — wallet-pay

`POST /api/bounties/wallet-pay` (new route, `requireMobileAuth`):

1. Validate the same fields today's `POST /api/bounties` validates (`triggerReportId`, `amount` ≥ `BOUNTY_MINIMUM_CENTS`, `title`, community report is fundable) — this validation logic moves into a shared helper (`site/src/lib/bounty-funding.ts`) so both new routes and the webhook call the identical checks, rather than duplicating them three times.
2. Look up the caller's wallet (`sponsor_wallets` where `worker_id = auth.workerId`). No wallet → `404` ("Fund your wallet first").
3. In one DB transaction:
   - `UPDATE sponsor_wallets SET bounty_fund_balance = bounty_fund_balance - :amount, total_spent = total_spent + :amount WHERE id = :walletId AND bounty_fund_balance >= :amount RETURNING *`. Zero rows affected (a concurrent request already spent the balance) → abort, `402` with the balance actually available.
   - Insert `wallet_transactions` (`type='spend'`, `fund_type='bounty'`, `amount=-amount`, `balance_after=<new bountyFundBalance>`, `reference_type='bounty_pledge'`).
   - Resolve-or-create the bounty for this `triggerReportId` (today's existing "insert new, or increment `amount`/`pledgeCount` on the existing open one" logic — unchanged, just moved into the shared helper).
   - Insert `bounty_pledges` (`walletId` set, Stripe columns null).
4. Return the bounty (same response shape as today's `POST /api/bounties`).

## 5. Data flow — checkout-pay

`POST /api/bounties/checkout` (new route, `requireMobileAuth`):

1. Same field validation as wallet-pay (shared helper), but no DB writes yet beyond confirming the report is currently fundable.
2. Create a Stripe Checkout Session: `mode: 'payment'`, one line item (`unit_amount: amount`, product name = bounty title), `metadata: { kind: 'bounty_payment', workerId, triggerReportId, title, visibility, isAnonymous, posterName }` (mirrors the existing `contribute/checkout` metadata style; `description` is *not* passed through metadata — Stripe metadata values cap at 500 chars and the webhook can re-derive a default description from the community report itself, same fallback `reportSource.description` the current route already uses). `success_url: ${appUrl}/bounties/checkout/success?session_id={CHECKOUT_SESSION_ID}`, `cancel_url: ${appUrl}/bounties/post?canceled=true`. Idempotency key via the existing `stripeIdempotencyKey()` helper.
3. Return `{ checkoutUrl, sessionId }`. Mobile calls `openUrl(checkoutUrl)` (existing pattern); web does `window.location.href = checkoutUrl`.
4. `POST /api/stripe/webhooks`, `checkout.session.completed`: add a branch at the **top** of that case — `if (session.metadata?.kind === 'bounty_payment') { ...; break }` — before the existing `sponsorId`-keyed dispatch, so existing donation/sponsorship sessions (which never set `kind`) are unaffected.
   - Idempotency guard: if a `bounty_pledges` row already has this `stripeCheckoutSessionId`, log and no-op (webhook redelivery).
   - Re-validate the community report is still fundable (time has passed since checkout was created). If not: **refund the payment** (`stripe.refunds.create`) and log clearly — money was captured but can't be attached to anything; refunding is safer than silently keeping it.
   - Resolve-or-create the bounty (shared helper, same as wallet-pay).
   - Insert `bounty_pledges` (`walletId` null, `stripeCheckoutSessionId`/`stripePaymentIntentId` set from the session).
5. `/bounties/checkout/success` page: a static "Payment received — your bounty will appear shortly" message (mirrors `/donate/success`'s role). No polling/live-confirmation in this pass — easy to add later, not needed for correctness.

## 6. Data flow — wallet deposit & allocate (real implementations)

`POST /api/wallet/deposit` (`requireMobileAuth`): body `{ amountCents }` (minimum $5). Find-or-create the caller's wallet. Create a Checkout Session, `metadata: { kind: 'wallet_deposit', workerId }`, `success_url: ${appUrl}/wallet/deposit/success?session_id=...`. Webhook branch (`kind === 'wallet_deposit'`): idempotency guard on `wallet_transactions.stripe_payment_intent_id`; `UPDATE sponsor_wallets SET total_balance += :amt, general_fund_balance += :amt, total_deposited += :amt`; insert `wallet_transactions` (`type='deposit'`, `fund_type='general'`).

`POST /api/wallet/allocate` (`requireMobileAuth`): body `{ toRoute?, toBounty? }` (cents, both default 0, both ≥ 0). In one transaction: `UPDATE sponsor_wallets SET general_fund_balance = general_fund_balance - :sum, route_fund_balance += :toRoute, bounty_fund_balance += :toBounty WHERE id = :walletId AND general_fund_balance >= :sum RETURNING *` (same zero-rows-affected race guard as wallet-pay). Insert one `wallet_transactions` row per non-zero bucket (`type='allocate'`).

## 7. Client changes

- **Remove** the `POST` handler from `site/src/app/api/bounties/route.ts` (self-serve creation with no payment). The file's `GET` handler (public listing of funded, open bounties) stays untouched — it's a separate export in the same route file.
- **Web** (`site/src/app/(app)/bounties/post/page.tsx`): `handleSubmit` posts to `/api/bounties/checkout` and redirects to the returned `checkoutUrl` instead of showing "Bounty Posted!" synchronously. (A wallet-pay option on web is a nice-to-have, not required by this spec — web has no wallet UI today.)
- **Mobile** (`shared/.../GccApiClient.kt`, `composeApp/.../WalletViewModel.kt`): replace `apiClient.createBounty(request)` in `confirmPostBounty()` with a choice between `apiClient.createBountyWalletPay(request)` (if `bountyFundBalance` shown in wallet UI covers the amount) and `apiClient.createBountyCheckout(request)` → `openUrl(checkoutUrl)` (mirroring the existing block-sponsorship call site one function above it in the same file).

## 8. Prod cleanup

Identify the specific unfunded bounty row(s) (`status='open'`, no corresponding `bounty_pledges` row once this ships, or just the one the user already spotted) via a read against the real `DATABASE_URL` host (`ep-spring-pine-ah0tw4lu-pooler...` — **not** the Neon MCP's "gcc" project, which is a different, stale database — see `reference_gcc-neon-mcp-project-mismatch` memory). Set its `status` to a cancelled/void state (prefer this over `DELETE` — reversible) rather than deleting the row outright. This is a real prod write; confirm the exact row with the user immediately before executing, even though scope approval was already given.

## 9. Error handling summary

| Case | Response |
|---|---|
| No wallet row for worker (wallet-pay) | `404`, "fund your wallet first" |
| Insufficient `bountyFundBalance`/`generalFundBalance` (race-safe conditional UPDATE returns 0 rows) | `402`, includes actual available balance |
| Stripe session creation throws | `500`, no DB writes have happened yet at that point |
| Webhook: report no longer fundable at fulfillment time | Refund via Stripe, log, no bounty created |
| Webhook: duplicate event for an already-processed session | No-op, single log line |

## 10. Testing plan

No unit-test framework exists for API routes today (only Playwright e2e for browser flows, and `tsc --noEmit`/Kotlin compile for type safety). For this feature: manual verification via the Stripe CLI's test-clock/event triggers (`stripe trigger checkout.session.completed` with crafted metadata) against a local dev server pointed at Stripe test mode, covering: successful wallet-pay, insufficient-balance wallet-pay (402), successful checkout-pay fulfillment, duplicate webhook delivery (no-op), and the report-no-longer-fundable refund path. Concurrent-request race guards (the conditional `UPDATE ... WHERE balance >= amount`) verified by firing two overlapping requests against a wallet with exactly one amount's worth of balance and confirming exactly one succeeds.

## 11. Follow-up (separate spec, not built here)

`POST /api/bounties/[id]/claim` and `.../accept` — the claim → verify → payout lifecycle. Should reuse `processJobPayout()` (`site/src/lib/stripe/connect.ts`) exactly as `docs/superpowers/specs/2026-06-30-same-day-payouts-design.md` designed for jobs (verify that spec's implementation status before assuming it's live), adding the equivalent `payoutStripeTransferId`/`payoutStatus` tracking columns to `bounties` and an atomic claim guard mirroring `admin/jobs/[id]/approve/route.ts`.
