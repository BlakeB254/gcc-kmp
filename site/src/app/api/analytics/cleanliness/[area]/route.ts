/**
 * Area Cleanliness Detail
 * GET /api/analytics/cleanliness/[area] — Single area with street-level breakdown
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports, spotVerifications } from '@/db/schema'
import { sql, eq, and, or } from 'drizzle-orm'
import { computeCleanlinessScore } from '@/lib/cleanliness-score'

export const dynamic = 'force-dynamic'

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ area: string }> },
) {
  try {
    const { area } = await params
    const decodedArea = decodeURIComponent(area)

    // Active spots per street
    const activeByStreet = await db
      .select({
        street: communityReports.locationAddress,
        count: sql<number>`count(*)::int`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        or(eq(communityReports.status, 'pending'), eq(communityReports.status, 'verified')),
      ))
      .groupBy(communityReports.locationAddress)

    // Resolved + avg duration per street
    const resolvedByStreet = await db
      .select({
        street: communityReports.locationAddress,
        count: sql<number>`count(*)::int`,
        avgDurationHours: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${communityReports.updatedAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        eq(communityReports.status, 'resolved'),
      ))
      .groupBy(communityReports.locationAddress)

    // Area-level active count
    const [areaActive] = await db
      .select({ count: sql<number>`count(*)::int` })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        or(eq(communityReports.status, 'pending'), eq(communityReports.status, 'verified')),
      ))

    // Area-level resolved count + avg duration
    const [areaResolved] = await db
      .select({
        count: sql<number>`count(*)::int`,
        avgDuration: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${communityReports.updatedAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        eq(communityReports.status, 'resolved'),
      ))

    // Verification stats
    const [verStats] = await db
      .select({
        total: sql<number>`count(${spotVerifications.id})::int`,
        avgVelocity: sql<number>`COALESCE(avg(EXTRACT(EPOCH FROM (${spotVerifications.createdAt}::timestamp - ${communityReports.createdAt}::timestamp)) / 3600.0), 0)::float`,
      })
      .from(spotVerifications)
      .innerJoin(communityReports, eq(spotVerifications.reportId, communityReports.id))
      .where(eq(communityReports.locationNeighborhood, decodedArea))

    // Volunteer cleanups
    const [cleanups] = await db
      .select({ count: sql<number>`count(*)::int` })
      .from(communityReports)
      .where(and(
        eq(communityReports.locationNeighborhood, decodedArea),
        eq(communityReports.type, 'volunteer_cleanup'),
        eq(communityReports.status, 'resolved'),
      ))

    const active = areaActive?.count || 0
    const resolved = areaResolved?.count || 0
    const avgDuration = areaResolved?.avgDuration || 0
    const totalVer = verStats?.total || 0
    const total = active + resolved
    const verificationRate = total > 0 ? totalVer / total : 0

    // Street-level breakdown
    const streetNames = new Set<string>()
    for (const row of [...activeByStreet, ...resolvedByStreet]) {
      if (row.street) streetNames.add(row.street)
    }
    const activeStreetMap = Object.fromEntries(
      activeByStreet.filter(r => r.street).map(r => [r.street!, r.count])
    )
    const resolvedStreetMap = Object.fromEntries(
      resolvedByStreet.filter(r => r.street).map(r => [r.street!, r])
    )

    const streets = Array.from(streetNames).sort().map(street => {
      const streetActive = activeStreetMap[street] || 0
      const streetResolved = resolvedStreetMap[street]
      const streetDuration = streetResolved?.avgDurationHours || 0
      return {
        street,
        activeSpots: streetActive,
        avgDurationHours: Math.round(streetDuration * 10) / 10,
        score: computeCleanlinessScore(streetActive, streetDuration, 0),
      }
    })

    return NextResponse.json({
      data: {
        area: decodedArea,
        activeSpots: active,
        resolvedSpots: resolved,
        avgMessDurationHours: Math.round(avgDuration * 10) / 10,
        avgVerificationVelocityHours: Math.round((verStats?.avgVelocity || 0) * 10) / 10,
        verificationRate: Math.round(verificationRate * 100) / 100,
        recurrenceRate: 0,
        cleanlinessScore: computeCleanlinessScore(active, avgDuration, 0),
        totalVerifications: totalVer,
        totalVolunteerCleanups: cleanups?.count || 0,
        streets,
      },
    })
  } catch (error) {
    console.error('Area cleanliness error:', error)
    return NextResponse.json({ error: 'Failed to fetch area analytics' }, { status: 500 })
  }
}
