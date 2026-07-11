import { routeStatusEnum } from '@/db/schema'

export type AdminRouteStatus = (typeof routeStatusEnum.enumValues)[number]
export type EligibleBlockStatus = 'available' | 'funded'

const ADMIN_ROUTE_STATUSES = new Set<string>(routeStatusEnum.enumValues)

export function parseAdminRouteStatus(value: unknown): AdminRouteStatus | null {
  return typeof value === 'string' && ADMIN_ROUTE_STATUSES.has(value)
    ? (value as AdminRouteStatus)
    : null
}

const ELIGIBLE_BLOCK_STATUS_BY_ROUTE_STATUS: Record<AdminRouteStatus, EligibleBlockStatus> = {
  draft: 'funded',
  published: 'available',
  archived: 'funded',
}

export function eligibleBlockStatusForRouteStatus(status: AdminRouteStatus): EligibleBlockStatus {
  return ELIGIBLE_BLOCK_STATUS_BY_ROUTE_STATUS[status]
}
