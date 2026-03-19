/**
 * Volunteer Cleanup API
 * POST /api/spots/:id/volunteer          - Start cleanup (before photos, sets status to acknowledged)
 * POST /api/spots/:id/volunteer?complete  - Complete cleanup (after photos, resolves)
 * POST /api/spots/:id/volunteer?already-clean - Report spot already cleaned (awards report bonus)
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, pointsLedger, workers } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'

export const dynamic = 'force-dynamic'

type RouteContext = { params: Promise<{ id: string }> }

async function requireAuth(request: NextRequest) {
  const authHeader = request.headers.get('authorization')
  if (!authHeader?.startsWith('Bearer ')) return null
  return verifyMobileToken(authHeader.substring(7))
}

export async function POST(request: NextRequest, context: RouteContext) {
  try {
    const auth = await requireAuth(request)
    if (!auth) {
      return NextResponse.json({ data: null, success: false, message: 'Authorization required' }, { status: 401 })
    }

    const { id } = await context.params
    const spotId = parseInt(id, 10)
    if (isNaN(spotId)) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid spot ID' }, { status: 400 })
    }

    // Look up the report
    const [report] = await db
      .select()
      .from(communityReports)
      .where(eq(communityReports.id, spotId))
      .limit(1)

    if (!report) {
      return NextResponse.json({ data: null, success: false, message: 'Spot report not found' }, { status: 404 })
    }

    const { searchParams } = new URL(request.url)
    const body = await request.json().catch(() => ({}))

    // ── Already Clean Path ────────────────────────────────────
    if (searchParams.has('already-clean')) {
      const { beforeMediaUrls } = body as { beforeMediaUrls?: string[] }

      // Update report status to resolved and store before photos as evidence
      await db
        .update(communityReports)
        .set({
          status: 'resolved',
          showOnMap: false,
          beforePhotos: beforeMediaUrls && beforeMediaUrls.length > 0 ? beforeMediaUrls : null,
          reviewedAt: new Date().toISOString(),
          reviewedById: auth.workerId,
          updatedAt: new Date().toISOString(),
        })
        .where(eq(communityReports.id, spotId))

      // Award already-clean report bonus via points ledger
      const reportBonusPoints = 5
      await db.insert(pointsLedger).values({
        workerId: auth.workerId,
        action: 'already_clean_report',
        points: reportBonusPoints,
        referenceType: 'spot',
        referenceId: spotId,
      })
      await db
        .update(workers)
        .set({
          statsCommunityPoints: sql`COALESCE(${workers.statsCommunityPoints}, 0) + ${reportBonusPoints}`,
        })
        .where(eq(workers.id, auth.workerId))

      return NextResponse.json({
        data: {
          id: spotId,
          reportId: spotId,
          workerId: auth.workerId,
          beforeMediaUrls: beforeMediaUrls || [],
          afterMediaUrls: [],
          trashHeapMediaUrl: null,
          aiTrashAnalysis: null,
          locationLat: 0,
          locationLng: 0,
          locationVerified: false,
          startedAt: new Date().toISOString(),
          completedAt: new Date().toISOString(),
          durationMinutes: 0,
          pdfUrl: null,
          status: 'already_clean',
          verificationNotes: 'Spot was already clean when volunteer arrived',
          pointsAwarded: reportBonusPoints,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
        success: true,
        message: null,
      })
    }

    // ── Complete Cleanup Path ─────────────────────────────────
    if (searchParams.has('complete')) {
      const { afterMediaUrls, trashHeapMediaUrl, durationMinutes } = body as {
        afterMediaUrls?: string[]
        trashHeapMediaUrl?: string
        durationMinutes?: number
      }

      // Update report with after photos and resolve
      await db
        .update(communityReports)
        .set({
          status: 'resolved',
          showOnMap: false,
          afterPhotos: afterMediaUrls && afterMediaUrls.length > 0 ? afterMediaUrls : null,
          cleanupDurationMinutes: durationMinutes || null,
          reviewedAt: new Date().toISOString(),
          reviewedById: auth.workerId,
          updatedAt: new Date().toISOString(),
        })
        .where(eq(communityReports.id, spotId))

      // Award volunteer cleanup points via points ledger
      const cleanupBonusPoints = 50
      await db.insert(pointsLedger).values({
        workerId: auth.workerId,
        action: 'volunteer_cleanup',
        points: cleanupBonusPoints,
        referenceType: 'spot',
        referenceId: spotId,
      })
      await db
        .update(workers)
        .set({
          statsCommunityPoints: sql`COALESCE(${workers.statsCommunityPoints}, 0) + ${cleanupBonusPoints}`,
        })
        .where(eq(workers.id, auth.workerId))

      // Fetch coordinates from original report
      const coords = report.coordinates as [number, number] | null

      return NextResponse.json({
        data: {
          id: spotId,
          reportId: spotId,
          workerId: auth.workerId,
          beforeMediaUrls: (report.beforePhotos as string[]) || [],
          afterMediaUrls: afterMediaUrls || [],
          trashHeapMediaUrl: trashHeapMediaUrl || null,
          aiTrashAnalysis: null,
          locationLat: coords ? coords[1] : 0,
          locationLng: coords ? coords[0] : 0,
          locationVerified: true,
          startedAt: report.updatedAt,
          completedAt: new Date().toISOString(),
          durationMinutes: durationMinutes || 0,
          pdfUrl: null,
          status: 'completed',
          verificationNotes: null,
          pointsAwarded: cleanupBonusPoints,
          createdAt: report.createdAt,
          updatedAt: new Date().toISOString(),
        },
        success: true,
        message: null,
      })
    }

    // ── Start Cleanup Path (default POST) ─────────────────────
    const { beforeMediaUrls } = body as { beforeMediaUrls?: string[] }

    // Update report: set status to acknowledged, store before photos
    await db
      .update(communityReports)
      .set({
        status: 'acknowledged',
        beforePhotos: beforeMediaUrls && beforeMediaUrls.length > 0 ? beforeMediaUrls : null,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(communityReports.id, spotId))

    const coords = report.coordinates as [number, number] | null

    return NextResponse.json({
      data: {
        id: spotId,
        reportId: spotId,
        workerId: auth.workerId,
        beforeMediaUrls: beforeMediaUrls || [],
        afterMediaUrls: [],
        trashHeapMediaUrl: null,
        aiTrashAnalysis: null,
        locationLat: coords ? coords[1] : 0,
        locationLng: coords ? coords[0] : 0,
        locationVerified: false,
        startedAt: new Date().toISOString(),
        completedAt: null,
        durationMinutes: null,
        pdfUrl: null,
        status: 'in_progress',
        verificationNotes: null,
        pointsAwarded: 0,
        createdAt: report.createdAt,
        updatedAt: new Date().toISOString(),
      },
    })
  } catch (error) {
    console.error('Volunteer cleanup error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to process volunteer cleanup' }, { status: 500 })
  }
}
