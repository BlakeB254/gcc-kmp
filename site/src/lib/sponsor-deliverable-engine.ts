// The engine that fulfills a single deliverable item: persists state,
// generates artifacts (URLs, receipt numbers, social copy), and sends
// the sponsor + admin emails. Used by the admin "Trigger" button and by
// the Stripe webhook auto-fulfillment path.

import { eq } from 'drizzle-orm'
import { db } from '@/db'
import {
  sponsorDeliverableItems,
  sponsorDeliverablePackages,
  sponsors,
} from '@/db/schema'
import { sendEmail } from '@/lib/email'
import {
  certificateEmail,
  newsletterFeatureEmail,
  recognitionWallEmail,
  socialRecognitionEmail,
  taxReceiptEmail,
  adminItemTriggeredEmail,
} from '@/lib/email/templates/sponsor-deliverable'
import {
  draftNewsletterBlurb,
  draftSocialCopy,
  formatReceiptNumber,
  getItemSpec,
  type DeliverableKind,
} from '@/lib/sponsor-deliverables'
import { appUrl } from '@/lib/env'

function siteUrl(): string {
  return appUrl()
}

function adminEmail(): string | null {
  return process.env.ADMIN_NOTIFICATION_EMAIL || process.env.ADMIN_EMAILS?.split(',')[0]?.trim() || null
}

function adminPackageUrl(packageId: number): string {
  return `${siteUrl()}/admin/sponsors?package=${packageId}`
}

/**
 * Trigger a single deliverable item. Idempotent for already-completed items
 * (re-triggering will resend the email and refresh the data payload but won't
 * change status from completed).
 */
