import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { jobs } from '@/db/schema'
import { and, eq, inArray } from 'drizzle-orm'
import { requireMobileAuth } from '@/lib/mobile-auth'
import { routeIdsByBlockId, toMobileJob } from '@/lib/mobile-job-response'
import { EVIDENCE_ALLOWED_STATUSES, evidenceAllowedForStatus, isOwnEvidenceUrl } from '@/lib/job-lifecycle'
import { checkEvidencePhotos } from '@/lib/photo-fingerprint-db'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

type RouteContext = { params: Promise<{ id: string }> }

function appendEvidence(existing: unknown, item: Record<string, unknown>) {
  return [...(Array.isArray(existing) ? existing : []), item]
}

export async function PATCH(request: NextRequest, context: RouteContext) {
  const auth = await requireMobileAuth(request)
  if (!auth) {
    return NextResponse.json({ data: null, success: false, message: 'Not authenticated' }, { status: 401 })
  }

  try {
    const { id } = await context.params
    const jobId = parseInt(id, 10)
    if (Number.isNaN(jobId)) {
      return NextResponse.json({ error: 'Invalid job ID', success: false }, { status: 400 })
    }

    const body = await request.json().catch(() => null)
    const beforePhotoUrl = typeof body?.beforePhotoUrl === 'string' ? body.beforePhotoUrl : null
    const afterPhotoUrl = typeof body?.afterPhotoUrl === 'string' ? body.afterPhotoUrl : null

    if (!beforePhotoUrl || !afterPhotoUrl) {
      return NextResponse.json(
        { error: 'beforePhotoUrl and afterPhotoUrl are required', success: false },
        { status: 400 },
      )
    }

    const [existing] = await db.select().from(jobs).where(eq(jobs.id, jobId)).limit(1)
    if (!existing) {
      return NextResponse.json({ error: 'Job not found', success: false }, { status: 404 })
    }
    if (auth.role !== 'admin' && existing.workerId !== auth.workerId) {
      return NextResponse.json({ error: 'Job belongs to a different worker', success: false }, { status: 403 })
    }
    // Evidence is part of doing the work — once a job is submitted for
    // review (or completed/released) its evidence is frozen.
    if (!evidenceAllowedForStatus(existing.status)) {
      return NextResponse.json(
        { error: `Evidence can only be added while the job is claimed or in progress (status: ${existing.status})`, success: false },
        { status: 409 },
      )
    }
    // Only photos the job's worker uploaded through /api/spots/upload (our
    // blob store, under spots/{workerId}/) — never an arbitrary URL.
    if (
      existing.workerId == null ||
      !isOwnEvidenceUrl(beforePhotoUrl, existing.workerId) ||
      !isOwnEvidenceUrl(afterPhotoUrl, existing.workerId)
    ) {
      return NextResponse.json(
        { error: 'Evidence photos must be uploaded through the app', success: false },
        { status: 400 },
      )
    }

    // Fingerprint both photos (lib/photo-fingerprint.ts). The same photo
    // already submitted for a different job is refused; everything else
    // (identical/near-identical before & after, old EXIF, unverifiable blob)
    // is recorded as a flag for the reviewer.
    const jobWorkerId = existing.workerId
    const photoCheck = await checkEvidencePhotos(
      db,
      [
        { url: beforePhotoUrl, role: 'before' },
        { url: afterPhotoUrl, role: 'after' },
      ],
      { kind: 'job', id: jobId, workStartedAt: existing.claimedAt ?? existing.startedAt ?? null },
      { isOwn: (url) => isOwnEvidenceUrl(url, jobWorkerId), pairs: [[beforePhotoUrl, afterPhotoUrl]] },
    )
    if (photoCheck.reject) {
      return NextResponse.json({ error: photoCheck.reject, success: false }, { status: 409 })
    }

    const now = new Date().toISOString()
    const common = { capturedAt: now }
    const beforePhotos = appendEvidence(existing.beforePhotos, { ...common, url: beforePhotoUrl })
    const afterPhotos = appendEvidence(existing.afterPhotos, { ...common, url: afterPhotoUrl })

    const [updated] = await db
      .update(jobs)
      .set({
        beforePhotos,
        afterPhotos,
        updatedAt: now,
      })
      .where(and(eq(jobs.id, jobId), inArray(jobs.status, [...EVIDENCE_ALLOWED_STATUSES])))
      .returning()
    if (!updated) {
      return NextResponse.json({ error: 'Job is no longer in progress', success: false }, { status: 409 })
    }

    const routeMap = await routeIdsByBlockId([updated.blockId])
    return NextResponse.json({
      data: toMobileJob(updated, routeMap.get(updated.blockId) ?? null),
      success: true,
    })
  } catch (error) {
    console.error('PATCH /api/jobs/[id]/evidence error:', error)
    return NextResponse.json({ error: 'Failed to save job evidence', success: false }, { status: 500 })
  }
}
