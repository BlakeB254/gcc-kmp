/**
 * POST /api/admin/routes/:routeId/unpublish
 * Pull a route back to draft: status -> 'draft', claim_status -> 'closed',
 * publishedAt cleared; linked blocks revert to 'funded' via the state matrix.
 *
 * Refuses with 409 while the route has active jobs. Send
 * `{ "releaseActiveJobs": true }` to release claimed/in-progress jobs and
 * unpublish in one transaction. See lib/admin-route-close.ts.
 */
import { closeRouteHandler } from '@/lib/admin-route-close'

export const dynamic = 'force-dynamic'

export const POST = closeRouteHandler({
  verb: 'unpublish',
  status: 'draft',
  routePatch: { publishedAt: null },
})
