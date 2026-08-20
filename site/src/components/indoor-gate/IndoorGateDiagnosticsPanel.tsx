import { summarizeIndoorGateV4, type IndoorGateV4Summary } from '@/lib/indoor-gate-v4'

export function IndoorGateDiagnosticsPanel({
  gateEvents,
  diagnosticsLog,
}: {
  gateEvents?: unknown[] | null
  diagnosticsLog?: unknown[] | null
}) {
  const summary: IndoorGateV4Summary = summarizeIndoorGateV4(gateEvents, diagnosticsLog)
  const chips: Array<[string, string | number]> = [
    ['state', summary.lastState],
    ['captureAllowed', summary.captureAllowed ? 'true' : 'false'],
    ['ceiling', summary.ceilingCueCount],
    ['override', summary.overridePressCount],
    ['yield', summary.overrideYieldCount],
    ['purge', summary.purgeEventCount],
  ]

  return (
    <section
      data-testid="indoor-gate-v4-diagnostics"
      className="mt-4 rounded-lg border border-neutral-800 bg-neutral-900/50"
    >
      <div className="border-b border-neutral-800 px-3 py-2 text-xs font-medium text-neutral-300">
        Indoor Gate v4
      </div>
      <div className="flex flex-wrap gap-1.5 px-3 py-3">
        {chips.map(([label, value]) => (
          <span key={label} className="rounded bg-neutral-800 px-2 py-1 text-[11px] text-neutral-300">
            {label}: {value}
          </span>
        ))}
      </div>
      {summary.events.length === 0 ? (
        <p className="px-3 pb-3 text-[11px] text-neutral-500">
          No gate events or outdoor-gate diagnostics in this session.
        </p>
      ) : (
        <div className="max-h-48 overflow-y-auto border-t border-neutral-800 px-3 py-2 font-mono text-[11px] leading-5">
          {summary.events.map((event, index) => (
            <div key={`${event.kind}-${index}`} className="flex gap-2 text-neutral-300">
              <span className="w-24 shrink-0 text-amber-400">{event.kind}</span>
              <span className="min-w-0 truncate" title={event.message}>
                {event.message}
              </span>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
