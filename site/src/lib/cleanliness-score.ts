/**
 * Compute a 0-100 cleanliness score from spot metrics.
 *
 * Three penalties (active spots, duration, recurrence) are capped
 * individually so no single factor can dominate the score.
 */
export function computeCleanlinessScore(
  activeSpots: number,
  avgDurationHours: number,
  recurrenceRate: number,
): number {
  const activePenalty = Math.min(40, activeSpots * 3)
  const durationPenalty = Math.min(30, avgDurationHours / 4)
  const recurrencePenalty = Math.min(30, recurrenceRate * 100)
  return Math.max(0, Math.min(100, Math.round(100 - activePenalty - durationPenalty - recurrencePenalty)))
}
