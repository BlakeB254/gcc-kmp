/**
 * POST /api/admin/routes/:routeId/archive
 * Archive a route: status -> 'archived', claim_status -> 'closed'; linked
 * blocks revert to 'funded' via the state matrix.
 *
 * Refuses with 409 while the route has active jobs. Send
 * `{ "releaseActiveJobs": true }` to release claimed/in-progress jobs and
 * archive in one transaction. See lib/admin-route-close.ts.
 */
import { closeRouteHandler } from '@/lib/admin-route-close'

export const dynamic = 'force-dynamic'

export const POST = closeRouteHandler({ verb: 'archive', status: 'archived' })
