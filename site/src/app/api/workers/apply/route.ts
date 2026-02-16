import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers, media, waitlistEntries, sponsors } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'
import { sendEmail } from '@/lib/email'
import { emailVerificationEmail } from '@/lib/email/templates/email-verification'
import { getEarlyAdopterTier, generateReferralCode } from '@/lib/movement-rankings'
import { getChicagoCommunityArea, isInLaunchArea, LAUNCH_AREA_ZIPS } from '@/lib/chicago-community-areas'
import crypto from 'crypto'
import { put } from '@vercel/blob'

export const runtime = 'nodejs'
export const maxDuration = 30

function generateVerificationCode(): string {
  return Math.floor(100000 + Math.random() * 900000).toString()
}

function generateVerificationToken(): string {
  return crypto.randomBytes(32).toString('hex')
}

interface FileData {
  data: string
  name: string
  type: string
}

interface RequestBody {
  name: string
  email: string
  phone: string
  street: string
  city: string
  state: string
  zip: string
  governmentId?: FileData | null
  proofOfAddress?: FileData | null
}

function base64ToBuffer(dataUrl: string): Buffer {
  const base64Data = dataUrl.split(',')[1] || dataUrl
  return Buffer.from(base64Data, 'base64')
}

async function uploadFile(fileData: FileData, category: string): Promise<number | null> {
  try {
    const buffer = base64ToBuffer(fileData.data)
    const filename = `${category}/${Date.now()}-${fileData.name}`

    // Upload to Vercel Blob if token available, otherwise skip
    let url = null
    if (process.env.BLOB_READ_WRITE_TOKEN) {
      const blob = await put(filename, buffer, {
        access: 'public',
        contentType: fileData.type,
      })
      url = blob.url
    }

    // Insert into media table
    const [inserted] = await db
      .insert(media)
      .values({
        alt: `${category} document`,
        category: category === 'government-id' ? 'worker_id' : 'proof_of_address',
        url,
        filename,
        mimeType: fileData.type,
        filesize: String(buffer.length),
      })
      .returning({ id: media.id })

    return inserted?.id ?? null
  } catch (error) {
    console.error(`Failed to upload ${category}:`, error)
    return null
  }
}

