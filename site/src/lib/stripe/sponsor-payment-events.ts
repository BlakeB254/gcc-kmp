/**
 * Sponsor money arriving from Stripe: checkout.session.completed (first
 * payment, one-time or subscription) and invoice.paid (subscription renewals).
 *
 * Retry safety. The webhook releases its event claim when a handler throws
 * (lib/stripe/webhook-event-claim.ts), so Stripe's retry re-runs the WHOLE
 * handler. neon-http has no interactive transactions, so every money write
 * here is ONE SQL statement:
 *
 *   WITH ins    AS (INSERT INTO transactions … ON CONFLICT DO NOTHING RETURNING …),
 *        funded AS (UPDATE blocks   … FROM ins WHERE blocks.id = ins.block_id),
 *        stats  AS (UPDATE sponsors … WHERE id = $sponsor AND EXISTS (SELECT 1 FROM ins))
 *   SELECT … FROM ins
 *
 * The partial unique indexes from migration 0049 —
 * transactions_checkout_session_block_unique (session, coalesce(block_id, 0))
 * and transactions_stripe_invoice_id_unique (stripe_invoice_id) — make the
 * insert a no-op on a re-run, and the block funding and the sponsor stats
 * increment ride on rows the insert actually created. They commit together or
 * not at all, so a failure anywhere later in the handler cannot double-count,
 * and a failure in this statement leaves nothing behind for the retry to trip on.
 *
 * Emails go out only when this run created the rows: at most once. A crash
 * after the insert and before the email loses that email rather than sending
 * it twice.
 */
import { and, eq, inArray, sql } from 'drizzle-orm'
import type Stripe from 'stripe'
import { blocks, sponsorDeliverablePackages, sponsors, transactions } from '@/db/schema'
import type { DbExecutor } from '@/lib/job-lifecycle'
import { sendEmail as defaultSendEmail } from '@/lib/email'
import { sponsorConfirmationEmail } from '@/lib/email/templates/sponsor-confirmation'
import { donationConfirmationEmail } from '@/lib/email/templates/donation-confirmation'
import { adminPackageCreatedEmail } from '@/lib/email/templates/sponsor-deliverable'
import { createDeliverablePackage as defaultCreateDeliverablePackage } from '@/lib/sponsor-deliverable-engine'
import { DELIVERABLE_THRESHOLD_CENTS, itemsForTier, tierFromAmount } from '@/lib/sponsor-deliverables'
import { appUrl } from '@/lib/env'
import { commitPublicDataChanges, type PublicDataInvalidator } from '@/lib/public-data/invalidation'
import { renewalFromInvoice, reservationFromMetadata, type InvoiceLike } from '@/lib/sponsor-checkout'

type TransactionRow = typeof transactions.$inferInsert
type BlockFunding = Partial<typeof blocks.$inferInsert>

export interface SponsorStatsIncrement {
  sponsorId: number
  contributedCents: number
  blocksFunded: number
  at: string
}

export interface RecordPaymentInput {
  rows: TransactionRow[]
  /** Applied to the blocks of the rows this statement inserted. */
  fundBlocks?: BlockFunding
  /** Applied once, only when this statement inserted at least one row. */
  stats?: SponsorStatsIncrement
}

export interface InsertedPaymentRow {
  id: number
  blockId: number | null
}

/**
 * Insert payment rows and apply their block funding and sponsor stats in one
 * statement. Returns the rows this call created: empty when every row already
 * existed (a retry or replay), in which case nothing else was written.
 */
