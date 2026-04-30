import { NextResponse } from 'next/server'
import { eq, desc } from 'drizzle-orm'
import { db } from '@/db'
import {
  sponsorDeliverableItems,
  sponsorDeliverablePackages,
  sponsors,
} from '@/db/schema'
import { requireAdmin } from '@/lib/admin-auth'
import {
  createDeliverablePackage,
} from '@/lib/sponsor-deliverable-engine'
import {
  DELIVERABLE_THRESHOLD_CENTS,
  itemsForTier,
  tierFromAmount,
} from '@/lib/sponsor-deliverables'
import { sendEmail } from '@/lib/email'
import { adminPackageCreatedEmail } from '@/lib/email/templates/sponsor-deliverable'

export const runtime = 'nodejs'

function siteUrl() {
  return process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'
}

function adminEmail(): string | null {
  return process.env.ADMIN_NOTIFICATION_EMAIL || process.env.ADMIN_EMAILS?.split(',')[0]?.trim() || null
}

// -------------------------------------------------------------------
// GET — list packages with sponsor info + items
// -------------------------------------------------------------------
export async function GET(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  const packages = await db
    .select({
      id: sponsorDeliverablePackages.id,
      sponsorId: sponsorDeliverablePackages.sponsorId,
      sponsorName: sponsors.name,
      sponsorEmail: sponsors.email,
      sponsorBusinessName: sponsors.businessName,
      amountCents: sponsorDeliverablePackages.amountCents,
      source: sponsorDeliverablePackages.source,
      tier: sponsorDeliverablePackages.tier,
      status: sponsorDeliverablePackages.status,
      externalReference: sponsorDeliverablePackages.externalReference,
      notes: sponsorDeliverablePackages.notes,
      transactionId: sponsorDeliverablePackages.transactionId,
      createdAt: sponsorDeliverablePackages.createdAt,
      completedAt: sponsorDeliverablePackages.completedAt,
    })
    .from(sponsorDeliverablePackages)
    .leftJoin(sponsors, eq(sponsorDeliverablePackages.sponsorId, sponsors.id))
    .orderBy(desc(sponsorDeliverablePackages.createdAt))

  if (packages.length === 0) {
    return NextResponse.json({ data: [] })
  }

  const allItems = await db
    .select()
    .from(sponsorDeliverableItems)

  const itemsByPackage = new Map<number, typeof allItems>()
  for (const it of allItems) {
    if (!itemsByPackage.has(it.packageId)) itemsByPackage.set(it.packageId, [])
    itemsByPackage.get(it.packageId)!.push(it)
  }

  const data = packages.map((p) => ({
    ...p,
    items: itemsByPackage.get(p.id) || [],
  }))

  return NextResponse.json({ data })
}

// -------------------------------------------------------------------
// POST — create offline package (admin manually entering a check/wire/etc.)
// Body: { sponsorId? | sponsorEmail + sponsorName, amountCents, externalReference?, notes? }
// -------------------------------------------------------------------
export async function POST(request: Request) {
  const auth = await requireAdmin(request)
  if (auth instanceof NextResponse) return auth

  let body: any
  try {
    body = await request.json()
  } catch {
    return NextResponse.json({ error: 'Invalid JSON' }, { status: 400 })
  }

  const amountCents = Number(body.amountCents)
  if (!Number.isFinite(amountCents) || amountCents <= 0) {
    return NextResponse.json({ error: 'amountCents must be a positive integer' }, { status: 400 })
  }
  if (amountCents < DELIVERABLE_THRESHOLD_CENTS) {
    return NextResponse.json(
      { error: `Amount must be at least $${DELIVERABLE_THRESHOLD_CENTS / 100} to qualify for this deliverable package` },
      { status: 400 }
    )
  }

  const tier = tierFromAmount(amountCents)
  if (!tier) {
    return NextResponse.json({ error: 'Amount does not qualify for any deliverable tier' }, { status: 400 })
  }

  // Resolve sponsor: either by id, or find/create by email.
  let sponsorId: number
  if (body.sponsorId) {
    const idNum = Number(body.sponsorId)
    if (!Number.isFinite(idNum)) {
      return NextResponse.json({ error: 'Invalid sponsorId' }, { status: 400 })
    }
    const [s] = await db.select().from(sponsors).where(eq(sponsors.id, idNum)).limit(1)
    if (!s) return NextResponse.json({ error: 'Sponsor not found' }, { status: 404 })
    sponsorId = s.id
  } else {
    const email = String(body.sponsorEmail || '').trim().toLowerCase()
    const name = String(body.sponsorName || '').trim()
    const businessName = body.sponsorBusinessName ? String(body.sponsorBusinessName).trim() : null
    if (!email || !name) {
      return NextResponse.json(
        { error: 'sponsorId, or sponsorEmail+sponsorName, is required' },
        { status: 400 }
      )
    }
    const [existing] = await db.select().from(sponsors).where(eq(sponsors.email, email)).limit(1)
    if (existing) {
      sponsorId = existing.id
    } else {
      const [created] = await db
        .insert(sponsors)
        .values({
          name,
          email,
          businessName: businessName,
          type: businessName ? 'organization' : 'individual',
          emailVerified: true, // Manually entered by admin
          emailVerifiedAt: new Date().toISOString(),
        })
        .returning({ id: sponsors.id })
      sponsorId = created.id
    }
  }

  const items = itemsForTier(tier).map((spec) => spec.kind)
  const { packageId } = await createDeliverablePackage({
    sponsorId,
    amountCents,
    source: 'offline',
    tier,
    externalReference: body.externalReference || null,
    notes: body.notes || null,
    createdByWorkerId: auth.workerId,
    itemKinds: items,
  })

  // Notify admin (record + link)
  const adminTo = adminEmail()
  if (adminTo) {
    const [pkgSponsor] = await db.select().from(sponsors).where(eq(sponsors.id, sponsorId)).limit(1)
    if (pkgSponsor) {
      const display = pkgSponsor.businessName || pkgSponsor.name
      const content = adminPackageCreatedEmail({
        sponsorName: display,
        sponsorEmail: pkgSponsor.email,
        amountCents,
        source: 'offline',
        externalRef: body.externalReference || null,
        adminUrl: `${siteUrl()}/admin/sponsors?package=${packageId}`,
      })
      await sendEmail({
        to: adminTo,
        subject: `[GCC] New offline sponsorship package: ${display} (${amountCents / 100})`,
        html: content.html,
        text: content.text,
      })
    }
  }

  return NextResponse.json({ data: { packageId } }, { status: 201 })
}

