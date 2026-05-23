import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

export async function GET(_request: NextRequest, context: RouteContext) {
  try {
    const { id } = await context.params
    const jobId = parseInt(id, 10)
    if (Number.isNaN(jobId)) {
      return NextResponse.json({ error: 'Invalid job ID', success: false }, { status: 400 })
    }

    const [job] = await db
      .select()
      .from(jobs)
      .where(eq(jobs.id, jobId))
      .limit(1)

    if (!job) {
      return NextResponse.json({ error: 'Job not found', success: false }, { status: 404 })
    }

    const routeMap = await routeIdsByBlockId([job.blockId])
    return NextResponse.json({
      data: toMobileJob(job, routeMap.get(job.blockId) ?? null),
      success: true,
    })
  } catch (error) {
    console.error('GET /api/jobs/[id] error:', error)
    return NextResponse.json({ error: 'Failed to fetch job', success: false }, { status: 500 })
  }
}
