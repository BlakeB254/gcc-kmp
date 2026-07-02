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
} from '@/db/schema'
import { eq, inArray } from 'drizzle-orm'
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

export const runtime = 'nodejs'

const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET || 'whsec_placeholder'

export async function POST(request: NextRequest) {
  // Return early if Stripe is not configured (demo mode)
  if (!isStripeConfigured) {
    return Response.json({ received: true, demo: true })
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

          // Create transaction records for each block
          for (const blockId of blockIds) {
            await db.insert(transactions).values({
              sponsorId: Number(sponsorId),
              blockId,
              stripeCheckoutSessionId: session.id,
              stripePaymentIntentId: session.payment_intent as string,
              amount: String(10000), // $100 per block
              currency: session.currency || 'usd',
              status: 'completed',
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
            })

            // Update block as funded with cleaning schedule
            await db
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
              .where(eq(blocks.id, blockId))
          }

          // Get sponsor details
          const [sponsor] = await db
            .select()
            .from(sponsors)
            .where(eq(sponsors.id, Number(sponsorId)))
            .limit(1)

          if (sponsor) {
            // Update sponsor stats
            await db
              .update(sponsors)
              .set({
                statsTotalContributed: String(
                  Number(sponsor.statsTotalContributed || 0) + amountCents
                ),
                statsBlocksFunded: String(Number(sponsor.statsBlocksFunded || 0) + blockCount),
                statsLastContributionAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
              })
              .where(eq(sponsors.id, Number(sponsorId)))

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
              isRecurring: frequency === 'monthly',
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

          // Create single transaction record for donation
          await db.insert(transactions).values({
            sponsorId: Number(sponsorId),
            blockId: null, // No block - general fund
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
          })

          // Get sponsor details
          const [sponsor] = await db
            .select()
            .from(sponsors)
            .where(eq(sponsors.id, Number(sponsorId)))
            .limit(1)

          if (sponsor) {
            // Update sponsor stats (contribution but no blocks)
            await db
              .update(sponsors)
              .set({
                statsTotalContributed: String(
                  Number(sponsor.statsTotalContributed || 0) + amountCents
                ),
                statsLastContributionAt: new Date().toISOString(),
                updatedAt: new Date().toISOString(),
              })
              .where(eq(sponsors.id, Number(sponsorId)))

            // Send donation confirmation email
            const emailContent = donationConfirmationEmail({
              name: sponsor.name,
              email: sponsor.email,
              amount: amountDollars,
              isRecurring: frequency === 'monthly',
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
                    const siteUrl =
                      process.env.NEXT_PUBLIC_APP_URL ||
                      'https://globalcommunitycleaners.org'
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
          await db.insert(transactions).values({
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
          })

          await db
            .update(blocks)
            .set({
              status: 'funded',
              fundedById: Number(sponsorId),
              fundedAt: new Date().toISOString(),
              fundingCurrent: String(session.amount_total || 10000),
              updatedAt: new Date().toISOString(),
            })
            .where(eq(blocks.id, Number(legacyBlockId)))
        }
        break
      }

      // Payout events (worker payouts)
      case 'payout.paid': {
        const payout = event.data.object as Stripe.Payout
        const jobId = payout.metadata?.jobId
        const workerId = payout.metadata?.workerId

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
              await db
                .update(workers)
                .set({
                  statsJobsCompleted: String(Number(worker.statsJobsCompleted || 0) + 1),
                  statsTotalEarnings: String(
                    Number(worker.statsTotalEarnings || 0) + Number(job.payoutAmount || 10000)
                  ),
                  statsLastJobAt: new Date().toISOString(),
                  updatedAt: new Date().toISOString(),
                })
                .where(eq(workers.id, Number(workerId)))

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
