interface Bar {
  label: string
  value: number
  displayValue: string
  sublabel?: string
}

const BRANAS_BARS: Bar[] = [
  { label: 'Gun assaults near treated lots', value: 29.1, displayValue: '−29.1%' },
  { label: 'Depression reports', value: 41.5, displayValue: '−41.5%' },
  { label: 'Assaults, low-income residents', value: 68.7, displayValue: '−68.7%' },
]

/** Horizontal bar chart — a single series (percent reduction), so one hue and no legend, per the dataviz form heuristic for magnitude comparisons. */
export function BranasResultsChart() {
  const max = Math.max(...BRANAS_BARS.map((b) => b.value))
  return (
    <figure className="mt-6 rounded-xl border border-neutral-200 bg-white p-6">
      <figcaption className="mb-5 text-xs font-bold uppercase tracking-widest text-neutral-500">
        Branas et al. — Philadelphia cluster-randomized trial, vacant-lot greening vs. controls
      </figcaption>
      <div className="space-y-4">
        {BRANAS_BARS.map((bar) => (
          <div key={bar.label}>
            <div className="mb-1.5 flex items-baseline justify-between gap-3 text-sm">
              <span className="text-neutral-700">{bar.label}</span>
              <span className="font-bold tabular-nums text-brand-green-700">{bar.displayValue}</span>
            </div>
            <div className="h-2.5 w-full overflow-hidden rounded-full bg-neutral-100">
              <div
                className="h-full rounded-full bg-brand-green-600"
                style={{ width: `${(bar.value / max) * 100}%` }}
              />
            </div>
          </div>
        ))}
      </div>
      <p className="mt-5 text-xs leading-relaxed text-neutral-500">
        Published in <em>PNAS</em> and <em>JAMA Network Open</em>. Bars show percent reduction in treated lots relative to untouched controls over the 18-month trial.
      </p>
    </figure>
  )
}

/** Single-comparison stat tile rather than a two-bar chart — one relative figure reads faster than a chart for a single number, per the dataviz "sometimes it's not a chart" guidance. */
export function CollectiveEfficacyStat() {
  return (
    <div className="mt-6 flex items-center gap-5 rounded-xl border border-brand-green-200 bg-brand-green-50 p-6">
      <p className="shrink-0 font-serif text-5xl font-bold tabular-nums text-brand-green-700">−40%</p>
      <p className="text-sm leading-relaxed text-neutral-700">
        Crime rate in neighborhoods that score high on <strong>collective efficacy</strong> — whether neighbors feel any agency over their block — compared to similar-income, similar-disorder neighborhoods that score low.{' '}
        <span className="text-neutral-500">— Robert Sampson, Chicago research</span>
      </p>
    </div>
  )
}
