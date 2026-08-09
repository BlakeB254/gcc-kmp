export type RouteReleaseMetrics = {
  examined: number
  processed: number
  released: number
  durationMs: number
  leaseAcquired: boolean
}

export function logRouteReleaseMetrics(metrics: RouteReleaseMetrics): void {
  // Structured single-line JSON for log sinks; never include worker PII.
  console.info(
    JSON.stringify({
      event: 'gcc.cron.route_releases',
      examined: metrics.examined,
      processed: metrics.processed,
      released: metrics.released,
      durationMs: metrics.durationMs,
      leaseAcquired: metrics.leaseAcquired,
    }),
  )
}

export function summarizeReleaseResults(
  results: ReadonlyArray<{ offersSent?: number }>,
): Pick<RouteReleaseMetrics, 'processed' | 'released'> {
  return {
    processed: results.length,
    released: results.reduce((sum, r) => sum + (r.offersSent ?? 0), 0),
  }
}