export async function recordPaymentOnce(exec: DbExecutor, input: RecordPaymentInput): Promise<InsertedPaymentRow[]> {
  if (input.rows.length === 0) return []

  const ins = exec
    .$with('ins', { id: transactions.id, blockId: transactions.blockId })
    .as(exec
      .insert(transactions)
      .values(input.rows)
      .onConflictDoNothing()
      .returning({ id: transactions.id, blockId: transactions.blockId }).getSQL())

  const ctes = []

  if (input.fundBlocks) {
    ctes.push(
      exec.$with('funded', { id: blocks.id }).as(exec
        .update(blocks)
        .set(input.fundBlocks)
        .where(inArray(blocks.id, sql`(select "block_id" from "ins" where "block_id" is not null)`))
        .returning({ id: blocks.id }).getSQL()),
    )
  }

  if (input.stats) {
    const s = input.stats
    ctes.push(
      exec.$with('stats', { id: sponsors.id }).as(exec
        .update(sponsors)
        .set({
          statsTotalContributed: sql`COALESCE(${sponsors.statsTotalContributed}, 0) + ${s.contributedCents}`,
          ...(s.blocksFunded > 0
            ? { statsBlocksFunded: sql`COALESCE(${sponsors.statsBlocksFunded}, 0) + ${s.blocksFunded}` }
            : {}),
          statsLastContributionAt: s.at,
          updatedAt: s.at,
        })
        .where(and(eq(sponsors.id, s.sponsorId), sql`exists (select 1 from "ins")`))
        .returning({ id: sponsors.id }).getSQL()),
    )
  }

  const rows = await exec
    .with(ins, ...ctes)
    .select({ id: ins.id, blockId: ins.blockId })
    .from(ins)
  return rows.map((r) => ({ id: Number(r.id), blockId: r.blockId == null ? null : Number(r.blockId) }))
}

export interface SponsorPaymentDeps {
  exec: DbExecutor
  sendEmail?: typeof defaultSendEmail
  createDeliverablePackage?: typeof defaultCreateDeliverablePackage
  /** Cache invalidation; tests pass a collector, production uses revalidateTag. */
  invalidate?: PublicDataInvalidator
}

type CheckoutSessionLike = Pick<
  Stripe.Checkout.Session,
  'id' | 'metadata' | 'amount_total' | 'currency' | 'payment_intent'
>

export interface CheckoutOutcome {
  /** Transaction rows this delivery created (0 on a retry/replay). */
  inserted: number
}

function paymentIntentId(session: CheckoutSessionLike): string | null {
  const pi = session.payment_intent
  if (!pi) return null
  return typeof pi === 'string' ? pi : pi.id
}

