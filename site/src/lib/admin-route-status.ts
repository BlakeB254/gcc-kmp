import { routeClaimStatusEnum, routeStatusEnum } from '@/db/schema'

export type AdminRouteStatus = (typeof routeStatusEnum.enumValues)[number]
export type AdminClaimStatus = (typeof routeClaimStatusEnum.enumValues)[number]
export type EligibleBlockStatus = 'available' | 'funded'

/**
 * Worker-facing state of a route, per the state matrix in
 * docs/plans/route-admin-plan-2026-08-20:
 *
 *   route.status | claim_status | worker sees  | can claim
 *   -------------+--------------+--------------+----------
 *   draft        | closed       | no           | no
 *   published    | closed       | yes (read)   | no
 *   published    | open         | yes          | yes
 *   published    | scheduled    | yes + date   | not yet
 *   archived     | closed       | no           | no
 */
export type EffectiveRouteState =
  | 'hidden' // draft / archived — workers never see it
  | 'readable' // published + closed — visible read-only, not claimable
  | 'claimable' // published + open — visible and claimable
  | 'scheduled' // published + scheduled — visible with an "opens at" date

const ADMIN_ROUTE_STATUSES = new Set<string>(routeStatusEnum.enumValues)
const ADMIN_CLAIM_STATUSES = new Set<string>(routeClaimStatusEnum.enumValues)

export function parseAdminRouteStatus(value: unknown): AdminRouteStatus | null {
  return typeof value === 'string' && ADMIN_ROUTE_STATUSES.has(value)
    ? (value as AdminRouteStatus)
    : null
}

export function parseAdminClaimStatus(value: unknown): AdminClaimStatus | null {
  return typeof value === 'string' && ADMIN_CLAIM_STATUSES.has(value)
    ? (value as AdminClaimStatus)
    : null
}

export type RouteStateInput = {
  status: AdminRouteStatus
  claimStatus?: AdminClaimStatus | null
}

export function effectiveRouteState(route: RouteStateInput): EffectiveRouteState {
  if (route.status !== 'published') return 'hidden'
  const claimStatus = route.claimStatus ?? 'closed'
  if (claimStatus === 'open') return 'claimable'
  if (claimStatus === 'scheduled') return 'scheduled'
  return 'readable'
}

/** True when a worker can claim this route right now (published + open). */
export function isClaimableRoute(route: RouteStateInput): boolean {
  return effectiveRouteState(route) === 'claimable'
}

/**
 * The block status a route's linked (still-flippable) blocks should sit in:
 * 'available' only for published + open; 'funded' for draft, closed,
 * scheduled, and archived alike.
 */
export function eligibleBlockStatusForRoute(route: RouteStateInput): EligibleBlockStatus {
  return isClaimableRoute(route) ? 'available' : 'funded'
}

const ELIGIBLE_BLOCK_STATUS_BY_ROUTE_STATUS: Record<AdminRouteStatus, EligibleBlockStatus> = {
  draft: 'funded',
  published: 'available',
  archived: 'funded',
}

/**
 * @deprecated Legacy pre-claim_status mapping (published ⇒ available). Kept
 * for backwards compatibility; new code should use
 * eligibleBlockStatusForRoute({ status, claimStatus }).
 */
export function eligibleBlockStatusForRouteStatus(status: AdminRouteStatus): EligibleBlockStatus {
  return ELIGIBLE_BLOCK_STATUS_BY_ROUTE_STATUS[status]
}
