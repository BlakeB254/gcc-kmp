/**
 * Spots API
 * GET  /api/spots - Returns community reports (full objects for admin, map markers for website)
 * POST /api/spots - Create a spot report (from iOS app)
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, pointsLedger, workers } from '@/db/schema'
import { eq, and, or, gt, isNull, desc, sql } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'
import { autoTagFromDescription, resolveSpotTags, toCommunityReport } from '@/lib/spot-utils'
import { reverseGeocode } from '@/lib/geo-lookup'
import { recordWorldObservation } from '@/lib/world-observations'

export const dynamic = 'force-dynamic'

export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url)
    const limit = Math.min(parseInt(searchParams.get('limit') || '100', 10), 500)
    const lat = searchParams.get('lat') ? parseFloat(searchParams.get('lat')!) : null
    const lng = searchParams.get('lng') ? parseFloat(searchParams.get('lng')!) : null
    const radiusMeters = searchParams.get('radius') ? parseFloat(searchParams.get('radius')!) : null

    // Build proximity bounding box filter if lat/lng/radius provided
    // ~0.000009 degrees per meter at Chicago latitude
    const degPerMeter = 0.000009
    const proximityFilter = lat != null && lng != null && radiusMeters != null
      ? and(
          or(
            // Object-style coordinates {lat, lng}
            and(
              sql`(${communityReports.coordinates}->>'lng')::float BETWEEN ${lng - radiusMeters * degPerMeter} AND ${lng + radiusMeters * degPerMeter}`,
              sql`(${communityReports.coordinates}->>'lat')::float BETWEEN ${lat - radiusMeters * degPerMeter} AND ${lat + radiusMeters * degPerMeter}`,
            ),
            // Array-style coordinates [lng, lat]
            and(
              sql`(${communityReports.coordinates}->>0)::float BETWEEN ${lng - radiusMeters * degPerMeter} AND ${lng + radiusMeters * degPerMeter}`,
              sql`(${communityReports.coordinates}->>1)::float BETWEEN ${lat - radiusMeters * degPerMeter} AND ${lat + radiusMeters * degPerMeter}`,
            ),
          ),
        )
      : undefined

    // Fetch community reports that should be visible on the map
    const reports = await db
      .select()
      .from(communityReports)
      .where(
        and(
          // showOnMap: treat NULL as true (column was added after initial data)
          or(eq(communityReports.showOnMap, true), isNull(communityReports.showOnMap)),
          or(
            eq(communityReports.status, 'pending'),
            eq(communityReports.status, 'verified'),
            eq(communityReports.status, 'acknowledged')
          ),
          or(
            isNull(communityReports.mapExpiresAt),
            gt(communityReports.mapExpiresAt, new Date().toISOString())
          ),
          proximityFilter,
        )
      )
      .orderBy(desc(communityReports.createdAt))
      .limit(limit)

    // Pre-compute tags once per report to avoid double autoTagFromDescription calls
    const tagsCache = new Map<number, string[]>()
    for (const r of reports) {
      const raw = (r as any).tags
      tagsCache.set(r.id, (Array.isArray(raw) && raw.length > 0) ? raw : autoTagFromDescription(r.description))
    }

    // Map markers for website frontend - normalize coordinates to [lng, lat]
    const spots = reports.map((report) => {
      const raw = report.coordinates as any
      let coords: [number, number]
      if (Array.isArray(raw)) {
        coords = [raw[0], raw[1]]
      } else if (raw && typeof raw === 'object') {
        coords = [raw.lng ?? raw.lon ?? 0, raw.lat ?? 0]
      } else {
        coords = [0, 0]
      }
      return {
        id: String(report.id),
        coordinates: coords,
        radiusMeters: parseFloat(report.affectedRadiusMeters || '15'),
        label: report.title || (report.type === 'sighting' ? 'Sighting' : 'Volunteer Cleanup'),
        tags: tagsCache.get(report.id) || [],
        affectedArea: (report as any).affectedArea || null,
        needsVerification: report.nextVerificationDue ? new Date(report.nextVerificationDue) <= new Date() : true,
      }
    })

    // Full CommunityReport objects for iOS admin panel (via ApiResponse<T>.data)
    const fullReports = reports.map((r) => toCommunityReport(r, tagsCache.get(r.id)))

    return NextResponse.json({ spots, data: fullReports, success: true, message: null })
  } catch (error) {
    console.error('Error fetching spots:', error)
    return NextResponse.json({ spots: [], data: [], success: true, message: null })
  }
}

export async function POST(request: NextRequest) {
  try {
    // Authenticate via mobile Bearer token
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json({ data: null, success: false, message: 'Authorization required' }, { status: 401 })
    }
    const token = authHeader.substring(7)
    const auth = await verifyMobileToken(token)
    if (!auth) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid token' }, { status: 401 })
    }

    // Look up worker name
    const [worker] = await db
      .select({ id: workers.id, name: workers.name })
      .from(workers)
      .where(eq(workers.id, auth.workerId))
      .limit(1)

    const workerName = worker?.name || 'Anonymous'

    const body = await request.json()
    const {
      type,
      lat,
      lng,
      description,
      mediaUrls,
      mediaCaptions,
      affectedRadiusMeters,
      affectedArea,
      tags,
      reporterName,
      depthAnalysis,
      captureAttestation,
      onDeviceAiResult,
      duplicateOverrideReason,
      observedItems,
      objectCapture,
    } = body

    // Validate coordinates
    if (lat == null || lng == null) {
      return NextResponse.json({ data: null, success: false, message: 'lat and lng are required' }, { status: 400 })
    }

    // Server-side image limit: max 3 photos
    if (mediaUrls && mediaUrls.length > 3) {
      return NextResponse.json({ data: null, success: false, message: 'Maximum 3 photos allowed per spot report' }, { status: 400 })
    }

    // Duplicate spot check: reject if another spot exists within ~15m.
    // Skip if user provided a duplicate override reason (they acknowledged the duplicate).
    //
    // 50m was too aggressive — it crossed streets and alleys in dense
    // urban grids and blocked reports of legitimately separate spots a
    // few feet across a curb. ~15m matches the default affectedRadiusMeters
    // for a single dump and corresponds to roughly one residential lot
    // width at Chicago latitudes (0.000135° ≈ 15m).
    const DUPLICATE_RADIUS_DEG = 0.000135
    // Scan submissions (the world-scan camera flow always carries
    // observedItems, even when empty) SUPERSEDE older active reports at the
    // same location instead of being rejected as duplicates: the latest scan
    // is fresher evidence, and its pin replaces the stale ones on the map.
    // Only non-scan submissions (no observedItems array) keep the 409 guard.
    const isScanSubmission = Array.isArray(observedItems)
    const nearbyActiveReports = await db
      .select({ id: communityReports.id })
      .from(communityReports)
      .where(
        and(
          or(
            eq(communityReports.status, 'pending'),
            eq(communityReports.status, 'verified')
          ),
          or(
            // Object-style coordinates {lat, lng}
            and(
              sql`(${communityReports.coordinates}->>'lng')::float BETWEEN ${lng - DUPLICATE_RADIUS_DEG} AND ${lng + DUPLICATE_RADIUS_DEG}`,
              sql`(${communityReports.coordinates}->>'lat')::float BETWEEN ${lat - DUPLICATE_RADIUS_DEG} AND ${lat + DUPLICATE_RADIUS_DEG}`,
            ),
            // Array-style coordinates [lng, lat]
            and(
              sql`(${communityReports.coordinates}->>0)::float BETWEEN ${lng - DUPLICATE_RADIUS_DEG} AND ${lng + DUPLICATE_RADIUS_DEG}`,
              sql`(${communityReports.coordinates}->>1)::float BETWEEN ${lat - DUPLICATE_RADIUS_DEG} AND ${lat + DUPLICATE_RADIUS_DEG}`,
            ),
          ),
        )
      )

    if (!isScanSubmission && !duplicateOverrideReason && nearbyActiveReports.length > 0) {
      return NextResponse.json(
        { data: null, success: false, message: 'A spot report already exists within 15m of this location', existingSpotId: nearbyActiveReports[0].id },
        { status: 409 }
      )
    }

    // Derive affectedRadiusMeters from affectedArea if present
    let effectiveRadius = affectedRadiusMeters || 15
    if (affectedArea) {
      if (affectedArea.type === 'circle') {
        effectiveRadius = affectedArea.radiusMeters
      } else if (affectedArea.type === 'rectangle') {
        // Use half-diagonal as effective radius
        const w = affectedArea.widthMeters || 10
        const h = affectedArea.heightMeters || 10
        effectiveRadius = Math.sqrt(w * w + h * h) / 2
      }
    }

    // Auto-tag from description if no tags provided
    const effectiveTags = tags && tags.length > 0 ? tags : autoTagFromDescription(description)

    // Create the community report with verification lifecycle fields
    const next24h = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()
    const [newReport] = await db
      .insert(communityReports)
      .values({
        reporterType: 'worker',
        reporterId: auth.workerId,
        reporterName: reporterName || workerName,
        type: type === 'sighting' ? 'sighting' : 'volunteer_cleanup',
        description: description || null,
        coordinates: [lng, lat], // GeoJSON [lng, lat]
        mediaIds: mediaUrls && mediaUrls.length > 0 ? mediaUrls : null,
        mediaCaptions: mediaCaptions && mediaCaptions.length > 0 ? mediaCaptions : null,
        affectedRadiusMeters: String(effectiveRadius),
        affectedArea: affectedArea || null,
        tags: effectiveTags.length > 0 ? effectiveTags : null,
        status: 'pending',
        showOnMap: true,
        capturedAt: new Date().toISOString(),
        // Verification lifecycle
        lastVerifiedAt: new Date().toISOString(),
        verificationCount: 0,
        nextVerificationDue: next24h,
        missedVerifications: 0,
        depthAnalysis: depthAnalysis || null,
        captureAttestation: captureAttestation || null,
        onDeviceAiResult: onDeviceAiResult || null,
        duplicateOverrideReason: duplicateOverrideReason || null,
        source: 'user', // User-submitted spots (vs 'admin' for GCC base data)
      })
      .returning()

    // Preferred: client-supplied per-item positions from the environment scan
    // (heading-projected at capture time). Each becomes a world observation at
    // its own coordinates so the map/heatmap layers place items accurately.
    // Sanity-gated to 150m of the report location to keep junk out of the twin.
    const OBSERVATION_DOMAINS = ['litter', 'vegetation', 'construction', 'land_use'] as const
    const clientItems: { lat: number; lng: number; itemType: string; severity?: number; confidence?: number; estimatedWeightGrams?: number; estimatedWidthMeters?: number; domain?: (typeof OBSERVATION_DOMAINS)[number] }[] =
      Array.isArray(observedItems)
        ? observedItems
            .filter((it: any) =>
              Number.isFinite(Number(it?.lat)) &&
              Number.isFinite(Number(it?.lng)) &&
              typeof it?.itemType === 'string' &&
              Math.abs(Number(it.lat) - lat) < 0.0015 &&
              Math.abs(Number(it.lng) - lng) < 0.002)
            .slice(0, 25)
            .map((it: any) => ({
              lat: Number(it.lat),
              lng: Number(it.lng),
              itemType: String(it.itemType).slice(0, 50),
              severity: Number.isFinite(Number(it.severity)) ? Number(it.severity) : undefined,
              confidence: Number.isFinite(Number(it.confidence)) ? Number(it.confidence) : undefined,
              estimatedWeightGrams: Number.isFinite(Number(it.estimatedWeightGrams)) ? Math.round(Number(it.estimatedWeightGrams)) : undefined,
              estimatedWidthMeters: Number.isFinite(Number(it.estimatedWidthMeters)) ? Number(it.estimatedWidthMeters) : undefined,
              domain: OBSERVATION_DOMAINS.includes(it.domain) ? (it.domain as (typeof OBSERVATION_DOMAINS)[number]) : undefined,
            }))
        : []

    // Scan supersede: the fresh scan's data replaces older active pins it
    // covered — near the report anchor AND near every scanned trash item.
    // Old pins resolve (they disappear from the map); heat continuity comes
    // from the new report + its world observations wherever the scan
    // re-confirmed trash, and fades where the scan found the area clean.
    if (isScanSubmission) {
      const coveredPoints = [{ lat, lng }, ...clientItems.map((it) => ({ lat: it.lat, lng: it.lng }))]
      const boxConditions = coveredPoints.map((p) =>
        or(
          and(
            sql`(${communityReports.coordinates}->>'lng')::float BETWEEN ${p.lng - DUPLICATE_RADIUS_DEG} AND ${p.lng + DUPLICATE_RADIUS_DEG}`,
            sql`(${communityReports.coordinates}->>'lat')::float BETWEEN ${p.lat - DUPLICATE_RADIUS_DEG} AND ${p.lat + DUPLICATE_RADIUS_DEG}`,
          ),
          and(
            sql`(${communityReports.coordinates}->>0)::float BETWEEN ${p.lng - DUPLICATE_RADIUS_DEG} AND ${p.lng + DUPLICATE_RADIUS_DEG}`,
            sql`(${communityReports.coordinates}->>1)::float BETWEEN ${p.lat - DUPLICATE_RADIUS_DEG} AND ${p.lat + DUPLICATE_RADIUS_DEG}`,
          ),
        )
      )
      const superseded = await db
        .update(communityReports)
        .set({
          status: 'resolved',
          reviewNotes: `Superseded by scan report #${newReport.id} — fresh scan data covers this location`,
        })
        .where(
          and(
            or(
              eq(communityReports.status, 'pending'),
              eq(communityReports.status, 'verified')
            ),
            sql`${communityReports.id} != ${newReport.id}`,
            or(...boxConditions),
          )
        )
        .returning({ id: communityReports.id })
      if (superseded.length > 0) {
        console.log(`Scan report ${newReport.id} superseded stale reports: ${superseded.map((r) => r.id).join(', ')}`)
      }
    }

    for (const item of clientItems) {
      await recordWorldObservation({
        sourceType: 'spot_report',
        communityReportId: newReport.id,
        lat: item.lat,
        lng: item.lng,
        itemType: item.itemType,
        state: 'dirty',
        domain: item.domain,
        attributes: (item.severity !== undefined || item.estimatedWeightGrams !== undefined || item.estimatedWidthMeters !== undefined)
          ? {
              ...(item.severity !== undefined ? { severity: item.severity } : {}),
              ...(item.estimatedWeightGrams !== undefined ? { estimatedWeightGrams: item.estimatedWeightGrams } : {}),
              ...(item.estimatedWidthMeters !== undefined ? { estimatedWidthMeters: item.estimatedWidthMeters } : {}),
            }
          : undefined,
        confidence: item.confidence,
        mediaUrl: Array.isArray(mediaUrls) && mediaUrls.length > 0 ? mediaUrls[0] : undefined,
        capturedAt: newReport.capturedAt ?? new Date().toISOString(),
      })
    }

    // Fallback: explode on-device AI detections into per-item world observations
    // at the report location; single observation from the first tag (or
    // 'general_litter') when no structured AI result is present.
    const detectedItems: { itemType: string; severity?: number; confidence?: number }[] =
      Array.isArray(onDeviceAiResult?.items) && onDeviceAiResult.items.length > 0
        ? onDeviceAiResult.items.map((it: any) => ({
            itemType: it.type ?? it.itemType ?? 'general_litter',
            severity: Number.isFinite(Number(it.severity)) ? Number(it.severity) : undefined,
            confidence: Number.isFinite(Number(it.confidence)) ? Number(it.confidence) : undefined,
          }))
        : [{ itemType: (effectiveTags?.[0] as string) ?? 'general_litter' }]

    // Guided Object Capture (photogrammetry) of a large pile, when performed:
    // its measured bounds + volume-derived weight ride along on the anchor
    // observation's attributes. Each numeric field is validated independently
    // so a partial/garbled payload contributes only its sound values.
    const objectCaptureAttributes: Record<string, unknown> = {}
    if (objectCapture && typeof objectCapture === 'object') {
      if (typeof objectCapture.r2Key === 'string') objectCaptureAttributes.objectCaptureR2Key = objectCapture.r2Key
      if (Number.isFinite(Number(objectCapture.widthMeters))) objectCaptureAttributes.objectCaptureWidthMeters = Number(objectCapture.widthMeters)
      if (Number.isFinite(Number(objectCapture.heightMeters))) objectCaptureAttributes.objectCaptureHeightMeters = Number(objectCapture.heightMeters)
      if (Number.isFinite(Number(objectCapture.depthMeters))) objectCaptureAttributes.objectCaptureDepthMeters = Number(objectCapture.depthMeters)
      if (Number.isFinite(Number(objectCapture.volumeLiters))) objectCaptureAttributes.objectCaptureVolumeLiters = Number(objectCapture.volumeLiters)
      if (Number.isFinite(Number(objectCapture.estimatedWeightGrams))) objectCaptureAttributes.objectCaptureWeightGrams = Math.round(Number(objectCapture.estimatedWeightGrams))
    }
    const hasObjectCapture = Object.keys(objectCaptureAttributes).length > 0

    for (const item of clientItems.length > 0 ? [] : detectedItems) {
      await recordWorldObservation({
        sourceType: 'spot_report',
        communityReportId: newReport.id,
        lat,
        lng,
        itemType: item.itemType,
        state: 'dirty',
        attributes: (item.severity !== undefined || hasObjectCapture)
          ? {
              ...(item.severity !== undefined ? { severity: item.severity } : {}),
              ...objectCaptureAttributes,
            }
          : undefined,
        confidence: item.confidence,
        mediaUrl: Array.isArray(mediaUrls) && mediaUrls.length > 0 ? mediaUrls[0] : undefined,
        depthData: depthAnalysis ? JSON.stringify(depthAnalysis) : undefined,
        capturedAt: newReport.capturedAt ?? new Date().toISOString(),
      })
    }

    // Object Capture with scanned items present: the fallback anchor loop
    // above is skipped when clientItems exist, so the measured pile gets its
    // own anchor observation — otherwise the 3D capture data would vanish on
    // exactly the scans most likely to include it.
    if (hasObjectCapture && clientItems.length > 0) {
      await recordWorldObservation({
        sourceType: 'spot_report',
        communityReportId: newReport.id,
        lat,
        lng,
        itemType: 'debris_pile',
        state: 'dirty',
        attributes: objectCaptureAttributes,
        mediaUrl: Array.isArray(mediaUrls) && mediaUrls.length > 0 ? mediaUrls[0] : undefined,
        depthData: depthAnalysis ? JSON.stringify(depthAnalysis) : undefined,
        capturedAt: newReport.capturedAt ?? new Date().toISOString(),
      })
    }

    // Reverse geocode to populate geographic categorization (best-effort, non-blocking)
    reverseGeocode(lat, lng).then(async (geo) => {
      if (geo.city || geo.state || geo.communityArea) {
        await db
          .update(communityReports)
          .set({
            geoCity: geo.city,
            geoState: geo.state,
            geoCommunityArea: geo.communityArea,
            geoCommunityAreaNumber: geo.communityAreaNumber,
            geoWard: geo.ward,
            geoZipCode: geo.zipCode,
            geoCensusTract: geo.censusTract,
            geoPoliceDistrict: geo.policeDistrict,
            geoStreetAddress: geo.streetAddress,
          })
          .where(eq(communityReports.id, newReport.id))
      }
    }).catch(() => {}) // Silently ignore geocoding failures

    // Award 10 observer points for creating a spot report
    const observerPoints = 10
    await db.insert(pointsLedger).values({
      workerId: auth.workerId,
      action: 'spot_created',
      points: observerPoints,
      referenceType: 'spot',
      referenceId: newReport.id,
    })

    // Update worker's denormalized points total
    await db
      .update(workers)
      .set({
        statsCommunityPoints: sql`COALESCE(${workers.statsCommunityPoints}, 0) + ${observerPoints}`,
      })
      .where(eq(workers.id, auth.workerId))

    // Build response matching SpotResponse { report, matchingBounties }
    const report = toCommunityReport(newReport)

    return NextResponse.json({
      data: {
        report,
        matchingBounties: [],
        pointsAwarded: observerPoints,
      },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('Create spot report error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to create report' }, { status: 500 })
  }
}
