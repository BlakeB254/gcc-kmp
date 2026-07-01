/**
 * Route-offer notification outbox — scheduling + dispatch.
 *
 * The route-release engine (lib/route-queue.ts) creates `route_job_offers`.
 * For each new offer we schedule rows in `route_offer_notifications` (an
 * outbox), and the cron drains the due ones. This mirrors the Ktor
 * RouteNotificationService design, but on the production Next.js/Drizzle path.
 *
 * Channels: email (Resend) + push (native APNs). Each respects the worker's
 * opt-in flags. Delivery results are recorded on the outbox row (status,
 * attempts, last_error) so failures are observable instead of silent.
 */
import { db } from '@/db'
import {
  routeOfferNotifications,
  routeJobOffers,
  routeReleaseBatches,
  workers,
} from '@/db/schema'
import { and, eq, lte } from 'drizzle-orm'
import { sendEmail } from '@/lib/email'
import { sendApns, apnsConfigured } from '@/lib/notifications/apns'

const REMINDER_1H_MS = 60 * 60 * 1000
const REMINDER_5M_MS = 5 * 60 * 1000
// A send that fails is retried on subsequent cron ticks until this ceiling,
// then marked permanently failed. Guards against transient APNs/Resend blips.
const MAX_SEND_ATTEMPTS = 5

type Channel = 'email' | 'push'
type Kind = 'initial' | 'reminder_1h' | 'reminder_5m'

/**
 * Schedule the notification rows for a freshly-created offer. Idempotent via
 * the unique (offer_id, channel, kind) index, so re-running a wave is safe.
 *
 * - email + initial          at offeredAt
 * - push  + initial          at offeredAt
 * - push  + reminder_1h      at expiresAt - 1h  (only if the window is longer)
 * - push  + reminder_5m      at expiresAt - 5m  (only if the window is longer)
 */
export async function scheduleOfferNotifications(args: {
  offerId: number
  workerId: number
  offeredAt: string
  expiresAt: string
}): Promise<void> {
  const { offerId, workerId, offeredAt, expiresAt } = args
  const offeredMs = new Date(offeredAt).getTime()
  const expiresMs = new Date(expiresAt).getTime()

  const rows: Array<{ channel: Channel; kind: Kind; scheduledAt: string }> = [
    { channel: 'email', kind: 'initial', scheduledAt: offeredAt },
    { channel: 'push', kind: 'initial', scheduledAt: offeredAt },
  ]
  if (expiresMs - offeredMs > REMINDER_1H_MS) {
    rows.push({ channel: 'push', kind: 'reminder_1h', scheduledAt: new Date(expiresMs - REMINDER_1H_MS).toISOString() })
  }
  if (expiresMs - offeredMs > REMINDER_5M_MS) {
    rows.push({ channel: 'push', kind: 'reminder_5m', scheduledAt: new Date(expiresMs - REMINDER_5M_MS).toISOString() })
  }

  for (const r of rows) {
    await db
      .insert(routeOfferNotifications)
      .values({
        offerId,
        workerId,
        channel: r.channel,
        kind: r.kind,
        status: 'pending',
        scheduledAt: r.scheduledAt,
        attempts: 0,
        payload: { offerId, kind: r.kind },
      })
      .onConflictDoNothing()
  }
}

export type DispatchResult = { scanned: number; sent: number; failed: number; skipped: number }

type OfferContext = {
  offer: typeof routeJobOffers.$inferSelect
  batch: typeof routeReleaseBatches.$inferSelect
  worker: typeof workers.$inferSelect
}

function offerCopy(ctx: OfferContext, kind: Kind) {
  const community = ctx.batch.communityName || 'your area'
  const expires = ctx.offer.expiresAt
  const expiresLabel = new Intl.DateTimeFormat('en-US', {
    timeZone: ctx.batch.timezone || 'America/Chicago',
    weekday: 'short',
    hour: 'numeric',
    minute: '2-digit',
    timeZoneName: 'short',
  }).format(new Date(expires))

  const urgency =
    kind === 'reminder_5m' ? ' Only 5 minutes left' : kind === 'reminder_1h' ? ' Only 1 hour left' : ''

  const title =
    kind === 'initial' ? 'A GCC route is open in your area' : 'Your GCC route offer is about to expire'
  const body = `A cleanup route is available in ${community}.${urgency} — open the GCC app to claim it. Offer expires ${expiresLabel}.`
  return { title, body, expiresLabel, community }
}

function readPushToken(sub: unknown): string | null {
  if (sub && typeof sub === 'object') {
    const t = (sub as Record<string, unknown>).token ?? (sub as Record<string, unknown>).endpoint
    if (typeof t === 'string' && t) return t
  }
  return null
}

/**
 * Drain due outbox rows. Claims each row (status → sending) before sending so
 * concurrent cron ticks don't double-send, then records the outcome.
 */
