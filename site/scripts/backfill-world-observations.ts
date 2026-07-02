/**
 * One-time backfill: convert existing scanHotspots + communityReports rows
 * into worldObservations, in chronological order, so history exists from
 * day one instead of only going forward. Safe to re-run (idempotent per
 * source row — checks scanHotspotId/communityReportId before inserting).
 */
import { db } from '../src/db'
import { scanHotspots, communityReports, worldObservations } from '../src/db/schema'
import { eq, asc } from 'drizzle-orm'
import { recordWorldObservation } from '../src/lib/world-observations'

async function alreadyBackfilled(column: 'scanHotspotId' | 'communityReportId', id: number): Promise<boolean> {
  const existing = await db
    .select({ id: worldObservations.id })
    .from(worldObservations)
    .where(eq(worldObservations[column], id))
    .limit(1)
  return existing.length > 0
}

async function backfillHotspots() {
  const rows = await db.select().from(scanHotspots).orderBy(asc(scanHotspots.detectedAt))
  let count = 0
  for (const hotspot of rows) {
    if (await alreadyBackfilled('scanHotspotId', hotspot.id)) continue
    const trashTypes = JSON.parse(hotspot.trashTypes || '[]')
    await recordWorldObservation({
      sourceType: 'route_scan',
      scanHotspotId: hotspot.id,
      lat: hotspot.lat,
      lng: hotspot.lng,
      itemType: trashTypes[0] ?? 'general_litter',
      state: 'dirty',
      severity: hotspot.severity,
      estimatedVolumeLiters: hotspot.estimatedVolumeLiters ?? undefined,
      estimatedWeightLbs: hotspot.estimatedWeightLbs ?? undefined,
      confidence: hotspot.confidence,
      mediaUrl: hotspot.beforePhotoUrl,
      depthData: hotspot.depthData ?? undefined,
      arAnchorData: hotspot.arAnchorData ?? undefined,
      capturedAt: hotspot.detectedAt,
    })
    count++
    if (hotspot.status === 'cleaned' && hotspot.cleanedAt) {
      await recordWorldObservation({
        sourceType: 'route_scan',
        scanHotspotId: hotspot.id,
        lat: hotspot.lat,
        lng: hotspot.lng,
        itemType: null,
        state: 'clean',
        mediaUrl: hotspot.afterPhotoUrl ?? undefined,
        capturedAt: hotspot.cleanedAt,
      })
      count++
    }
  }
  console.log(`Backfilled ${count} observations from scanHotspots`)
}

async function backfillSpotReports() {
  const rows = await db.select().from(communityReports).orderBy(asc(communityReports.createdAt))
  // recordWorldObservation's supersession logic relies on insertion order
  // reflecting true chronological order, but we store capturedAt (falling
  // back to createdAt) as the observation's timestamp — so sort the
  // in-memory array by that same value before iterating, rather than by
  // createdAt alone.
  rows.sort((a, b) => {
    const aTime = new Date(a.capturedAt ?? a.createdAt).getTime()
    const bTime = new Date(b.capturedAt ?? b.createdAt).getTime()
    return aTime - bTime
  })
  let count = 0
  for (const report of rows) {
    if (await alreadyBackfilled('communityReportId', report.id)) continue
    const coords = report.coordinates as any
    const [lng, lat] = Array.isArray(coords) ? coords : [coords?.lng ?? 0, coords?.lat ?? 0]
    if (!lat || !lng) continue
    const tags = Array.isArray(report.tags) ? report.tags : []

    // Explode on-device AI detections into per-item world observations,
    // mirroring the live write-path in api/spots/route.ts; fall back to a
    // single observation from the first tag (or 'general_litter') when no
    // structured AI result is present on the row.
    const onDeviceAiResult = report.onDeviceAiResult as any
    const detectedItems: { itemType: string; severity?: number; confidence?: number }[] =
      Array.isArray(onDeviceAiResult?.items) && onDeviceAiResult.items.length > 0
        ? onDeviceAiResult.items.map((it: any) => ({
            itemType: it.type ?? it.itemType ?? 'general_litter',
            severity: Number.isFinite(Number(it.severity)) ? Number(it.severity) : undefined,
            confidence: Number.isFinite(Number(it.confidence)) ? Number(it.confidence) : undefined,
          }))
        : [{ itemType: (tags?.[0] as string) ?? 'general_litter' }]

    for (const item of detectedItems) {
      await recordWorldObservation({
        sourceType: 'spot_report',
        communityReportId: report.id,
        lat,
        lng,
        itemType: item.itemType,
        state: 'dirty',
        severity: item.severity,
        confidence: item.confidence,
        mediaUrl: Array.isArray(report.mediaIds) && report.mediaIds.length > 0 ? String(report.mediaIds[0]) : undefined,
        depthData: report.depthAnalysis ? JSON.stringify(report.depthAnalysis) : undefined,
        capturedAt: report.capturedAt ?? report.createdAt,
      })
      count++
    }
  }
  console.log(`Backfilled ${count} observations from communityReports`)
}

async function main() {
  await backfillHotspots()
  await backfillSpotReports()
  process.exit(0)
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
