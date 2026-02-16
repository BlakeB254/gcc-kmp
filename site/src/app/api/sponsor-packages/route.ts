import { NextResponse } from 'next/server'
import { db } from '@/db'
import { sponsors, sponsorPackages } from '@/db/schema'
import { eq } from 'drizzle-orm'
import {
  calculatePackagePrice,
  requiresCustomQuote,
  getRecognitionLevel,
  getEnterpriseTier,
  type SponsorPackageConfig,
} from '@/lib/sponsor-pricing'

// Map camelCase tier keys to snake_case database enum values
const enterpriseTierToDb: Record<string, 'community_champion' | 'impact_partner' | 'documentary_sponsor' | 'movement_partner' | 'legacy_sponsor' | 'founding_partner'> = {
  communityChampion: 'community_champion',
  impactPartner: 'impact_partner',
  documentarySponsor: 'documentary_sponsor',
  movementPartner: 'movement_partner',
  legacySponsor: 'legacy_sponsor',
  foundingPartner: 'founding_partner',
}

interface SubmissionRequest {
  // Package configuration from calculator
  config: SponsorPackageConfig

  // Contact information
  contact: {
    name: string
    email: string
    phone?: string
    company?: string
    notes?: string
  }
}

/**
 * POST /api/sponsor-packages
 *
 * Saves a calculator submission from the sponsor calculator wizard.
 * Creates or finds sponsor by email, then inserts the package with status 'quoted'.
 */
export async function POST(request: Request) {
  console.log('[sponsor-packages] POST request received')

  try {
    const body: SubmissionRequest = await request.json()
    const { config, contact } = body

    console.log('[sponsor-packages] Parsed request:', {
      hasConfig: !!config,
      blockCount: config?.blockCount,
      contactEmail: contact?.email,
      contactName: contact?.name,
    })

    // Validate required fields
    if (!config || !contact?.name || !contact?.email) {
      console.log('[sponsor-packages] Validation failed: missing required fields')
      return NextResponse.json(
        { error: 'Missing required fields: config, contact.name, contact.email' },
        { status: 400 }
      )
    }

    // Validate email format
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (!emailRegex.test(contact.email)) {
      return NextResponse.json(
        { error: 'Invalid email format' },
        { status: 400 }
      )
    }

    // Validate block count
    if (!config.blockCount || config.blockCount < 1) {
      return NextResponse.json(
        { error: 'Block count must be at least 1' },
        { status: 400 }
      )
    }

    // Calculate pricing breakdown
    const priceBreakdown = calculatePackagePrice(config)

    // Find or create sponsor by email
    let sponsor = await db.query.sponsors.findFirst({
      where: eq(sponsors.email, contact.email.toLowerCase()),
    })

    if (!sponsor) {
      // Create new sponsor
      console.log('[sponsor-packages] Creating new sponsor for:', contact.email.toLowerCase())
      const [newSponsor] = await db.insert(sponsors).values({
        name: contact.name,
        email: contact.email.toLowerCase(),
        phone: contact.phone || null,
        businessName: contact.company || null,
        type: contact.company ? 'business' : 'individual',
        signupTimestamp: new Date().toISOString(),
      }).returning()
      sponsor = newSponsor
      console.log('[sponsor-packages] Created sponsor with ID:', sponsor.id)
    } else {
      console.log('[sponsor-packages] Found existing sponsor with ID:', sponsor.id)
    }

    // Determine enterprise tier and recognition level
    const enterpriseTier = getEnterpriseTier(priceBreakdown.total)
    const recognitionLevel = getRecognitionLevel(priceBreakdown.total)

    // Create sponsor package
    const [newPackage] = await db.insert(sponsorPackages).values({
      sponsorId: sponsor.id,

      // Base configuration
      blockCount: config.blockCount,
      baseAmount: priceBreakdown.baseTotal.toString(),
      isRecurring: config.isRecurring,
      recurringFrequency: config.recurringFrequency || null,

      // Add-ons as JSONB
      workerBrandingAddons: config.workerBranding || {},
      equipmentBrandingAddons: config.equipmentBranding || {},
      streetAdvertisingAddons: config.streetAdvertising || {},
      digitalPresenceAddons: config.digitalPresence || {},
      enhancedCleaningAddons: config.enhancedCleaning || {},
      seasonalServicesAddons: config.seasonalServices || {},
      greenServicesAddons: config.greenServices || {},
      businessEngagementAddons: config.businessEngagement || {},

      // Events and media
      eventPackages: config.events || [],
      photoPackage: config.photoPackage || null,
      videoPackage: config.videoPackage || null,

      // Calculated amounts (stored in cents as strings for numeric columns)
      workerBrandingAmount: priceBreakdown.workerBrandingTotal.toString(),
      equipmentBrandingAmount: priceBreakdown.equipmentBrandingTotal.toString(),
      streetAdvertisingAmount: priceBreakdown.streetAdvertisingTotal.toString(),
      digitalPresenceAmount: priceBreakdown.digitalPresenceTotal.toString(),
      enhancedCleaningAmount: priceBreakdown.enhancedCleaningTotal.toString(),
      seasonalServicesAmount: priceBreakdown.seasonalServicesTotal.toString(),
      greenServicesAmount: priceBreakdown.greenServicesTotal.toString(),
      businessEngagementAmount: priceBreakdown.businessEngagementTotal.toString(),
      eventsAmount: priceBreakdown.eventsTotal.toString(),
      mediaAmount: priceBreakdown.mediaTotal.toString(),

      // Totals
      addonsAmount: priceBreakdown.addonsTotal.toString(),
      volumeDiscountAmount: priceBreakdown.addonDiscountAmount.toString(),
      recurringDiscountAmount: priceBreakdown.recurringDiscountAmount.toString(),
      totalAmount: priceBreakdown.total.toString(),
      workerPayoutAmount: priceBreakdown.workerPayoutTotal.toString(),
      gccFeeAmount: priceBreakdown.gccOperationsTotal.toString(),

      // Enterprise and recognition
      isEnterprise: priceBreakdown.isEnterprise,
      enterpriseTier: enterpriseTier ? enterpriseTierToDb[enterpriseTier] || null : null,
      requiresCustomQuote: requiresCustomQuote(priceBreakdown.total),
      recognitionLevel: recognitionLevel,

      // Status and notes
      status: 'quoted',
      notes: contact.notes || null,
      metadata: {
        submittedAt: new Date().toISOString(),
        contactPhone: contact.phone || null,
        contactCompany: contact.company || null,
      },
    }).returning()

    console.log('[sponsor-packages] Created package with ID:', newPackage.id, 'for sponsor:', sponsor.id)

    return NextResponse.json({
      success: true,
      packageId: newPackage.id,
      sponsorId: sponsor.id,
      total: priceBreakdown.total,
      isEnterprise: priceBreakdown.isEnterprise,
      requiresCustomQuote: requiresCustomQuote(priceBreakdown.total),
    }, { status: 201 })

  } catch (error) {
    console.error('[sponsor-packages] Error creating sponsor package:', error)
    console.error('[sponsor-packages] Error details:', {
      name: (error as Error).name,
      message: (error as Error).message,
      stack: (error as Error).stack?.split('\n').slice(0, 5).join('\n'),
    })

    // Check for unique constraint violation
    if ((error as Error).message?.includes('unique')) {
      return NextResponse.json(
        { error: 'A package with this configuration already exists' },
        { status: 409 }
      )
    }

    return NextResponse.json(
      { error: 'Failed to create sponsor package' },
      { status: 500 }
    )
  }
}
