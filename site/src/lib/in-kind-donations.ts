// Single source of truth for in-kind donation logic — pairs with the
// in_kind_donations / in_kind_donation_items tables and the /in-kind/*
// public pages.

import type { InKindDonation, InKindDonationItem } from '@/db/schema'

export const ORG_NAME = 'Global Community Cleaners NFP'
export const ORG_TAGLINE = '501(c)(3) tax-exempt nonprofit'

// =====================
// IRS compliance helpers
// =====================

// Per IRS Pub 1771, the donor-facing acknowledgment must describe the items
// but must NOT assign a dollar value — the donor is responsible for determining
// fair market value for their tax deduction. Internal records keep the FMV
// for grant reporting and Form 990 Schedule M.
export const IRS_NO_GOODS_STATEMENT =
  'No goods or services were provided by Global Community Cleaners NFP in exchange for this contribution.'

// Threshold above which a donor must obtain a qualified appraisal
// (Form 8283 Section B, signed by the recipient organization).
export const APPRAISAL_REQUIRED_THRESHOLD_CENTS = 500000 // $5,000

// =====================
// Formatting helpers
// =====================

export function formatInKindGiftId(donationId: number, donationDateIso: string): string {
  const year = new Date(donationDateIso).getUTCFullYear()
  return `GCC-IK-${year}-${String(donationId).padStart(5, '0')}`
}

export function formatDollars(cents: number | null | undefined): string {
  if (cents == null) return '—'
  const dollars = cents / 100
  if (Number.isInteger(dollars)) {
    return `$${dollars.toLocaleString('en-US')}`
  }
  return dollars.toLocaleString('en-US', { style: 'currency', currency: 'USD' })
}

export function formatLongDate(iso: string): string {
  return new Date(iso + (iso.length === 10 ? 'T12:00:00Z' : '')).toLocaleDateString('en-US', {
    dateStyle: 'long',
  })
}

export function formatItemLine(item: InKindDonationItem): string {
  const qty = item.quantity
  const unit = item.unitLabel ? ` ${item.unitLabel}` : ''
  return `${qty}${unit} ${item.description}`.trim()
}

export function totalEstimatedValueCents(items: InKindDonationItem[], headlineCents: number | null): number | null {
  // Prefer the headline donation-level estimate when set; fall back to summing
  // line-item values when individual unit values are present.
  if (headlineCents != null) return headlineCents
  const summed = items.reduce((acc, it) => {
    if (it.estimatedUnitValueCents == null) return acc
    return acc + it.estimatedUnitValueCents * it.quantity
  }, 0)
  return summed > 0 ? summed : null
}

export function requiresAppraisal(estimatedValueCents: number | null): boolean {
  return estimatedValueCents != null && estimatedValueCents >= APPRAISAL_REQUIRED_THRESHOLD_CENTS
}

// =====================
// Default copy
// =====================

export function defaultThankYouMessage(donorDisplay: string): string {
  return (
    `On behalf of Global Community Cleaners, thank you for your generous in-kind contribution. ` +
    `The materials you've provided will directly support our cleanup work — funding clean blocks ` +
    `and paid local work in North Lawndale and beyond. Partners like ${donorDisplay} make it possible ` +
    `for every dollar and every donation to show up on the street and in the pockets of the residents ` +
    `who clean them.`
  )
}

// =====================
// Seed data
// =====================
// These records are inserted (idempotently, keyed by slug) when the seed
// script runs. Keep them in code so the template + first real gift can be
// reproduced on any fresh DB.

export interface InKindSeedRecord {
  donation: Omit<NewInKindDonationInput, 'id'>
  items: NewInKindItemInput[]
}

type NewInKindDonationInput = Partial<InKindDonation> & {
  slug: string
  donorName: string
  donationDate: string
}

type NewInKindItemInput = Partial<InKindDonationItem> & {
  description: string
  quantity: number
}

