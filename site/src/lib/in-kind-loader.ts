// Server-only loader. Queries the in_kind_donations + items tables and falls
// back to the typed seed registry so the routes still render before
// `npm run seed:in-kind` has been executed against a fresh DB.

import 'server-only'
import { asc, eq } from 'drizzle-orm'
import { db } from '@/db'
import {
  inKindDonationItems,
  inKindDonations,
  type InKindDonation,
  type InKindDonationItem,
} from '@/db/schema'
import { IN_KIND_SEED_RECORDS } from './in-kind-donations'

export interface InKindBundle {
  donation: InKindDonation
  items: InKindDonationItem[]
  source: 'db' | 'seed-fallback'
}

export async function getInKindBundleBySlug(slug: string): Promise<InKindBundle | null> {
  try {
    const [donation] = await db
      .select()
      .from(inKindDonations)
      .where(eq(inKindDonations.slug, slug))
      .limit(1)

    if (donation) {
      const items = await db
        .select()
        .from(inKindDonationItems)
        .where(eq(inKindDonationItems.donationId, donation.id))
        .orderBy(asc(inKindDonationItems.sortOrder))
      return { donation, items, source: 'db' }
    }
  } catch (err) {
    // DB unavailable (build-time prerender, no DATABASE_URI set, etc).
    // Fall through to seed registry so /in-kind/* still renders.
    console.warn('[in-kind-loader] DB unavailable, using seed fallback:', err instanceof Error ? err.message : err)
  }

  return seedFallbackBySlug(slug)
}

export async function listInKindBundles(): Promise<InKindBundle[]> {
  try {
    const donations = await db
      .select()
      .from(inKindDonations)
      .orderBy(asc(inKindDonations.donationDate))

    if (donations.length > 0) {
      const result: InKindBundle[] = []
      for (const donation of donations) {
        const items = await db
          .select()
          .from(inKindDonationItems)
          .where(eq(inKindDonationItems.donationId, donation.id))
          .orderBy(asc(inKindDonationItems.sortOrder))
        result.push({ donation, items, source: 'db' })
      }
      return result
    }
  } catch (err) {
    console.warn('[in-kind-loader] DB unavailable, using seed fallback:', err instanceof Error ? err.message : err)
  }

  return seedAllFallback()
}

// =====================
// Fallback path (no DB)
// =====================

function seedFallbackBySlug(slug: string): InKindBundle | null {
  const idx = IN_KIND_SEED_RECORDS.findIndex((r) => r.donation.slug === slug)
  if (idx < 0) return null
  return seedToBundle(idx)
}

function seedAllFallback(): InKindBundle[] {
  return IN_KIND_SEED_RECORDS.map((_, i) => seedToBundle(i))
}

function seedToBundle(idx: number): InKindBundle {
  const record = IN_KIND_SEED_RECORDS[idx]
  const id = idx + 1
  const now = new Date().toISOString()
  const donation: InKindDonation = {
    id,
    slug: record.donation.slug,
    isTemplate: record.donation.isTemplate ?? false,
    donorName: record.donation.donorName,
    donorType: record.donation.donorType ?? 'individual',
    donorContactName: record.donation.donorContactName ?? null,
    donorEmail: record.donation.donorEmail ?? null,
    donorPhone: record.donation.donorPhone ?? null,
    donorAddressStreet: record.donation.donorAddressStreet ?? null,
    donorAddressCity: record.donation.donorAddressCity ?? null,
    donorAddressState: record.donation.donorAddressState ?? null,
    donorAddressZip: record.donation.donorAddressZip ?? null,
    donationDate: record.donation.donationDate,
    receivedDate: record.donation.receivedDate ?? null,
    dropOffLocation: record.donation.dropOffLocation ?? null,
    estimatedValueCents: record.donation.estimatedValueCents ?? null,
    valueSource: record.donation.valueSource ?? 'unknown',
    valueNotes: record.donation.valueNotes ?? null,
    storageLocation: record.donation.storageLocation ?? null,
    intendedUse: record.donation.intendedUse ?? null,
    programFund: record.donation.programFund ?? null,
    status: record.donation.status ?? 'received',
    acknowledgedAt: record.donation.acknowledgedAt ?? null,
    acknowledgmentMethod: record.donation.acknowledgmentMethod ?? null,
    signingOfficerName: record.donation.signingOfficerName ?? null,
    signingOfficerTitle: record.donation.signingOfficerTitle ?? null,
    thankYouMessage: record.donation.thankYouMessage ?? null,
    notes: record.donation.notes ?? null,
    createdByWorkerId: null,
    createdAt: now,
    updatedAt: now,
  }
  const items: InKindDonationItem[] = record.items.map((it, j) => ({
    id: j + 1,
    donationId: id,
    description: it.description,
    quantity: it.quantity,
    unitLabel: it.unitLabel ?? null,
    condition: it.condition ?? null,
    estimatedUnitValueCents: it.estimatedUnitValueCents ?? null,
    sortOrder: it.sortOrder ?? j,
    createdAt: now,
  }))
  return { donation, items, source: 'seed-fallback' }
}
