'use client'

// Vertical intensity scale — doppler ramp (bottom = Light/green, top = Extreme/white).
const RAMP_BOTTOM_TO_TOP = [
  '#2EEA52', '#7CFC2F', '#FDE047', '#F59E0B',
  '#F97316', '#DC2626', '#FFF7AD', '#FFFFFF',
]

export function PrecipitationLegend({ onDone }: { onDone?: () => void }) {
  const gradient = `linear-gradient(to top, ${RAMP_BOTTOM_TO_TOP.join(', ')})`
  return (
    <div
      data-testid="precip-legend"
      className="absolute top-3 left-3 bg-white/95 backdrop-blur-sm rounded-xl p-3 shadow-md z-10 w-36"
    >
      {onDone && (
        <button
          data-testid="precip-legend-done"
          onClick={onDone}
          className="text-sm font-semibold text-blue-600 mb-2"
        >
          Done
        </button>
      )}
      <div className="text-xs font-semibold text-neutral-700 mb-2">Report Density</div>
      <div className="flex gap-2">
        <div className="w-3 rounded-full" style={{ background: gradient, height: 96 }} />
        <div className="flex flex-col justify-between text-[11px] text-neutral-600 py-0.5">
          <span>Extreme</span>
          <span>Heavy</span>
          <span>Moderate</span>
          <span>Light</span>
        </div>
      </div>
    </div>
  )
}
