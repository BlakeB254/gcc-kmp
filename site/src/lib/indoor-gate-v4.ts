/** Indoor Gate v4 audit helpers — parse client gateEvents / diagnosticsLog. */

export const OUTDOOR_STATES = ['OUTDOOR', 'UNCERTAIN', 'INDOOR'] as const
export type OutdoorState = (typeof OUTDOOR_STATES)[number]

export type GateEventKind =
  | 'state'
  | 'ceiling'
  | 'override'
  | 'override_yield'
  | 'purge'
  | 'map'
  | 'scene'
  | 'score'
  | 'other'

export interface ParsedGateEvent {
  raw: string
  kind: GateEventKind
  atMs: number | null
  state: OutdoorState | null
  message: string
}

export interface IndoorGateV4Summary {
  lastState: OutdoorState
  captureAllowed: boolean
  lastIndoorFlipAtMs: number | null
  ceilingCueCount: number
  overridePressCount: number
  overrideYieldCount: number
  purgeEventCount: number
  events: ParsedGateEvent[]
}

const STATE_RE = /\b(OUTDOOR|UNCERTAIN|INDOOR)\b/
const TS_RE = /(?:gate|purge)@(\d+)/i

export function parseOutdoorState(value: unknown): OutdoorState | null {
  if (typeof value !== 'string') return null
  const upper = value.trim().toUpperCase()
  return (OUTDOOR_STATES as readonly string[]).includes(upper)
    ? (upper as OutdoorState)
    : null
}

function classify(raw: string): GateEventKind {
  const text = raw.toLowerCase()
  if (text.includes('purge@') || text.includes('purge ')) return 'purge'
  if (text.includes('override cleared') || text.includes('yield')) return 'override_yield'
  if (text.includes('manual override') || text.includes("i'm outside")) return 'override'
  if (text.includes('ceiling')) return 'ceiling'
  if (text.includes('indoorscore') || text.includes('outdoorScore'.toLowerCase()) || text.includes('scene score') || text.includes('weighted')) {
    return 'score'
  }
  if (text.includes('mapped building') || text.includes('map containment')) return 'map'
  if (text.includes('camera indoor') || text.includes('scene vote')) return 'scene'
  if (STATE_RE.test(raw) || text.startsWith('gate@')) return 'state'
  return 'other'
}

export function parseGateEvent(raw: unknown): ParsedGateEvent | null {
  if (raw == null) return null
  if (typeof raw === 'object') {
    const rec = raw as Record<string, unknown>
    const message =
      typeof rec.msg === 'string'
        ? rec.msg
        : typeof rec.message === 'string'
          ? rec.message
          : typeof rec.state === 'string'
            ? rec.state
            : JSON.stringify(raw)
    const atMs =
      typeof rec.t === 'number'
        ? rec.t
        : typeof rec.atMs === 'number'
          ? rec.atMs
          : null
    const state = parseOutdoorState(rec.state) ?? parseOutdoorState(message)
    return {
      raw: message,
      kind: classify(message),
      atMs,
      state,
      message,
    }
  }
  if (typeof raw !== 'string' || !raw.trim()) return null
  const text = raw.trim()
  const tsMatch = text.match(TS_RE)
  const stateMatch = text.match(STATE_RE)
  return {
    raw: text,
    kind: classify(text),
    atMs: tsMatch ? Number(tsMatch[1]) : null,
    state: stateMatch ? (stateMatch[1] as OutdoorState) : null,
    message: text,
  }
}

export function summarizeIndoorGateV4(
  gateEvents: unknown[] | null | undefined,
  diagnosticsLog: unknown[] | null | undefined = null,
): IndoorGateV4Summary {
  const events = [...(gateEvents ?? []), ...(diagnosticsLog ?? [])]
    .map(parseGateEvent)
    .filter((e): e is ParsedGateEvent => e != null)

  let lastState: OutdoorState = 'UNCERTAIN'
  let lastIndoorFlipAtMs: number | null = null
  let ceilingCueCount = 0
  let overridePressCount = 0
  let overrideYieldCount = 0
  let purgeEventCount = 0

  for (const event of events) {
    if (event.state) lastState = event.state
    if (event.state === 'INDOOR' && event.atMs != null) lastIndoorFlipAtMs = event.atMs
    if (event.kind === 'ceiling') ceilingCueCount += 1
    if (event.kind === 'override') overridePressCount += 1
    if (event.kind === 'override_yield') overrideYieldCount += 1
    if (event.kind === 'purge') purgeEventCount += 1
  }

  return {
    lastState,
    captureAllowed: lastState === 'OUTDOOR',
    lastIndoorFlipAtMs,
    ceilingCueCount,
    overridePressCount,
    overrideYieldCount,
    purgeEventCount,
    events,
  }
}

export const INDOOR_GATE_V4_COPY = {
  uncertainChip: "Confirming you're outside…",
  indoorHeadline: 'Go outside to scan the environment',
  indoorBody:
    'Scanning only works outdoors in public areas. Step outside and the camera will unlock automatically.',
  imOutside: "I'm outside — continue",
  closeScan: 'Close Scan',
  completeScan: 'Complete Scan',
} as const
