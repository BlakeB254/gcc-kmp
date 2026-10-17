import { NextRequest, NextResponse } from 'next/server'
import { db } from '@/db'
import { workers, media } from '@/db/schema'
import { eq } from 'drizzle-orm'
import { verifyToken } from '@/lib/auth'
import { PutObjectCommand } from '@aws-sdk/client-s3'
import { processImage } from '@/lib/image-utils'
import { isR2Configured, r2BucketName, r2Client } from '@/lib/r2'
import { verificationDocKey } from '@/lib/verification-doc-key'

export const runtime = 'nodejs'

// Proof of address is a PII document: same formats and cap as onboarding.
const ACCEPTED_MIME = new Set(['image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'application/pdf'])
const MAX_BYTES = 10 * 1024 * 1024

// 24 hour cooldown in milliseconds
const ADDRESS_CHANGE_COOLDOWN = 24 * 60 * 60 * 1000

// Valid zip codes for North Lawndale service area
const VALID_ZIP_CODES = ['60623', '60624']

// Helper to authenticate and get worker ID from JWT
async function getAuthenticatedWorkerId(request: NextRequest): Promise<number | null> {
  const token = request.cookies.get('worker-token')?.value
  if (!token) return null

  const payload = await verifyToken(token)
  if (!payload || payload.type !== 'worker') return null

  return payload.id
}

export async function POST(request: NextRequest) {
  try {
    const workerId = await getAuthenticatedWorkerId(request)

    if (!workerId) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    // Get current worker
    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!worker) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    // Check if worker is approved
    if (worker.verificationStatus !== 'approved') {
      return NextResponse.json(
        { error: 'Your account must be approved before requesting an address change' },
        { status: 403 }
      )
    }

    // Check for existing pending address change
    if (worker.pendingAddressChangeStatus === 'pending') {
      return NextResponse.json(
        { error: 'You already have a pending address change request' },
        { status: 400 }
      )
    }

    // Check 24-hour cooldown
    if (worker.lastAddressChangeAt) {
      const lastChange = new Date(worker.lastAddressChangeAt).getTime()
      const now = Date.now()
      const timeSinceLastChange = now - lastChange

      if (timeSinceLastChange < ADDRESS_CHANGE_COOLDOWN) {
        const remainingMs = ADDRESS_CHANGE_COOLDOWN - timeSinceLastChange
        const remainingHours = Math.ceil(remainingMs / (60 * 60 * 1000))
        return NextResponse.json(
          {
            error: `You can only request an address change once every 24 hours. Please try again in ${remainingHours} hour${remainingHours > 1 ? 's' : ''}.`,
            remainingMs,
            canRequestAt: new Date(lastChange + ADDRESS_CHANGE_COOLDOWN).toISOString(),
          },
          { status: 429 }
        )
      }
    }

    // Parse form data (for file upload)
    const formData = await request.formData()
    const street = formData.get('street') as string
    const city = formData.get('city') as string
    const state = formData.get('state') as string
    const zip = formData.get('zip') as string
    const proofOfAddress = formData.get('proofOfAddress') as File | null

    // Validate required fields
    if (!street || !city || !state || !zip) {
      return NextResponse.json({ error: 'All address fields are required' }, { status: 400 })
    }

    // Validate proof of address
    if (!proofOfAddress || proofOfAddress.size === 0) {
      return NextResponse.json(
        { error: 'Proof of address document is required' },
        { status: 400 }
      )
    }

    // Check zip code is in an active paid-block service area.
    // Anyone can sign up worldwide and submit spot reports; paid blocks unlock
    // when their neighborhood goes active.
    if (!VALID_ZIP_CODES.includes(zip)) {
      return NextResponse.json(
        {
          error:
            'Paid blocks aren\'t active in this zip code yet. You can still sign up and submit spot reports anywhere — we\'ll unlock paid work in your neighborhood as we expand.',
          code: 'OUTSIDE_SERVICE_AREA',
        },
        { status: 400 }
      )
    }

    if (!ACCEPTED_MIME.has(proofOfAddress.type)) {
      return NextResponse.json({ error: 'Unsupported file type. Use JPEG, PNG, WebP, or PDF.' }, { status: 400 })
    }
    if (proofOfAddress.size > MAX_BYTES) {
      return NextResponse.json({ error: 'File too large. Max 10MB.' }, { status: 400 })
    }

    // Stored PRIVATELY in R2 (never public Blob). No public fallback — a
    // misconfigured server refuses rather than publishing the document.
    if (!isR2Configured()) {
      return NextResponse.json(
        { error: 'Document uploads are temporarily unavailable. Please try again later.' },
        { status: 503 }
      )
    }

    // Images: auto-rotate, resize, strip EXIF, re-encode as JPEG. PDFs pass through.
    const isPdf = proofOfAddress.type === 'application/pdf'
    const raw = await proofOfAddress.arrayBuffer()
    const processed = isPdf ? Buffer.from(raw) : await processImage(raw)
    const contentType = isPdf ? 'application/pdf' : 'image/jpeg'
    const key = verificationDocKey(workerId, 'address_change', isPdf ? 'pdf' : 'jpg')

    await r2Client().send(
      new PutObjectCommand({ Bucket: r2BucketName(), Key: key, Body: processed, ContentType: contentType })
    )

    // Create media record (url holds the private R2 key, not a fetchable URL)
    const [proofDoc] = await db
      .insert(media)
      .values({
        alt: `Proof of address for ${worker.name} - ${new Date().toISOString()}`,
        category: 'proof_of_address',
        url: key,
        filename: key,
        mimeType: contentType,
        filesize: String(processed.length),
      })
      .returning({ id: media.id })

    // Create pending address change request
    await db
      .update(workers)
      .set({
        pendingAddressChangeStreet: street,
        pendingAddressChangeCity: city,
        pendingAddressChangeState: state,
        pendingAddressChangeZip: zip,
        pendingAddressChangeProofOfAddressId: proofDoc.id,
        pendingAddressChangeStatus: 'pending',
        pendingAddressChangeRequestedAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
      .where(eq(workers.id, workerId))

    return NextResponse.json({
      success: true,
      message: 'Address change request submitted. It will be reviewed within 24-48 hours.',
      pendingAddress: {
        street,
        city,
        state,
        zip,
        requestedAt: new Date().toISOString(),
      },
    })
  } catch (error) {
    console.error('Address change request error:', error)
    return NextResponse.json({ error: 'Failed to submit address change request' }, { status: 500 })
  }
}

// GET endpoint to check address change status and cooldown
export async function GET(request: NextRequest) {
  try {
    const workerId = await getAuthenticatedWorkerId(request)

    if (!workerId) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!worker) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    // Calculate cooldown status
    let canRequestChange = true
    let cooldownEndsAt: string | null = null

    if (worker.lastAddressChangeAt) {
      const lastChange = new Date(worker.lastAddressChangeAt).getTime()
      const now = Date.now()
      const timeSinceLastChange = now - lastChange

      if (timeSinceLastChange < ADDRESS_CHANGE_COOLDOWN) {
        canRequestChange = false
        cooldownEndsAt = new Date(lastChange + ADDRESS_CHANGE_COOLDOWN).toISOString()
      }
    }

    // Check for pending request
    if (worker.pendingAddressChangeStatus === 'pending') {
      canRequestChange = false
    }

    return NextResponse.json({
      currentAddress: {
        street: worker.addressStreet,
        city: worker.addressCity,
        state: worker.addressState,
        zip: worker.addressZip,
        isNorthLawndale: worker.addressIsNorthLawndale,
      },
      pendingAddressChange:
        worker.pendingAddressChangeStatus === 'pending'
          ? {
              street: worker.pendingAddressChangeStreet,
              city: worker.pendingAddressChangeCity,
              state: worker.pendingAddressChangeState,
              zip: worker.pendingAddressChangeZip,
              requestedAt: worker.pendingAddressChangeRequestedAt,
              status: worker.pendingAddressChangeStatus,
            }
          : null,
      lastAddressChangeAt: worker.lastAddressChangeAt,
      canRequestChange,
      cooldownEndsAt,
    })
  } catch (error) {
    console.error('Get address change status error:', error)
    return NextResponse.json({ error: 'Failed to get address status' }, { status: 500 })
  }
}

// DELETE endpoint to cancel a pending address change
export async function DELETE(request: NextRequest) {
  try {
    const workerId = await getAuthenticatedWorkerId(request)

    if (!workerId) {
      return NextResponse.json({ error: 'Not authenticated' }, { status: 401 })
    }

    const [worker] = await db
      .select()
      .from(workers)
      .where(eq(workers.id, workerId))
      .limit(1)

    if (!worker) {
      return NextResponse.json({ error: 'Worker not found' }, { status: 404 })
    }

    if (worker.pendingAddressChangeStatus !== 'pending') {
      return NextResponse.json({ error: 'No pending address change to cancel' }, { status: 400 })
    }

    // Clear the pending address change
    await db
      .update(workers)
      .set({
        pendingAddressChangeStatus: 'none',
        pendingAddressChangeStreet: null,
        pendingAddressChangeCity: null,
        pendingAddressChangeState: null,
        pendingAddressChangeZip: null,
        pendingAddressChangeProofOfAddressId: null,
        pendingAddressChangeRequestedAt: null,
        pendingAddressChangeReviewNotes: null,
        updatedAt: new Date().toISOString(),
      })
      .where(eq(workers.id, workerId))

    return NextResponse.json({
      success: true,
      message: 'Address change request cancelled',
    })
  } catch (error) {
    console.error('Cancel address change error:', error)
    return NextResponse.json({ error: 'Failed to cancel address change request' }, { status: 500 })
  }
}
