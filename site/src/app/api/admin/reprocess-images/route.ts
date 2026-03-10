/**
 * POST /api/admin/reprocess-images
 *
 * One-time utility to re-download existing spot photos from Vercel Blob,
 * auto-rotate them based on EXIF orientation, and re-upload the corrected
 * versions. Fixes landscape phone photos that display sideways.
 *
 * Body: { dryRun?: boolean }
 * - dryRun: true (default) — count images that would be processed
 * - dryRun: false — actually re-upload corrected images
 */
import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { communityReports } from '@/db/schema'
import { desc, eq, isNotNull } from 'drizzle-orm'
import { put } from '@vercel/blob'
import { requireAdmin } from '@/lib/admin-auth'
import { processImage } from '@/lib/image-utils'

export const maxDuration = 300 // 5 min for batch processing

export async function POST(request: NextRequest) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  try {
    const body = await request.json().catch(() => ({}))
    const dryRun = body.dryRun !== false // default true for safety

    // Fetch all spots that have media
    const reports = await db
      .select({ id: communityReports.id, mediaIds: communityReports.mediaIds })
      .from(communityReports)
      .where(isNotNull(communityReports.mediaIds))
      .orderBy(desc(communityReports.createdAt))
      .limit(200)

    let totalImages = 0
    let processed = 0
    let errors = 0
    const results: { spotId: number; imageCount: number; status: string }[] = []

    for (const report of reports) {
      const urls = report.mediaIds as string[] | null
      if (!urls || urls.length === 0) continue

      totalImages += urls.length

      if (dryRun) {
        results.push({ spotId: report.id, imageCount: urls.length, status: 'would_process' })
        continue
      }

      // Process each image URL
      const newUrls: string[] = []
      let hadError = false

      for (const url of urls) {
        try {
          // Download the existing image
          const response = await fetch(url)
          if (!response.ok) {
            newUrls.push(url) // keep original if download fails
            hadError = true
            continue
          }

          const arrayBuffer = await response.arrayBuffer()
          const corrected = await processImage(arrayBuffer)

          // Re-upload with same path structure
          const urlObj = new URL(url)
          const path = urlObj.pathname.replace(/^\//, '')
          const blob = await put(path, corrected, {
            access: 'public',
            contentType: 'image/jpeg',
          })

          newUrls.push(blob.url)
          processed++
        } catch (err) {
          console.error(`Failed to reprocess image ${url}:`, err)
          newUrls.push(url) // keep original on error
          errors++
          hadError = true
        }
      }

      // Update the report with new URLs (only if we actually changed something)
      if (!dryRun && newUrls.some((u, i) => u !== urls[i])) {
        await db
          .update(communityReports)
          .set({ mediaIds: newUrls })
          .where(eq(communityReports.id, report.id))
      }

      results.push({
        spotId: report.id,
        imageCount: urls.length,
        status: hadError ? 'partial' : 'done',
      })
    }

    return NextResponse.json({
      dryRun,
      totalSpots: reports.length,
      totalImages,
      processed,
      errors,
      results,
    })
  } catch (error) {
    console.error('Reprocess images error:', error)
    return NextResponse.json({ error: 'Failed to reprocess images' }, { status: 500 })
  }
}
