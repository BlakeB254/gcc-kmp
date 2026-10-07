import { NextRequest } from 'next/server'
import { db } from '@/db'
import { blocks, workers, jobs } from '@/db/schema'
import { eq, and, inArray } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { verifyToken } from '@/lib/auth'
import { claimAvailableBlock, releaseBlockClaim, routePayoutCentsForBlock } from '@/lib/job-lifecycle'

export const runtime = 'nodejs'

/**
 * POST /api/jobs/claim
 * Claim an available block - first come, first served
 *
 * The block flip available → in_progress is a single conditional UPDATE, so
 * when two workers race only one gets a row back; the other gets 409. The
 * job's payout comes from the block's route (routes.payout_amount, cents) —
 * the same source the route-offer accept flow uses — never from
 * blocks.funding_goal, which holds dollars on some legacy rows.
 *
 * Auth: the worker is ALWAYS the authenticated caller — Bearer mobile JWT
 * (same helper as /api/jobs/[id]/claim) or the web `worker-token` cookie
 * (same pattern as /api/workers/me). A `workerId` in the body is ignored
 * (kept in the accepted shape only for older clients).
 */
async function authenticatedWorkerId(request: NextRequest): Promise<number | null> {
  const mobile = await requireMobileAuth(request)
  if (mobile) return mobile.workerId

  // Web dashboard fallback (cookie session).
  const token = request.cookies.get('worker-token')?.value
  if (!token) return null
  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'worker') return null
  return payload.id
}

export async function POST(request: NextRequest) {
  try {
    const workerId = await authenticatedWorkerId(request)
    if (!workerId) {
      return Response.json({ message: 'Not authenticated' }, { status: 401 })
    }

    const body = await request.json()
    const { blockId } = body

    if (!blockId) {
      return Response.json(
        { message: 'Block ID is required' },
        { status: 400 }
      )
    }

    // Verify worker exists and is approved
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!worker) {
      return Response.json({ message: 'Worker not found' }, { status: 404 })
    }

    if (worker.verificationStatus !== 'approved') {
      return Response.json(
        { message: 'Your account is not yet approved for work' },
        { status: 403 }
      )
    }

    // Check worker doesn't have an active job
    const activeJobs = await db
      .select({ id: jobs.id })
      .from(jobs)
      .where(
        and(
          eq(jobs.workerId, workerId),
          inArray(jobs.status, ['claimed', 'in_progress', 'pending_review'])
        )
      )
      .limit(1)

    if (activeJobs.length > 0) {
      return Response.json(
        { message: 'You already have an active job. Complete it first.' },
        { status: 400 }
      )
    }

    // Attempt to claim the block with optimistic locking
    const [block] = await db
      .select()
      .from(blocks)
      .where(eq(blocks.id, Number(blockId)))
      .limit(1)

    if (!block) {
      return Response.json({ message: 'Block not found' }, { status: 404 })
    }

    // Only allow claiming blocks that have been explicitly made available by admin
    if (block.status !== 'available') {
      return Response.json(
        {
          message:
            block.status === 'in_progress'
              ? 'This block has already been claimed by another worker'
              : block.status === 'funded'
              ? 'This block is funded but not yet available for work. Please wait for admin to release it.'
              : 'This block is not available for claiming',
        },
        { status: 409 } // Conflict
      )
    }

    const payoutCents = await routePayoutCentsForBlock(db, block)
    if (payoutCents == null) {
      return Response.json(
        { message: 'This block is not linked to a route yet, so its payout is not set. Ask an admin.' },
        { status: 409 }
      )
    }

    // Atomic claim: only one concurrent request gets the block.
    const now = new Date().toISOString()
    const claimedBlock = await claimAvailableBlock(db, block.id, now)
    if (!claimedBlock) {
      return Response.json(
        { message: 'This block has already been claimed by another worker' },
        { status: 409 }
      )
    }

    let job: typeof jobs.$inferSelect
    try {
      ;[job] = await db
        .insert(jobs)
        .values({
          blockId: block.id,
          workerId,
          status: 'claimed',
          claimedAt: now,
          payoutAmount: String(payoutCents),
        })
        .returning()
    } catch (insertError) {
      // Don't strand the block as in_progress with no job.
      await releaseBlockClaim(db, block.id, new Date().toISOString())
      throw insertError
    }

    // TODO: Send push notification / SMS to worker with job details

    return Response.json({
      success: true,
      job: {
        id: job.id,
        blockId: block.id,
        block: {
          id: block.id,
          name: block.name,
          description: block.description,
          location: {
            neighborhood: block.locationNeighborhood,
            boundary: block.locationBoundary,
          },
        },
        claimedAt: job.claimedAt,
      },
      message: 'Block claimed! You have 24 hours to complete the cleaning.',
    })
  } catch (error) {
    console.error('Job claim error:', error)
    return Response.json(
      { message: 'Failed to claim block. Please try again.' },
      { status: 500 }
    )
  }
}
