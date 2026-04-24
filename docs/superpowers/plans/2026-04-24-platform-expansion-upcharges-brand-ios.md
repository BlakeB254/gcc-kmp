# GCC Platform Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship four independent platform upgrades: (A) flex upcharges added post-booking up to T-12h with an approval flow for requests inside T-12h, (B) an iMessage-grade rich preview when the iOS-app link is texted, (C) a single brand-token source of truth applied across web + iOS + Android, (D) a dynamic heatmap in the Compose-Multiplatform iOS app that highlights all affected blocks.

**Architecture:** Four phases. Each phase ships working software on its own and can be merged independently — Phase A touches web/DB/Stripe only, Phase B touches web og metadata only, Phase C sweeps tokens across all three platforms, Phase D is iOS/KMP + one API endpoint. Phases A and B can run in parallel; C should precede D so the iOS heatmap lands with correct brand colors.

**Tech stack:** Next.js 16 App Router (TypeScript), Drizzle ORM + PostgreSQL, Stripe (API v2025-*), next/og (@vercel/og edge runtime), Tailwind + CSS custom properties for tokens, Compose Multiplatform + KMP (shared/commonMain), MapLibre Native for Compose, Swift host wrapper (iosApp/).

---

## Phase A: Flex upcharges on booked blocks

### Domain rules (locked)

- Sponsor books a **package** (`sponsor_packages`) over N blocks. At checkout, a `transactions` row is created per block (existing pattern). Each transaction's scheduled cleaning date is **optional** — sponsor may pick one, or defer and let GCC assign it.
- **Booking attachment model:**
  - New column `transactions.sponsorPackageId` links each transaction back to its package (so "all blocks in this campaign" is one query).
  - New column `transactions.scheduledCleaningAt` combines date + start time into one timestamp (nullable).
  - New column `transactions.assignmentStatus` (enum: `awaiting_assignment | scheduled | in_progress | completed | cancelled`) tracks the cleaning-lifecycle separately from the Stripe `status` column.
