/**
 * Cleanliness Analytics API
 * GET /api/analytics/cleanliness — City-wide summary + per-area breakdown
 *
 * Public endpoint (no auth). Real-time SQL aggregation from
 * communityReports and spotVerifications.
 */
import { NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, spotVerifications } from '@/db/schema'
import { sql, eq, and, or } from 'drizzle-orm'
import { computeCleanlinessScore } from '@/lib/cleanliness-score'

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    // Active spots per area
    const activeByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        count: sql<number>`count(*)::int`,
      })
      .from(communityReports)
      .where(or(
        eq(communityReports.status, 'pending'),
        eq(communityReports.status, 'verified'),
      ))
      .groupBy(communityReports.locationNeighborhood)

    // Resolved spots per area + avg duration
    const resolvedByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        count: sql<number>`count(*)::int`,
        avgDurationHours: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${communityReports.updatedAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(communityReports)
      .where(eq(communityReports.status, 'resolved'))
      .groupBy(communityReports.locationNeighborhood)

    // Verification stats per area
    const verificationsByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        totalVerifications: sql<number>`count(${spotVerifications.id})::int`,
        avgVelocityHours: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${spotVerifications.createdAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(spotVerifications)
      .innerJoin(communityReports, eq(spotVerifications.reportId, communityReports.id))
      .groupBy(communityReports.locationNeighborhood)

    // Volunteer cleanup count per area
    const cleanupsByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        count: sql<number>`count(*)::int`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.type, 'volunteer_cleanup'),
        eq(communityReports.status, 'resolved'),
      ))
      .groupBy(communityReports.locationNeighborhood)

    // Recurrence: resolved addresses that have new active spots
    const recurrenceByArea = await db
      .select({
        area: communityReports.locationNeighborhood,
        recurrentCount: sql<number>`count(DISTINCT ${communityReports.locationAddress})::int`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.status, 'resolved'),
        sql`${communityReports.locationAddress} IN (
          SELECT location_address FROM community_reports
          WHERE status IN ('pending','verified')
          AND location_address IS NOT NULL
        )`,
      ))
      .groupBy(communityReports.locationNeighborhood)

    // Merge into area stats
    const areaNames = new Set<string>()
    for (const row of [...activeByArea, ...resolvedByArea, ...verificationsByArea, ...cleanupsByArea]) {
      if (row.area) areaNames.add(row.area)
    }

    const activeMap = Object.fromEntries(activeByArea.map(r => [r.area || '', r.count]))
    const resolvedMap = Object.fromEntries(resolvedByArea.map(r => [r.area || '', r]))
    const verificationsMap = Object.fromEntries(verificationsByArea.map(r => [r.area || '', r]))
    const cleanupsMap = Object.fromEntries(cleanupsByArea.map(r => [r.area || '', r.count]))
    const recurrenceMap = Object.fromEntries(recurrenceByArea.map(r => [r.area || '', r.recurrentCount]))

    let totalActive = 0
    let totalResolved = 0
    let totalDurationSum = 0
    let totalDurationCount = 0

    const areas = Array.from(areaNames).sort().map(area => {
      const active = activeMap[area] || 0
      const resolvedData = resolvedMap[area]
      const resolved = resolvedData?.count || 0
      const avgDuration = resolvedData?.avgDurationHours || 0
      const verData = verificationsMap[area]
      const totalVer = verData?.totalVerifications || 0
      const avgVelocity = verData?.avgVelocityHours || 0
      const cleanups = cleanupsMap[area] || 0
      const recurrent = recurrenceMap[area] || 0
      const total = active + resolved
      const verificationRate = total > 0 ? totalVer / total : 0
      const recurrenceRate = resolved > 0 ? recurrent / resolved : 0

      totalActive += active
      totalResolved += resolved
      if (resolved > 0) {
        totalDurationSum += avgDuration * resolved
        totalDurationCount += resolved
      }

      return {
        area,
        activeSpots: active,
        resolvedSpots: resolved,
        avgMessDurationHours: Math.round(avgDuration * 10) / 10,
        avgVerificationVelocityHours: Math.round(avgVelocity * 10) / 10,
        verificationRate: Math.round(verificationRate * 100) / 100,
        recurrenceRate: Math.round(recurrenceRate * 100) / 100,
        cleanlinessScore: computeCleanlinessScore(active, avgDuration, recurrenceRate),
        totalVerifications: totalVer,
        totalVolunteerCleanups: cleanups,
      }
    })

    const cityAvgDuration = totalDurationCount > 0 ? totalDurationSum / totalDurationCount : 0
    const cityScore = areas.length > 0
      ? Math.round(areas.reduce((s, a) => s + a.cleanlinessScore, 0) / areas.length)
      : 100

    return NextResponse.json({
      data: {
        cityScore,
        totalActiveSpots: totalActive,
        totalResolvedSpots: totalResolved,
        avgMessDurationHours: Math.round(cityAvgDuration * 10) / 10,
        areas,
      },
    })
  } catch (error) {
    console.error('Cleanliness analytics error:', error)
    return NextResponse.json({ error: 'Failed to fetch analytics' }, { status: 500 })
  }
}
