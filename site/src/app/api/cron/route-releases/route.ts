/**
 * GET /api/cron/route-releases  — release-queue tick.
 * Advances every due route-release batch (expire stale offers, send the next
 * wave to eligible workers in queue order), then drains the notification outbox
 * (offer email + native iOS push, including the 1h/5m expiry reminders).
 * Guarded by CRON_SECRET (`Authorization: Bearer $CRON_SECRET`); fails closed
 * when the secret is unset.
 *
 * Driven by the Render cron job `gcc-cron-route-releases` (every 15m; see
 * `render.yaml`), because Vercel Hobby caps crons at one-per-day and a
 * more-frequent `vercel.json` entry breaks all deploys. The daily `vercel.json`
 * cron is retained as a backstop — `withCronLease` makes a double-fire a no-op
 * rather than a double-issue of offers.
 *
 * Reminder precision and initial-offer latency track the Render cadence, not
 * this file.
 *
 * Also sweeps abandoned $0 test jobs (claimed and never started past the
 * start window, or in progress past 24h) back off their worker via the
 * canonical `releaseJobPatch` — see lib/stale-test-jobs.ts.
 *
 * Paid jobs follow lib/paid-job-ttl.ts (`paidJobs` in the response, ids per
 * action): claimed-not-started → push at 4h, release + reopen the queue slot
 * at 24h; in progress → push at 12h, at 24h moved to review when a verified
 * scan exists, otherwise flagged `stalled_at` for an admin. A paid job in
 * progress is never released automatically. (`staleJobs.paidStale` still
 * lists paid rows past the $0 TTLs, as before — informational only.)
 *
 * Finally expires stale records (`staleRecords`): route scans `in_progress`
 * over 7 days → `abandoned`; pending sightings over 30 days → hidden from the
 * public map via `map_expires_at` (nothing deleted).
 */
import { NextRequest, NextResponse } from 'next/server'
import { processDueBatches } from '@/lib/route-queue'
import { dispatchDueOfferNotifications } from '@/lib/route-queue-notifications'
import { withCronLease } from '@/lib/cron-lease'
import { cronAuthFailure } from '@/lib/cron-auth'
import { expireStaleRecordsNow, sweepPaidJobsNow, sweepStaleTestJobsNow } from '@/lib/stale-job-sweep'
import { logRouteReleaseMetrics, summarizeReleaseResults } from '@/lib/cron/metrics'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'
// Was implicitly Vercel's ~10s default. processDueBatches() walks every due
// batch and the notification drain does up to 100 sends, so give it headroom
// (60s is the Hobby ceiling). Must stay below the lease TTL.
export const maxDuration = 60

const LEASE_TTL_SECONDS = 120

