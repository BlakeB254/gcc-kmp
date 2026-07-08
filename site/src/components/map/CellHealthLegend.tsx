'use client'

// Legend for the far-zoom cell-health choropleth (`choropleth-cell-health-fill`
// in CombinedMap.tsx). That layer's `fill-color` is a `match` on `dominant`
// ('unscanned' -> grey) falling back to an `interpolate` over `health`
// (0.0 -> red, 0.5 -> amber, 1.0 -> green) — see cell-health-choropleth.ts's
// `aggregateCellHealth` for how `health`/`dominant` are derived. This used to
// be a doppler-radar "Report Density" legend (Light/Moderate/Heavy/Extreme)
// left over from the point-density spot heatmap that layer replaced; the
// choropleth's real vocabulary is clean/stale/dirty/unscanned ground, not
// report intensity — same green/amber/grey language as the coverage-freshness
// tiles and mobile's ScanCoverageLegend (MapFullScreen.kt).
const RAMP_BOTTOM_TO_TOP = ['#EF4444', '#F59E0B', '#22C55E']
const UNSCANNED_COLOR = '#4B5563'

export function CellHealthLegend({ onDone }: { onDone?: () => void }) {
  const gradient = `linear-gradient(to top, ${RAMP_BOTTOM_TO_TOP.join(', ')})`
  return (
    <div
      data-testid="cell-health-legend"
      className="absolute top-3 left-3 bg-white/95 backdrop-blur-sm rounded-xl p-3 shadow-md z-10 w-40"
    >
      {onDone && (
        <button
          data-testid="cell-health-legend-done"
          onClick={onDone}
          className="text-sm font-semibold text-blue-600 mb-2"
        >
          Done
        </button>
      )}
      <div className="text-xs font-semibold text-neutral-700 mb-2">Ground Health</div>
      <div className="flex gap-2">
        <div className="w-3 rounded-full" style={{ background: gradient, height: 72 }} />
        <div className="flex flex-col justify-between text-[11px] text-neutral-600 py-0.5" style={{ height: 72 }}>
          <span>Clean · scanned</span>
          <span>Stale · re-scan</span>
          <span>Dirty · litter</span>
        </div>
      </div>
      <div className="flex items-center gap-2 mt-2 pt-2 border-t border-neutral-200">
        <div className="w-3 h-3 rounded-full flex-shrink-0" style={{ background: UNSCANNED_COLOR }} />
        <span className="text-[11px] text-neutral-600">Never scanned</span>
      </div>
    </div>
  )
}
