import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, pointsLedger, workers } from '@/db/schema'
import { and, desc, eq, gt, sql } from 'drizzle-orm'
import { requireAdmin } from '@/lib/admin-auth'
import { verifyMobileToken } from '@/lib/mobile-auth'
import { toCommunityReport } from '@/lib/spot-utils'
import { OBSERVER_SPOT_REWARD } from '@/lib/points-config'

export async function GET(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const reports = await db
      .select()
      .from(communityReports)
      .orderBy(desc(communityReports.createdAt))
      .limit(500)

    const fullReports = reports.map((r) => toCommunityReport(r))

    return NextResponse.json({ data: fullReports, success: true })
  } catch (error) {
    console.error('Admin list spots error:', error)
    return NextResponse.json({ error: 'Failed to list spots' }, { status: 500 })
  }
}

export async function POST(request: NextRequest) {
  try {
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json(
        { data: null, success: false, message: 'Authorization required' },
        { status: 401 }
      )
    }

    const token = authHeader.substring(7)
    const auth = await verifyMobileToken(token)
    if (!auth) {
      return NextResponse.json(
        { data: null, success: false, message: 'Invalid token' },
        { status: 401 }
      )
    }

    if (auth.role !== 'admin') {
      return NextResponse.json(
        { data: null, success: false, message: 'Admin access required' },
        { status: 403 }
      )
    }

    const body = await request.json()
    const {
      type,
      lat,
      lng,
      description,
      mediaUrls,
      affectedRadiusMeters,
      affectedArea,
      tags,
      // Optional attribution: when an admin records a spot that another
      // worker actually discovered, set discoveredByWorkerId to that
      // worker's id. The spot becomes attributed to them and they
      // receive the standard observer reward instead of the admin.
      discoveredByWorkerId,
    } = body
    const numericLat = Number(lat)
    const numericLng = Number(lng)
    let effectiveRadius = affectedRadiusMeters == null ? 15 : Number(affectedRadiusMeters)

    if (affectedArea) {
      if (affectedArea.type === 'circle') {
        effectiveRadius = Number(affectedArea.radiusMeters)
      } else if (affectedArea.type === 'rectangle') {
        const widthMeters = Number(affectedArea.widthMeters)
        const heightMeters = Number(affectedArea.heightMeters)
        effectiveRadius = Math.sqrt(widthMeters * widthMeters + heightMeters * heightMeters) / 2
      }
    }

    // Strict coordinate validation: must arrive as actual numbers (not
    // empty strings, booleans, etc. that Number() coerces to 0 silently).
    if (
      typeof lat !== 'number' ||
      typeof lng !== 'number' ||
      !Number.isFinite(numericLat) ||
      !Number.isFinite(numericLng) ||
      numericLat <= -90 ||
      numericLat >= 90 ||
      numericLng <= -180 ||
      numericLng >= 180
    ) {
      return NextResponse.json(
        { data: null, success: false, message: 'lat and lng must be valid numeric coordinates' },
        { status: 400 }
      )
    }

    if (!Number.isFinite(effectiveRadius) || effectiveRadius < 1 || effectiveRadius > 500) {
      return NextResponse.json(
        { data: null, success: false, message: 'affectedRadiusMeters must be between 1 and 500' },
        { status: 400 }
      )
    }

    if (mediaUrls && (!Array.isArray(mediaUrls) || mediaUrls.length > 10)) {
      return NextResponse.json(
        { data: null, success: false, message: 'mediaUrls must contain no more than 10 items' },
        { status: 400 }
      )
    }

    // Rate-limit window for the plain-select check below. Note: the
    // neon-http driver (site/src/db/index.ts) has no `db.transaction`
    // support, so unlike a SERIALIZABLE-wrapped check this can no longer
    // guarantee two concurrent admin POSTs can't both observe count=29 and
    // both pass (ending up with 31 in the hour). Accepted as a best-effort
    // limit for this low-volume admin-only endpoint rather than switching
    // drivers.
    const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString()

    // Resolve attribution. If discoveredByWorkerId is provided and points
    // to a real worker (and isn't the admin themselves), attribute the
    // spot to them; otherwise the admin remains the reporter and no
    // gamification points are awarded.
    let attributedWorkerId = auth.workerId
    let attributedWorkerName: string = 'GCC'
    let pointsAwardedTo: number | null = null
    let pointsAwardedAmount = 0

    if (discoveredByWorkerId != null) {
      const id = Number(discoveredByWorkerId)
      if (!Number.isInteger(id) || id <= 0) {
        return NextResponse.json(
          { data: null, success: false, message: 'discoveredByWorkerId must be a positive integer' },
          { status: 400 },
        )
      }
      if (id === auth.workerId) {
        // Self-attribution would let an admin farm observer points by
        // crediting their own admin actions — refuse explicitly so the
        // ledger never reflects this pattern.
        return NextResponse.json(
          { data: null, success: false, message: 'Cannot self-attribute admin spots' },
          { status: 400 },
        )
      }
      const [discoverer] = await db
        .select({ id: workers.id, name: workers.name })
        .from(workers)
        .where(eq(workers.id, id))
        .limit(1)
      if (!discoverer) {
        return NextResponse.json(
          { data: null, success: false, message: 'discoveredByWorkerId not found' },
          { status: 400 },
        )
      }
      attributedWorkerId = discoverer.id
      attributedWorkerName = discoverer.name
      pointsAwardedAmount = OBSERVER_SPOT_REWARD
      pointsAwardedTo = discoverer.id
    }

    // Sequential, non-transactional statements (see note above re:
    // neon-http). Ordered so a mid-request crash leaves recoverable state:
    // rate-limit check first (plain select, no writes), then the report
    // insert, then — only if points are owed — the ledger row (append-only
    // source of truth) BEFORE the denormalized total it derives from.
    const [recentCreateCount] = await db
      .select({ count: sql<number>`count(*)` })
      .from(communityReports)
      .where(
        and(
          eq(communityReports.reporterId, auth.workerId),
          gt(communityReports.createdAt, oneHourAgo),
        ),
      )

    if ((Number(recentCreateCount?.count) || 0) >= 30) {
      return NextResponse.json(
        { data: null, success: false, message: 'Rate limit exceeded: maximum 30 admin-created spots per hour' },
        { status: 429 },
      )
    }

    const [insertedReport] = await db
      .insert(communityReports)
      .values({
        reporterType: 'worker',
        reporterId: attributedWorkerId,
        reporterName: attributedWorkerName,
        type: type === 'sighting' ? 'sighting' : 'volunteer_cleanup',
        description: description || null,
        coordinates: [numericLng, numericLat],
        mediaIds: mediaUrls && mediaUrls.length > 0 ? mediaUrls : null,
        affectedRadiusMeters: String(effectiveRadius),
        affectedArea: affectedArea || null,
        tags: tags && tags.length > 0 ? tags : null,
        status: 'pending',
        showOnMap: true,
        capturedAt: new Date().toISOString(),
        source: pointsAwardedTo != null ? 'user' : 'admin',
      })
      .returning()

    if (pointsAwardedTo != null && pointsAwardedAmount > 0) {
      await db.insert(pointsLedger).values({
        workerId: pointsAwardedTo,
        action: 'spot_created',
        points: pointsAwardedAmount,
        referenceType: 'spot',
        referenceId: insertedReport.id,
      })
      await db
        .update(workers)
        .set({
          statsCommunityPoints: sql`COALESCE(${workers.statsCommunityPoints}, 0) + ${pointsAwardedAmount}`,
        })
        .where(eq(workers.id, pointsAwardedTo))
    }

    const report = toCommunityReport(insertedReport)

    return NextResponse.json({
      data: {
        report,
        matchingBounties: [],
        pointsAwarded: pointsAwardedAmount,
        attributedWorkerId: pointsAwardedTo,
      },
    })
  } catch (error) {
    console.error('Admin create spot error:', error)
    return NextResponse.json({ error: 'Failed to create report' }, { status: 500 })
  }
}
