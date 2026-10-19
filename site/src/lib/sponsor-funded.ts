/**
 * Which transaction rows count as a SPONSORED (funded) block.
 *
 * A block payment that landed after its block was claimed, in progress or
 * cleaned is recorded as a reservation for the block's next cycle
 * (reserved_block_count set — lib/stripe/sponsor-payment-events.ts). It keeps
 * its block_id but funded nothing, so every public count of sponsored blocks
 * (impact/sponsor-wall totals, a sponsor's public profile, the map's
 * per-block sponsor count) must use this predicate, never `block_id IS NOT NULL`.
 *
 * No `@/db` import, so tests and scripts can use it without a DATABASE_URL.
 */
import { sql, type SQL } from 'drizzle-orm'
import { transactions } from '@/db/schema'

/** WHERE condition: this row funded a block. */
export function isFundedBlockRow(): SQL {
  return sql`(${transactions.blockId} IS NOT NULL AND ${transactions.reservedBlockCount} IS NULL)`
}

/** For count(): 1 when this row funded a block, else NULL. */
export function fundedBlockRow(): SQL {
  return sql`CASE WHEN ${isFundedBlockRow()} THEN 1 END`
}
