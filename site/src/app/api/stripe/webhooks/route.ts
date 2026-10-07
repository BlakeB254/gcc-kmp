import { NextRequest } from 'next/server'
import { stripe, isStripeConfigured } from '@/lib/stripe/client'
import { db } from '@/db'
import { workers, jobs } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { dbEventClaimStore, processEventOnce, type EventOutcome } from '@/lib/stripe/webhook-event-claim'
import {
  creditWalletForReversedTransfer,
  recordConnectPayoutFailed,
  recordConnectPayoutPaid,
  recordJobPayoutPaid,
} from '@/lib/stripe/connect-payout-events'
import { handleCheckoutSessionCompleted, handleInvoicePaid } from '@/lib/stripe/sponsor-payment-events'
import Stripe from 'stripe'
import { notifyWorker, notificationTemplates } from '@/lib/notifications'
import {
  commitPublicDataChanges,
  commitPublicDataMutation,
  type PublicDataInvalidator,
} from '@/lib/public-data/invalidation'

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

/**
 * payoutSent push/SMS. Fire-and-forget-with-logging: the payout is already
 * confirmed by Stripe (this IS the payout.paid webhook), so a notification
 * failure must not fail the webhook.
 */
async function notifyPayoutSent(workerId: number, amountCents: number, label: string): Promise<void> {
  try {
    const [worker] = await db.select().from(workers).where(eq(workers.id, workerId)).limit(1)
    if (!worker) return
    await notifyWorker(
      {
        phone: worker.phone ?? undefined,
        pushSubscription: (worker.notificationsPushSubscription as any) ?? undefined,
        preferences: {
          pushEnabled: worker.notificationsPushEnabled ?? undefined,
          smsEnabled: worker.notificationsSmsEnabled ?? undefined,
        },
      },
      notificationTemplates.payoutSent(`$${(amountCents / 100).toFixed(2)}`),
    )
  } catch (notifyErr) {
    console.error('[webhook] Failed to send payoutSent notification for ' + label, notifyErr)
  }
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

  // Idempotency guard: Stripe delivers events at-least-once, so the event id
  // is claimed before any work (a duplicate delivery is acked without
  // re-running side effects). If the handler throws, the claim is released
  // and we answer 500 so Stripe's retry is actually processed — see
  // lib/stripe/webhook-event-claim.ts.
  let outcome: EventOutcome
  try {
    outcome = await processEventOnce(event.id, dbEventClaimStore(db), async () => {
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

        // Checkout session completed (unified contribution - donations and
        // sponsorships) and subscription renewals. Both are retry-safe: the
        // transactions insert, block funding and sponsor stats are one
        // statement keyed on the 0049 unique indexes, and emails go out only
        // for rows this delivery created (lib/stripe/sponsor-payment-events.ts).
        case 'checkout.session.completed': {
          await handleCheckoutSessionCompleted(event.data.object as Stripe.Checkout.Session, { exec: db })
          break
        }

        // A subscription renewed (weekly, every two weeks or monthly). The first
        // payment is recorded by checkout.session.completed above; without this,
        // every later payment was charged by Stripe and never reached our books.
        // Stripe must be sending `invoice.paid` to this endpoint for it to run.
        case 'invoice.paid': {
          await handleInvoicePaid(event.data.object as Stripe.Invoice, { exec: db })
          break
        }

        // Payout events (worker payouts). Each status flip is conditional and
        // carries its stats bump in the same statement, so a retry neither
        // double-counts earnings nor re-notifies.
        case 'payout.paid': {
          const payout = event.data.object as Stripe.Payout
          const jobId = payout.metadata?.jobId
          const workerId = payout.metadata?.workerId
          const adhocPayoutId = payout.metadata?.payoutId

          // Payouts-table payout (wallet withdrawal, or legacy ad-hoc admin
          // payout): reconcile the row and notify the worker.
          if (adhocPayoutId) {
            const paid = await commitCleanupImpactMutation(() =>
              recordConnectPayoutPaid(db, { payoutRowId: Number(adhocPayoutId), stripePayoutId: payout.id }),
            )
            if (paid) {
              await notifyPayoutSent(paid.workerId, paid.amountCents, 'ad-hoc payout ' + adhocPayoutId)
            }
            break
          }

          if (jobId) {
            const paid = await commitCleanupImpactMutation(() =>
              recordJobPayoutPaid(db, { jobId: Number(jobId), workerId: workerId ? Number(workerId) : null }),
            )
            if (paid && workerId) {
              await notifyPayoutSent(Number(workerId), paid.payoutCents, 'worker ' + workerId)
            }
          }
          break
        }

        case 'payout.failed': {
          const payout = event.data.object as Stripe.Payout
          const jobId = payout.metadata?.jobId
          const adhocPayoutId = payout.metadata?.payoutId

          if (adhocPayoutId) {
            // The bank payout failed AFTER the transfer: Stripe puts the money
            // back in the worker's CONNECTED ACCOUNT balance, not ours. Do NOT
            // re-credit the wallet (that paid the worker twice). Mark the row
            // failed and record where the funds are; an admin retries the
            // payout from the connected balance or reverses the transfer (a
            // full transfer.reversed is what credits the wallet back).
            const record = await recordConnectPayoutFailed(db, {
              payoutRowId: Number(adhocPayoutId),
              stripePayoutId: payout.id,
              connectedAccountId: event.account ?? null,
              failureCode: payout.failure_code ?? null,
            })

            if (record) {
              console.error(`[webhook] payout.failed for payout ${adhocPayoutId}: ${record.note}`)
              try {
                const [worker] = await db
                  .select()
                  .from(workers)
                  .where(eq(workers.id, record.workerId))
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
                    notificationTemplates.withdrawalBankFailed(
                      `$${(record.amountCents / 100).toFixed(2)}`
                    )
                  )
                }
              } catch (notifyErr) {
                console.error(
                  '[webhook] Failed to send withdrawalBankFailed notification for payout ' + adhocPayoutId,
                  notifyErr
                )
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

        case 'transfer.reversed': {
          // The platform got a cash-out transfer back from the worker's
          // connected account — only now is the money ours again, so only
          // now does the wallet get its withdrawal_reversal credit (full
          // reversals only; once per payout via the unique index).
          const transfer = event.data.object as Stripe.Transfer
          const payoutRowId = transfer.metadata?.payoutId
          if (payoutRowId) {
            const outcome = await creditWalletForReversedTransfer(db, {
              payoutRowId: Number(payoutRowId),
              stripeTransferId: transfer.id,
              amountCents: transfer.amount,
              amountReversedCents: transfer.amount_reversed,
            })
            console.log(`[webhook] transfer.reversed ${transfer.id} for payout ${payoutRowId}:`, outcome)
          }
          break
        }

        default:
          // Unhandled event type
          console.log(`Unhandled event type: ${event.type}`)
      }
    })
  } catch (error) {
    console.error('Error processing webhook:', error)
    return Response.json({ error: 'Webhook processing failed' }, { status: 500 })
  }

  if (outcome === 'duplicate') {
    console.log(`[Webhook] Duplicate delivery of event ${event.id} (${event.type}) — skipping`)
    return Response.json({ received: true, duplicate: true })
  }

  return Response.json({ received: true })
}