export async function handleCheckoutSessionCompleted(
  session: CheckoutSessionLike,
  deps: SponsorPaymentDeps,
): Promise<CheckoutOutcome> {
  const { exec } = deps
  const sendEmail = deps.sendEmail ?? defaultSendEmail
  const createDeliverablePackage = deps.createDeliverablePackage ?? defaultCreateDeliverablePackage
  const meta = session.metadata ?? {}

  const sponsorId = meta.sponsorId
  const allocationType = meta.allocationType || 'general'
  const blockIdsStr = meta.blockIds
  const frequency = meta.frequency || 'one_time'
  const displayName = meta.displayName
  const isAnonymous = meta.isAnonymous === 'true'
  const message = meta.message
  const preferredCleaningDate = meta.preferredCleaningDate
  const preferredCleaningTimeStart = meta.preferredCleaningTimeStart
  const preferredCleaningTimeEnd = meta.preferredCleaningTimeEnd

  console.log('[Webhook] checkout.session.completed:', {
    sessionId: session.id,
    sponsorId,
    allocationType,
    blockIds: blockIdsStr,
    amount: session.amount_total,
  })

  if (!sponsorId) {
    console.error('[Webhook] Missing sponsorId in session metadata')
    return { inserted: 0 }
  }

  const sponsorIdNum = Number(sponsorId)
  const amountCents = session.amount_total || 0
  const amountDollars = amountCents / 100
  const now = new Date().toISOString()
  let inserted = 0

  const loadSponsor = async () => {
    const [sponsor] = await exec
      .select({ name: sponsors.name, email: sponsors.email, businessName: sponsors.businessName })
      .from(sponsors)
      .where(eq(sponsors.id, sponsorIdNum))
      .limit(1)
    return sponsor
  }

  if ((allocationType === 'block' || allocationType === 'area') && blockIdsStr) {
    // One row per distinct block: the unique key is (session, block), so a
    // repeated id in the metadata would otherwise collapse silently.
    const blockIds = [...new Set(blockIdsStr.split(',').map(Number).filter(Boolean))]
    const blockCount = blockIds.length
    console.log('[Webhook] Processing block sponsorship:', { blockIds })

    const rows = await commitPublicDataChanges(
      ['sponsorship', 'affected-area'],
      () =>
        recordPaymentOnce(exec, {
          rows: blockIds.map((blockId) => ({
            sponsorId: sponsorIdNum,
            blockId,
            stripeCheckoutSessionId: session.id,
            stripePaymentIntentId: paymentIntentId(session),
            amount: String(10000), // $100 per block
            currency: session.currency || 'usd',
            status: 'completed' as const,
            allocationType: allocationType as 'block' | 'area',
            sponsorType: frequency,
            displayName: displayName || null,
            isAnonymous,
            message: message || null,
            preferredCleaningDate: preferredCleaningDate || null,
            preferredCleaningTimeStart: preferredCleaningTimeStart || null,
            preferredCleaningTimeEnd: preferredCleaningTimeEnd || null,
            completedAt: now,
          })),
          fundBlocks: {
            status: 'funded',
            fundedById: sponsorIdNum,
            fundedAt: now,
            fundingCurrent: '10000',
            preferredCleaningDate: preferredCleaningDate || null,
            preferredCleaningTimeStart: preferredCleaningTimeStart || null,
            preferredCleaningTimeEnd: preferredCleaningTimeEnd || null,
            updatedAt: now,
          },
          stats: { sponsorId: sponsorIdNum, contributedCents: amountCents, blocksFunded: blockCount, at: now },
        }),
      deps.invalidate,
    )
    inserted += rows.length

    if (rows.length === 0) {
      console.log(`[Webhook] Session ${session.id} already recorded — no rows, stats or email this time`)
    } else {
      const sponsor = await loadSponsor()
      if (sponsor) {
        const fundedBlocks = await exec.select({ name: blocks.name }).from(blocks).where(inArray(blocks.id, blockIds))
        const blockNames = fundedBlocks.map((b) => b.name).join(', ')
        const emailContent = sponsorConfirmationEmail({
          name: sponsor.name,
          email: sponsor.email,
          amount: amountDollars,
          blockAddress: blockCount === 1 ? blockNames : `${blockCount} blocks: ${blockNames}`,
          isRecurring: frequency !== 'one_time',
        })
        const emailResult = await sendEmail({
          to: sponsor.email,
          subject: 'Thank You for Your Sponsorship - GCC',
          html: emailContent.html,
          text: emailContent.text,
        })
        if (emailResult.success) {
          console.log('[Webhook] Sponsorship confirmation email sent to:', sponsor.email)
        } else {
          console.error('[Webhook] Failed to send sponsorship email:', emailResult.error)
        }
      }
    }
  } else {
    console.log('[Webhook] Processing general donation:', { amount: amountCents })

    // A block sponsorship paid while no block could be assigned arrives here
    // as a general payment carrying a reservation. Recording it changes no
    // block: an admin allocates it from /admin/reservations.
    const reservation = reservationFromMetadata(session.metadata)

    const rows = await commitPublicDataChanges(
      ['sponsorship'],
      () =>
        recordPaymentOnce(exec, {
          rows: [
            {
              sponsorId: sponsorIdNum,
              blockId: null, // No block - general fund
              reservedBlockCount: reservation?.blockCount ?? null,
              reservedBlockCodes: reservation?.blockCodes ?? null,
              preferredCleaningDate: reservation && preferredCleaningDate ? preferredCleaningDate : null,
              preferredCleaningTimeStart: reservation ? preferredCleaningTimeStart || null : null,
              preferredCleaningTimeEnd: reservation ? preferredCleaningTimeEnd || null : null,
              stripeCheckoutSessionId: session.id,
              stripePaymentIntentId: paymentIntentId(session),
              amount: String(amountCents),
              currency: session.currency || 'usd',
              status: 'completed',
              allocationType: 'general',
              sponsorType: frequency,
              displayName: displayName || null,
              isAnonymous,
              message: message || null,
              completedAt: now,
            },
          ],
          stats: { sponsorId: sponsorIdNum, contributedCents: amountCents, blocksFunded: 0, at: now },
        }),
      deps.invalidate,
    )
    inserted += rows.length

    if (rows.length === 0) {
      console.log(`[Webhook] Session ${session.id} already recorded — no row, stats or email this time`)
    } else {
      const sponsor = await loadSponsor()
      if (sponsor) {
        const emailContent = donationConfirmationEmail({
          name: sponsor.name,
          email: sponsor.email,
          amount: amountDollars,
          isRecurring: frequency !== 'one_time',
        })
        const emailResult = await sendEmail({
          to: sponsor.email,
          subject: 'Thank You for Your Donation - GCC',
          html: emailContent.html,
          text: emailContent.text,
        })
        if (emailResult.success) {
          console.log('[Webhook] Donation confirmation email sent to:', sponsor.email)
        } else {
          console.error('[Webhook] Failed to send donation email:', emailResult.error)
        }
      }
    }
  }

  // Auto-create a sponsor deliverable package for $2,000+ gifts. Retry-safe
  // on its own: one package per stripe_session external reference (checked
  // here), and concurrent deliveries of one event are stopped by the event
  // claim. Bookkeeping failures never fail the webhook.
  if (amountCents >= DELIVERABLE_THRESHOLD_CENTS) {
    const tier = tierFromAmount(amountCents)
    if (tier) {
      try {
        const existingForSession = await exec
          .select({ id: sponsorDeliverablePackages.id })
          .from(sponsorDeliverablePackages)
          .where(eq(sponsorDeliverablePackages.externalReference, `stripe_session:${session.id}`))
          .limit(1)

        if (existingForSession.length === 0) {
          const recentTx = await exec
            .select({ id: transactions.id })
            .from(transactions)
            .where(eq(transactions.stripeCheckoutSessionId, session.id))
            .limit(1)

          const { packageId } = await createDeliverablePackage({
            sponsorId: sponsorIdNum,
            amountCents,
            source: 'online',
            tier,
            transactionId: recentTx[0]?.id ?? null,
            externalReference: `stripe_session:${session.id}`,
            itemKinds: itemsForTier(tier).map((it) => it.kind),
          })

          console.log(
            `[Webhook] Created deliverable package #${packageId} (${tier}) for $${amountCents / 100} sponsorship`,
          )

          const adminTo =
            process.env.ADMIN_NOTIFICATION_EMAIL || process.env.ADMIN_EMAILS?.split(',')[0]?.trim()
          if (adminTo) {
            const s = await loadSponsor()
            if (s) {
              const display = s.businessName || s.name
              const content = adminPackageCreatedEmail({
                sponsorName: display,
                sponsorEmail: s.email,
                amountCents,
                source: 'online',
                externalRef: `Stripe session ${session.id}`,
                adminUrl: `${appUrl()}/admin/sponsors?package=${packageId}`,
              })
              await sendEmail({
                to: adminTo,
                subject: `[GCC] New ${tier} sponsorship: ${display} ($${amountCents / 100})`,
                html: content.html,
                text: content.text,
              })
            }
          }
        }
      } catch (err) {
        console.error('[Webhook] Failed to create deliverable package:', err)
      }
    }
  }

  // Legacy support: old checkout sessions carried a single blockId. Its row
  // keys on (session, that block), distinct from the general row above.
  const legacyBlockId = meta.blockId
  if (legacyBlockId && !blockIdsStr && allocationType !== 'block') {
    console.log('[Webhook] Processing legacy single block sponsorship:', { blockId: legacyBlockId })
    const rows = await commitPublicDataChanges(
      ['sponsorship', 'affected-area'],
      () =>
        recordPaymentOnce(exec, {
          rows: [
            {
              sponsorId: sponsorIdNum,
              blockId: Number(legacyBlockId),
              stripeCheckoutSessionId: session.id,
              stripePaymentIntentId: paymentIntentId(session),
              amount: String(session.amount_total || 10000),
              currency: session.currency || 'usd',
              status: 'completed',
              allocationType: 'block',
              sponsorType: meta.sponsorType || 'one_time',
              message: meta.message || null,
              completedAt: now,
            },
          ],
          fundBlocks: {
            status: 'funded',
            fundedById: sponsorIdNum,
            fundedAt: now,
            fundingCurrent: String(session.amount_total || 10000),
            updatedAt: now,
          },
        }),
      deps.invalidate,
    )
    inserted += rows.length
  }

  return { inserted }
}

