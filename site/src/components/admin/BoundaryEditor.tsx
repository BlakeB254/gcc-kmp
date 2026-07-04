'use client'

/**
 * Admin block-boundary editor (route-v2).
 *
 * Full interactive editing of a block's EXTERNAL boundary (locationBoundary,
 * GeoJSON Polygon) and its INTERNAL exclusion islands (innerBoundary, GeoJSON
 * MultiPolygon — one island per built-up stretch; vacant stretches have no
 * island and stay scannable).
 *
 * Interactions (hand-rolled on maplibre events, same idiom as the existing
 * ExclusionZoneDrawer / RouteDrawer click-to-place tooling — no mapbox-draw):
 *   - drag a vertex handle to move it
 *   - mousedown an edge midpoint to insert a vertex there (and keep dragging)
 *   - click a vertex, press Delete/Backspace to remove it (min 3 per ring)
 *   - drag an island body to reposition the whole island
 *   - "New island": click to place points, Enter / double-click closes,
 *     Escape cancels
 *   - Cmd/Ctrl+Z undo, Reset restores the last saved geometry
 *
 * Saving goes through PUT /api/admin/blocks/[blockId]/boundaries which owns
 * the tiling semantics: expanding into a neighbor auto-thins the neighbor
 * (never below 40% of its area — past that the save is rejected). The
 * response's thinnedNeighbors list is surfaced after every save.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Source, Layer } from 'react-map-gl/maplibre'
import { BaseMap } from '@/components/map/BaseMap'
import intersect from '@turf/intersect'
import difference from '@turf/difference'
import turfArea from '@turf/area'
import { featureCollection, polygon as turfPolygon } from '@turf/helpers'

// ─── Geometry model ───────────────────────────────────────────────────────────

type Pt = [number, number]
/** Open ring — no closing duplicate point. Closed only at GeoJSON boundaries. */
type Ring = Pt[]
interface Geom {
  outer: Ring
  islands: Ring[]
}

type GeoPolygon = { type: 'Polygon'; coordinates: number[][][] }
type GeoMultiPolygon = { type: 'MultiPolygon'; coordinates: number[][][][] }

export interface EditorBlock {
  id: number
  code: string | null
  name: string
  routeId: number | null
  boundary: GeoPolygon | null
  innerBoundary: GeoMultiPolygon | null
}

/** ring index −1 = outer boundary; 0..n−1 = island index */
const OUTER = -1

interface Selection {
  ring: number
  /** null = whole island selected (only meaningful for ring >= 0) */
  vertex: number | null
}

interface DragState {
  kind: 'vertex' | 'island'
  ring: number
  vertex: number
  last: Pt
  startGeom: Geom
  pushed: boolean
  moved: boolean
}

function closeRing(r: Ring): number[][] {
  return [...r, r[0]]
}

function openRing(coords: number[][] | undefined): Ring {
  if (!coords || coords.length < 4) return []
  const pts = coords.slice(0, -1)
  return pts.map((p) => [p[0], p[1]] as Pt)
}

function cloneGeom(g: Geom): Geom {
  return {
    outer: g.outer.map((p) => [...p] as Pt),
    islands: g.islands.map((r) => r.map((p) => [...p] as Pt)),
  }
}

function geomFromBlock(b: EditorBlock): Geom | null {
  const outer = openRing(b.boundary?.coordinates?.[0])
  if (outer.length < 3) return null
  const islands = (b.innerBoundary?.coordinates || [])
    .map((poly) => openRing(poly?.[0]))
    .filter((r) => r.length >= 3)
  return { outer, islands }
}

function geomBounds(g: Geom): [[number, number], [number, number]] {
  let minLng = Infinity, minLat = Infinity, maxLng = -Infinity, maxLat = -Infinity
  const eat = (p: Pt) => {
    if (p[0] < minLng) minLng = p[0]
    if (p[0] > maxLng) maxLng = p[0]
    if (p[1] < minLat) minLat = p[1]
    if (p[1] > maxLat) maxLat = p[1]
  }
  g.outer.forEach(eat)
  g.islands.forEach((r) => r.forEach(eat))
  return [[minLng, minLat], [maxLng, maxLat]]
}

/** Drop consecutive duplicate points (double-click close leaves two). */
function dedupeRing(r: Ring): Ring {
  const out: Ring = []
  for (const p of r) {
    const prev = out[out.length - 1]
    if (prev && Math.abs(prev[0] - p[0]) < 1e-9 && Math.abs(prev[1] - p[1]) < 1e-9) continue
    out.push(p)
  }
  return out
}

// ─── Colors — match the existing admin visual language ──────────────────────
const OUTER_COLOR = '#22C55E' // green like published routes / approved blocks
const ISLAND_COLOR = '#C2410C' // amber/orange like exclusion zones
const NEIGHBOR_COLOR = '#4B5563'
const DRAW_COLOR = '#F59E0B'

interface Props {
  blocks: EditorBlock[]
  onBlocksChanged: () => Promise<void> | void
}

function authHeaders(): HeadersInit {
  const token = localStorage.getItem('adminToken')
  return { Authorization: `Bearer ${token}` }
}

