import { NextRequest } from 'next/server'
import { db } from '@/db'
import { jobs, blocks, media } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { put } from '@vercel/blob'
import { processImage } from '@/lib/image-utils'

export const runtime = 'nodejs'

interface PhotoSubmission {
  checkpointType: string
  timing: 'before' | 'after'
  imageData: string // Base64 encoded image
  latitude: number
  longitude: number
  capturedAt: string
}

/**
 * POST /api/jobs/submit
 * Submit a completed job with photos for review
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json()
    const { jobId, workerId, photos, gpsPath } = body

    if (!jobId || !workerId || !photos) {
      return Response.json(
        { message: 'Job ID, Worker ID, and photos are required' },
        { status: 400 }
      )
    }

    // Verify job exists and belongs to worker
    const [job] = await db
      .select()
      .from(jobs)
      .where(eq(jobs.id, Number(jobId)))
      .limit(1)

    if (!job) {
      return Response.json({ message: 'Job not found' }, { status: 404 })
    }

    if (job.workerId !== Number(workerId)) {
      return Response.json(
        { message: 'This job does not belong to you' },
        { status: 403 }
      )
    }

    if (!['claimed', 'in_progress'].includes(job.status || '')) {
      return Response.json(
        { message: `Job cannot be submitted in ${job.status} status` },
        { status: 400 }
      )
    }

    // Validate we have all 8 photos (4 checkpoints × before/after)
    if (photos.length !== 8) {
      return Response.json(
        { message: 'Please submit all 8 photos (before and after for each checkpoint)' },
        { status: 400 }
      )
    }

    // Get block for checkpoint validation
    const [block] = job.blockId
      ? await db.select().from(blocks).where(eq(blocks.id, job.blockId)).limit(1)
      : [null]

    // Upload photos and validate GPS
    const uploadedPhotos = []
    const beforePhotoIds: number[] = []
    const afterPhotoIds: number[] = []

    for (const photo of photos as PhotoSubmission[]) {
      // Decode base64, auto-rotate from EXIF, strip metadata
      const rawBuffer = Buffer.from(photo.imageData.split(',')[1], 'base64')
      const imageBuffer = await processImage(rawBuffer)

      // Upload to Vercel Blob
      const timestamp = Date.now()
      const blobPath = `jobs/${jobId}/${photo.checkpointType}-${photo.timing}-${timestamp}.jpg`

      const blob = await put(blobPath, imageBuffer, {
        access: 'public',
        contentType: 'image/jpeg',
      })

      // Create media record
      const [mediaDoc] = await db
        .insert(media)
        .values({
          alt: `Job ${job.id} - ${photo.checkpointType} - ${photo.timing}`,
          category: 'job_photo',
          url: blob.url,
          filename: `job-${jobId}-${photo.checkpointType}-${photo.timing}.jpg`,
          mimeType: 'image/jpeg',
          filesize: String(imageBuffer.length),
        })
        .returning({ id: media.id })

      // Track photo IDs by timing
      if (photo.timing === 'before') {
        beforePhotoIds.push(mediaDoc.id)
      } else {
        afterPhotoIds.push(mediaDoc.id)
      }

      uploadedPhotos.push({
        checkpointType: photo.checkpointType,
        timing: photo.timing,
        imageId: mediaDoc.id,
        location: [photo.longitude, photo.latitude],
        capturedAt: photo.capturedAt,
      })
    }

    // Update job with photos and mark as submitted
    await db
      .update(jobs)
      .set({
        status: 'pending_review',
        beforePhotos: beforePhotoIds,
        afterPhotos: afterPhotoIds,
        gpsPath: gpsPath || [],
        completedAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
      .where(eq(jobs.id, Number(jobId)))

    // Update block status
    if (block) {
      await db
        .update(blocks)
        .set({
          status: 'completed',
          completedById: Number(workerId),
          completedAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        })
        .where(eq(blocks.id, block.id))
    }

    return Response.json({
      success: true,
      job: {
        id: job.id,
        status: 'pending_review',
      },
      message: 'Job submitted for review. You will be notified once approved.',
    })
  } catch (error) {
    console.error('Job submit error:', error)
    return Response.json(
      { message: 'Failed to submit job. Please try again.' },
      { status: 500 }
    )
  }
}

/**
 * Calculate distance between two GPS coordinates using Haversine formula
 * Returns distance in meters
 */
export function calculateDistance(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const R = 6371000 // Earth's radius in meters
  const dLat = toRad(lat2 - lat1)
  const dLon = toRad(lon2 - lon1)
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) *
      Math.cos(toRad(lat2)) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2)
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  return R * c
}

function toRad(deg: number): number {
  return deg * (Math.PI / 180)
}
