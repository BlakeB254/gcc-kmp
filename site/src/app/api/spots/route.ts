/**
 * Spots API
 * GET  /api/spots - Returns community reports (full objects for admin, map markers for website)
 * POST /api/spots - Create a spot report (from iOS app)
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, pointsLedger, workers, type NewCommunityReport } from '@/db/schema'
import { eq, and, or, gt, isNull, desc, sql } from 'drizzle-orm'
import { verifyMobileToken } from '@/lib/mobile-auth'
import {
  autoTagFromDescription,
  resolveSpotTags,
  toCommunityReport,
  isScanSubmission as isScanSubmissionOf,
  resolvePointsAction,
  buildSyntheticScanReport,
} from '@/lib/spot-utils'
import { reverseGeocode } from '@/lib/geo-lookup'
import { toSpotMarker, type SpotMarkerSource } from '@/lib/spot-marker'
import {
  recordWorldObservation,
  gridCell,
  parseCoveredCellIds,
  resolveByCoveredCells,
  selectSubmittedObservationMedia,
} from '@/lib/world-observations'

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

    // Map markers for website frontend — coordinates normalised to [lng, lat]
    // and the spot's photos carried through. See lib/spot-marker.ts: markers
    // used to omit media entirely, so the map popup rendered no photos even
    // though the sibling `data` array below carried the same URLs.
    const spots = reports.map((report) =>
      toSpotMarker(report as SpotMarkerSource, tagsCache.get(report.id) || [])
    )

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
      coveredCellIds: rawCoveredCellIds,
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
    // A live-capture attestation also marks a scan: deployed clients send
    // observedItems = null when the scan confirmed zero items, and a
    // zero-item scan must supersede, not 409.
    const isScanSubmission = isScanSubmissionOf(observedItems, captureAttestation)
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

    // Report field values, computed for both paths — the manual path
    // inserts them as a communityReports row exactly as before; the scan
    // path (Amendment 2 / Task 1b, Change 1) uses the SAME values to build
    // a synthetic, non-persisted report for the response instead of
    // inserting anything (see buildSyntheticScanReport below).
    const next24h = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()
    const reportCapturedAt = new Date().toISOString()
    const reportValues: NewCommunityReport = {
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
      capturedAt: reportCapturedAt,
      // Verification lifecycle
      lastVerifiedAt: reportCapturedAt,
      verificationCount: 0,
      nextVerificationDue: next24h,
      missedVerifications: 0,
      depthAnalysis: depthAnalysis || null,
      captureAttestation: captureAttestation || null,
      onDeviceAiResult: onDeviceAiResult || null,
      duplicateOverrideReason: duplicateOverrideReason || null,
      source: 'user', // User-submitted spots (vs 'admin' for GCC base data)
    }

    // Scan submissions (isScanSubmission) SKIP this insert entirely — world
    // observations become the sole record (Amendment 2 / Task 1b). Manual
    // "Report a Spot" submissions are unaffected: insert, geocode,
    // spot_created points, real report in the response — exactly as today.
    const newReport = isScanSubmission
      ? null
      : (
          await db
            .insert(communityReports)
            .values(reportValues)
            .returning()
        )[0]

    // Preferred: client-supplied per-item positions from the environment scan
    // (heading-projected at capture time). Each becomes a world observation at
    // its own coordinates so the map/heatmap layers place items accurately.
    // Sanity-gated to 150m of the report location to keep junk out of the twin.
    const OBSERVATION_DOMAINS = ['litter', 'vegetation', 'construction', 'land_use'] as const
    // 100, not 25: a single thorough walk confirms 30+ items (2026-07-29 field
    // session: 29). Items past the cap didn't just drop — their cells were
    // then swept "clean" below, deleting the in-scan marker rows (and photos).
    const MAX_ITEMS_PER_SUBMIT = 100
    const clientItems: { lat: number; lng: number; itemType: string; severity?: number; confidence?: number; estimatedWeightGrams?: number; estimatedWidthMeters?: number; yawDegrees?: number; domain?: (typeof OBSERVATION_DOMAINS)[number]; mediaUrl?: string }[] =
      Array.isArray(observedItems)
        ? observedItems
            .filter((it: any) =>
              Number.isFinite(Number(it?.lat)) &&
              Number.isFinite(Number(it?.lng)) &&
              typeof it?.itemType === 'string' &&
              Math.abs(Number(it.lat) - lat) < 0.0015 &&
              Math.abs(Number(it.lng) - lng) < 0.002)
            .slice(0, MAX_ITEMS_PER_SUBMIT)
            .map((it: any) => ({
              lat: Number(it.lat),
              lng: Number(it.lng),
              itemType: String(it.itemType).slice(0, 50),
              severity: Number.isFinite(Number(it.severity)) ? Number(it.severity) : undefined,
              confidence: Number.isFinite(Number(it.confidence)) ? Number(it.confidence) : undefined,
              estimatedWeightGrams: Number.isFinite(Number(it.estimatedWeightGrams)) ? Math.round(Number(it.estimatedWeightGrams)) : undefined,
              estimatedWidthMeters: typeof it.estimatedWidthMeters === 'number' && Number.isFinite(it.estimatedWidthMeters) ? it.estimatedWidthMeters : undefined,
              yawDegrees: typeof it.yawDegrees === 'number' && Number.isFinite(it.yawDegrees) ? it.yawDegrees : undefined,
              domain: OBSERVATION_DOMAINS.includes(it.domain) ? (it.domain as (typeof OBSERVATION_DOMAINS)[number]) : undefined,
              // Same shape gate as /api/observations/ar-detected: https + sane length.
              mediaUrl: typeof it.mediaUrl === 'string' && it.mediaUrl.startsWith('https://') && it.mediaUrl.length <= 500 ? it.mediaUrl : undefined,
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
      // Amendment 2 / Task 1b: the scan path no longer inserts a report row,
      // so there is nothing to exclude from this sweep by id (the old
      // `id != newReport.id` guard existed solely to keep the just-inserted
      // row from resolving itself) — every pending/verified report in the
      // covered boxes is now an eligible candidate. reviewNotes references
      // the scan's timestamp instead of a report id, since none exists.
      const superseded = await db
        .update(communityReports)
        .set({
          status: 'resolved',
          reviewNotes: `Superseded by scan sweep ${reportCapturedAt} — fresh scan covers this location`,
        })
        .where(
          and(
            or(
              eq(communityReports.status, 'pending'),
              eq(communityReports.status, 'verified')
            ),
            or(...boxConditions),
          )
        )
        .returning({ id: communityReports.id })
      if (superseded.length > 0) {
        console.log(`Scan sweep ${reportCapturedAt} superseded stale reports: ${superseded.map((r) => r.id).join(', ')}`)
      }
    }

    // Broader cell-coverage supersede: a scan's coverage tracker sweeps far
    // more ground than just the anchor + item radius above (every GRAZED+
    // cell the camera/GPS actually covered). `coveredCellIds` is optional
    // and only ever sent by scan submissions — old clients never send it,
    // so this whole block is a no-op for them (wire-compat). Gated on
    // isScanSubmission defensively even though a non-scan submission would
    // never carry this field in practice.
    if (isScanSubmission) {
      const coveredCellIds = parseCoveredCellIds(rawCoveredCellIds)
      if (coveredCellIds.length > 0) {
        // Cells this very submission just re-confirmed (the anchor or a
        // detected item lives there) must survive even if the scan's
        // coverage sweep also covered them — "items re-confirmed by the
        // scan survive."
        const confirmedCells = new Set([
          gridCell(lat, lng),
          ...clientItems.map((it) => gridCell(it.lat, it.lng)),
        ])
        const staleCells = [...new Set(coveredCellIds)].filter((c) => !confirmedCells.has(c))
        if (staleCells.length > 0) {
          // excludeReportId is undefined here (Amendment 2 / Task 1b): no
          // report row exists on the scan path to exclude from the sweep.
          const { resolvedReportIds, invalidatedObservationCount } = await resolveByCoveredCells(
            staleCells,
            undefined,
            `Superseded by scan sweep ${reportCapturedAt} — covered by scan cell sweep`
          )
          if (resolvedReportIds.length > 0 || invalidatedObservationCount > 0) {
            console.log(
              `Scan sweep ${reportCapturedAt} cell-sweep superseded reports [${resolvedReportIds.join(', ')}] and invalidated ${invalidatedObservationCount} world observation(s) across ${staleCells.length} stale cell(s)`
            )
          }
        }
      }
    }

    // World observations link back to the real report row on the manual
    // path (communityReportId set); on the scan path there is no row to
    // link (Amendment 2 / Task 1b), so this is undefined. `observationsAt`
    // is the report's own capturedAt for the manual path (round-tripped
    // through the just-inserted row) or the same server timestamp used to
    // build the synthetic report on the scan path — either way, "now."
    const observationReportId = newReport?.id
    const observationsAt = newReport?.capturedAt ?? reportCapturedAt
    // The first observation recorded by this submission — becomes the
    // synthetic report's id (SCAN_OBSERVATION_PIN_ID_BASE + this) on the
    // scan path. Always ends up set for a scan submission in practice: the
    // detectedItems fallback loop below guarantees at least one
    // recordWorldObservation call even when clientItems is empty.
    let anchorObservationId: number | undefined
    const hasPerItemMedia = clientItems.some((item) => item.mediaUrl !== undefined)

    for (const item of clientItems) {
      const observation = await recordWorldObservation({
        sourceType: 'spot_report',
        communityReportId: observationReportId,
        reporterWorkerId: auth.workerId,
        lat: item.lat,
        lng: item.lng,
        itemType: item.itemType,
        state: 'dirty',
        domain: item.domain,
        attributes: (item.severity !== undefined || item.estimatedWeightGrams !== undefined || item.estimatedWidthMeters !== undefined || item.yawDegrees !== undefined)
          ? {
              ...(item.severity !== undefined ? { severity: item.severity } : {}),
              ...(item.estimatedWeightGrams !== undefined ? { estimatedWeightGrams: item.estimatedWeightGrams } : {}),
              ...(item.estimatedWidthMeters !== undefined ? { estimatedWidthMeters: item.estimatedWidthMeters } : {}),
              ...(item.yawDegrees !== undefined ? { yawDegrees: item.yawDegrees } : {}),
            }
          : undefined,
        confidence: item.confidence,
        // The item's OWN evidence photo when the client sent one. Once the
        // payload demonstrates per-item support, unlinked siblings stay
        // photoless instead of every row sharing mediaUrls[0]. The shared
        // fallback remains only for pre-per-item-photo clients.
        mediaUrl: selectSubmittedObservationMedia(
          item.mediaUrl,
          Array.isArray(mediaUrls) ? mediaUrls : undefined,
          hasPerItemMedia,
        ),
        capturedAt: observationsAt,
      })
      if (anchorObservationId === undefined) anchorObservationId = observation?.id
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
      const observation = await recordWorldObservation({
        sourceType: 'spot_report',
        communityReportId: observationReportId,
        reporterWorkerId: auth.workerId,
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
        capturedAt: observationsAt,
      })
      if (anchorObservationId === undefined) anchorObservationId = observation?.id
    }

    // Object Capture with scanned items present: the fallback anchor loop
    // above is skipped when clientItems exist, so the measured pile gets its
    // own anchor observation — otherwise the 3D capture data would vanish on
    // exactly the scans most likely to include it.
    if (hasObjectCapture && clientItems.length > 0) {
      const observation = await recordWorldObservation({
        sourceType: 'spot_report',
        communityReportId: observationReportId,
        reporterWorkerId: auth.workerId,
        lat,
        lng,
        itemType: 'debris_pile',
        state: 'dirty',
        attributes: objectCaptureAttributes,
        mediaUrl: Array.isArray(mediaUrls) && mediaUrls.length > 0 ? mediaUrls[0] : undefined,
        depthData: depthAnalysis ? JSON.stringify(depthAnalysis) : undefined,
        capturedAt: observationsAt,
      })
      if (anchorObservationId === undefined) anchorObservationId = observation?.id
    }

    // Reverse geocode to populate geographic categorization (best-effort,
    // non-blocking) — manual path only. The scan path has no report row to
    // update, and world_observations has no geo columns today; re-adding
    // geo enrichment for observations is follow-up (Amendment 2 / Task 1b,
    // Change 6 — deferred).
    if (!isScanSubmission && newReport) {
      const reportId = newReport.id
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
            .where(eq(communityReports.id, reportId))
        }
      }).catch(() => {}) // Silently ignore geocoding failures
    }

    // Award 10 observer points for creating a spot report (manual path) or
    // recording an observation (scan path — Amendment 2 / Task 1b, Change 2).
    // Admin scans/submissions work exactly like user ones — same supersede,
    // same world observations, same map data — but issue NO points (admins
    // seeding or auditing the map must not climb the leaderboard).
    const observerPoints = auth.role === 'admin' ? 0 : 10
    const pointsAction = resolvePointsAction(isScanSubmission)
    if (observerPoints > 0) {
      await db.insert(pointsLedger).values({
        workerId: auth.workerId,
        action: pointsAction.action,
        points: observerPoints,
        referenceType: pointsAction.referenceType,
        referenceId: isScanSubmission ? (anchorObservationId ?? null) : newReport!.id,
      })

      // Update worker's denormalized points total
      await db
        .update(workers)
        .set({
          statsCommunityPoints: sql`COALESCE(${workers.statsCommunityPoints}, 0) + ${observerPoints}`,
        })
        .where(eq(workers.id, auth.workerId))
    }

    // Build response matching SpotResponse { report, matchingBounties }.
    // Wire-compat (CRITICAL — see buildSyntheticScanReport doc comment):
    // deployed clients dereference `data.report` non-null regardless of
    // path, so the scan path must still return a syntactically valid
    // report — a synthetic one, since no row was inserted.
    const report = isScanSubmission
      ? buildSyntheticScanReport(reportValues, anchorObservationId ?? 0, reportCapturedAt)
      : toCommunityReport(newReport)

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