export async function POST(request: NextRequest) {
  console.log('[apply] Starting worker registration')
  try {
    let body: RequestBody
    try {
      body = await request.json()
      console.log('[apply] Parsed body:', {
        ...body,
        governmentId: body.governmentId ? '[FILE]' : null,
        proofOfAddress: body.proofOfAddress ? '[FILE]' : null,
      })
    } catch {
      return Response.json({ message: 'We couldn\'t process your request. Please refresh the page and try again.' }, { status: 400 })
    }

    const { name, email, phone, street, city, state, zip, governmentId, proofOfAddress } = body

    if (!name || !email || !phone || !street || !zip) {
      return Response.json({ message: 'Please fill in all required fields: name, email, phone, street address, and zip code.' }, { status: 400 })
    }

    if (!isInLaunchArea(zip)) {
      return Response.json(
        { message: `GCC is currently only available in North Lawndale (zip codes ${LAUNCH_AREA_ZIPS.join(' and ')})` },
        { status: 400 }
      )
    }

    // Get community area for this zip code
    const communityArea = getChicagoCommunityArea(zip) || 'North Lawndale'

    // Check if email already exists
    const existing = await db.select({ id: workers.id }).from(workers).where(eq(workers.email, email)).limit(1)

    if (existing.length > 0) {
      return Response.json({ message: 'An account with this email already exists. Please log in instead, or use a different email address.' }, { status: 400 })
    }

    // Upload documents
    const governmentIdMediaId = governmentId?.data ? await uploadFile(governmentId, 'government-id') : null
    const proofOfAddressMediaId = proofOfAddress?.data ? await uploadFile(proofOfAddress, 'proof-of-address') : null

    // Generate verification token and code
    const verificationToken = generateVerificationToken()
    const verificationCode = generateVerificationCode()
    const verificationExpires = new Date(Date.now() + 24 * 60 * 60 * 1000)

    // Calculate movement rankings (count all participants: waitlist + workers + sponsors)
    const signupTimestamp = new Date()

    const [{ waitlistCount }] = await db
      .select({ waitlistCount: sql<number>`count(*)` })
      .from(waitlistEntries)

    const [{ workersCount }] = await db
      .select({ workersCount: sql<number>`count(*)` })
      .from(workers)

    const [{ sponsorsCount }] = await db
      .select({ sponsorsCount: sql<number>`count(*)` })
      .from(sponsors)

    // Global rank includes all movement participants
    const globalRank = Number(waitlistCount) + Number(workersCount) + Number(sponsorsCount) + 1

    // Calculate location-specific ranks
    const [{ stateCount }] = await db
      .select({ stateCount: sql<number>`count(*)` })
      .from(workers)
      .where(eq(workers.addressState, state || 'IL'))

    const [{ waitlistStateCount }] = await db
      .select({ waitlistStateCount: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.locationStateCode, state || 'IL'))

    // City count - all of Chicago
    const [{ cityCount }] = await db
      .select({ cityCount: sql<number>`count(*)` })
      .from(workers)
      .where(eq(workers.addressCity, 'Chicago'))

    const [{ waitlistCityCount }] = await db
      .select({ waitlistCityCount: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.locationCity, 'Chicago'))

    // Neighborhood count - specific community area (North Lawndale for launch)
    const [{ neighborhoodCount }] = await db
      .select({ neighborhoodCount: sql<number>`count(*)` })
      .from(workers)
      .where(sql`${workers.addressZip} IN ('60623', '60624')`)

    const [{ waitlistNeighborhoodCount }] = await db
      .select({ waitlistNeighborhoodCount: sql<number>`count(*)` })
      .from(waitlistEntries)
      .where(eq(waitlistEntries.locationNeighborhood, communityArea))

    const signupRankings = {
      global: globalRank,
      country: globalRank, // US only for now
      state: Number(stateCount) + Number(waitlistStateCount) + 1,
      city: Number(cityCount) + Number(waitlistCityCount) + 1,
      neighborhood: Number(neighborhoodCount) + Number(waitlistNeighborhoodCount) + 1,
    }

    const earlyAdopterTier = getEarlyAdopterTier(globalRank)
    const referralCode = generateReferralCode()

    // Create worker record
    const [worker] = await db
      .insert(workers)
      .values({
        name,
        email,
        phone,
        addressStreet: street,
        addressCity: 'Chicago', // All workers are in Chicago for now
        addressState: 'IL',
        addressZip: zip,
        addressNeighborhood: communityArea,
        addressIsNorthLawndale: isInLaunchArea(zip),
        emailVerified: false,
        emailVerificationToken: `${verificationToken}:${verificationCode}`,
        emailVerificationExpires: verificationExpires.toISOString(),
        verificationGovernmentIdId: governmentIdMediaId,
        verificationProofOfAddressId: proofOfAddressMediaId,
        verificationStatus: 'pending',
        signupTimestamp: signupTimestamp.toISOString(),
        signupRankingsGlobal: String(signupRankings.global),
        signupRankingsCountry: String(signupRankings.country),
        signupRankingsState: String(signupRankings.state),
        signupRankingsCity: String(signupRankings.city),
        signupRankingsNeighborhood: String(signupRankings.neighborhood),
        earlyAdopterTier,
        referralCode,
        notificationsSmsEnabled: true,
        notificationsEmailEnabled: true,
      })
      .returning({ id: workers.id })

    // Send verification email
    const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'
    const verificationUrl = `${appUrl}/worker/verify-email?token=${verificationToken}&id=${worker.id}`

    const emailContent = emailVerificationEmail({
      name,
      verificationUrl,
      verificationCode,
    })

    const emailResult = await sendEmail({
      to: email,
      subject: 'Verify your email - GCC Worker Application',
      html: emailContent.html,
      text: emailContent.text,
    })

    if (!emailResult.success) {
      console.error('[apply] Email failed to send:', emailResult.error)
    } else {
      console.log('[apply] Verification email sent to:', email)
    }

    // Note: Stripe Connect account will be created when worker is approved
    // and ready to receive payouts, not during registration

    return Response.json({
      success: true,
      workerId: worker.id,
      message: emailResult.success
        ? 'Registration submitted. Please check your email to verify your account.'
        : 'Registration submitted. If you don\'t receive a verification email, please use the resend option on the verification page.',
      requiresEmailVerification: true,
      emailSent: emailResult.success,
      rankings: signupRankings,
      earlyAdopterTier,
      referralCode,
    })
  } catch (error) {
    console.error('Worker registration error:', error)
    const errorMessage = error instanceof Error ? error.message : 'Unknown error'
    const errorStack = error instanceof Error ? error.stack : undefined

    // Log detailed error info for debugging
    console.error('Error details:', {
      message: errorMessage,
      stack: errorStack,
      name: error instanceof Error ? error.name : 'Unknown',
    })

    // Check for specific database errors
    let userMessage = 'Something went wrong during registration. Please try again, or contact support if the problem continues.'

    if (errorMessage.includes('duplicate key') || errorMessage.includes('unique constraint')) {
      userMessage = 'An account with this email already exists. Please try logging in instead.'
    } else if (errorMessage.includes('connection') || errorMessage.includes('ECONNREFUSED')) {
      userMessage = 'Unable to connect to our servers. Please check your internet connection and try again.'
    } else if (errorMessage.includes('timeout')) {
      userMessage = 'The request took too long. Please try again.'
    }

    // Always return valid JSON
    return Response.json(
      {
        message: userMessage,
        error: errorMessage,
        success: false,
      },
      {
        status: 500,
        headers: {
          'Content-Type': 'application/json',
        }
      }
    )
  }
}
