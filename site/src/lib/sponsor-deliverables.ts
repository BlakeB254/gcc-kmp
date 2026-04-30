// Single source of truth for sponsor deliverable logic.
// Pairs with the sponsor_deliverable_packages / sponsor_deliverable_items tables
// and the /admin/sponsors UI.

export type DeliverableKind =
  | 'recognition_wall'
  | 'certificate'
  | 'tax_receipt'
  | 'newsletter_feature'
  | 'social_recognition'

export interface DeliverableItemSpec {
  kind: DeliverableKind
  label: string
  description: string
  // Whether the trigger requires admin follow-up (e.g. newsletter, social) vs.
  // is fully fulfilled at trigger time (cert, receipt, wall).
  needsManualFollowUp: boolean
}

// $2,000 ("silver+") deliverables — the canonical list the user defined.
// Higher tiers extend this set, but for now $2K = silver = these 5.
const SILVER_ITEMS: DeliverableItemSpec[] = [
  {
    kind: 'recognition_wall',
    label: 'Name on sponsor recognition wall',
    description: 'Add the sponsor to the public recognition wall on the site.',
    needsManualFollowUp: false,
  },
  {
    kind: 'certificate',
    label: 'Digital certificate of appreciation',
    description: 'Generate a shareable digital certificate and email it to the sponsor.',
    needsManualFollowUp: false,
  },
  {
    kind: 'tax_receipt',
    label: 'Tax-deductible donation receipt',
    description: 'Generate a 501(c)(3) tax receipt with EIN and gift details.',
    needsManualFollowUp: false,
  },
  {
    kind: 'newsletter_feature',
    label: 'Featured on monthly newsletter',
    description: 'Queue a feature blurb for the next monthly newsletter send.',
    needsManualFollowUp: true,
  },
  {
    kind: 'social_recognition',
    label: 'Social media recognition',
    description: 'Draft social copy (X/IG/LinkedIn) for admin to review and post.',
    needsManualFollowUp: true,
  },
]

export const DELIVERABLE_THRESHOLD_CENTS = 200000 // $2,000

/**
 * Determine the tier label from the gift amount in cents.
 * Stays in lockstep with sponsor-tiers.ts for now — silver triggers at $2K
 * because that is what the user defined when announcing the UIC gift.
 */
export function tierFromAmount(amountCents: number): 'silver' | 'gold' | 'platinum' | null {
  if (amountCents >= 1000000) return 'platinum' // $10K+
  if (amountCents >= 500000) return 'gold'      // $5K+
  if (amountCents >= DELIVERABLE_THRESHOLD_CENTS) return 'silver' // $2K+
  return null
}

/**
 * The set of items owed at a given tier. Higher tiers stack the lower-tier set.
 */
export function itemsForTier(tier: 'silver' | 'gold' | 'platinum'): DeliverableItemSpec[] {
  // For silver/gold/platinum the user's defined $2K-level set applies. When the
  // higher tiers grow extra items (branded photo, plaque, etc.) extend here.
  void tier
  return SILVER_ITEMS
}

export function getItemSpec(kind: DeliverableKind): DeliverableItemSpec {
  const spec = SILVER_ITEMS.find((it) => it.kind === kind)
  if (!spec) throw new Error(`Unknown deliverable kind: ${kind}`)
  return spec
}

/**
 * Build a stable receipt number from package id + year.
 */
export function formatReceiptNumber(packageId: number, createdAtIso: string): string {
  const year = new Date(createdAtIso).getUTCFullYear()
  return `GCC-${year}-${String(packageId).padStart(5, '0')}`
}

/**
 * Format cents as a $X,XXX string (no decimals when whole dollars).
 */
export function formatDollars(cents: number): string {
  const dollars = cents / 100
  if (Number.isInteger(dollars)) {
    return `$${dollars.toLocaleString('en-US')}`
  }
  return dollars.toLocaleString('en-US', { style: 'currency', currency: 'USD' })
}

/**
 * Default social-recognition copy. Admin can edit before posting.
 */
export function draftSocialCopy(opts: {
  sponsorDisplay: string
  amountCents: number
}): { twitter: string; linkedin: string; instagram: string } {
  const amount = formatDollars(opts.amountCents)
  return {
    twitter:
      `Huge thanks to ${opts.sponsorDisplay} for sponsoring Global Community Cleaners with ${amount}! ` +
      `100% of every block sponsorship pays a local resident to clean their own neighborhood. ` +
      `Together we keep streets clean and put real money in real pockets. 💚`,
    linkedin:
      `We're proud to recognize ${opts.sponsorDisplay} for their generous ${amount} sponsorship of ` +
      `Global Community Cleaners. Their support directly funds local residents cleaning the blocks ` +
      `they live on — a model where every dollar shows up on the street and in a paycheck. ` +
      `Thank you for partnering with us.`,
    instagram:
      `Big shout-out to ${opts.sponsorDisplay} 💚 — their ${amount} sponsorship goes straight to ` +
      `North Lawndale residents cleaning their own blocks. Clean streets. Real money. Real neighborhoods. ` +
      `#GCC #GlobalCommunityCleaners`,
  }
}

/**
 * Default newsletter blurb.
 */
export function draftNewsletterBlurb(opts: {
  sponsorDisplay: string
  amountCents: number
}): string {
  return (
    `${opts.sponsorDisplay} stepped up this month with a ${formatDollars(opts.amountCents)} sponsorship ` +
    `of Global Community Cleaners — funding clean blocks and paid local work in North Lawndale. ` +
    `Thank you for backing the movement.`
  )
}
