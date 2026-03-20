/**
 * Public Data Export — Per-Community-Area Aggregation Stats
 *
 * GET /api/public/data-export/stats
 *
 * Returns aggregate statistics per community area for the Chicago Data Portal pitch.
 */
import { NextResponse } from 'next/server'
import { db } from '@/db'
import { sql } from 'drizzle-orm'

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    const statsResult = await db.execute(sql`
      SELECT
        geo_community_area_number AS community_area,
        geo_community_area AS community_area_name,
        geo_ward AS ward,
        geo_police_district AS police_district,
        COUNT(*) AS total_reports,
        COUNT(CASE WHEN type = 'sighting' THEN 1 END) AS sightings,
        COUNT(CASE WHEN type = 'volunteer_cleanup' THEN 1 END) AS volunteer_cleanups,
        COUNT(CASE WHEN status = 'pending' THEN 1 END) AS open_reports,
        COUNT(CASE WHEN status = 'verified' THEN 1 END) AS verified_reports,
        COUNT(CASE WHEN status = 'resolved' THEN 1 END) AS resolved_reports,
        COUNT(CASE WHEN on_device_ai_result IS NOT NULL THEN 1 END) AS with_ai_analysis,
        COUNT(CASE WHEN depth_analysis IS NOT NULL THEN 1 END) AS with_lidar_analysis,
        ROUND(AVG((on_device_ai_result->>'severityScore')::float)::numeric, 1) AS avg_severity,
        ROUND(AVG((on_device_ai_result->>'estimatedWeightLbs')::float)::numeric, 1) AS avg_weight_lbs,
        SUM(COALESCE(verification_count, 0)) AS total_verifications,
        SUM(COALESCE(cleanup_bags_collected, 0)) AS total_bags_collected,
        MIN(created_at) AS earliest_report,
        MAX(created_at) AS latest_report
      FROM community_reports
      WHERE geo_community_area_number IS NOT NULL
      GROUP BY geo_community_area_number, geo_community_area, geo_ward, geo_police_district
      ORDER BY geo_community_area_number
    `)

    // City-wide totals
    const totalsResult = await db.execute(sql`
      SELECT
        COUNT(*) AS total_reports,
        COUNT(DISTINCT geo_community_area_number) AS community_areas_covered,
        COUNT(DISTINCT geo_ward) AS wards_covered,
        COUNT(CASE WHEN on_device_ai_result IS NOT NULL THEN 1 END) AS with_ai_analysis,
        COUNT(CASE WHEN depth_analysis IS NOT NULL THEN 1 END) AS with_lidar_analysis,
        ROUND(AVG((on_device_ai_result->>'severityScore')::float)::numeric, 1) AS avg_severity,
        SUM(COALESCE(cleanup_bags_collected, 0)) AS total_bags_collected,
        SUM(COALESCE(verification_count, 0)) AS total_verifications
      FROM community_reports
    `)
    const totals = (totalsResult.rows as any[])?.[0] ?? {}

    const stats = (statsResult.rows ?? statsResult) as any[]

    return NextResponse.json({
      city_wide: totals,
      by_community_area: stats,
      generated_at: new Date().toISOString(),
    }, {
      headers: {
        'Access-Control-Allow-Origin': '*',
      },
    })
  } catch (error) {
    console.error('Data export stats error:', error)
    return NextResponse.json({ error: 'Internal server error' }, { status: 500 })
  }
}
