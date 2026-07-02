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
  let count = 0
  for (const report of rows) {
    if (await alreadyBackfilled('communityReportId', report.id)) continue
    const coords = report.coordinates as any
    const [lng, lat] = Array.isArray(coords) ? coords : [coords?.lng ?? 0, coords?.lat ?? 0]
    if (!lat || !lng) continue
    const tags = Array.isArray(report.tags) ? report.tags : []
    await recordWorldObservation({
      sourceType: 'spot_report',
      communityReportId: report.id,
      lat,
      lng,
      itemType: tags[0] ?? 'general_litter',
      state: 'dirty',
      mediaUrl: Array.isArray(report.mediaIds) && report.mediaIds.length > 0 ? String(report.mediaIds[0]) : undefined,
      capturedAt: report.capturedAt ?? report.createdAt,
    })
    count++
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
