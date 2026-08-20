import { db } from '@/db'
import { blocks, routes } from '@/db/schema'
import { and, eq, inArray } from 'drizzle-orm'
import type { EligibleBlockStatus } from '@/lib/admin-route-status'

/**
 * Flip a route's linked blocks to the matrix-eligible status. Only blocks
 * still in 'available'/'funded' are touched — 'in_progress' and 'completed'
 * blocks belong to a worker's active/finished job and are never reverted.
 *
 * Returns the update statement so callers can compose it into a db.batch
 * (single transaction on the neon-http driver) with the route update.
 */
export function linkedBlockStatusUpdate(routeId: number, status: EligibleBlockStatus, now: string) {
  return db
    .update(blocks)
    .set({ status, updatedAt: now })
    .where(and(eq(blocks.routeId, routeId), inArray(blocks.status, ['available', 'funded'])))
}

/**
 * Apply a route transition (status / claim_status / schedule timestamps) and
 * sync linked blocks in ONE transaction (db.batch is transactional on the
 * neon-http driver). Returns the updated route row, or undefined when the
 * route does not exist.
 */
export async function applyRouteTransition(
  routeId: number,
  updateData: Record<string, unknown>,
  blockStatus: EligibleBlockStatus,
  now: string,
): Promise<typeof routes.$inferSelect | undefined> {
  const [updatedRoutes] = await db.batch([
    db.update(routes).set(updateData).where(eq(routes.id, routeId)).returning(),
    linkedBlockStatusUpdate(routeId, blockStatus, now),
  ])
  return updatedRoutes[0]
}

/** Parse an ISO-8601 timestamp body field; null when absent, throws when invalid. */
export function parseOptionalTimestamp(value: unknown, field: string): string | null {
  if (value === undefined || value === null) return null
  if (typeof value !== 'string' || Number.isNaN(new Date(value).getTime())) {
    throw new Error(`${field} must be an ISO-8601 timestamp`)
  }
  return new Date(value).toISOString()
}
