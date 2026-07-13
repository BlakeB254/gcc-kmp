/**
 * Live-check a worker's Stripe Connect account and persist the result on the
 * workers row.
 *
 * Why this exists: stripeOnboardingStatus was historically written ONLY by the
 * `account.updated` webhook. Connect (Express) account events are delivered to
 * a Connect-scoped webhook endpoint — if that endpoint isn't configured (or an
 * event is missed), a fully-onboarded worker stays 'not_started' in our DB
 * forever and everything gated on it wrongly fails. Calling this on every
 * status read / cash-out attempt makes the DB self-healing.
 */
import { eq } from 'drizzle-orm'
import { db } from '@/db'
import { workers } from '@/db/schema'
import { checkAccountStatus } from './connect'

export interface SyncedConnectStatus {
  payoutsReady: boolean
  onboardingStatus: 'not_started' | 'in_progress' | 'completed' | 'restricted'
  instantPayoutsEnabled: boolean
}

export async function syncConnectStatus(
  workerId: number,
  accountId: string
): Promise<SyncedConnectStatus> {
  const status = await checkAccountStatus(accountId)

  const payoutsReady = Boolean(status.payoutsEnabled && status.detailsSubmitted)
  const onboardingStatus = status.detailsSubmitted
    ? status.payoutsEnabled
      ? 'completed'
      : 'restricted'
    : 'in_progress'

  await db
    .update(workers)
    .set({
      stripeOnboardingStatus: onboardingStatus,
      stripeInstantPayoutsEnabled: status.instantPayoutsEnabled ?? false,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(workers.id, workerId))

  return {
    payoutsReady,
    onboardingStatus,
    instantPayoutsEnabled: status.instantPayoutsEnabled ?? false,
  }
}
