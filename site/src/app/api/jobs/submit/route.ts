import { NextRequest } from 'next/server'
import { db } from '@/db'
import { jobs, blocks, media, workers } from '@/db/schema'
import { eq, inArray } from 'drizzle-orm'
import { put } from '@vercel/blob'
import { processImage } from '@/lib/image-utils'
import { verifyToken } from '@/lib/auth'
import { verifyMobileToken } from '@/lib/mobile-auth'
import { notificationTemplates, sendPushNotification } from '@/lib/notifications'

export const runtime = 'nodejs'

/**
 * Resolve the worker id from auth — Bearer token (mobile) or `worker-token`
 * cookie (web). Never trust a worker id from the request body.
 */
async function getAuthenticatedWorkerId(request: NextRequest): Promise<number | null> {
  const authHeader = request.headers.get('authorization')
  if (authHeader?.startsWith('Bearer ')) {
    const result = await verifyMobileToken(authHeader.substring(7))
    if (result) return result.workerId
  }
  const token = request.cookies.get('worker-token')?.value
  if (!token) return null
  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'worker') return null
  return payload.id
}

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
    const workerId = await getAuthenticatedWorkerId(request)
    if (!workerId) {
      return Response.json({ message: 'Not authenticated' }, { status: 401 })
    }

    const body = await request.json()
    const { jobId, photos, gpsPath } = body

    if (!jobId || !photos) {
      return Response.json(
        { message: 'Job ID and photos are required' },
        { status: 400 }
      )
    }

    // Verify job exists and belongs to the authenticated worker.
    const [job] = await db
      .select()
      .from(jobs)
      .where(eq(jobs.id, Number(jobId)))
      .limit(1)

    if (!job) {
      return Response.json({ message: 'Job not found' }, { status: 404 })
    }

    if (job.workerId !== workerId) {
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
          completedById: workerId,
          completedAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        })
        .where(eq(blocks.id, block.id))
    }

    // Best-effort: notify admins that a job needs payout approval.
    // Wrapped in try/catch — push failure MUST NOT fail the job completion.
    // Note: only web-push (VAPID) subscriptions are deliverable from this
    // backend. Mobile FCM/APNs tokens stored via POST /api/workers/me/device-token
    // require a separate FCM/APNs integration not yet wired here.
    void notifyAdminsOfPendingReview(
      workerId,
      Number(jobId),
      job.payoutAmount ? parseFloat(job.payoutAmount) : null
    ).catch((err) =>
      console.warn('[submit] Admin push notification failed (non-fatal):', err)
    )

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
 * Best-effort push notification to all admin workers that have a web-push
 * subscription registered. Called after a job transitions to pending_review.
 * Failures are silently swallowed by the caller — this must never block
 * the job completion response.
 */
async function notifyAdminsOfPendingReview(
  submitterWorkerId: number,
  jobId: number,
  payoutAmount: number | null
): Promise<void> {
  const adminEmails = (process.env.ADMIN_EMAILS || '')
    .split(',')
    .map((e) => e.trim().toLowerCase())
    .filter(Boolean)

  if (adminEmails.length === 0) return

  // Fetch submitter name for notification body
  const [submitter] = await db
    .select({ name: workers.name })
    .from(workers)
    .where(eq(workers.id, submitterWorkerId))
    .limit(1)
  const workerName = submitter?.name ?? 'A worker'

  // Fetch admin workers who have push subscriptions and push enabled
  const adminWorkers = await db
    .select({
      notificationsPushEnabled: workers.notificationsPushEnabled,
      notificationsPushSubscription: workers.notificationsPushSubscription,
    })
    .from(workers)
    .where(inArray(workers.email, adminEmails))

  const amountDollars = payoutAmount
    ? (payoutAmount / 100).toFixed(2)
    : '0.00'

  const notification = notificationTemplates.payoutNeedsApproval(workerName, jobId, amountDollars)

  const pushPromises = adminWorkers
    .filter((w) => w.notificationsPushEnabled && w.notificationsPushSubscription)
    .map((w) => {
      const sub = w.notificationsPushSubscription as Record<string, unknown>
      // Only send via web-push if the stored subscription has the VAPID endpoint shape.
      // Mobile FCM/APNs tokens ({ token, platform }) are not deliverable via web-push.
      if (
        typeof sub.endpoint === 'string' &&
        sub.keys &&
        typeof (sub.keys as Record<string, unknown>).p256dh === 'string' &&
        typeof (sub.keys as Record<string, unknown>).auth === 'string'
      ) {
        return sendPushNotification(
          sub as { endpoint: string; keys: { p256dh: string; auth: string } },
          notification
        )
      }
      return Promise.resolve(false)
    })

  await Promise.allSettled(pushPromises)
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