export async function dispatchDueOfferNotifications(
  now: string = new Date().toISOString(),
  limit = 100
): Promise<DispatchResult> {
  const due = await db
    .select()
    .from(routeOfferNotifications)
    .where(and(eq(routeOfferNotifications.status, 'pending'), lte(routeOfferNotifications.scheduledAt, now)))
    .limit(limit)

  const res: DispatchResult = { scanned: due.length, sent: 0, failed: 0, skipped: 0 }

  for (const n of due) {
    // Claim it. If another tick already moved it off 'pending', skip.
    const claimed = await db
      .update(routeOfferNotifications)
      .set({ status: 'sending', attempts: n.attempts + 1, updatedAt: now })
      .where(and(eq(routeOfferNotifications.id, n.id), eq(routeOfferNotifications.status, 'pending')))
      .returning({ id: routeOfferNotifications.id })
    if (!claimed.length) {
      res.skipped++
      continue
    }

    const finalize = async (status: 'sent' | 'failed' | 'skipped', error?: string) => {
      await db
        .update(routeOfferNotifications)
        .set({
          status,
          sentAt: status === 'sent' ? now : null,
          lastError: error ?? null,
          updatedAt: now,
        })
        .where(eq(routeOfferNotifications.id, n.id))
      if (status === 'sent') res.sent++
      else if (status === 'failed') res.failed++
      else res.skipped++
    }

    // Record a channel send result: success → sent; failure → back to pending
    // for a later retry until the attempt ceiling, then permanently failed.
    const recordSend = async (r: { success: boolean; error?: string }) => {
      if (r.success) return finalize('sent')
      const attemptsNow = n.attempts + 1 // we already incremented on claim
      if (attemptsNow < MAX_SEND_ATTEMPTS) {
        await db
          .update(routeOfferNotifications)
          .set({ status: 'pending', lastError: r.error ?? null, updatedAt: now })
          .where(eq(routeOfferNotifications.id, n.id))
        res.failed++ // count this tick's attempt as a failure for observability
        return
      }
      return finalize('failed', r.error)
    }

    try {
      const [offer] = await db.select().from(routeJobOffers).where(eq(routeJobOffers.id, n.offerId)).limit(1)
      if (!offer) {
        await finalize('skipped', 'offer not found')
        continue
      }
      // Don't nag about offers that are no longer actionable.
      if (offer.status !== 'pending' || new Date(offer.expiresAt).getTime() <= new Date(now).getTime()) {
        await finalize('skipped', `offer is ${offer.status}/expired`)
        continue
      }
      const [batch] = await db
        .select()
        .from(routeReleaseBatches)
        .where(eq(routeReleaseBatches.id, offer.batchId))
        .limit(1)
      const [worker] = await db.select().from(workers).where(eq(workers.id, n.workerId)).limit(1)
      if (!batch || !worker) {
        await finalize('skipped', 'batch or worker not found')
        continue
      }

      const ctx: OfferContext = { offer, batch, worker }
      const copy = offerCopy(ctx, n.kind as Kind)

      if (n.channel === 'email') {
        if (worker.notificationsEmailEnabled === false) {
          await finalize('skipped', 'email disabled by worker')
          continue
        }
        const r = await sendEmail({
          to: worker.email,
          subject: copy.title,
          html: `<p>Hi ${escapeHtml(worker.name)},</p><p>${escapeHtml(copy.body)}</p><p>Open the GCC app to view the route and claim it.</p>`,
          text: `Hi ${worker.name},\n\n${copy.body}\n\nOpen the GCC app to view the route and claim it.`,
        })
        await recordSend(r)
        continue
      }

      // push
      if (worker.notificationsPushEnabled !== true) {
        await finalize('skipped', 'push disabled by worker')
        continue
      }
      const token = readPushToken(worker.notificationsPushSubscription)
      if (!token) {
        await finalize('skipped', 'no push token')
        continue
      }
      if (!apnsConfigured()) {
        await finalize('failed', 'APNs not configured')
        continue
      }
      const r = await sendApns(token, {
        title: copy.title,
        body: copy.body,
        data: {
          type: 'route_offer',
          offerId: offer.id,
          batchId: offer.batchId,
          expiresAt: offer.expiresAt,
          // The iOS tap handler reads `deep_link`. Offers surface on the
          // Dashboard (there is no dedicated offers screen).
          deep_link: '/dashboard',
        },
      })
      await recordSend(r)
    } catch (err) {
      await finalize('failed', err instanceof Error ? err.message : String(err))
    }
  }

  return res
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) =>
    c === '&' ? '&amp;' : c === '<' ? '&lt;' : c === '>' ? '&gt;' : c === '"' ? '&quot;' : '&#39;'
  )
}
