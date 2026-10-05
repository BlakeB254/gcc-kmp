import { NextRequest } from 'next/server'
import { stripe, isStripeConfigured } from '@/lib/stripe/client'
import { db } from '@/db'
import {
  workers,
  jobs,
  blocks,
  transactions,
  sponsors,
  sponsorDeliverablePackages,
  payouts,
  stripeEvents,
  workerWalletEntries,
} from '@/db/schema'
import { and, eq, inArray, sql } from 'drizzle-orm'
import { creditWallet } from '@/lib/wallet'
import Stripe from 'stripe'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'
import { sendEmail } from '@/lib/email'
import { sponsorConfirmationEmail } from '@/lib/email/templates/sponsor-confirmation'
import { donationConfirmationEmail } from '@/lib/email/templates/donation-confirmation'
import { adminPackageCreatedEmail } from '@/lib/email/templates/sponsor-deliverable'
import { createDeliverablePackage } from '@/lib/sponsor-deliverable-engine'
import {
  DELIVERABLE_THRESHOLD_CENTS,
  itemsForTier,
  tierFromAmount,
} from '@/lib/sponsor-deliverables'
import { appUrl } from '@/lib/env'
import {
  commitPublicDataChanges,
  commitPublicDataMutation,
  type PublicDataInvalidator,
} from '@/lib/public-data/invalidation'
import { renewalFromInvoice, reservationFromMetadata } from '@/lib/sponsor-checkout'

export const runtime = 'nodejs'

const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET

export function commitSponsorshipMutation<T>(
  mutation: () => Promise<T>,
  invalidate?: PublicDataInvalidator,
): Promise<T> {
  return commitPublicDataMutation('sponsorship', mutation, invalidate)
}

export async function commitBlockSponsorshipMutation<T>(
  mutation: () => Promise<T>,
  invalidate?: PublicDataInvalidator,
): Promise<T> {
  return commitPublicDataChanges(
    ['sponsorship', 'affected-area'],
    mutation,
    invalidate,
  )
}

function commitCleanupImpactMutation<T>(mutation: () => Promise<T>): Promise<T> {
  return commitPublicDataMutation('cleanup-impact', mutation)
}

