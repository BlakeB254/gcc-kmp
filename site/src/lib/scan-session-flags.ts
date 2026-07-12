/**
 * Red-flag derivation for the admin scan-session review surface
 * (scan-session-replay PRD Phase 2). Computed SERVER-SIDE in the
 * /api/admin/scan-sessions routes so every client (site admin panel, iOS
 * admin via GccApiClient/AdminScanSessionSummary) renders the same flags —
 * the thresholds live in exactly one place. Thresholds start manual and get
 * tuned from real data (PRD Phase 3).
 */
export interface ScanSessionFlagInput {
  lapKind: string | null
  chunkCount: number
  gateFlipCount: number | null
  uploadFailureCount: number | null
  errorCount: number | null
  raycastMissCount: number | null
}

export function scanSessionRedFlags(s: ScanSessionFlagInput): string[] {
  const flags: string[] = []
  if (s.lapKind === 'recovered') flags.push('crashed')
  if ((s.gateFlipCount ?? 0) >= 4) flags.push('gate-flapped')
  if ((s.uploadFailureCount ?? 0) > 0) flags.push('upload-failures')
  if ((s.errorCount ?? 0) >= 3) flags.push('errors')
  if (s.chunkCount === 0 && s.lapKind !== 'recovered') flags.push('no-capture')
  if ((s.raycastMissCount ?? 0) >= 3) flags.push('raycast-misses')
  return flags
}
