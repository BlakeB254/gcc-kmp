'use client'

import { useMemo, useState } from 'react'
import {
  INDOOR_GATE_V4_COPY,
  summarizeIndoorGateV4,
  type OutdoorState,
} from '@/lib/indoor-gate-v4'

const STATES: OutdoorState[] = ['UNCERTAIN', 'INDOOR', 'OUTDOOR']

const DEMO_EVENTS: Record<OutdoorState, string[]> = {
  UNCERTAIN: ['gate@1000 state=UNCERTAIN'],
  INDOOR: [
    'gate@1000 state=UNCERTAIN',
    'INDOOR (ceiling plane detected)',
    'gate@1800 state=INDOOR',
    'purge@1800 dropped=2',
  ],
  OUTDOOR: [
    'gate@1000 state=UNCERTAIN',
    'gate@2400 state=OUTDOOR',
  ],
}

function StateChip({
  state,
  active,
  onClick,
}: {
  state: OutdoorState
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`rounded-full px-3 py-1.5 text-xs font-semibold tracking-wide ${
        active
          ? 'bg-brand-green-600 text-white'
          : 'bg-neutral-800 text-neutral-300 hover:bg-neutral-700'
      }`}
    >
      {state}
    </button>
  )
}

export function IndoorGateAppPreview({
  initialState = 'UNCERTAIN',
}: {
  initialState?: OutdoorState
}) {
  const [state, setState] = useState<OutdoorState>(initialState)
  const summary = useMemo(() => summarizeIndoorGateV4(DEMO_EVENTS[state]), [state])

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,320px)_1fr] items-start">
      <div
        data-testid="indoor-gate-phone"
        data-gate-state={state}
        data-capture-allowed={summary.captureAllowed ? 'true' : 'false'}
        className="relative mx-auto h-[560px] w-[280px] overflow-hidden rounded-[2rem] border-4 border-neutral-800 bg-neutral-950 shadow-2xl"
      >
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_30%_20%,rgba(74,222,128,0.18),transparent_45%),linear-gradient(180deg,#14532d_0%,#052e16_100%)]" />
        <div className="relative flex h-full flex-col">
          <div className="flex items-center justify-between px-4 pt-5 text-[10px] uppercase tracking-[0.2em] text-white/70">
            <span>Env scan</span>
            <span>{summary.captureAllowed ? 'REC' : 'REC ∅'}</span>
          </div>

          {state === 'UNCERTAIN' && (
            <div
              data-testid="confirming-outdoors-chip"
              className="mx-auto mt-4 rounded-full bg-black/55 px-3.5 py-1.5 text-xs text-white"
            >
              {INDOOR_GATE_V4_COPY.uncertainChip}
            </div>
          )}

          <div className="mt-auto px-4 pb-6">
            <button
              type="button"
              disabled={!summary.captureAllowed}
              className={`w-full rounded-full py-3 text-sm font-semibold ${
                summary.captureAllowed
                  ? 'bg-brand-green-600 text-white'
                  : 'cursor-not-allowed bg-white/15 text-white/50'
              }`}
            >
              {INDOOR_GATE_V4_COPY.completeScan}
            </button>
          </div>
        </div>

        {state === 'INDOOR' && (
          <div
            data-testid="go-outside-overlay"
            className="absolute inset-0 flex flex-col items-center justify-center bg-black/78 px-8 text-center"
          >
            <div className="text-3xl">🏠 → 🌳</div>
            <h3 className="mt-4 text-lg font-bold text-white">
              {INDOOR_GATE_V4_COPY.indoorHeadline}
            </h3>
            <p className="mt-2 text-sm text-white/85">
              {INDOOR_GATE_V4_COPY.indoorBody}
            </p>
            <button
              type="button"
              onClick={() => setState('OUTDOOR')}
              className="mt-6 rounded-full bg-brand-green-600 px-7 py-3 text-sm font-bold text-white"
            >
              {INDOOR_GATE_V4_COPY.imOutside}
            </button>
            <button
              type="button"
              className="mt-3 rounded-full bg-white/18 px-7 py-3 text-sm font-bold text-white"
            >
              {INDOOR_GATE_V4_COPY.closeScan}
            </button>
          </div>
        )}
      </div>

      <div>
        <div className="mb-4 flex flex-wrap gap-2" role="tablist" aria-label="Indoor Gate v4 states">
          {STATES.map((item) => (
            <StateChip
              key={item}
              state={item}
              active={state === item}
              onClick={() => setState(item)}
            />
          ))}
        </div>
        <p className="mb-4 text-sm text-neutral-600">
          Capture is allowed only while the gate is OUTDOOR. UNCERTAIN keeps the
          preview live but disables Complete Scan — no frames persist until
          outdoors is proven.
        </p>
        <dl className="grid grid-cols-2 gap-3 text-sm">
          <div className="rounded-lg border border-neutral-200 bg-white p-3">
            <dt className="text-xs uppercase tracking-wide text-neutral-500">State</dt>
            <dd className="font-semibold text-neutral-900">{summary.lastState}</dd>
          </div>
          <div className="rounded-lg border border-neutral-200 bg-white p-3">
            <dt className="text-xs uppercase tracking-wide text-neutral-500">captureAllowed</dt>
            <dd className="font-semibold text-neutral-900">
              {summary.captureAllowed ? 'true' : 'false'}
            </dd>
          </div>
          <div className="rounded-lg border border-neutral-200 bg-white p-3">
            <dt className="text-xs uppercase tracking-wide text-neutral-500">Ceiling cues</dt>
            <dd className="font-semibold text-neutral-900">{summary.ceilingCueCount}</dd>
          </div>
          <div className="rounded-lg border border-neutral-200 bg-white p-3">
            <dt className="text-xs uppercase tracking-wide text-neutral-500">Purge events</dt>
            <dd className="font-semibold text-neutral-900">{summary.purgeEventCount}</dd>
          </div>
        </dl>
      </div>
    </div>
  )
}
