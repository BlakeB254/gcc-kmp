/**
 * Idempotent seed for in-kind donation acknowledgment records.
 *
 *   npm run seed:in-kind
 *
 * Inserts (or updates) the template record and any seed gifts defined in
 * src/lib/in-kind-donations.ts keyed by `slug`. Safe to re-run; existing
 * rows are updated in place and their items are replaced.
 */

import { eq } from 'drizzle-orm'
import { db } from '../db'
import { inKindDonations, inKindDonationItems } from '../db/schema'
import { IN_KIND_SEED_RECORDS } from '../lib/in-kind-donations'

async function upsertRecord(record: (typeof IN_KIND_SEED_RECORDS)[number]) {
  const { donation, items } = record

  const existing = await db
    .select({ id: inKindDonations.id })
    .from(inKindDonations)
    .where(eq(inKindDonations.slug, donation.slug))
    .limit(1)

  let donationId: number

  if (existing.length > 0) {
    donationId = existing[0].id
    await db
      .update(inKindDonations)
      .set({ ...donation, updatedAt: new Date().toISOString() })
      .where(eq(inKindDonations.id, donationId))
    await db.delete(inKindDonationItems).where(eq(inKindDonationItems.donationId, donationId))
    console.log(`  ↻ Updated  ${donation.slug} (#${donationId})`)
  } else {
    const [inserted] = await db.insert(inKindDonations).values(donation).returning({ id: inKindDonations.id })
    donationId = inserted.id
    console.log(`  ✓ Inserted ${donation.slug} (#${donationId})`)
  }

  if (items.length > 0) {
    await db
      .insert(inKindDonationItems)
      .values(items.map((it, idx) => ({ ...it, donationId, sortOrder: it.sortOrder ?? idx })))
  }
}

async function main() {
  console.log('Seeding in-kind donations…')
  for (const record of IN_KIND_SEED_RECORDS) {
    await upsertRecord(record)
  }
  console.log(`Done. ${IN_KIND_SEED_RECORDS.length} record(s) seeded.`)
}

main()
  .catch((err) => {
    console.error('Seed failed:', err)
    process.exit(1)
  })
  .then(() => process.exit(0))