export const IN_KIND_SEED_RECORDS: InKindSeedRecord[] = [
  // -----------------------------------------------------------------------
  // TEMPLATE — generic placeholder. Use this to preview the PDF structure
  // before a real gift comes in. Marked isTemplate=true so it can be excluded
  // from public counts and admin lists.
  // -----------------------------------------------------------------------
  {
    donation: {
      slug: 'template',
      isTemplate: true,
      donorName: '[Donor Name or Organization]',
      donorType: 'organization',
      donorContactName: '[Contact Person]',
      donorEmail: 'donor@example.org',
      donorAddressStreet: '[Street Address]',
      donorAddressCity: '[City]',
      donorAddressState: 'IL',
      donorAddressZip: '[ZIP]',
      donationDate: '2026-05-13',
      receivedDate: '2026-05-13',
      dropOffLocation: '[Drop-off location, e.g. UIC Neighborhood Center]',
      estimatedValueCents: 50000,
      valueSource: 'donor_provided',
      valueNotes: 'Estimate provided by donor in donation notification email.',
      storageLocation: '[Where items are stored]',
      intendedUse: 'Community cleanup operations across GCC service areas.',
      programFund: 'Cleanup Operations',
      status: 'received',
      signingOfficerName: '[Authorized Representative]',
      signingOfficerTitle: 'Executive Director',
      thankYouMessage: defaultThankYouMessage('[Donor Name]'),
      notes: 'Template record — use as a starting point when entering new gifts.',
    },
    items: [
      { description: '[Item description]', quantity: 1, unitLabel: 'unit', condition: 'new', sortOrder: 0 },
      { description: '[Second item description]', quantity: 1, unitLabel: 'unit', condition: 'new', sortOrder: 1 },
    ],
  },

  // -----------------------------------------------------------------------
  // GCC's first in-kind donation — Fields of Dreams Chicago, May 2026
  // -----------------------------------------------------------------------
  {
    donation: {
      slug: 'fields-of-dreams-chicago-2026-05',
      isTemplate: false,
      donorName: 'Fields of Dreams Chicago',
      donorType: 'organization',
      donorContactName: 'James Fields',
      donorEmail: 'james@fieldsofdreamschicago.org', // placeholder — update with real email
      donationDate: '2026-05-13',
      receivedDate: '2026-05-13',
      dropOffLocation: 'UIC Neighborhood Center (back corner)',
      estimatedValueCents: 50000, // $500 — donor-provided estimate
      valueSource: 'donor_provided',
      valueNotes: 'Estimate stated by James Fields in donation notification email dated 2026-05-13.',
      storageLocation: 'UIC Neighborhood Center, back corner',
      intendedUse:
        'Direct community cleanup operations — equipping GCC workers and volunteers for block-level cleanups across North Lawndale and surrounding service areas.',
      programFund: 'Cleanup Operations - North Lawndale',
      status: 'received',
      // Signing officer intentionally omitted — IRS Pub 1771 does not require
      // a signature on charitable acknowledgments. Org name + date stamp the
      // letter instead. Set these fields later if a named officer wants to sign.
      signingOfficerName: null,
      signingOfficerTitle: null,
      thankYouMessage:
        'On behalf of Global Community Cleaners, thank you for your generous in-kind contribution. ' +
        'These materials will directly equip our cleanup crews working in North Lawndale and beyond — ' +
        'where every shovel, broom, and pickup stick translates into a cleaner block and paid local work. ' +
        "Fields of Dreams Chicago's intentionality in naming this gift as in-kind, and your guidance on " +
        'building strong donor records, are gifts in themselves. We are grateful to be in community with you.',
      notes:
        "GCC's first in-kind donation. James Fields cc'd Cameron and emphasized importance of " +
        'documenting in-kind gifts for grant reporting and long-term sustainability.',
    },
    items: [
      { description: 'Gloves', quantity: 3, unitLabel: 'boxes', condition: 'new', sortOrder: 0 },
      { description: 'Shovels', quantity: 3, unitLabel: 'pieces', condition: 'new', sortOrder: 1 },
      {
        description: 'Stainless steel dustpan-and-broom sets',
        quantity: 4,
        unitLabel: 'sets',
        condition: 'new',
        sortOrder: 2,
      },
      { description: 'Push brooms', quantity: 2, unitLabel: 'pieces', condition: 'new', sortOrder: 3 },
      {
        description: 'Metal pickup sticks',
        quantity: 1,
        unitLabel: '30-pack',
        condition: 'new',
        sortOrder: 4,
      },
    ],
  },
]