- **GCC assignment flow** (for transactions the sponsor didn't schedule):
  - GCC admin assigns a specific `blockId` (if not already picked) and `scheduledCleaningAt` via an admin queue.
  - On assignment, `assignmentStatus` flips to `scheduled` and the sponsor is notified (email + dashboard toast + inbox entry).
  - Sponsor dashboard shows an **itemized ledger** — every transaction as a line item with "assigned block / assigned date / amount / upcharges / subtotal" — that updates live as GCC fills things in.
- **Campaign-wide upcharges** (applied to every transaction in the package) receive the calculator discount ladder (same schedule as initial booking). Subsequent transactions added to the same package retroactively inherit campaign upcharges.
- **Per-block upcharges** (applied to some subset) are allowed but **charged at full list price — no discount**.
- **T-12h cutoff** is only meaningful once `scheduledCleaningAt` is set. Before scheduling, sponsor can add/remove upcharges freely with no cutoff. Once `scheduledCleaningAt` is set:
  - **≥ T-12h:** sponsor can add/remove upcharges directly, Stripe is charged immediately on confirmation.
  - **< T-12h:** submission becomes a **request** — GCC admin must manually approve before charge. Sponsor sees "Awaiting GCC" state; resolves to Confirmed+Charged or Denied.
- **Refunds:** removing an auto-applied upcharge ≥ T-12h issues a Stripe refund; removing after <12h is not allowed (must request cancellation).

### File structure

- **Create:**
  - `site/src/lib/upcharges/catalog.ts` — typed catalog with `{ slug, name, priceCents, campaignDiscountable, description, category }`
  - `site/src/lib/upcharges/pricing.ts` — `computeUpchargeBundlePrice(items, scope)` respecting discount rules
  - `site/src/lib/upcharges/deadline.ts` — `isBeforeCutoff(scheduledCleaningAt | null, hours = 12)` — returns `true` if unscheduled, so unscheduled txs always use the direct path
  - `site/src/lib/upcharges/ledger.ts` — builds the sponsor-facing itemized ledger from transactions + upcharges + package
  - `site/src/db/migrations/000X_transactions_assignment.sql` — add assignment columns + backfill `scheduledCleaningAt` from existing date+time pair
  - `site/src/db/migrations/000Y_block_upcharges.sql` — new upcharge tables
  - `site/src/app/api/sponsor/transactions/[txId]/upcharges/route.ts` — POST add, DELETE remove (unscheduled & T-12h+ path)
  - `site/src/app/api/sponsor/transactions/[txId]/upcharge-requests/route.ts` — POST request (T-12h- path only)
  - `site/src/app/api/sponsor/packages/[packageId]/upcharges/route.ts` — campaign-wide path (all transactions)
  - `site/src/app/api/sponsor/packages/[packageId]/ledger/route.ts` — GET itemized ledger payload
  - `site/src/app/api/admin/upcharge-requests/route.ts` — GET queue, POST approve/deny
  - `site/src/app/api/admin/transactions/[txId]/assign/route.ts` — POST to set `blockId` + `scheduledCleaningAt`, triggers sponsor notice
  - `site/src/app/api/admin/unassigned-transactions/route.ts` — GET admin queue of `awaiting_assignment` rows
  - `site/src/app/api/stripe/webhook/route.ts:handleUpchargeChargeSucceeded` — mark upcharge `confirmed`
  - `site/src/components/sponsor/dashboard/ItemizedLedger.tsx` — live per-transaction line-item view
  - `site/src/components/sponsor/dashboard/UpchargePanel.tsx` — per-transaction + campaign UI
  - `site/src/components/sponsor/dashboard/UpchargeCatalogModal.tsx` — picker
  - `site/src/components/admin/UpchargeRequestQueue.tsx`
  - `site/src/components/admin/UnassignedTransactionQueue.tsx`
  - `site/src/app/(app)/admin/upcharge-requests/page.tsx`
  - `site/src/app/(app)/admin/transaction-assignments/page.tsx`
  - `site/src/lib/notifications/sendAssignmentNotice.ts` — email + dashboard inbox fan-out
  - `site/tests/e2e/upcharge-flow.spec.ts`
- **Modify:**
  - `site/src/db/schema.ts` — add `assignmentStatusEnum`, `blockUpcharges`, `upchargeStatusEnum`; add `sponsorPackageId`, `scheduledCleaningAt`, `assignmentStatus` columns to `transactions`
  - `site/src/app/(app)/sponsor/dashboard/page.tsx` — mount `ItemizedLedger` above block list; mount `UpchargePanel` per transaction
  - `site/src/app/api/sponsor/checkout/route.ts` (or the calculator checkout handler) — write `sponsorPackageId` on newly created transactions, initialize `assignmentStatus` based on whether the sponsor picked a date
  - `site/src/lib/stripe/createCharge.ts` (if exists, else add) — accept upcharge-specific metadata

### Tasks

#### Task A0: Assignment schema + backfill

**Context (resolved 2026-04-24):** Cleaning date lives on `transactions.preferredCleaningDate` (+ `preferredCleaningTimeStart`/`End` as HH:MM varchars). `sponsorPackages` has no FK to its transactions today. Upcharges attach to **`transactions.id`** — one transaction = one sponsor's booking of one block on one date = the atomic "cleaning event" upcharges modify. Sponsor-scheduled date is optional: unscheduled transactions wait in an admin queue until GCC assigns a block + date.

**Files:**
- Modify: `site/src/db/schema.ts`
- Create: `site/src/db/migrations/<timestamp>_transactions_assignment.sql`

- [ ] **Step 1: Add enum + columns to `transactions`**

```ts
// site/src/db/schema.ts — new enum
export const assignmentStatusEnum = pgEnum('enum_transactions_assignment_status', [
  'awaiting_assignment',  // sponsor didn't pick date; GCC hasn't assigned yet
  'scheduled',            // blockId + scheduledCleaningAt set
  'in_progress',          // cleaning underway
  'completed',            // cleaning done
  'cancelled',
])

// Inside transactions pgTable — append:
sponsorPackageId: integer('sponsor_package_id'),  // FK to sponsor_packages, nullable for legacy
scheduledCleaningAt: timestamp('scheduled_cleaning_at', { mode: 'string', withTimezone: true }),
assignmentStatus: assignmentStatusEnum('assignment_status').default('awaiting_assignment').notNull(),
```

- [ ] **Step 2: Generate Drizzle migration**

```bash
cd site && pnpm exec drizzle-kit generate --name transactions_assignment
```

- [ ] **Step 3: Extend the migration with backfill SQL**

Hand-edit the generated SQL and append:

```sql
-- Backfill scheduledCleaningAt from existing date + time strings
UPDATE transactions
SET scheduled_cleaning_at = (
  preferred_cleaning_date + COALESCE(preferred_cleaning_time_start, '00:00')::time
)
WHERE preferred_cleaning_date IS NOT NULL
  AND scheduled_cleaning_at IS NULL;

-- Flip legacy rows with a date to 'scheduled'
UPDATE transactions
SET assignment_status = 'scheduled'
WHERE scheduled_cleaning_at IS NOT NULL
  AND assignment_status = 'awaiting_assignment';
```

- [ ] **Step 4: Apply migration**

```bash
cd site && pnpm exec drizzle-kit migrate
```

Verify: `psql $DATABASE_URL -c '\d transactions' | grep -E "sponsor_package_id|scheduled_cleaning_at|assignment_status"` — three rows.

- [ ] **Step 5: Patch the checkout handler to write `sponsorPackageId` on new transactions**

Find the route that creates transactions on calculator-flow checkout (grep `insert(transactions)` under `src/app/api/sponsor/`). Ensure it passes the `sponsorPackageId` from the package being paid, and sets `assignmentStatus` to `'scheduled'` if the sponsor picked a date or `'awaiting_assignment'` otherwise.

- [ ] **Step 6: Commit**

```bash
git add site/src/db/schema.ts site/src/db/migrations/ site/src/app/api/sponsor/
git commit -m "feat(upcharges): transactions.assignment_status + sponsor_package_id + backfill"
```

#### Task A1: Upcharge catalog

**Files:**
- Create: `site/src/lib/upcharges/catalog.ts`

- [ ] **Step 1: Write the catalog module**

```ts
// site/src/lib/upcharges/catalog.ts
export type UpchargeCategory = 'service' | 'branding' | 'media' | 'event'

export interface UpchargeItem {
  slug: string
  name: string
  priceCents: number            // list price per block
  category: UpchargeCategory
  description: string
  /** True if this item qualifies for the campaign-wide discount when applied to every block in the package. */
  campaignDiscountable: boolean
  /** Minimum lead time before cleaning (hours). Default 12 — below this it becomes a request. */
  minLeadTimeHours?: number
}

export const UPCHARGE_CATALOG: readonly UpchargeItem[] = [
  {
    slug: 'extra-pass',
    name: 'Extra cleaning pass',
    priceCents: 5000,
    category: 'service',
    description: 'Second full pass of the block within the same day.',
    campaignDiscountable: true,
  },
  {
    slug: 'trash-bin-haul',
    name: 'Trash bin haul-out',
    priceCents: 2500,
    category: 'service',
    description: 'Crew takes bins to curb and returns them after pickup.',
    campaignDiscountable: true,
  },
  {
    slug: 'sidewalk-pressure-wash',
    name: 'Sidewalk pressure-wash',
    priceCents: 12000,
    category: 'service',
    description: 'Full sidewalk pressure-wash along the block — weather permitting.',
    campaignDiscountable: true,
  },
  {
    slug: 'leaf-bag-haul',
    name: 'Seasonal leaf/snow haul',
    priceCents: 4000,
    category: 'service',
    description: 'Bag and haul seasonal debris (leaves fall, snow piles winter).',
    campaignDiscountable: true,
  },
  {
    slug: 'worker-branding-vests',
    name: 'Co-branded worker vests',
    priceCents: 8000,
    category: 'branding',
    description: 'Vests with your logo for the cleaning crew on this block.',
    campaignDiscountable: true,
  },
  {
    slug: 'photo-package',
    name: 'Professional photo package',
    priceCents: 15000,
    category: 'media',
    description: 'Before/after pro photography delivered within 48 hours.',
    campaignDiscountable: true,
  },
  {
    slug: 'yard-sign',
    name: '"Cleaned by [sponsor]" yard sign',
    priceCents: 3500,
    category: 'branding',
    description: 'Branded yard sign staked in the parkway for 7 days post-cleaning.',
    campaignDiscountable: true,
  },
]

export function findUpcharge(slug: string): UpchargeItem | undefined {
  return UPCHARGE_CATALOG.find((i) => i.slug === slug)
}
```

- [ ] **Step 2: Commit**

```bash
git add site/src/lib/upcharges/catalog.ts
git commit -m "feat(upcharges): add typed upcharge catalog"
```

#### Task A2: Pricing logic with discount rule

**Files:**
- Create: `site/src/lib/upcharges/pricing.ts`
- Create: `site/src/lib/upcharges/pricing.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// site/src/lib/upcharges/pricing.test.ts
import { describe, it, expect } from 'vitest'
import { computeUpchargeBundlePrice } from './pricing'

describe('computeUpchargeBundlePrice', () => {
  it('applies campaign discount when all blocks share the same upcharge item', () => {
    const result = computeUpchargeBundlePrice(
      [{ slug: 'extra-pass', blockCount: 10 }],
      { scope: 'campaign', totalBlocks: 10 }
    )
    // 10 blocks × $50 = $500, 15% campaign discount = $425
    expect(result.totalCents).toBe(42500)
    expect(result.discountApplied).toBe(true)
  })

  it('charges full list price for per-block application', () => {
    const result = computeUpchargeBundlePrice(
      [{ slug: 'extra-pass', blockCount: 3 }],
      { scope: 'per-block', totalBlocks: 10 }
    )
    // 3 blocks × $50 = $150, no discount
    expect(result.totalCents).toBe(15000)
    expect(result.discountApplied).toBe(false)
  })

  it('refuses campaign scope if blockCount < totalBlocks', () => {
    expect(() =>
      computeUpchargeBundlePrice(
        [{ slug: 'extra-pass', blockCount: 5 }],
        { scope: 'campaign', totalBlocks: 10 }
      )
    ).toThrow(/campaign scope requires all blocks/)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm exec vitest run src/lib/upcharges/pricing.test.ts`
Expected: FAIL — `computeUpchargeBundlePrice` not defined

- [ ] **Step 3: Implement**

```ts
// site/src/lib/upcharges/pricing.ts
import { findUpcharge } from './catalog'

export interface BundleLine {
  slug: string
  blockCount: number
}

export type BundleScope =
  | { scope: 'campaign'; totalBlocks: number }
  | { scope: 'per-block'; totalBlocks: number }

export interface BundleResult {
  totalCents: number
  discountApplied: boolean
  lineItems: Array<{ slug: string; subtotalCents: number }>
}

const CAMPAIGN_DISCOUNT = 0.15

export function computeUpchargeBundlePrice(
  lines: BundleLine[],
  scope: BundleScope
): BundleResult {
  if (scope.scope === 'campaign') {
    for (const line of lines) {
      if (line.blockCount !== scope.totalBlocks) {
        throw new Error('campaign scope requires all blocks to share the upcharge')
      }
    }
  }

  const discountable = scope.scope === 'campaign'
  const lineItems = lines.map((line) => {
    const item = findUpcharge(line.slug)
    if (!item) throw new Error(`unknown upcharge: ${line.slug}`)
    const listCents = item.priceCents * line.blockCount
    const subtotal =
      discountable && item.campaignDiscountable
        ? Math.round(listCents * (1 - CAMPAIGN_DISCOUNT))
        : listCents
    return { slug: line.slug, subtotalCents: subtotal }
  })

  return {
    totalCents: lineItems.reduce((sum, li) => sum + li.subtotalCents, 0),
    discountApplied: discountable && lineItems.length > 0,
    lineItems,
  }
}
```

- [ ] **Step 4: Run test to verify all pass**

Run: `pnpm exec vitest run src/lib/upcharges/pricing.test.ts`
Expected: PASS 3/3

- [ ] **Step 5: Commit**

```bash
git add site/src/lib/upcharges/
git commit -m "feat(upcharges): pricing with campaign-scope discount"
```

#### Task A3: Deadline helper

**Files:**
- Create: `site/src/lib/upcharges/deadline.ts`
- Create: `site/src/lib/upcharges/deadline.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// site/src/lib/upcharges/deadline.test.ts
import { describe, it, expect } from 'vitest'
import { isBeforeCutoff, hoursUntilCutoff } from './deadline'

describe('deadline helpers', () => {
  const cleaning = new Date('2026-05-01T14:00:00Z')

  it('returns true when now is more than 12h before cleaning', () => {
    const now = new Date('2026-05-01T01:00:00Z') // 13h before
    expect(isBeforeCutoff(cleaning, now, 12)).toBe(true)
  })

  it('returns false when now is less than 12h before cleaning', () => {
    const now = new Date('2026-05-01T03:00:00Z') // 11h before
    expect(isBeforeCutoff(cleaning, now, 12)).toBe(false)
  })

  it('returns false after the cleaning has started', () => {
    const now = new Date('2026-05-01T15:00:00Z')
    expect(isBeforeCutoff(cleaning, now, 12)).toBe(false)
  })

  it('hoursUntilCutoff returns negative when past cutoff', () => {
    const now = new Date('2026-05-01T03:00:00Z')
    expect(hoursUntilCutoff(cleaning, now, 12)).toBeCloseTo(-1)
  })
})
```

- [ ] **Step 2: Verify fails**

Run: `pnpm exec vitest run src/lib/upcharges/deadline.test.ts`
Expected: FAIL

- [ ] **Step 3: Implement**

```ts
// site/src/lib/upcharges/deadline.ts
export function hoursUntilCutoff(
  cleaningStartAt: Date,
  now: Date = new Date(),
  cutoffHours: number = 12
): number {
  const cutoff = cleaningStartAt.getTime() - cutoffHours * 3600 * 1000
  return (cutoff - now.getTime()) / (3600 * 1000)
}

export function isBeforeCutoff(
  cleaningStartAt: Date,
  now: Date = new Date(),
  cutoffHours: number = 12
): boolean {
  return hoursUntilCutoff(cleaningStartAt, now, cutoffHours) > 0
}
```

- [ ] **Step 4: Run tests, commit**

```bash
pnpm exec vitest run src/lib/upcharges/deadline.test.ts
git add site/src/lib/upcharges/deadline.ts site/src/lib/upcharges/deadline.test.ts
git commit -m "feat(upcharges): T-12h cutoff helper"
```

#### Task A4: Schema migration

**Files:**
- Modify: `site/src/db/schema.ts`
- Create: `site/src/db/migrations/<timestamp>_block_upcharges.sql` (generated)

- [ ] **Step 1: Add new tables to schema.ts**

```ts
// Append to site/src/db/schema.ts

export const upchargeStatusEnum = pgEnum('enum_block_upcharges_status', [
  'pending_charge',   // auto path, Stripe charge in flight
  'confirmed',        // charged successfully
  'awaiting_gcc',     // <12h request, GCC must approve
  'approved_charging',// GCC approved, Stripe charge in flight
  'denied',
  'cancelled',
  'refunded',
])

export const blockUpcharges = pgTable('block_upcharges', {
  id: serial('id').primaryKey(),

  // Attachment
  sponsorPackageId: integer('sponsor_package_id').notNull(),
  blockId: integer('block_id'),          // null if scope=campaign (applies to all blocks in package)
  scope: varchar('scope', { length: 16 }).notNull(), // 'campaign' | 'per-block'

  // Catalog
  catalogSlug: varchar('catalog_slug', { length: 64 }).notNull(),
  priceCentsAtBooking: numeric('price_cents_at_booking').notNull(), // snapshot of list price

  // Pricing snapshot for auditability
  chargedCents: numeric('charged_cents'), // actual cents charged after discounts
  discountAppliedPct: numeric('discount_applied_pct').default('0'),

  // Lifecycle
  status: upchargeStatusEnum('status').notNull().default('pending_charge'),
  requestedAt: timestamp('requested_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  chargedAt: timestamp('charged_at', { mode: 'string', withTimezone: true }),
  reviewedAt: timestamp('reviewed_at', { mode: 'string', withTimezone: true }),
  reviewedById: integer('reviewed_by_id'), // admin who approved/denied
  reviewNotes: text('review_notes'),
  stripeChargeId: varchar('stripe_charge_id'),

  createdAt: timestamp('created_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
  updatedAt: timestamp('updated_at', { mode: 'string', withTimezone: true }).defaultNow().notNull(),
})
```

- [ ] **Step 2: Generate migration**

Run: `cd site && pnpm exec drizzle-kit generate --name block_upcharges`
Expected: new SQL file in `src/db/migrations/`

- [ ] **Step 3: Apply migration locally**

Run: `cd site && pnpm exec drizzle-kit migrate`
Expected: "migrations applied" — verify via psql `\d block_upcharges`

- [ ] **Step 4: Commit**

```bash
git add site/src/db/schema.ts site/src/db/migrations/
git commit -m "feat(upcharges): add block_upcharges table + status enum"
```

#### Task A5: API — direct path (T-12h+)

**Files:**
- Create: `site/src/app/api/sponsor/blocks/[blockId]/upcharges/route.ts`

- [ ] **Step 1: Implement POST (add) and DELETE (remove)**

```ts
// site/src/app/api/sponsor/blocks/[blockId]/upcharges/route.ts
import { NextResponse } from 'next/server'
import { db } from '@/db'
import { blocks, blockUpcharges, sponsorPackages } from '@/db/schema'
import { eq, and } from 'drizzle-orm'
import { requireSponsorSession } from '@/lib/auth/session'
import { findUpcharge } from '@/lib/upcharges/catalog'
import { isBeforeCutoff } from '@/lib/upcharges/deadline'
import { computeUpchargeBundlePrice } from '@/lib/upcharges/pricing'
import { chargeUpcharge, refundUpcharge } from '@/lib/stripe/upcharges'

export async function POST(req: Request, { params }: { params: Promise<{ blockId: string }> }) {
  const session = await requireSponsorSession()
  const { blockId: blockIdParam } = await params
  const blockId = Number(blockIdParam)
  const body = await req.json()
  const { catalogSlug } = body
  const item = findUpcharge(catalogSlug)
  if (!item) return NextResponse.json({ error: 'unknown upcharge' }, { status: 400 })

  const [block] = await db.select().from(blocks).where(eq(blocks.id, blockId))
  if (!block || !block.scheduledCleaningAt) {
    return NextResponse.json({ error: 'block not found or unscheduled' }, { status: 404 })
  }
  if (!isBeforeCutoff(new Date(block.scheduledCleaningAt))) {
    return NextResponse.json({
      error: 'inside T-12h cutoff — submit as request instead',
      redirectTo: `/api/sponsor/blocks/${blockId}/upcharge-requests`,
    }, { status: 409 })
  }

  // Verify the block belongs to an active sponsor_package owned by this session
  const [pkg] = await db.select().from(sponsorPackages)
    .where(and(eq(sponsorPackages.sponsorId, session.sponsorId), /* package-block link condition */))
  if (!pkg) return NextResponse.json({ error: 'not authorized' }, { status: 403 })

  // Price (per-block scope always — campaign uses the other endpoint)
  const price = computeUpchargeBundlePrice(
    [{ slug: catalogSlug, blockCount: 1 }],
    { scope: 'per-block', totalBlocks: pkg.blockCount }
  )

  // Insert as pending_charge
  const [row] = await db.insert(blockUpcharges).values({
    sponsorPackageId: pkg.id,
    blockId,
    scope: 'per-block',
    catalogSlug,
    priceCentsAtBooking: String(item.priceCents),
    chargedCents: String(price.totalCents),
    status: 'pending_charge',
  }).returning()

  // Stripe charge (uses sponsor's saved payment method)
  try {
    const charge = await chargeUpcharge({ sponsorId: session.sponsorId, amountCents: price.totalCents, upchargeId: row.id })
    await db.update(blockUpcharges).set({
      status: 'confirmed',
      chargedAt: new Date().toISOString(),
      stripeChargeId: charge.id,
    }).where(eq(blockUpcharges.id, row.id))
  } catch (err) {
    await db.update(blockUpcharges).set({ status: 'denied', reviewNotes: 'stripe charge failed: ' + (err as Error).message })
      .where(eq(blockUpcharges.id, row.id))
    return NextResponse.json({ error: 'payment failed' }, { status: 402 })
  }

  return NextResponse.json({ id: row.id, status: 'confirmed' })
}

export async function DELETE(req: Request, { params }: { params: Promise<{ blockId: string }> }) {
  const session = await requireSponsorSession()
  const { blockId: blockIdParam } = await params
  const blockId = Number(blockIdParam)
  const { searchParams } = new URL(req.url)
  const upchargeId = Number(searchParams.get('upchargeId'))

  const [existing] = await db.select().from(blockUpcharges).where(eq(blockUpcharges.id, upchargeId))
  if (!existing || existing.blockId !== blockId) {
    return NextResponse.json({ error: 'not found' }, { status: 404 })
  }

  const [block] = await db.select().from(blocks).where(eq(blocks.id, blockId))
  if (!isBeforeCutoff(new Date(block.scheduledCleaningAt))) {
    return NextResponse.json({ error: 'cannot remove inside T-12h' }, { status: 409 })
  }

  if (existing.status === 'confirmed' && existing.stripeChargeId) {
    await refundUpcharge({ chargeId: existing.stripeChargeId, amountCents: Number(existing.chargedCents) })
  }
  await db.update(blockUpcharges).set({ status: 'refunded' }).where(eq(blockUpcharges.id, upchargeId))
  return NextResponse.json({ status: 'refunded' })
}
```

- [ ] **Step 2: Write Stripe helper**

Create `site/src/lib/stripe/upcharges.ts` — wraps `stripe.paymentIntents.create` with the sponsor's default payment method and metadata `{ upchargeId, blockId, sponsorId }`.

- [ ] **Step 3: Commit**

```bash
git add site/src/app/api/sponsor/blocks site/src/lib/stripe/upcharges.ts
git commit -m "feat(upcharges): direct API for T-12h+ add/remove with Stripe"
```

#### Task A6: API — request path (T-12h-)

**Files:**
- Create: `site/src/app/api/sponsor/blocks/[blockId]/upcharge-requests/route.ts`

- [ ] **Step 1: Implement POST (submit request) that stores with `status = 'awaiting_gcc'` and emails GCC admin**

```ts
// site/src/app/api/sponsor/blocks/[blockId]/upcharge-requests/route.ts
// Same structure as Task A5 POST, but:
//   - Does NOT call Stripe.
//   - status='awaiting_gcc'
//   - Sends an email to GCC admin via existing mail helper with a direct link
//     to /admin/upcharge-requests/<id>
//   - Also writes a sponsor-facing notification entry if an inbox exists.
```

- [ ] **Step 2: Verify email fires (integration test with local SMTP sink or mock)**

- [ ] **Step 3: Commit**

```bash
git add site/src/app/api/sponsor/blocks/[blockId]/upcharge-requests/
git commit -m "feat(upcharges): T-12h- request path (awaiting_gcc)"
```

#### Task A7: API — campaign scope (all blocks)

**Files:**
- Create: `site/src/app/api/sponsor/packages/[packageId]/upcharges/route.ts`

- [ ] **Step 1: Implement POST that inserts N rows (one per block) with `scope='campaign'`, charges discounted total as one Stripe PaymentIntent.**

- [ ] **Step 2: Verify discount applied by reading back chargedCents / sum(priceCentsAtBooking) ≈ 0.85**

- [ ] **Step 3: Commit**

```bash
git add site/src/app/api/sponsor/packages
git commit -m "feat(upcharges): campaign-scope API with 15% discount"
```

#### Task A8: Sponsor dashboard — UpchargePanel

**Files:**
- Create: `site/src/components/sponsor/dashboard/UpchargePanel.tsx`
- Create: `site/src/components/sponsor/dashboard/UpchargeCatalogModal.tsx`
- Modify: `site/src/app/(app)/sponsor/dashboard/page.tsx`

- [ ] **Step 1: UpchargePanel props**

```tsx
interface UpchargePanelProps {
  blockId: number
  packageId: number
  cleaningStartAt: string  // ISO
  totalBlocksInPackage: number
  existingUpcharges: BlockUpchargeRow[]  // from /api/sponsor/blocks/[id]/upcharges GET
}
```

- [ ] **Step 2: Implement UI states**

   - **Before T-12h:** "Add-on" button opens modal, list of add-ons with prices. Clicking Confirm hits POST direct API. Existing add-ons show with Remove button.
   - **T-12h- to T-0:** Same modal but labeled "Request add-on" — copy "Submit request — GCC will message back within 2 hours." Button hits request API. Existing awaiting rows show with "Cancel request" option.
   - **After T-0:** Panel collapses to a read-only summary of what was cleaned.

- [ ] **Step 3: Live countdown to cutoff** — small "Changes close in 3h 42m" label, computed from `cleaningStartAt - 12h`. Uses `setInterval` every 30s.

- [ ] **Step 4: Mount in dashboard per block row**

- [ ] **Step 5: Commit**

```bash
git add site/src/components/sponsor/dashboard/Upcharge*.tsx site/src/app/\(app\)/sponsor/dashboard/page.tsx
git commit -m "feat(sponsor-dashboard): UpchargePanel with T-12h state split"
```

#### Task A9: Admin review queue

**Files:**
- Create: `site/src/app/(app)/admin/upcharge-requests/page.tsx`
- Create: `site/src/app/api/admin/upcharge-requests/route.ts`
- Create: `site/src/components/admin/UpchargeRequestQueue.tsx`

- [ ] **Step 1:** GET `/api/admin/upcharge-requests` returns all `awaiting_gcc` rows with sponsor + block context.

- [ ] **Step 2:** POST `/api/admin/upcharge-requests/[id]` with `{action: 'approve' | 'deny', notes?}`. On approve, charge Stripe (same helper as A5) then set `status='approved_charging' → 'confirmed'`. On deny, set `status='denied'` + email sponsor.

- [ ] **Step 3:** Admin page: simple list with sponsor name, block code, catalog item, amount, requested-at, Approve / Deny buttons. Auth guard: `requireAdminSession()`.

- [ ] **Step 4:** Commit

```bash
git add site/src/app/\(app\)/admin/upcharge-requests site/src/app/api/admin site/src/components/admin
git commit -m "feat(admin): upcharge request review queue"
```

#### Task A10: E2E

**Files:**
- Create: `site/tests/e2e/upcharge-flow.spec.ts`

- [ ] **Step 1:** Test that books a package → adds a per-block upcharge > T-12h → asserts Stripe charge + status=confirmed.

- [ ] **Step 2:** Test that sets cleaning date to now+6h → upcharge submission becomes `awaiting_gcc` → admin approves → status=confirmed.

- [ ] **Step 3:** Test campaign-wide upcharge → chargedCents reflects 15% discount.

- [ ] **Step 4:** Commit

```bash
git add site/tests/e2e/upcharge-flow.spec.ts
git commit -m "test(upcharges): E2E coverage for all three paths"
```

---

## Phase B: iMessage-grade rich preview

**Goal:** When someone texts `globalcommunitycleaners.org` or the iOS App Store deep-link, iMessage's link preview should be a polished, branded image (not the current generic stat card).

### File structure

- **Modify:**
  - `site/src/app/opengraph-image.tsx` — root, iMessage-friendly layout
  - `site/src/app/twitter-image.tsx` — mirror
  - `site/src/app/layout.tsx` — verify `metadata.openGraph` + `metadata.twitter` reference the image, add `appleItunesApp: 'app-id=...'` for iOS Smart Banner
- **Create:**
  - `site/public/.well-known/apple-app-site-association` (no extension, served with `application/json`) — universal link manifest so `https://globalcommunitycleaners.org/app/...` opens the native app if installed
  - `site/src/app/app/page.tsx` — a public-facing "Get the app" route with clean metadata, used as the text-friendly URL
  - `site/src/app/app/opengraph-image.tsx` — high-fidelity iPhone-mockup OG image
  - `site/public/images/imessage-preview/*` — hero art assets generated via nanobanana (iPhone with the GCC app heatmap on-screen)

### Tasks

#### Task B1: Generate the hero visual

- [ ] **Step 1:** Use nanobanana MCP (`gemini-3-pro-image-preview`) to produce a 1200×630 editorial photo of a hand holding an iPhone displaying the GCC app's heatmap view. Prompt should specify: kelly-green brand overlay, "Global Community Cleaners" in-app title visible, Chicago residential block behind phone, warm documentary lighting.

- [ ] **Step 2:** Save as `site/public/images/imessage-preview/hero.jpg`. Also generate a 1024×1024 square variant for Twitter/WhatsApp fallback.

- [ ] **Step 3:** Commit

```bash
git add site/public/images/imessage-preview/
git commit -m "feat(preview): iMessage hero visual"
```

#### Task B2: Rewrite root OG image

**Files:**
- Modify: `site/src/app/opengraph-image.tsx`

- [ ] **Step 1:** Replace the stat-card gradient with a layout that foregrounds the app — iPhone hero on the left 60%, right 40% is the brand panel (logo + "Clean your block. Pay your neighbors." + download CTA row with the two store badges rendered as SVG). Reuse brand tokens.

- [ ] **Step 2:** Use `next/og`'s `ImageResponse` with `fetch(new URL('./hero.jpg', import.meta.url))` for the iPhone image. Include official App Store + Google Play badges fetched from assets.

- [ ] **Step 3:** Verify render: `curl -I http://localhost:7350/opengraph-image` returns 200 + `image/png`. Open `http://localhost:7350/opengraph-image` in browser.

- [ ] **Step 4:** Commit

```bash
git add site/src/app/opengraph-image.tsx
git commit -m "feat(preview): iMessage-optimized root OG image"
```

#### Task B3: `/app` landing route + universal link manifest

**Files:**
- Create: `site/src/app/app/page.tsx` (simple branded landing with App Store + Play Store buttons)
- Create: `site/src/app/app/opengraph-image.tsx`
- Create: `site/public/.well-known/apple-app-site-association`
- Modify: `site/next.config.ts` — rewrite `/.well-known/apple-app-site-association` to serve with `application/json`

- [ ] **Step 1:** Content for `apple-app-site-association` (confirm team ID + bundle ID from `iosApp/iosApp/Info.plist`):

```json
{
  "applinks": {
    "apps": [],
    "details": [
      {
        "appID": "<TEAM_ID>.<BUNDLE_ID>",
        "paths": ["/app/*", "/block/*", "/sponsor/*"]
      }
    ]
  }
}
```

- [ ] **Step 2:** Add `appleItunesApp` to metadata in `src/app/layout.tsx`:

```ts
export const metadata: Metadata = {
  // ...existing
  appleItunesApp: { appId: '<APP_STORE_ID>' },
}
```

- [ ] **Step 3:** Mobile-test using `https://developer.apple.com/ios/universal-links/` validator + real iPhone.

- [ ] **Step 4:** Commit

```bash
git add site/src/app/app site/public/.well-known site/next.config.ts site/src/app/layout.tsx
git commit -m "feat(preview): /app universal-link landing + AASA manifest"
```

---

## Phase C: Brand kit unification

**Goal:** Single source of truth for brand tokens → Tailwind, iOS asset catalog, Compose theme all read from the same JSON.

### File structure

- **Create:**
  - `packages/brand-tokens/package.json` (workspace pkg) OR top-level `brand/tokens.json`
  - `brand/tokens.json` — canonical tokens (colors, typography, radii, spacing, logo paths)
  - `brand/README.md`
  - `scripts/gen-brand-web.ts` — produces `site/src/styles/brand-tokens.css` + extends Tailwind config
  - `scripts/gen-brand-ios.ts` — writes `iosApp/iosApp/Assets.xcassets/Colors.xcassets/*.colorset/Contents.json` files
  - `scripts/gen-brand-compose.kts` — writes `composeApp/src/commonMain/kotlin/com/gcc/ui/BrandTokens.kt`
- **Modify:**
  - `site/tailwind.config.ts` — import from `brand/tokens.json`
  - Any component that hard-codes `#16763A` etc — now references the token
  - `iosApp/iosApp/Info.plist` — branded launch-screen color reference
  - `composeApp/src/commonMain/kotlin/.../App.kt` — use MaterialTheme extended with BrandTokens

### Tasks

#### Task C1: Capture canonical brand tokens

- [ ] **Step 1:** Transcribe the brand kit from the Claude Design Tool output into `brand/tokens.json`. Expected structure:

```json
{
  "colors": {
    "green": { "50": "#E8F7EC", "500": "#16763A", "700": "#0E5226", "900": "#0A3A1C" },
    "blue":  { "500": "#30A0CE", "700": "#155B92" },
    "neutral": { "50": "#FAFAFA", "900": "#0F0F0F" }
  },
  "typography": {
    "heading": { "family": "Inter", "weights": [600, 700, 800] },
    "body":    { "family": "Inter", "weights": [400, 500] }
  },
  "radii": { "sm": 6, "md": 10, "lg": 16, "xl": 24, "2xl": 32 },
  "logo": { "full": "/images/gcc-logo.png", "symbol": "/images/gcc-symbol.png" }
}
```

- [ ] **Step 2:** Document what each color is used for in `brand/README.md`.

- [ ] **Step 3:** Commit

```bash
git add brand/
git commit -m "feat(brand): canonical tokens.json"
```

#### Task C2: Web generator + Tailwind integration

**Files:**
- Create: `scripts/gen-brand-web.ts`
- Modify: `site/tailwind.config.ts`
- Create: `site/src/styles/brand-tokens.css` (generated, gitignored)

- [ ] **Step 1:** Script reads `brand/tokens.json` and emits:
  - `:root { --gcc-green-500: #16763A; ... }` in CSS file
  - `module.exports.brandTokens = { ... }` that Tailwind consumes

- [ ] **Step 2:** Update `tailwind.config.ts` to spread the generated colors into `theme.extend.colors`.

- [ ] **Step 3:** Run on `postinstall`: add to `site/package.json` scripts.

- [ ] **Step 4:** Audit web source for hard-coded hex colors — grep `#[0-9A-F]{6}` and replace with `brand-green-*` utilities where appropriate.

- [ ] **Step 5:** Commit

```bash
git add scripts/gen-brand-web.ts site/tailwind.config.ts site/src/styles/brand-tokens.css site/package.json
git commit -m "feat(brand): web generator + tailwind integration"
```

#### Task C3: iOS generator (Compose Multiplatform app)

Since the iOS app is Compose Multiplatform (`ContentView.swift` → `MainViewControllerKt.MainViewController()`), iOS themeing lives in Compose — not in the SwiftUI host. So iOS tokens flow through Phase C4 (Compose). This task only needs to produce the **native** iOS asset catalog for the SwiftUI splash/launch screen + icon tinting:

**Files:**
- Create: `scripts/gen-brand-ios.ts`
- Modify: `iosApp/iosApp/Assets.xcassets/*`

- [ ] **Step 1:** Script writes:
  - `iosApp/iosApp/Assets.xcassets/AccentColor.colorset/Contents.json` with brand green
  - `iosApp/iosApp/Assets.xcassets/BrandPrimary.colorset/Contents.json`
  - `iosApp/iosApp/Assets.xcassets/BrandSecondary.colorset/Contents.json`

- [ ] **Step 2:** Verify in Xcode: open `iosApp.xcodeproj`, confirm colors appear in asset catalog.

- [ ] **Step 3:** Commit

```bash
git add scripts/gen-brand-ios.ts iosApp/iosApp/Assets.xcassets
git commit -m "feat(brand): iOS asset catalog generator"
```

#### Task C4: Compose theme generator (drives the iOS + Android UI)

**Files:**
- Create: `scripts/gen-brand-compose.kts`
- Create: `composeApp/src/commonMain/kotlin/com/gcc/ui/BrandTokens.kt` (generated)
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/App.kt` (or wherever `MaterialTheme` is applied)

- [ ] **Step 1:** Script writes `BrandTokens.kt`:

```kotlin
package com.gcc.ui

import androidx.compose.ui.graphics.Color

object BrandTokens {
    val Green50  = Color(0xFFE8F7EC)
    val Green500 = Color(0xFF16763A)
    val Green700 = Color(0xFF0E5226)
    val Blue500  = Color(0xFF30A0CE)
    // ... etc
}
```

- [ ] **Step 2:** Wrap top-level app in a branded `MaterialTheme` that maps Material color roles to BrandTokens.

- [ ] **Step 3:** Regenerate + verify by running the iOS app (`./gradlew :composeApp:iosArm64:linkDebugFrameworkIosArm64 && cd iosApp && xcodebuild …`).

- [ ] **Step 4:** Commit

```bash
git add scripts/gen-brand-compose.kts composeApp/src/commonMain/kotlin/com/gcc/ui/ composeApp/src/commonMain/kotlin/com/gcc/App.kt
git commit -m "feat(brand): Compose MaterialTheme driven by shared tokens"
```

---

## Phase D: Dynamic iOS heatmap

**Goal:** The Compose-Multiplatform iOS app surfaces a richer heatmap showing every affected block (funded, scheduled, completed, pending-issue) with visible intensity, tap-to-inspect, and live updates.

### File structure

- **Create:**
  - `shared/src/commonMain/kotlin/com/gcc/shared/maps/AffectedArea.kt` — model
  - `shared/src/commonMain/kotlin/com/gcc/shared/maps/AffectedAreasRepository.kt` — data access
  - `shared/src/commonMain/kotlin/com/gcc/shared/maps/HeatmapRenderer.kt` — platform-agnostic intensity math
  - `composeApp/src/commonMain/kotlin/com/gcc/ui/map/GccMapView.kt` — MapLibre Compose wrapper with heatmap layer
  - `composeApp/src/commonMain/kotlin/com/gcc/ui/map/AffectedAreaDetailSheet.kt`
  - `site/src/app/api/affected-areas/route.ts` — GeoJSON endpoint consumed by the app
- **Modify:**
  - `composeApp/src/commonMain/kotlin/com/gcc/App.kt` — wire map screen
  - `composeApp/build.gradle.kts` — add MapLibre Native Compose dep

### Tasks

#### Task D1: API — affected-areas GeoJSON

**Files:**
- Create: `site/src/app/api/affected-areas/route.ts`

- [ ] **Step 1:** Returns a FeatureCollection with one Feature per block in any non-trivial status. Properties:

```json
{
  "blockId": 42,
  "status": "scheduled",        // available | scheduled | cleaning_in_progress | completed | issue
  "intensity": 0.82,            // 0..1 — engagement/funding intensity
  "scheduledCleaningAt": "2026-05-01T14:00:00Z",
  "sponsorCount": 3,
  "lastCleanedAt": "2026-04-18T13:20:00Z"
}
```

- [ ] **Step 2:** Query joins `blocks` + `sponsor_packages` + `cleanings` (or whatever A0 resolved). Cache with 60s revalidate.

- [ ] **Step 3:** Unit test returns stable GeoJSON for seeded DB.

- [ ] **Step 4:** Commit

```bash
git add site/src/app/api/affected-areas/
git commit -m "feat(api): affected-areas GeoJSON endpoint"
```

#### Task D2: Shared model + repo

**Files:**
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/maps/AffectedArea.kt`
- Create: `shared/src/commonMain/kotlin/com/gcc/shared/maps/AffectedAreasRepository.kt`
- Create: `shared/src/commonTest/kotlin/com/gcc/shared/maps/AffectedAreasRepositoryTest.kt`

- [ ] **Step 1:** Model (`@Serializable`) — mirror the API JSON shape + `polygon: List<LatLng>` decoded from GeoJSON.

- [ ] **Step 2:** Repository uses Ktor client to fetch + Kotlinx-Serialization to decode. Returns `Flow<List<AffectedArea>>` with optional polling.

- [ ] **Step 3:** Test: hit mock HTTP client, expect list of 3 areas.

- [ ] **Step 4:** Commit

```bash
git add shared/src/commonMain/kotlin/com/gcc/shared/maps shared/src/commonTest/kotlin/com/gcc/shared/maps
git commit -m "feat(shared): AffectedArea model + repository"
```

#### Task D3: Compose map view with heatmap layer

**Files:**
- Modify: `composeApp/build.gradle.kts` — add `implementation("org.maplibre.gl:android-sdk:11.x")` + iOS MapLibre dep via Cocoapods
- Create: `composeApp/src/commonMain/kotlin/com/gcc/ui/map/GccMapView.kt`

- [ ] **Step 1:** Use MapLibre-Compose (or platform-specific `expect`/`actual` with AndroidView + UIViewController). Source = GeoJSON from the repo. Two visual layers stacked:
  - **Heatmap** at zoom 10-14 (`type = heatmap`), intensity weighted by `intensity` property, color ramp using `BrandTokens.Green50 → Green500 → amber accent`.
  - **Polygon fills** at zoom 14+, fill color keyed by `status`, opacity keyed by `intensity`.

- [ ] **Step 2:** Tap handler — at zoom 14+, tapping a polygon opens `AffectedAreaDetailSheet` with block code, status, sponsor count, scheduled cleaning, CTA "Sponsor this block".

- [ ] **Step 3:** Live updates — the repo flow polls every 30s; map source data refreshes without redrawing the base map.

- [ ] **Step 4:** Commit

```bash
git add composeApp/build.gradle.kts composeApp/src/commonMain/kotlin/com/gcc/ui/map/
git commit -m "feat(ios-map): MapLibre Compose heatmap + polygon overlays"
```

#### Task D4: Wire into app

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/gcc/App.kt`

- [ ] **Step 1:** Replace (or augment) the current map screen with `GccMapView`. Default camera centered on North Lawndale, zoom 13.

- [ ] **Step 2:** Verify on simulator: `./gradlew :composeApp:iosSimulatorArm64:runDebugIosSimulatorArm64` (or xcodebuild with simulator destination).

- [ ] **Step 3:** Commit

```bash
git add composeApp/src/commonMain/kotlin/com/gcc/App.kt
git commit -m "feat(ios-map): wire dynamic heatmap into main app"
```

#### Task D5: Performance + offline

- [ ] **Step 1:** Render budget: ensure >30fps scroll with 500+ polygons by using vector tiles or line-simplified polygons.

- [ ] **Step 2:** Offline cache last GeoJSON payload to disk (`shared`). Show cached on cold launch before network refresh.

- [ ] **Step 3:** Commit

```bash
git commit -m "perf(ios-map): polygon simplification + offline cache"
```

---

## Ship order

1. **Phase A** (upcharges) — revenue-impacting, independent, does not block anything else. **Start here.**
2. **Phase B** (preview) — low-risk, 2-3 day project, run concurrently with A.
3. **Phase C** (brand) — unblocks consistent visuals on D. Ship before D.
4. **Phase D** (iOS heatmap) — runs on the brand tokens from C.

## Self-review checklist

- [ ] A0 resolved the cleaning-date source before any A task hard-codes a column name.
- [ ] Every `chargeUpcharge` call-site has a matching refund path.
- [ ] AASA file served with `application/json` content-type (Next.js rewrite).
- [ ] Brand token changes regenerated on every merge (postinstall hook).
- [ ] Compose MapLibre dependency added for both Android + iOS targets in Gradle.

---

**Plan saved to:** `docs/superpowers/plans/2026-04-24-platform-expansion-upcharges-brand-ios.md`