export async function GET(request: NextRequest) {
  // CRON_SECRET guard: fails closed when unset, constant-time compare.
  const denied = cronAuthFailure(request)
  if (denied) return denied
  try {
    const started = Date.now()
    const outcome = await withCronLease('route-releases', LEASE_TTL_SECONDS, async () => {
      const releases = await processDueBatches()
      // Drain after processing so this tick's freshly-scheduled "initial"
      // notifications go out in the same run.
      const notifications = await dispatchDueOfferNotifications()
      // Last, so a sweep failure can't hold up offers or notifications.
      let staleJobs
      try {
        staleJobs = await sweepStaleTestJobsNow()
        if (staleJobs.released.length > 0 || staleJobs.paidStale.length > 0) {
          console.log(
            `[cron:route-releases] stale jobs: released ${staleJobs.released.length} $0 test job(s) ` +
              `[${staleJobs.released.map((j) => j.id).join(',')}]; ` +
              `${staleJobs.paidStale.length} stale paid job(s) left in place ` +
              `[${staleJobs.paidStale.map((j) => j.id).join(',')}]`,
          )
        }
      } catch (error) {
        console.error('[cron:route-releases] stale job sweep failed:', error)
        staleJobs = { error: 'stale job sweep failed' }
      }
      let paidJobs
      try {
        paidJobs = await sweepPaidJobsNow()
        const acted =
          paidJobs.startReminders.length + paidJobs.released.length + paidJobs.progressReminders.length +
          paidJobs.movedToReview.length + paidJobs.flagged.length + paidJobs.failed.length
        if (acted > 0) {
          console.log(
            `[cron:route-releases] paid jobs: ${paidJobs.startReminders.length} start reminder(s), ` +
              `${paidJobs.released.length} released [${paidJobs.released.join(',')}] ` +
              `(${paidJobs.slotsReopened.length} queue slot(s) reopened), ` +
              `${paidJobs.progressReminders.length} progress reminder(s), ` +
              `${paidJobs.movedToReview.length} moved to review [${paidJobs.movedToReview.join(',')}], ` +
              `${paidJobs.flagged.length} flagged stalled [${paidJobs.flagged.join(',')}], ` +
              `${paidJobs.failed.length} failed`,
          )
        }
      } catch (error) {
        console.error('[cron:route-releases] paid job sweep failed:', error)
        paidJobs = { error: 'paid job sweep failed' }
      }
      let staleRecords
      try {
        staleRecords = await expireStaleRecordsNow()
        if (staleRecords.abandonedScans.length > 0 || staleRecords.expiredSightings.length > 0) {
          console.log(
            `[cron:route-releases] stale records: ${staleRecords.abandonedScans.length} scan(s) abandoned, ` +
              `${staleRecords.expiredSightings.length} sighting(s) hidden from the map`,
          )
        }
      } catch (error) {
        console.error('[cron:route-releases] stale record expiry failed:', error)
        staleRecords = { error: 'stale record expiry failed' }
      }
      const counts = {
        testJobsReleased: 'released' in staleJobs ? staleJobs.released.length : null,
        paidStartReminders: 'startReminders' in paidJobs ? paidJobs.startReminders.length : null,
        paidReleased: 'released' in paidJobs ? paidJobs.released.length : null,
        queueSlotsReopened: 'slotsReopened' in paidJobs ? paidJobs.slotsReopened.length : null,
        paidProgressReminders: 'progressReminders' in paidJobs ? paidJobs.progressReminders.length : null,
        paidMovedToReview: 'movedToReview' in paidJobs ? paidJobs.movedToReview.length : null,
        paidFlaggedStalled: 'flagged' in paidJobs ? paidJobs.flagged.length : null,
        scansAbandoned: 'abandonedScans' in staleRecords ? staleRecords.abandonedScans.length : null,
        sightingsHidden: 'expiredSightings' in staleRecords ? staleRecords.expiredSightings.length : null,
      }
      return { releases, notifications, staleJobs, paidJobs, staleRecords, counts }
    })

    // A held lease means a previous tick is still running. That is expected at a
    // 15m cadence, not an error — 200 so the scheduler doesn't alert.
    if (!outcome.ran) {
      logRouteReleaseMetrics({
        examined: 0,
        processed: 0,
        released: 0,
        durationMs: Date.now() - started,
        leaseAcquired: false,
      })
      return NextResponse.json({
        data: {
          skipped: outcome.skipped,
          ranAt: new Date().toISOString(),
          metrics: {
            examined: 0,
            processed: 0,
            released: 0,
            durationMs: Date.now() - started,
            leaseAcquired: false,
          },
        },
        success: true,
      })
    }

    const summary = summarizeReleaseResults(outcome.result.releases)
    const metrics = {
      examined: outcome.result.releases.length,
      processed: summary.processed,
      released: summary.released,
      durationMs: Date.now() - started,
      leaseAcquired: true,
    }
    logRouteReleaseMetrics(metrics)

    return NextResponse.json({
      data: { ...outcome.result, ranAt: new Date().toISOString(), metrics },
      success: true,
    })
  } catch (error) {
    console.error('GET /api/cron/route-releases error:', error)
    return NextResponse.json({ error: 'Failed to process due releases', success: false }, { status: 500 })
  }
}