export function BoundaryEditor({ blocks, onBlocksChanged }: Props) {
  const [mapInst, setMapInst] = useState<any>(null)
  // Stable callback — an inline arrow would make React detach/re-attach the
  // ref (null → instance) on every render and thrash the listener effect.
  const handleMapRef = useCallback((ref: any) => {
    if (ref) setMapInst(ref)
  }, [])
  const [selectedBlockId, setSelectedBlockId] = useState<number | null>(null)
  const [geom, setGeom] = useState<Geom | null>(null)
  const [savedGeom, setSavedGeom] = useState<Geom | null>(null)
  const [selection, setSelection] = useState<Selection | null>(null)
  const [drawing, setDrawing] = useState<Ring | null>(null)
  const [cursorPos, setCursorPos] = useState<Pt | null>(null)
  const [histLen, setHistLen] = useState(0)
  const [saving, setSaving] = useState(false)
  const [regenLoading, setRegenLoading] = useState(false)
  const [setbackMeters, setSetbackMeters] = useState(8)
  const [status, setStatus] = useState<{ kind: 'ok' | 'error' | 'warn'; text: string } | null>(null)
  const [search, setSearch] = useState('')

  const historyRef = useRef<Geom[]>([])
  const dragRef = useRef<DragState | null>(null)
  const suppressClickRef = useRef(false)
  const statusTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const fittedAllRef = useRef(false)

  // Live mirror of state for the native maplibre handlers (attached once).
  const live = useRef({
    geom, selection, drawing, selectedBlockId, blocks, saving,
    dirty: false,
  })

  const selectedBlock = useMemo(
    () => blocks.find((b) => b.id === selectedBlockId) || null,
    [blocks, selectedBlockId]
  )

  const dirty = useMemo(
    () => JSON.stringify(geom) !== JSON.stringify(savedGeom),
    [geom, savedGeom]
  )

  live.current = { geom, selection, drawing, selectedBlockId, blocks, saving, dirty }

  const showStatus = useCallback((kind: 'ok' | 'error' | 'warn', text: string, ms = 6000) => {
    if (statusTimerRef.current) clearTimeout(statusTimerRef.current)
    setStatus({ kind, text })
    statusTimerRef.current = setTimeout(() => setStatus(null), ms)
  }, [])

  // ── History ────────────────────────────────────────────────────────────────

  const pushHistory = useCallback((g: Geom) => {
    historyRef.current.push(cloneGeom(g))
    if (historyRef.current.length > 50) historyRef.current.shift()
    setHistLen(historyRef.current.length)
  }, [])

  const undo = useCallback(() => {
    const last = historyRef.current.pop()
    if (!last) return
    setHistLen(historyRef.current.length)
    setGeom(cloneGeom(last))
    setSelection(null)
  }, [])

  const resetToSaved = useCallback(() => {
    const g = live.current.geom
    if (!savedGeom || !g) return
    pushHistory(g)
    setGeom(cloneGeom(savedGeom))
    setSelection(null)
    setDrawing(null)
  }, [savedGeom, pushHistory])

  // ── Block selection ────────────────────────────────────────────────────────

  const fitToGeom = useCallback((g: Geom, padding = 80) => {
    if (!mapInst) return
    const [[minLng, minLat], [maxLng, maxLat]] = geomBounds(g)
    if (!Number.isFinite(minLng)) return
    mapInst.fitBounds([[minLng, minLat], [maxLng, maxLat]], {
      padding, maxZoom: 18.5, duration: 600,
    })
  }, [mapInst])

  const selectBlock = useCallback((id: number) => {
    const s = live.current
    if (s.selectedBlockId === id) return
    if (s.dirty && !window.confirm('Discard unsaved boundary changes?')) return
    const block = s.blocks.find((b) => b.id === id)
    if (!block) return
    const g = block ? geomFromBlock(block) : null
    if (!g) {
      showStatus('error', 'This block has no boundary polygon to edit yet.')
      return
    }
    setSelectedBlockId(id)
    setGeom(g)
    setSavedGeom(cloneGeom(g))
    historyRef.current = []
    setHistLen(0)
    setSelection(null)
    setDrawing(null)
    setStatus(null)
    fitToGeom(g)
  }, [fitToGeom, showStatus])

  const deselectBlock = useCallback(() => {
    if (live.current.dirty && !window.confirm('Discard unsaved boundary changes?')) return
    setSelectedBlockId(null)
    setGeom(null)
    setSavedGeom(null)
    setSelection(null)
    setDrawing(null)
    historyRef.current = []
    setHistLen(0)
  }, [])

  // Overview fit once blocks load.
  useEffect(() => {
    if (!mapInst || fittedAllRef.current || selectedBlockId !== null) return
    const rings = blocks
      .map((b) => openRing(b.boundary?.coordinates?.[0]))
      .filter((r) => r.length >= 3)
    if (rings.length === 0) return
    fittedAllRef.current = true
    fitToGeom({ outer: rings.flat(), islands: [] }, 48)
  }, [mapInst, blocks, selectedBlockId, fitToGeom])

  // ── Geometry mutations ─────────────────────────────────────────────────────

  const setRingVertex = useCallback((ring: number, idx: number, p: Pt) => {
    setGeom((g) => {
      if (!g) return g
      const next = cloneGeom(g)
      const target = ring === OUTER ? next.outer : next.islands[ring]
      if (!target || idx < 0 || idx >= target.length) return g
      target[idx] = p
      return next
    })
  }, [])

  const translateIsland = useCallback((ring: number, dLng: number, dLat: number) => {
    setGeom((g) => {
      if (!g || !g.islands[ring]) return g
      const next = cloneGeom(g)
      next.islands[ring] = next.islands[ring].map((p) => [p[0] + dLng, p[1] + dLat] as Pt)
      return next
    })
  }, [])

  const deleteSelected = useCallback(() => {
    const s = live.current
    if (!s.geom || !s.selection) return
    const { ring, vertex } = s.selection
    if (vertex === null) {
      // whole island selected
      if (ring < 0 || !s.geom.islands[ring]) return
      pushHistory(s.geom)
      setGeom((g) => {
        if (!g) return g
        const next = cloneGeom(g)
        next.islands.splice(ring, 1)
        return next
      })
      setSelection(null)
      showStatus('ok', 'Island deleted.', 3000)
      return
    }
    const target = ring === OUTER ? s.geom.outer : s.geom.islands[ring]
    if (!target) return
    if (target.length <= 3) {
      showStatus(
        'warn',
        ring === OUTER
          ? 'The external boundary needs at least 3 points.'
          : 'An island needs at least 3 points — select the island body and press Delete to remove it entirely.'
      )
      return
    }
    pushHistory(s.geom)
    setGeom((g) => {
      if (!g) return g
      const next = cloneGeom(g)
      const t = ring === OUTER ? next.outer : next.islands[ring]
      t.splice(vertex, 1)
      return next
    })
    setSelection(null)
  }, [pushHistory, showStatus])

  // ── Drawing new islands ────────────────────────────────────────────────────

  const startDrawing = useCallback(() => {
    setSelection(null)
    setDrawing([])
    setStatus(null)
  }, [])

  const cancelDrawing = useCallback(() => {
    setDrawing(null)
    setCursorPos(null)
  }, [])

  const finishDrawing = useCallback(() => {
    const s = live.current
    if (!s.drawing || !s.geom) return
    const pts = dedupeRing(s.drawing)
    if (pts.length < 3) {
      showStatus('warn', 'An island needs at least 3 points — keep clicking to add them.')
      return
    }
    pushHistory(s.geom)
    setGeom((g) => {
      if (!g) return g
      const next = cloneGeom(g)
      next.islands.push(pts)
      return next
    })
    setSelection({ ring: s.geom.islands.length, vertex: null })
    setDrawing(null)
    setCursorPos(null)
  }, [pushHistory, showStatus])

  // Disable double-click zoom while drawing.
  useEffect(() => {
    if (!mapInst) return
    const map = mapInst.getMap ? mapInst.getMap() : mapInst
    if (!map) return
    if (drawing) map.doubleClickZoom.disable()
    else map.doubleClickZoom.enable()
  }, [mapInst, drawing])

  // ── Validation (client-side; the server re-validates tiling) ──────────────

  const validation = useMemo(() => {
    const outsideIslands: number[] = []
    const overlapPairs: [number, number][] = []
    let invalidGeometry = false
    if (!geom || geom.outer.length < 3) return { outsideIslands, overlapPairs, invalidGeometry }
    try {
      const outerPoly = turfPolygon([closeRing(geom.outer)])
      const islandPolys = geom.islands
        .filter((r) => r.length >= 3)
        .map((r) => turfPolygon([closeRing(r)]))
      islandPolys.forEach((ip, i) => {
        try {
          const escaped = difference(featureCollection([ip, outerPoly]))
          if (escaped && turfArea(escaped) > 0.5) outsideIslands.push(i)
        } catch {
          invalidGeometry = true
        }
      })
      for (let i = 0; i < islandPolys.length; i++) {
        for (let j = i + 1; j < islandPolys.length; j++) {
          try {
            const ov = intersect(featureCollection([islandPolys[i], islandPolys[j]]))
            if (ov && turfArea(ov) > 0.5) overlapPairs.push([i, j])
          } catch {
            invalidGeometry = true
          }
        }
      }
    } catch {
      invalidGeometry = true
    }
    return { outsideIslands, overlapPairs, invalidGeometry }
  }, [geom])

  const saveBlockers: string[] = []
  if (geom && geom.outer.length < 3) saveBlockers.push('External boundary needs at least 3 points.')
  if (validation.outsideIslands.length > 0) {
    saveBlockers.push(
      `Island${validation.outsideIslands.length > 1 ? 's' : ''} ${validation.outsideIslands
        .map((i) => `#${i + 1}`)
        .join(', ')} extend${validation.outsideIslands.length > 1 ? '' : 's'} outside the external boundary — drag ${
        validation.outsideIslands.length > 1 ? 'them' : 'it'
      } back inside.`
    )
  }

  // ── Save ───────────────────────────────────────────────────────────────────

  const handleSave = useCallback(async () => {
    const s = live.current
    if (!s.geom || s.selectedBlockId === null || s.saving) return
    setSaving(true)
    setStatus(null)
    try {
      const body = {
        locationBoundary: { type: 'Polygon', coordinates: [closeRing(s.geom.outer)] },
        innerBoundary: s.geom.islands.length
          ? { type: 'MultiPolygon', coordinates: s.geom.islands.map((r) => [closeRing(r)]) }
          : null,
      }
      const res = await fetch(`/api/admin/blocks/${s.selectedBlockId}/boundaries`, {
        method: 'PUT',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok || !data.success) {
        showStatus('error', data.error || `Save failed (${res.status})`, 10000)
        return
      }
      setSavedGeom(cloneGeom(s.geom))
      const thinned: number[] = data.thinnedNeighbors || []
      if (thinned.length > 0) {
        const names = thinned
          .map((id) => {
            const b = s.blocks.find((x) => x.id === id)
            return b?.code ? `${b.code} (#${id})` : `#${id}`
          })
          .join(', ')
        showStatus('warn', `Saved. Expanding this block thinned neighbor${thinned.length > 1 ? 's' : ''}: ${names}.`, 12000)
      } else {
        showStatus('ok', 'Boundaries saved.')
      }
      await onBlocksChanged() // pick up thinned neighbor geometry
    } catch {
      showStatus('error', 'Network error while saving.', 10000)
    } finally {
      setSaving(false)
    }
  }, [onBlocksChanged, showStatus])

  const handleRegenerate = useCallback(async () => {
    const s = live.current
    const block = s.blocks.find((b) => b.id === s.selectedBlockId)
    if (!block?.routeId || !s.geom) return
    if (
      !window.confirm(
        'Regenerate the internal islands from OSM building data? This replaces the current islands and saves them immediately.'
      )
    )
      return
    setRegenLoading(true)
    try {
      const res = await fetch(`/api/admin/routes/${block.routeId}/regenerate-inner-boundary`, {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ setbackMeters }),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok || !data.success) {
        showStatus('error', data.error || `Regenerate failed (${res.status})`, 10000)
        return
      }
      const islands = ((data.innerBoundary as GeoMultiPolygon | null)?.coordinates || [])
        .map((poly) => openRing(poly?.[0]))
        .filter((r) => r.length >= 3)
      pushHistory(s.geom)
      setGeom((g) => (g ? { ...cloneGeom(g), islands } : g))
      // The endpoint persists the islands, so the saved snapshot moves too
      // (any unsaved OUTER edits stay unsaved).
      setSavedGeom((sg) => (sg ? { outer: sg.outer.map((p) => [...p] as Pt), islands: islands.map((r) => r.map((p) => [...p] as Pt)) } : sg))
      setSelection(null)
      showStatus('ok', `Regenerated ${islands.length} island${islands.length === 1 ? '' : 's'} from OSM building frontages.`)
      await onBlocksChanged()
    } catch {
      showStatus('error', 'Network error while regenerating.', 10000)
    } finally {
      setRegenLoading(false)
    }
  }, [setbackMeters, pushHistory, onBlocksChanged, showStatus])

  // ── Native map interactions ────────────────────────────────────────────────

  useEffect(() => {
    if (!mapInst) return
    const map = mapInst.getMap ? mapInst.getMap() : mapInst
    if (!map) return

    const query = (point: { x: number; y: number }, layerIds: string[], pad: number) => {
      const layers = layerIds.filter((id) => map.getLayer(id))
      if (layers.length === 0) return []
      const box: [[number, number], [number, number]] = [
        [point.x - pad, point.y - pad],
        [point.x + pad, point.y + pad],
      ]
      try {
        return map.queryRenderedFeatures(box, { layers })
      } catch {
        return []
      }
    }

    const setCursor = (c: string) => {
      map.getCanvas().style.cursor = c
    }

    const endDrag = () => {
      if (dragRef.current?.moved) suppressClickRef.current = true
      dragRef.current = null
      setCursor('')
      window.removeEventListener('mouseup', endDrag)
    }

    const onMouseDown = (e: any) => {
      const s = live.current
      if (!s.geom || s.drawing || s.saving) return
      if (e.originalEvent?.button !== 0) return

      // 1) vertex handle
      const vx = query(e.point, ['bnd-vertex'], 7)[0]
      if (vx) {
        const ring = Number(vx.properties?.ring)
        const idx = Number(vx.properties?.idx)
        e.preventDefault()
        dragRef.current = {
          kind: 'vertex', ring, vertex: idx,
          last: [e.lngLat.lng, e.lngLat.lat],
          startGeom: cloneGeom(s.geom), pushed: false, moved: false,
        }
        setSelection({ ring, vertex: idx })
        setCursor('grabbing')
        window.addEventListener('mouseup', endDrag)
        return
      }

      // 2) midpoint handle → insert vertex, then drag it
      const mid = query(e.point, ['bnd-mid'], 7)[0]
      if (mid) {
        const ring = Number(mid.properties?.ring)
        const insertAt = Number(mid.properties?.insertAt)
        e.preventDefault()
        const srcRing = ring === OUTER ? s.geom.outer : s.geom.islands[ring]
        if (!srcRing || srcRing.length === 0) return
        // Compute the midpoint from live geometry — the queried feature's
        // coordinates are quantized to the tile grid and drift at low zooms.
        const a = srcRing[(insertAt - 1 + srcRing.length) % srcRing.length]
        const b = srcRing[insertAt % srcRing.length]
        const midCoords: Pt = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
        const startGeom = cloneGeom(s.geom)
        pushHistory(startGeom)
        setGeom((g) => {
          if (!g) return g
          const next = cloneGeom(g)
          const target = ring === OUTER ? next.outer : next.islands[ring]
          if (!target) return g
          target.splice(insertAt, 0, midCoords)
          return next
        })
        dragRef.current = {
          kind: 'vertex', ring, vertex: insertAt,
          last: [e.lngLat.lng, e.lngLat.lat],
          startGeom, pushed: true, moved: true,
        }
        setSelection({ ring, vertex: insertAt })
        setCursor('grabbing')
        window.addEventListener('mouseup', endDrag)
        return
      }

      // 3) island body → select + drag whole island
      const island = query(e.point, ['bnd-islands-fill'], 0)[0]
      if (island) {
        const ring = Number(island.properties?.ring)
        e.preventDefault()
        dragRef.current = {
          kind: 'island', ring, vertex: 0,
          last: [e.lngLat.lng, e.lngLat.lat],
          startGeom: cloneGeom(s.geom), pushed: false, moved: false,
        }
        setSelection({ ring, vertex: null })
        setCursor('grabbing')
        window.addEventListener('mouseup', endDrag)
      }
    }

    const onMouseMove = (e: any) => {
      const s = live.current
      const drag = dragRef.current
      if (drag) {
        if (!drag.pushed) {
          pushHistory(drag.startGeom)
          drag.pushed = true
        }
        drag.moved = true
        if (drag.kind === 'vertex') {
          setRingVertex(drag.ring, drag.vertex, [e.lngLat.lng, e.lngLat.lat])
        } else {
          const dLng = e.lngLat.lng - drag.last[0]
          const dLat = e.lngLat.lat - drag.last[1]
          translateIsland(drag.ring, dLng, dLat)
          drag.last = [e.lngLat.lng, e.lngLat.lat]
        }
        return
      }
      if (s.drawing) {
        setCursorPos([e.lngLat.lng, e.lngLat.lat])
        setCursor('crosshair')
        return
      }
      if (!s.geom) {
        const hit = query(e.point, ['bnd-neighbors-fill'], 0)[0]
        setCursor(hit ? 'pointer' : '')
        return
      }
      if (query(e.point, ['bnd-vertex', 'bnd-mid'], 7).length > 0) setCursor('pointer')
      else if (query(e.point, ['bnd-islands-fill'], 0).length > 0) setCursor('move')
      else if (query(e.point, ['bnd-neighbors-fill'], 0).length > 0) setCursor('pointer')
      else setCursor('')
    }

    const onClick = (e: any) => {
      if (suppressClickRef.current) {
        suppressClickRef.current = false
        return
      }
      const s = live.current
      if (s.drawing) {
        setDrawing((d) => (d ? [...d, [e.lngLat.lng, e.lngLat.lat] as Pt] : d))
        return
      }
      // Vertex / island selection
      if (s.geom) {
        const vx = query(e.point, ['bnd-vertex'], 7)[0]
        if (vx) {
          setSelection({ ring: Number(vx.properties?.ring), vertex: Number(vx.properties?.idx) })
          return
        }
        const island = query(e.point, ['bnd-islands-fill'], 0)[0]
        if (island) {
          setSelection({ ring: Number(island.properties?.ring), vertex: null })
          return
        }
      }
      // Block picking (works both before a block is chosen and to switch)
      const nb = query(e.point, ['bnd-neighbors-fill'], 0)[0]
      if (nb) {
        const id = Number(nb.properties?.id)
        if (Number.isFinite(id)) selectBlock(id)
        return
      }
      setSelection(null)
    }

    const onDblClick = (e: any) => {
      if (live.current.drawing) {
        e.preventDefault()
        finishDrawing()
      }
    }

    map.on('mousedown', onMouseDown)
    map.on('mousemove', onMouseMove)
    map.on('click', onClick)
    map.on('dblclick', onDblClick)
    return () => {
      map.off('mousedown', onMouseDown)
      map.off('mousemove', onMouseMove)
      map.off('click', onClick)
      map.off('dblclick', onDblClick)
      window.removeEventListener('mouseup', endDrag)
    }
  }, [mapInst, pushHistory, setRingVertex, translateIsland, selectBlock, finishDrawing])

  // ── Keyboard ───────────────────────────────────────────────────────────────

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return
      const s = live.current
      if (e.key === 'Escape') {
        if (s.drawing) cancelDrawing()
        else setSelection(null)
        return
      }
      if (e.key === 'Enter' && s.drawing) {
        e.preventDefault()
        finishDrawing()
        return
      }
      if ((e.key === 'Delete' || e.key === 'Backspace') && s.geom && s.selection) {
        e.preventDefault()
        deleteSelected()
        return
      }
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'z' && !e.shiftKey) {
        e.preventDefault()
        undo()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [cancelDrawing, finishDrawing, deleteSelected, undo])

  // ── GeoJSON sources ────────────────────────────────────────────────────────

  const neighborsGeo = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: blocks
        .filter((b) => b.id !== selectedBlockId && b.boundary && (b.boundary.coordinates?.[0]?.length ?? 0) >= 4)
        .map((b) => ({
          type: 'Feature' as const,
          properties: { id: b.id, code: b.code || `#${b.id}` },
          geometry: b.boundary as GeoPolygon,
        })),
    }),
    [blocks, selectedBlockId]
  )

  const outerGeo = useMemo(() => {
    if (!geom || geom.outer.length < 3) return null
    return {
      type: 'Feature' as const,
      properties: {},
      geometry: { type: 'Polygon' as const, coordinates: [closeRing(geom.outer)] },
    }
  }, [geom])

  const islandsGeo = useMemo(
    () => ({
      type: 'FeatureCollection' as const,
      features: (geom?.islands || [])
        .filter((r) => r.length >= 3)
        .map((r, i) => ({
          type: 'Feature' as const,
          properties: {
            ring: i,
            selected: selection?.ring === i && selection?.vertex === null,
            outside: validation.outsideIslands.includes(i),
          },
          geometry: { type: 'Polygon' as const, coordinates: [closeRing(r)] },
        })),
    }),
    [geom, selection, validation.outsideIslands]
  )

  const handlesGeo = useMemo(() => {
    if (!geom || drawing) return { type: 'FeatureCollection' as const, features: [] }
    const features: any[] = []
    const pushRing = (ring: Ring, ringIdx: number) => {
      ring.forEach((p, idx) => {
        features.push({
          type: 'Feature' as const,
          properties: {
            ring: ringIdx,
            idx,
            selected: selection?.ring === ringIdx && selection?.vertex === idx,
            kind: ringIdx === OUTER ? 'outer' : 'island',
          },
          geometry: { type: 'Point' as const, coordinates: p },
        })
      })
    }
    pushRing(geom.outer, OUTER)
    geom.islands.forEach((r, i) => pushRing(r, i))
    return { type: 'FeatureCollection' as const, features }
  }, [geom, selection, drawing])

  const midpointsGeo = useMemo(() => {
    if (!geom || drawing) return { type: 'FeatureCollection' as const, features: [] }
    const features: any[] = []
    const pushRing = (ring: Ring, ringIdx: number) => {
      ring.forEach((p, idx) => {
        const q = ring[(idx + 1) % ring.length]
        features.push({
          type: 'Feature' as const,
          properties: {
            ring: ringIdx,
            insertAt: idx + 1,
            kind: ringIdx === OUTER ? 'outer' : 'island',
          },
          geometry: { type: 'Point' as const, coordinates: [(p[0] + q[0]) / 2, (p[1] + q[1]) / 2] },
        })
      })
    }
    pushRing(geom.outer, OUTER)
    geom.islands.forEach((r, i) => pushRing(r, i))
    return { type: 'FeatureCollection' as const, features }
  }, [geom, drawing])

  const drawGeo = useMemo(() => {
    if (!drawing) return null
    const pts = cursorPos ? [...drawing, cursorPos] : drawing
    const line = {
      type: 'Feature' as const,
      properties: {},
      geometry: { type: 'LineString' as const, coordinates: pts.length >= 2 ? pts : [] },
    }
    const fill =
      pts.length >= 3
        ? {
            type: 'Feature' as const,
            properties: {},
            geometry: { type: 'Polygon' as const, coordinates: [[...pts, pts[0]]] },
          }
        : null
    const points = {
      type: 'FeatureCollection' as const,
      features: drawing.map((p, i) => ({
        type: 'Feature' as const,
        properties: { idx: i },
        geometry: { type: 'Point' as const, coordinates: p },
      })),
    }
    return { line, fill, points }
  }, [drawing, cursorPos])

  // ── Block list (side panel) ────────────────────────────────────────────────

  const filteredBlocks = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return blocks
    return blocks.filter(
      (b) => (b.code || '').toLowerCase().includes(q) || b.name.toLowerCase().includes(q)
    )
  }, [blocks, search])

  const selectionLabel = useMemo(() => {
    if (!selection) return null
    if (selection.ring === OUTER) return `External boundary — vertex ${selection.vertex! + 1}`
    if (selection.vertex === null) return `Island #${selection.ring + 1} (whole island)`
    return `Island #${selection.ring + 1} — vertex ${selection.vertex + 1}`
  }, [selection])

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
    <div className="grid grid-cols-[1fr_340px] gap-4 h-[calc(100vh-150px)] min-h-[520px]">
      {/* Map */}
      <div className="rounded-lg overflow-hidden border border-neutral-800 relative">
        <BaseMap
          height="100%"
          initialViewState={{ longitude: -87.72, latitude: 41.86, zoom: 14 }}
          mapRef={handleMapRef}
        >
          {/* Neighbor blocks — tiling context, clickable to pick/switch */}
          <Source id="bnd-neighbors" type="geojson" data={neighborsGeo}>
            <Layer
              id="bnd-neighbors-fill"
              type="fill"
              paint={{ 'fill-color': NEIGHBOR_COLOR, 'fill-opacity': 0.1 }}
            />
            <Layer
              id="bnd-neighbors-line"
              type="line"
              paint={{ 'line-color': NEIGHBOR_COLOR, 'line-width': 1.5, 'line-dasharray': [3, 2] }}
            />
            <Layer
              id="bnd-neighbors-label"
              type="symbol"
              layout={{ 'text-field': ['get', 'code'], 'text-size': 10, 'text-allow-overlap': false }}
              paint={{ 'text-color': '#ffffff', 'text-halo-color': '#000000', 'text-halo-width': 1 }}
            />
          </Source>

          {/* External boundary of the edited block */}
          {outerGeo && (
            <Source id="bnd-outer" type="geojson" data={outerGeo}>
              <Layer id="bnd-outer-fill" type="fill" paint={{ 'fill-color': OUTER_COLOR, 'fill-opacity': 0.08 }} />
              <Layer id="bnd-outer-line" type="line" paint={{ 'line-color': OUTER_COLOR, 'line-width': 2.5 }} />
            </Source>
          )}

          {/* Internal islands */}
          <Source id="bnd-islands" type="geojson" data={islandsGeo}>
            <Layer
              id="bnd-islands-fill"
              type="fill"
              paint={{
                'fill-color': ['case', ['get', 'outside'], '#EF4444', ISLAND_COLOR],
                'fill-opacity': ['case', ['get', 'selected'], 0.5, 0.3],
              }}
            />
            <Layer
              id="bnd-islands-line"
              type="line"
              paint={{
                'line-color': ['case', ['get', 'outside'], '#EF4444', ISLAND_COLOR],
                'line-width': ['case', ['get', 'selected'], 3, 1.5],
              }}
            />
          </Source>

          {/* Draft island while drawing */}
          {drawGeo && (
            <Source id="bnd-draw" type="geojson" data={{ type: 'FeatureCollection', features: drawGeo.fill ? [drawGeo.fill, drawGeo.line] : [drawGeo.line] }}>
              <Layer id="bnd-draw-fill" type="fill" filter={['==', ['geometry-type'], 'Polygon']} paint={{ 'fill-color': DRAW_COLOR, 'fill-opacity': 0.15 }} />
              <Layer id="bnd-draw-line" type="line" filter={['==', ['geometry-type'], 'LineString']} paint={{ 'line-color': DRAW_COLOR, 'line-width': 2, 'line-dasharray': [2, 1.5] }} />
            </Source>
          )}
          {drawGeo && (
            <Source id="bnd-draw-points" type="geojson" data={drawGeo.points}>
              <Layer
                id="bnd-draw-points-layer"
                type="circle"
                paint={{ 'circle-radius': 5, 'circle-color': '#ffffff', 'circle-stroke-color': DRAW_COLOR, 'circle-stroke-width': 2 }}
              />
            </Source>
          )}

          {/* Edge midpoints (click to insert a vertex) */}
          <Source id="bnd-mid-src" type="geojson" data={midpointsGeo}>
            <Layer
              id="bnd-mid"
              type="circle"
              paint={{
                'circle-radius': 4,
                'circle-color': '#ffffff',
                'circle-opacity': 0.55,
                'circle-stroke-color': ['match', ['get', 'kind'], 'outer', OUTER_COLOR, ISLAND_COLOR],
                'circle-stroke-width': 1.5,
                'circle-stroke-opacity': 0.55,
              }}
            />
          </Source>

          {/* Vertex handles */}
          <Source id="bnd-vertex-src" type="geojson" data={handlesGeo}>
            <Layer
              id="bnd-vertex"
              type="circle"
              paint={{
                'circle-radius': ['case', ['get', 'selected'], 8, 6],
                'circle-color': [
                  'case',
                  ['get', 'selected'],
                  ['match', ['get', 'kind'], 'outer', OUTER_COLOR, ISLAND_COLOR],
                  '#ffffff',
                ],
                'circle-stroke-color': ['match', ['get', 'kind'], 'outer', OUTER_COLOR, ISLAND_COLOR],
                'circle-stroke-width': 2,
              }}
            />
          </Source>
        </BaseMap>

        {/* Map legend */}
        <div className="absolute bottom-8 left-3 bg-neutral-900/90 backdrop-blur-sm rounded-lg p-3 text-xs shadow-md z-10 border border-neutral-700">
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full inline-block" style={{ backgroundColor: OUTER_COLOR }} />
              <span className="text-neutral-300">External boundary</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full inline-block" style={{ backgroundColor: ISLAND_COLOR }} />
              <span className="text-neutral-300">Internal islands (excluded from scans)</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full inline-block border border-dashed" style={{ borderColor: NEIGHBOR_COLOR, backgroundColor: 'transparent' }} />
              <span className="text-neutral-300">Neighbor blocks (click to edit)</span>
            </div>
          </div>
        </div>

        {/* Draw-mode banner */}
        {drawing && (
          <div className="absolute top-3 left-1/2 -translate-x-1/2 bg-amber-600/95 text-white text-xs font-medium rounded-lg px-4 py-2 shadow-lg z-10">
            Drawing island — click to place points ({drawing.length}), Enter or double-click to finish, Esc to cancel
          </div>
        )}
      </div>

      {/* Side panel */}
      <div className="space-y-4 overflow-y-auto pr-1">
        {!selectedBlock ? (
          <>
            <div>
              <h2 className="text-lg font-semibold text-white">Pick a block</h2>
              <p className="text-xs text-neutral-500 mt-1">
                Click a block on the map or in the list below to open its boundaries for editing.
              </p>
            </div>
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search by code or name…"
              className="w-full px-3 py-2 rounded-md bg-neutral-900 border border-neutral-700 text-white text-sm focus:outline-none focus:border-neutral-500"
            />
            <div className="space-y-1">
              {filteredBlocks.length === 0 && (
                <div className="text-neutral-500 text-sm py-4">No blocks match.</div>
              )}
              {filteredBlocks.map((b) => {
                const editable = (b.boundary?.coordinates?.[0]?.length ?? 0) >= 4
                return (
                  <button
                    key={b.id}
                    onClick={() => selectBlock(b.id)}
                    disabled={!editable}
                    className="w-full text-left px-3 py-2 rounded-md text-sm transition-colors bg-neutral-900 hover:bg-neutral-800 disabled:opacity-40 disabled:cursor-not-allowed border border-neutral-800"
                  >
                    <span className="font-mono text-green-400">{b.code || `#${b.id}`}</span>
                    <span className="text-neutral-400 ml-2">{b.name}</span>
                    {!editable && <span className="text-neutral-600 text-xs ml-2">(no boundary)</span>}
                    {editable && (b.innerBoundary?.coordinates?.length ?? 0) > 0 && (
                      <span className="text-amber-600/80 text-xs ml-2">
                        {b.innerBoundary!.coordinates.length} island{b.innerBoundary!.coordinates.length === 1 ? '' : 's'}
                      </span>
                    )}
                  </button>
                )
              })}
            </div>
          </>
        ) : (
          <>
            <div className="flex items-start justify-between gap-2">
              <div>
                <h2 className="text-lg font-semibold text-white flex items-center gap-2">
                  <span className="font-mono text-green-400">{selectedBlock.code || `#${selectedBlock.id}`}</span>
                  {dirty && (
                    <span className="inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-amber-400">
                      <span className="w-1.5 h-1.5 rounded-full bg-amber-400 inline-block" /> Unsaved
                    </span>
                  )}
                </h2>
                <p className="text-xs text-neutral-500">{selectedBlock.name}</p>
              </div>
              <button
                onClick={deselectBlock}
                className="text-xs text-neutral-400 hover:text-white transition-colors shrink-0"
              >
                Change block
              </button>
            </div>

            {/* Stats */}
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="bg-neutral-900 rounded-md p-2">
                <div className="text-neutral-500">Outer vertices</div>
                <div className="text-white font-mono">{geom?.outer.length ?? 0}</div>
              </div>
              <div className="bg-neutral-900 rounded-md p-2">
                <div className="text-neutral-500">Islands</div>
                <div className="text-white font-mono">{geom?.islands.length ?? 0}</div>
              </div>
            </div>

            {selectionLabel && (
              <div className="text-xs text-neutral-400 bg-neutral-900 rounded-md px-3 py-2 flex items-center justify-between gap-2">
                <span>
                  Selected: <span className="text-white">{selectionLabel}</span>
                </span>
                <button
                  onClick={deleteSelected}
                  className="text-red-400 hover:text-red-300 transition-colors font-medium"
                >
                  Delete
                </button>
              </div>
            )}

            {/* Island tools */}
            <div className="space-y-2">
              {!drawing ? (
                <button
                  onClick={startDrawing}
                  className="w-full py-2 rounded-md bg-amber-700 text-white text-sm font-medium hover:bg-amber-600 transition-colors"
                >
                  + New island (draw)
                </button>
              ) : (
                <div className="flex gap-2">
                  <button
                    onClick={finishDrawing}
                    disabled={dedupeRing(drawing).length < 3}
                    className="flex-1 py-2 rounded-md bg-amber-600 text-white text-sm font-medium hover:bg-amber-500 disabled:opacity-40 transition-colors"
                  >
                    Finish island ({drawing.length} pts)
                  </button>
                  <button
                    onClick={cancelDrawing}
                    className="flex-1 py-2 rounded-md bg-neutral-800 text-neutral-300 text-sm hover:bg-neutral-700 transition-colors"
                  >
                    Cancel
                  </button>
                </div>
              )}

              {/* Regenerate from OSM */}
              <div className="flex items-center gap-2">
                <button
                  onClick={handleRegenerate}
                  disabled={!selectedBlock.routeId || regenLoading || drawing !== null}
                  title={selectedBlock.routeId ? 'Rebuild islands from OSM building frontages (saves immediately)' : 'This block has no route — regeneration unavailable'}
                  className="flex-1 py-2 rounded-md bg-neutral-800 text-neutral-200 text-xs font-medium hover:bg-neutral-700 disabled:opacity-40 transition-colors"
                >
                  {regenLoading ? 'Regenerating…' : 'Regenerate islands from OSM'}
                </button>
                <label className="flex items-center gap-1 text-xs text-neutral-500">
                  <input
                    type="number"
                    min={1}
                    max={30}
                    value={setbackMeters}
                    onChange={(e) => setSetbackMeters(Math.max(1, Math.min(30, Number(e.target.value) || 8)))}
                    className="w-14 px-2 py-1.5 rounded-md bg-neutral-900 border border-neutral-700 text-white text-xs focus:outline-none focus:border-neutral-500"
                  />
                  m
                </label>
              </div>
            </div>

            <hr className="border-neutral-800" />

            {/* Validation warnings */}
            {saveBlockers.map((msg) => (
              <div key={msg} className="text-xs text-red-400 bg-red-950/40 border border-red-900/50 rounded-md px-3 py-2">
                {msg}
              </div>
            ))}
            {validation.overlapPairs.length > 0 && (
              <div className="text-xs text-amber-400 bg-amber-950/40 border border-amber-900/50 rounded-md px-3 py-2">
                Overlapping islands: {validation.overlapPairs.map(([a, b]) => `#${a + 1}∕#${b + 1}`).join(', ')} — consider merging or separating them.
              </div>
            )}
            {validation.invalidGeometry && (
              <div className="text-xs text-amber-400 bg-amber-950/40 border border-amber-900/50 rounded-md px-3 py-2">
                Some geometry could not be validated (possible self-intersection) — double-check the shapes before saving.
              </div>
            )}

            {/* History / save */}
            <div className="flex gap-2">
              <button
                onClick={undo}
                disabled={histLen === 0}
                className="flex-1 py-1.5 text-xs rounded-md bg-neutral-800 text-neutral-300 hover:bg-neutral-700 disabled:opacity-30 transition-colors"
              >
                Undo (⌘Z)
              </button>
              <button
                onClick={resetToSaved}
                disabled={!dirty}
                className="flex-1 py-1.5 text-xs rounded-md bg-neutral-800 text-neutral-300 hover:bg-neutral-700 disabled:opacity-30 transition-colors"
              >
                Reset to saved
              </button>
            </div>

            <button
              onClick={handleSave}
              disabled={saving || !dirty || saveBlockers.length > 0}
              className="w-full py-2.5 rounded-md bg-green-600 text-white text-sm font-medium hover:bg-green-500 disabled:opacity-50 transition-colors"
            >
              {saving ? 'Saving…' : dirty ? 'Save boundaries' : 'Saved'}
            </button>

            {/* Help strip */}
            <div className="text-[11px] leading-relaxed text-neutral-500 bg-neutral-900/60 rounded-md px-3 py-2 space-y-1">
              <div><span className="text-neutral-300">Drag</span> a vertex handle to reshape · drag an <span className="text-neutral-300">island body</span> to move it</div>
              <div>Click a faint <span className="text-neutral-300">midpoint</span> dot to add a vertex on that edge</div>
              <div>Click a vertex or island, then <span className="text-neutral-300">Delete</span> to remove it (rings keep ≥ 3 points)</div>
              <div><span className="text-neutral-300">Enter</span>/double-click closes a new island · <span className="text-neutral-300">Esc</span> cancels · <span className="text-neutral-300">⌘Z</span> undoes</div>
              <div>Expanding over a neighbor block thins the neighbor on save (never below 40% of its area)</div>
            </div>
          </>
        )}

        {/* Status toast */}
        {status && (
          <div
            className={`text-xs rounded-md px-3 py-2 border ${
              status.kind === 'ok'
                ? 'text-green-400 bg-green-950/40 border-green-900/50'
                : status.kind === 'warn'
                  ? 'text-amber-400 bg-amber-950/40 border-amber-900/50'
                  : 'text-red-400 bg-red-950/40 border-red-900/50'
            }`}
          >
            {status.text}
          </div>
        )}
      </div>
    </div>
  )
}