export async function POST(request: NextRequest) {
  // Return early if Stripe is not configured (demo mode)
  if (!isStripeConfigured) {
    return Response.json({ received: true, demo: true })
  }

  // Stripe is configured but the webhook secret is missing — a deployment
  // error. Fail loudly instead of "verifying" against a placeholder (which
  // would reject every real event while looking like a signature problem).
  if (!webhookSecret) {
    console.error('[Webhook] STRIPE_WEBHOOK_SECRET is not set — cannot verify webhook signatures')
    return Response.json({ error: 'Webhook secret not configured' }, { status: 500 })
  }

  const body = await request.text()
  const signature = request.headers.get('stripe-signature')

  if (!signature) {
    return Response.json({ error: 'Missing signature' }, { status: 400 })
  }

  let event: Stripe.Event

  try {
    event = stripe.webhooks.constructEvent(body, signature, webhookSecret)
  } catch (err) {
    console.error('Webhook signature verification failed:', err)
    return Response.json({ error: 'Invalid signature' }, { status: 400 })
  }

  // Idempotency guard: Stripe delivers events at-least-once, so record the
  // event id before doing any work. If the insert hits the primary key
  // (ON CONFLICT DO NOTHING returns no row), we've seen this event before —
  // ack it without re-running side effects (duplicate transactions rows,
  // double-incremented stats, duplicate emails).
  const claimed = await db
    .insert(stripeEvents)
    .values({ id: event.id })
    .onConflictDoNothing()
    .returning({ id: stripeEvents.id })

  if (claimed.length === 0) {
    console.log(`[Webhook] Duplicate delivery of event ${event.id} (${event.type}) — skipping`)
    return Response.json({ received: true, duplicate: true })
  }

  try {
    switch (event.type) {
      // Connect account events (worker onboarding)
      case 'account.updated': {
        const account = event.data.object as Stripe.Account
        const workerId = account.metadata?.workerId

        if (workerId) {
          // Update worker's Stripe status
          const status = account.details_submitted
            ? account.payouts_enabled
              ? 'completed'
              : 'restricted'
            : 'in_progress'

          await db
            .update(workers)
            .set({
              stripeOnboardingStatus: status as
                | 'not_started'
                | 'in_progress'
                | 'completed'
                | 'restricted',
              stripeInstantPayoutsEnabled:
                (account.capabilities as Record<string, string>)?.instant_payouts === 'active',
              updatedAt: new Date().toISOString(),
            })
            .where(eq(workers.id, Number(workerId)))
        }
        break
      }

      // Checkout session completed (unified contribution - donations and sponsorships)
      case 'checkout.session.completed': {
        const session = event.data.object as Stripe.Checkout.Session
        const sponsorId = session.metadata?.sponsorId
        const allocationType = session.metadata?.allocationType || 'general'
        const blockIdsStr = session.metadata?.blockIds
        const frequency = session.metadata?.frequency || 'one_time'
        const displayName = session.metadata?.displayName
        const isAnonymous = session.metadata?.isAnonymous === 'true'
        const message = session.metadata?.message

        // Cleaning schedule
        const preferredCleaningDate = session.metadata?.preferredCleaningDate
        const preferredCleaningTimeStart = session.metadata?.preferredCleaningTimeStart
        const preferredCleaningTimeEnd = session.metadata?.preferredCleaningTimeEnd

        console.log('[Webhook] checkout.session.completed:', {
          sessionId: session.id,
          sponsorId,
          allocationType,
          blockIds: blockIdsStr,
          amount: session.amount_total,
        })

        if (!sponsorId) {
          console.error('[Webhook] Missing sponsorId in session metadata')
          break
        }

        const amountCents = session.amount_total || 0
        const amountDollars = amountCents / 100

        // Handle block sponsorship (both specific blocks and area-assigned blocks)
        if ((allocationType === 'block' || allocationType === 'area') && blockIdsStr) {
          const blockIds = blockIdsStr.split(',').map(Number).filter(Boolean)
          const blockCount = blockIds.length

          console.log('[Webhook] Processing block sponsorship:', { blockIds })

          // Create transaction records for all blocks in one insert (the
          // per-row values only differ by blockId, so no need for N round
          // trips).
          await commitBlockSponsorshipMutation(() => db.insert(transactions).values(
            blockIds.map((blockId) => ({
              sponsorId: Number(sponsorId),
              blockId,
              stripeCheckoutSessionId: session.id,
              stripePaymentIntentId: session.payment_intent as string,
              amount: String(10000), // $100 per block
              currency: session.currency || 'usd',
              status: 'completed' as const,
              allocationType: allocationType as 'block' | 'area',
              sponsorType: frequency,
              displayName: displayName || null,
              isAnonymous,
              message: message || null,
              // Cleaning schedule
              preferredCleaningDate: preferredCleaningDate || null,
              preferredCleaningTimeStart: preferredCleaningTimeStart || null,
              preferredCleaningTimeEnd: preferredCleaningTimeEnd || null,
              completedAt: new Date().toISOString(),
            })),
          ))

          // Mark every block funded with the cleaning schedule in one update
          await commitBlockSponsorshipMutation(() => db
            .update(blocks)
            .set({
              status: 'funded',
              fundedById: Number(sponsorId),
              fundedAt: new Date().toISOString(),
              fundingCurrent: '10000',
              // Cleaning schedule for workers to see
              preferredCleaningDate: preferredCleaningDate || null,
              preferredCleaningTimeStart: preferredCleaningTimeStart || null,
              preferredCleaningTimeEnd: preferredCleaningTimeEnd || null,
              updatedAt: new Date().toISOString(),
            })
            .where(inArray(blocks.id, blockIds)))

          // Get sponsor details
          const [sponsor] = await db
            .select()
            .from(sponsors)
            .where(eq(sponsors.id, Number(sponsorId)))
            .limit(1)

          if (sponsor) {
            // Update sponsor stats — atomic SQL increments so concurrent
            // webhooks can't lose updates via read-modify-write races.
            await commitSponsorshipMutation(() => db
              .update(sponsors)
              .set({
                statsTotalContributed: sql`COALESCE(${sponsors.statsTotalContributed}, 0) + ${amountCents}`,
                statsBlocksFunded: sql`COALESCE(${sponsors.statsBlocksFunded}, 0) + ${blockCount}`,
                statsLastContributionAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
              })
              .where(eq(sponsors.id, Number(sponsorId))))

            // Get block details for email
            const fundedBlocks = await db
              .select()
              .from(blocks)
              .where(inArray(blocks.id, blockIds))

            const blockNames = fundedBlocks.map(b => b.name).join(', ')

            // Send confirmation email
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
        } else {
          // Handle general donation
          console.log('[Webhook] Processing general donation:', { amount: amountCents })

          // A block sponsorship paid while no block could be assigned arrives
          // here as a general payment carrying a reservation. Recording it
          // changes no block: an admin allocates it from /admin/reservations.
          const reservation = reservationFromMetadata(session.metadata)

          // Create single transaction record for donation
          await commitSponsorshipMutation(() => db.insert(transactions).values({
            sponsorId: Number(sponsorId),
            blockId: null, // No block - general fund
            reservedBlockCount: reservation?.blockCount ?? null,
            reservedBlockCodes: reservation?.blockCodes ?? null,
            preferredCleaningDate: reservation && preferredCleaningDate ? preferredCleaningDate : null,
            preferredCleaningTimeStart: reservation ? preferredCleaningTimeStart || null : null,
            preferredCleaningTimeEnd: reservation ? preferredCleaningTimeEnd || null : null,
            stripeCheckoutSessionId: session.id,
            stripePaymentIntentId: session.payment_intent as string,
            amount: String(amountCents),
            currency: session.currency || 'usd',
            status: 'completed',
            allocationType: 'general',
            sponsorType: frequency,
            displayName: displayName || null,
            isAnonymous,
            message: message || null,
            completedAt: new Date().toISOString(),
          }))

          // Get sponsor details
          const [sponsor] = await db
            .select()
            .from(sponsors)
            .where(eq(sponsors.id, Number(sponsorId)))
            .limit(1)

          if (sponsor) {
            // Update sponsor stats (contribution but no blocks) — atomic
            // increment, same rationale as the block-sponsorship branch.
            await commitSponsorshipMutation(() => db
              .update(sponsors)
              .set({
                statsTotalContributed: sql`COALESCE(${sponsors.statsTotalContributed}, 0) + ${amountCents}`,
                statsLastContributionAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
              })
              .where(eq(sponsors.id, Number(sponsorId))))

            // Send donation confirmation email
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

        // Auto-create sponsor deliverable package for $2,000+ gifts.
        // Idempotency: Stripe retries the same event, so we only create one
        // package per (sponsorId, stripeCheckoutSessionId).
        if (sponsorId && amountCents >= DELIVERABLE_THRESHOLD_CENTS) {
          const tier = tierFromAmount(amountCents)
          if (tier) {
            try {
              const existingForSession = await db
                .select({ id: sponsorDeliverablePackages.id })
                .from(sponsorDeliverablePackages)
                .where(eq(sponsorDeliverablePackages.externalReference, `stripe_session:${session.id}`))
                .limit(1)

              if (existingForSession.length === 0) {
                // Find the most recent transaction we just created for this session
                // so the deliverable package can link back to it.
                const recentTx = await db
                  .select({ id: transactions.id })
                  .from(transactions)
                  .where(eq(transactions.stripeCheckoutSessionId, session.id))
                  .limit(1)

                const { packageId } = await createDeliverablePackage({
                  sponsorId: Number(sponsorId),
                  amountCents,
                  source: 'online',
                  tier,
                  transactionId: recentTx[0]?.id ?? null,
                  externalReference: `stripe_session:${session.id}`,
                  itemKinds: itemsForTier(tier).map((it) => it.kind),
                })

                console.log(
                  `[Webhook] Created deliverable package #${packageId} (${tier}) for $${amountCents / 100} sponsorship`
                )

                // Notify admin
                const adminTo =
                  process.env.ADMIN_NOTIFICATION_EMAIL ||
                  process.env.ADMIN_EMAILS?.split(',')[0]?.trim()
                if (adminTo) {
                  const [s] = await db
                    .select()
                    .from(sponsors)
                    .where(eq(sponsors.id, Number(sponsorId)))
                    .limit(1)
                  if (s) {
                    const display = s.businessName || s.name
                    const siteUrl = appUrl()
                    const content = adminPackageCreatedEmail({
                      sponsorName: display,
                      sponsorEmail: s.email,
                      amountCents,
                      source: 'online',
                      externalRef: `Stripe session ${session.id}`,
                      adminUrl: `${siteUrl}/admin/sponsors?package=${packageId}`,
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
              // Don't fail the webhook over deliverable bookkeeping
            }
          }
        }

        // Legacy support: handle old-style single blockId metadata
        const legacyBlockId = session.metadata?.blockId
        if (legacyBlockId && !blockIdsStr && allocationType !== 'block') {
          console.log('[Webhook] Processing legacy single block sponsorship:', { blockId: legacyBlockId })
          // This handles old checkout sessions that used single blockId
          await commitBlockSponsorshipMutation(() => db.insert(transactions).values({
            sponsorId: Number(sponsorId),
            blockId: Number(legacyBlockId),
            stripeCheckoutSessionId: session.id,
            stripePaymentIntentId: session.payment_intent as string,
            amount: String(session.amount_total || 10000),
            currency: session.currency || 'usd',
            status: 'completed',
            allocationType: 'block',
            sponsorType: session.metadata?.sponsorType || 'one_time',
            message: session.metadata?.message || null,
            completedAt: new Date().toISOString(),
          }))

          await commitBlockSponsorshipMutation(() => db
            .update(blocks)
            .set({
              status: 'funded',
              fundedById: Number(sponsorId),
              fundedAt: new Date().toISOString(),
              fundingCurrent: String(session.amount_total || 10000),
              updatedAt: new Date().toISOString(),
            })
            .where(eq(blocks.id, Number(legacyBlockId))))
        }
        break
      }

      // Payout events (worker payouts)
      // A subscription renewed (weekly, every two weeks or monthly). The first
      // payment is recorded by checkout.session.completed above; without this,
      // every later payment was charged by Stripe and never reached our books.
      // Stripe must be sending `invoice.paid` to this endpoint for it to run.
      case 'invoice.paid': {
        const invoice = event.data.object as Stripe.Invoice
        const renewal = renewalFromInvoice(invoice)
        if (!renewal) {
          // A paid renewal we cannot attribute is money missing from our
          // books: say so loudly rather than drop it in silence.
          if (invoice.billing_reason === 'subscription_cycle' && (invoice.amount_paid ?? 0) > 0) {
            console.error('[Webhook] Paid renewal NOT recorded — no sponsor on the subscription:', {
              invoiceId: invoice.id,
              amount: invoice.amount_paid,
            })
          }
          break
        }

        const now = new Date().toISOString()
        try {
          await commitSponsorshipMutation(() => db.insert(transactions).values({
          sponsorId: renewal.sponsorId,
          blockId: null,
          stripeSubscriptionId: renewal.subscriptionId,
          amount: String(renewal.amountCents),
          currency: renewal.currency,
          status: 'completed',
          allocationType: 'general',
          sponsorType: renewal.frequency,
          displayName: renewal.displayName,
          isAnonymous: renewal.isAnonymous,
          // A renewed block reservation owes the sponsor another cleaning; it
          // shows up in /admin/reservations and opens no block by itself.
          reservedBlockCount: renewal.reservation?.blockCount ?? null,
          reservedBlockCodes: renewal.reservation?.blockCodes ?? null,
          completedAt: now,
          }))
        } catch (insertError) {
          // Nothing has been written yet, so it is safe to let Stripe retry:
          // release the event claim taken above, or the retry would be treated
          // as a duplicate and this payment would never be recorded.
          await db.delete(stripeEvents).where(eq(stripeEvents.id, event.id))
          throw insertError
        }

        await commitSponsorshipMutation(() => db
          .update(sponsors)
          .set({
            statsTotalContributed: sql`COALESCE(${sponsors.statsTotalContributed}, 0) + ${renewal.amountCents}`,
            statsLastContributionAt: now,
            updatedAt: now,
          })
          .where(eq(sponsors.id, renewal.sponsorId)))

        console.log('[Webhook] Recorded subscription renewal:', {
          sponsorId: renewal.sponsorId,
          subscriptionId: renewal.subscriptionId,
          amount: renewal.amountCents,
        })
        break
      }

      case 'payout.paid': {
        const payout = event.data.object as Stripe.Payout
        const jobId = payout.metadata?.jobId
        const workerId = payout.metadata?.workerId
        const adhocPayoutId = payout.metadata?.payoutId

        // Payouts-table payout (wallet withdrawal, or legacy ad-hoc admin
        // payout): reconcile the row and notify the worker.
        if (adhocPayoutId) {
          const [payoutRow] = await db
            .update(payouts)
            .set({
              status: 'completed',
              stripePayoutId: payout.id,
              updatedAt: new Date().toISOString(),
            })
            .where(eq(payouts.id, Number(adhocPayoutId)))
            .returning({ amountCents: payouts.amountCents, workerId: payouts.workerId })

          if (payoutRow) {
            const [worker] = await db
              .select()
              .from(workers)
              .where(eq(workers.id, payoutRow.workerId))
              .limit(1)

            if (worker) {
              // Wallet withdrawals were already counted as earnings when the
              // wallet was credited — bumping here would double-count. Only
              // legacy pre-wallet ad-hoc payouts (no withdrawal ledger entry)
              // still bump on payment.
              const [withdrawalEntry] = await db
                .select({ id: workerWalletEntries.id })
                .from(workerWalletEntries)
                .where(
                  and(
                    eq(workerWalletEntries.payoutId, Number(adhocPayoutId)),
                    eq(workerWalletEntries.kind, 'withdrawal')
                  )
                )
                .limit(1)

              if (!withdrawalEntry) {
                await commitCleanupImpactMutation(() => db
                  .update(workers)
                  .set({
                    statsTotalEarnings: sql`COALESCE(${workers.statsTotalEarnings}, 0) + ${payoutRow.amountCents}`,
                    updatedAt: new Date().toISOString(),
                  })
                  .where(eq(workers.id, payoutRow.workerId)))
              }

              // Fire-and-forget-with-logging, same pattern as the job branch.
              try {
                const amountDollars = (payoutRow.amountCents / 100).toFixed(2)
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
                console.error(
                  '[webhook] Failed to send payoutSent notification for ad-hoc payout ' + adhocPayoutId,
                  notifyErr
                )
              }
            }
          }
          break
        }

        if (jobId) {
          // Update job payout status
          await db
            .update(jobs)
            .set({
              payoutStatus: 'completed',
              payoutCompletedAt: new Date().toISOString(),
              updatedAt: new Date().toISOString(),
            })
            .where(eq(jobs.id, Number(jobId)))

          // Update worker stats
          if (workerId) {
            const [worker] = await db
              .select()
              .from(workers)
              .where(eq(workers.id, Number(workerId)))
              .limit(1)

            const [job] = await db
              .select()
              .from(jobs)
              .where(eq(jobs.id, Number(jobId)))
              .limit(1)

            if (worker && job) {
              await commitCleanupImpactMutation(() => db
                .update(workers)
                .set({
                  statsJobsCompleted: sql`COALESCE(${workers.statsJobsCompleted}, 0) + 1`,
                  statsTotalEarnings: sql`COALESCE(${workers.statsTotalEarnings}, 0) + ${Number(job.payoutAmount || 10000)}`,
                  statsLastJobAt: new Date().toISOString(),
                  updatedAt: new Date().toISOString(),
                })
                .where(eq(workers.id, Number(workerId))))

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
            }
          }
        }
        break
      }

      case 'payout.failed': {
        const payout = event.data.object as Stripe.Payout
        const jobId = payout.metadata?.jobId
        const adhocPayoutId = payout.metadata?.payoutId

        if (adhocPayoutId) {
          const [payoutRow] = await db
            .update(payouts)
            .set({
              status: 'failed',
              stripePayoutId: payout.id,
              updatedAt: new Date().toISOString(),
            })
            .where(eq(payouts.id, Number(adhocPayoutId)))
            .returning({ amountCents: payouts.amountCents, workerId: payouts.workerId })

          // Wallet withdrawal that failed after the funds were held: put the
          // money back. The partial unique index on (payout_id) WHERE
          // kind='withdrawal_reversal' makes redeliveries refund only once,
          // and we only refund when a matching 'withdrawal' debit exists
          // (legacy ad-hoc payouts never debited a wallet).
          if (payoutRow) {
            const [withdrawalEntry] = await db
              .select({ id: workerWalletEntries.id })
              .from(workerWalletEntries)
              .where(
                and(
                  eq(workerWalletEntries.payoutId, Number(adhocPayoutId)),
                  eq(workerWalletEntries.kind, 'withdrawal')
                )
              )
              .limit(1)

            if (withdrawalEntry) {
              const refund = await creditWallet({
                workerId: payoutRow.workerId,
                amountCents: payoutRow.amountCents,
                kind: 'withdrawal_reversal',
                payoutId: Number(adhocPayoutId),
                note: 'Cash out failed — funds returned to wallet',
              })

              if (refund.credited) {
                try {
                  const [worker] = await db
                    .select()
                    .from(workers)
                    .where(eq(workers.id, payoutRow.workerId))
                    .limit(1)
                  if (worker) {
                    await notifyWorker(
                      {
                        phone: worker.phone ?? undefined,
                        pushSubscription: (worker.notificationsPushSubscription as any) ?? undefined,
                        preferences: {
                          pushEnabled: worker.notificationsPushEnabled ?? undefined,
                          smsEnabled: worker.notificationsSmsEnabled ?? undefined,
                        },
                      },
                      notificationTemplates.withdrawalFailed(
                        `$${(payoutRow.amountCents / 100).toFixed(2)}`
                      )
                    )
                  }
                } catch (notifyErr) {
                  console.error(
                    '[webhook] Failed to send withdrawalFailed notification for payout ' + adhocPayoutId,
                    notifyErr
                  )
                }
              }
            }
          }
          break
        }

        if (jobId) {
          await db
            .update(jobs)
            .set({
              payoutStatus: 'failed',
              updatedAt: new Date().toISOString(),
            })
            .where(eq(jobs.id, Number(jobId)))
        }
        break
      }

      default:
        // Unhandled event type
        console.log(`Unhandled event type: ${event.type}`)
    }
  } catch (error) {
    console.error('Error processing webhook:', error)
    return Response.json({ error: 'Webhook processing failed' }, { status: 500 })
  }

  return Response.json({ received: true })
}
