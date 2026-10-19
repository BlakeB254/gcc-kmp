import { NextRequest } from 'next/server'
import { db } from '@/db'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { verifyToken } from '@/lib/auth'
import { claimWork } from '@/lib/job-lifecycle'

export const runtime = 'nodejs'

/**
 * POST /api/jobs/claim
 * Claim an available block - first come, first served
 *
 * The rules live in claimWork (lib/job-lifecycle.ts), shared with the app's
 * POST /api/jobs/[id]/claim so both paths enforce the same things: verified
 * worker, one active job, block `available` and linked to a route, and an
 * atomic block flip available → in_progress (two racing workers: one gets
 * the block, the other 409). The job's payout comes from the block's route
 * (routes.payout_amount, cents), never from blocks.funding_goal, which holds
 * dollars on some legacy rows.
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
    const blockId = Number(body?.blockId)

    if (!Number.isInteger(blockId) || blockId <= 0) {
      return Response.json(
        { message: 'Block ID is required' },
        { status: 400 }
      )
    }

    const result = await claimWork(db, { blockId }, workerId, new Date().toISOString())
    if (!result.ok) {
      // `error` too: /worker/available reads data.error.
      return Response.json({ message: result.message, error: result.message }, { status: result.status })
    }
    const { job, block } = result

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
