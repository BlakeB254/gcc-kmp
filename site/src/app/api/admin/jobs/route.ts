import { NextResponse } from 'next/server'
import { desc, eq } from 'drizzle-orm'
import { db } from '@/db'
import { blocks, jobs, jobStatusEnum, routes, workers } from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

type JobStatus = (typeof jobStatusEnum.enumValues)[number]

const MAX_LIMIT = 100

const toIso = (value: string | null) => (value ? new Date(value).toISOString() : null)

/**
 * GET /api/admin/jobs?status=<enum_jobs_status>&page=1&limit=50
 *
 * Backs the Operations drill-in list in the mobile admin panel. Jobs carry no
 * route reference, so the route has to be reached through blocks.
 */
export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const { searchParams } = new URL(request.url)

    // Reject an unknown status here. Handing it to the query casts it to
    // enum_jobs_status inside Postgres, which raises and surfaces as an opaque
    // 500 rather than telling the caller the value was bad.
    const statusParam = searchParams.get('status')
    if (statusParam !== null && !jobStatusEnum.enumValues.includes(statusParam as JobStatus)) {
      return NextResponse.json(
        { error: `Unknown job status '${statusParam}'. Expected one of: ${jobStatusEnum.enumValues.join(', ')}` },
        { status: 400 }
      )
    }
    const status = statusParam as JobStatus | null

    const limit = Math.min(Math.max(parseInt(searchParams.get('limit') || '50', 10), 1), MAX_LIMIT)
    const page = Math.max(parseInt(searchParams.get('page') || '1', 10), 1)
    const offset = (page - 1) * limit

    const columns = {
      jobId: jobs.id,
      status: jobs.status,
      workerId: jobs.workerId,
      workerName: workers.name,
      blockId: jobs.blockId,
      blockName: blocks.name,
      routeId: blocks.routeId,
      routeCode: routes.code,
      routeName: routes.displayName,
      payoutAmount: jobs.payoutAmount,
      createdAt: jobs.createdAt,
      claimedAt: jobs.claimedAt,
      startedAt: jobs.startedAt,
      completedAt: jobs.completedAt,
    }

    // createdAt alone is not a total order — ties let LIMIT/OFFSET pages
    // overlap or drop rows. Tie-break on the serial id.
    const ordering = [desc(jobs.createdAt), desc(jobs.id)] as const

    const base = db
      .select(columns)
      .from(jobs)
      .leftJoin(blocks, eq(jobs.blockId, blocks.id))
      .leftJoin(routes, eq(blocks.routeId, routes.id))
      .leftJoin(workers, eq(jobs.workerId, workers.id))

    const rows = status
      ? await base.where(eq(jobs.status, status)).orderBy(...ordering).limit(limit).offset(offset)
      : await base.orderBy(...ordering).limit(limit).offset(offset)

    const data = rows.map((row) => ({
      jobId: row.jobId,
      status: row.status ?? 'available',
      workerId: row.workerId,
      workerName: row.workerName,
      blockId: row.blockId,
      blockName: row.blockName,
      routeId: row.routeId,
      routeCode: row.routeCode,
      routeName: row.routeName,
      payoutAmount: row.payoutAmount ? parseFloat(row.payoutAmount) : null,
      createdAt: new Date(row.createdAt).toISOString(),
      claimedAt: toIso(row.claimedAt),
      startedAt: toIso(row.startedAt),
      completedAt: toIso(row.completedAt),
    }))

    return NextResponse.json({ data })
  } catch (error) {
    console.error('Error fetching admin jobs:', error)
    return NextResponse.json({ error: 'Failed to fetch jobs' }, { status: 500 })
  }
}
