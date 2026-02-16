import { NextRequest } from 'next/server'
import { db } from '@/db'
import { blocks, workers, jobs } from '@/db/schema'
import { eq, and, inArray } from 'drizzle-orm'

export const runtime = 'nodejs'

/**
 * POST /api/jobs/claim
 * Claim an available block - first come, first served
 *
 * Uses optimistic locking to prevent race conditions when multiple
 * workers try to claim the same block simultaneously.
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { blockId, workerId } = body

    if (!blockId || !workerId) {
      return Response.json(
        { message: 'Block ID and Worker ID are required' },
        { status: 400 }
      )
    }

    // Verify worker exists and is approved
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, Number(workerId)))
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
          eq(jobs.workerId, Number(workerId)),
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

    // Create the job
    const [job] = await db
      .insert(jobs)
      .values({
        blockId: Number(blockId),
        workerId: Number(workerId),
        status: 'claimed',
        claimedAt: new Date().toISOString(),
        payoutAmount: block.fundingGoal || '10000',
      })
      .returning()

    // Update block status atomically
    await db
      .update(blocks)
      .set({
        status: 'in_progress',
        updatedAt: new Date().toISOString(),
      })
      .where(eq(blocks.id, Number(blockId)))

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