export type InvoicePaidOutcome =
  | { recorded: true }
  | { recorded: false; reason: 'not_a_renewal' | 'already_recorded' }

/**
 * invoice.paid for a subscription renewal. The first payment is recorded by
 * checkout.session.completed; renewalFromInvoice returns null for it. One row
 * per invoice (transactions_stripe_invoice_id_unique) with the stats increment
 * in the same statement.
 */
export async function handleInvoicePaid(
  invoice: InvoiceLike,
  deps: SponsorPaymentDeps,
): Promise<InvoicePaidOutcome> {
  const renewal = renewalFromInvoice(invoice)
  if (!renewal) {
    // A paid renewal we cannot attribute is money missing from our books:
    // say so loudly rather than drop it in silence.
    if (invoice.billing_reason === 'subscription_cycle' && (invoice.amount_paid ?? 0) > 0) {
      console.error('[Webhook] Paid renewal NOT recorded — no sponsor on the subscription:', {
        invoiceId: invoice.id,
        amount: invoice.amount_paid,
      })
    }
    return { recorded: false, reason: 'not_a_renewal' }
  }
  if (!invoice.id) {
    // Every real Stripe invoice has an id; without one there is no retry key.
    console.error('[Webhook] invoice.paid without an invoice id — recording without a retry guard')
  }

  const now = new Date().toISOString()
  const rows = await commitPublicDataChanges(
    ['sponsorship'],
    () =>
      recordPaymentOnce(deps.exec, {
        rows: [
          {
            sponsorId: renewal.sponsorId,
            blockId: null,
            stripeSubscriptionId: renewal.subscriptionId,
            stripeInvoiceId: invoice.id ?? null,
            amount: String(renewal.amountCents),
            currency: renewal.currency,
            status: 'completed',
            allocationType: 'general',
            sponsorType: renewal.frequency,
            displayName: renewal.displayName,
            isAnonymous: renewal.isAnonymous,
            // A renewed block reservation owes the sponsor another cleaning;
            // it shows up in /admin/reservations and opens no block by itself.
            reservedBlockCount: renewal.reservation?.blockCount ?? null,
            reservedBlockCodes: renewal.reservation?.blockCodes ?? null,
            completedAt: now,
          },
        ],
        stats: { sponsorId: renewal.sponsorId, contributedCents: renewal.amountCents, blocksFunded: 0, at: now },
      }),
    deps.invalidate,
  )

  if (rows.length === 0) {
    console.log(`[Webhook] Renewal invoice ${invoice.id} already recorded — skipping`)
    return { recorded: false, reason: 'already_recorded' }
  }
  console.log('[Webhook] Recorded subscription renewal:', {
    sponsorId: renewal.sponsorId,
    subscriptionId: renewal.subscriptionId,
    invoiceId: invoice.id,
    amount: renewal.amountCents,
  })
  return { recorded: true }
}