export async function triggerDeliverableItem(opts: {
  itemId: number
  workerId?: number | null
}) {
  const [item] = await db
    .select()
    .from(sponsorDeliverableItems)
    .where(eq(sponsorDeliverableItems.id, opts.itemId))
    .limit(1)
  if (!item) throw new Error(`Item ${opts.itemId} not found`)

  const [pkg] = await db
    .select()
    .from(sponsorDeliverablePackages)
    .where(eq(sponsorDeliverablePackages.id, item.packageId))
    .limit(1)
  if (!pkg) throw new Error(`Package ${item.packageId} not found`)

  const [sponsor] = await db
    .select()
    .from(sponsors)
    .where(eq(sponsors.id, pkg.sponsorId))
    .limit(1)
  if (!sponsor) throw new Error(`Sponsor ${pkg.sponsorId} not found`)

  const sponsorDisplay = sponsor.businessName || sponsor.name
  const baseEmailData = {
    sponsorName: sponsorDisplay,
    amountCents: pkg.amountCents,
    giftDate: pkg.createdAt,
    externalRef: pkg.externalReference,
  }

  let data: Record<string, unknown> = {}
  let artifactUrl: string | undefined
  let sponsorEmailContent: { html: string; text: string } | null = null
  let sponsorSubject = ''

  switch (item.kind as DeliverableKind) {
    case 'recognition_wall': {
      const wallUrl = `${siteUrl()}/sponsor/wall`
      data = { wallUrl, addedAt: new Date().toISOString() }
      artifactUrl = wallUrl
      sponsorEmailContent = recognitionWallEmail({ ...baseEmailData, wallUrl })
      sponsorSubject = `You're on the GCC sponsor recognition wall`
      break
    }
    case 'certificate': {
      const certificateUrl = `${siteUrl()}/sponsor/certificates/${pkg.id}`
      data = { certificateUrl, generatedAt: new Date().toISOString() }
      artifactUrl = certificateUrl
      sponsorEmailContent = certificateEmail({ ...baseEmailData, certificateUrl })
      sponsorSubject = `Your GCC certificate of appreciation`
      break
    }
    case 'tax_receipt': {
      const receiptNumber = formatReceiptNumber(pkg.id, pkg.createdAt)
      const receiptUrl = `${siteUrl()}/sponsor/receipts/${pkg.id}`
      data = { receiptNumber, receiptUrl, generatedAt: new Date().toISOString() }
      artifactUrl = receiptUrl
      sponsorEmailContent = taxReceiptEmail({ ...baseEmailData, receiptNumber, receiptUrl })
      sponsorSubject = `Tax receipt ${receiptNumber} — Global Community Cleaners`
      break
    }
    case 'newsletter_feature': {
      const blurb = draftNewsletterBlurb({
        sponsorDisplay,
        amountCents: pkg.amountCents,
      })
      data = { blurb, queuedAt: new Date().toISOString() }
      sponsorEmailContent = newsletterFeatureEmail(baseEmailData)
      sponsorSubject = `You'll be featured in the next GCC newsletter`
      break
    }
    case 'social_recognition': {
      const social = draftSocialCopy({
        sponsorDisplay,
        amountCents: pkg.amountCents,
      })
      data = { ...social, draftedAt: new Date().toISOString() }
      sponsorEmailContent = socialRecognitionEmail(baseEmailData)
      sponsorSubject = `Social media recognition for your GCC sponsorship`
      break
    }
    default:
      throw new Error(`Unknown deliverable kind: ${item.kind}`)
  }

  const triggeredAt = new Date().toISOString()

  // Send sponsor email (best-effort — engine still records the trigger).
  if (sponsorEmailContent) {
    const result = await sendEmail({
      to: sponsor.email,
      subject: sponsorSubject,
      html: sponsorEmailContent.html,
      text: sponsorEmailContent.text,
    })
    data.sponsorEmail = {
      to: sponsor.email,
      sentAt: triggeredAt,
      success: result.success,
      error: result.error,
    }
  }

  // Send admin notification email so admin has a record + artifact link.
  const adminTo = adminEmail()
  if (adminTo) {
    const adminContent = adminItemTriggeredEmail({
      sponsorName: sponsorDisplay,
      sponsorEmail: sponsor.email,
      itemLabel: getItemSpec(item.kind as DeliverableKind).label,
      amountCents: pkg.amountCents,
      artifactUrl,
      adminUrl: adminPackageUrl(pkg.id),
    })
    const adminResult = await sendEmail({
      to: adminTo,
      subject: `[GCC] Triggered: ${getItemSpec(item.kind as DeliverableKind).label} for ${sponsorDisplay}`,
      html: adminContent.html,
      text: adminContent.text,
    })
    data.adminEmail = {
      to: adminTo,
      sentAt: triggeredAt,
      success: adminResult.success,
      error: adminResult.error,
    }
  }

  // Persist
  await db
    .update(sponsorDeliverableItems)
    .set({
      status: 'completed',
      data: data,
      triggeredAt,
      triggeredByWorkerId: opts.workerId ?? null,
      updatedAt: triggeredAt,
    })
    .where(eq(sponsorDeliverableItems.id, item.id))

  // Aggregate package status (pending → in_progress → completed)
  const allItems = await db
    .select()
    .from(sponsorDeliverableItems)
    .where(eq(sponsorDeliverableItems.packageId, pkg.id))
  const completedCount = allItems.filter((it) =>
    it.id === item.id ? true : it.status === 'completed'
  ).length
  const newStatus =
    completedCount === allItems.length ? 'completed' : 'in_progress'
  await db
    .update(sponsorDeliverablePackages)
    .set({
      status: newStatus,
      completedAt: newStatus === 'completed' ? triggeredAt : null,
      updatedAt: triggeredAt,
    })
    .where(eq(sponsorDeliverablePackages.id, pkg.id))

  return { itemId: item.id, kind: item.kind, data, artifactUrl }
}

/**
 * Create a new deliverable package + its 5 child items. Used by both the admin
 * "create offline package" endpoint and the Stripe webhook auto-trigger.
 */
export async function createDeliverablePackage(opts: {
  sponsorId: number
  amountCents: number
  source: 'online' | 'offline'
  tier: 'silver' | 'gold' | 'platinum'
  transactionId?: number | null
  sponsorPackageId?: number | null
  externalReference?: string | null
  notes?: string | null
  createdByWorkerId?: number | null
  itemKinds: DeliverableKind[]
}): Promise<{ packageId: number }> {
  const [pkg] = await db
    .insert(sponsorDeliverablePackages)
    .values({
      sponsorId: opts.sponsorId,
      amountCents: opts.amountCents,
      source: opts.source,
      tier: opts.tier,
      transactionId: opts.transactionId ?? null,
      sponsorPackageId: opts.sponsorPackageId ?? null,
      externalReference: opts.externalReference ?? null,
      notes: opts.notes ?? null,
      createdByWorkerId: opts.createdByWorkerId ?? null,
      status: 'pending',
    })
    .returning({ id: sponsorDeliverablePackages.id })

  await db.insert(sponsorDeliverableItems).values(
    opts.itemKinds.map((kind) => ({
      packageId: pkg.id,
      kind,
      status: 'pending' as const,
    }))
  )

  return { packageId: pkg.id }
}
