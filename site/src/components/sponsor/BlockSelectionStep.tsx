'use client'

import { forwardRef } from 'react'
import Link from 'next/link'
import dynamic from 'next/dynamic'
import { MapPin, Check, Users, X, Calendar } from 'lucide-react'
import { StepHeader } from './StepHeader'
import type { SponsorBlock, SponsorshipMode } from './types'
import { GENERAL_BLOCK_COUNTS, formatStreetName } from './types'
import { NORTH_LAWNDALE_BLOCKS, type BlockDefinition } from '@/data/blocks'

/**
 * Convert a static BlockDefinition (from the hardcoded route data) into
 * the SponsorBlock shape used by the map + selection flow. We mark these
 * with status='available' so they render on the map and can be selected
 * — the `upcoming-` id prefix signals to the backend that this is a
 * route-reservation rather than a funded-block claim, and the server
 * can create or reserve the block on demand at checkout.
 */
function fallbackRouteBlocks(): SponsorBlock[] {
  return NORTH_LAWNDALE_BLOCKS.map((b: BlockDefinition) => ({
    id: `upcoming-${b.code}`,
    code: b.code,
    streetName: b.streetName,
    fromCorner: b.fromCorner,
    toCorner: b.toCorner,
    status: 'available' as const,
    location: {
      neighborhood: 'North Lawndale',
      boundary: b.geo.polygon,
      center: b.geo.center,
    },
    geo: {
      center: { coordinates: b.geo.center.coordinates },
      polygon: { coordinates: b.geo.polygon.coordinates },
    },
  }))
}

const SponsorBlockMap = dynamic(
  () => import('./SponsorBlockMap').then(mod => ({ default: mod.SponsorBlockMap })),
  {
    ssr: false,
    loading: () => (
      <div className="h-[450px] bg-neutral-100 rounded-xl animate-pulse flex items-center justify-center">
        <span className="text-neutral-400">Loading map...</span>
      </div>
    ),
  }
)

interface BlockSelectionStepProps {
  mode: SponsorshipMode
  blocks: SponsorBlock[]
  selectedBlocks: SponsorBlock[]
  isLoading: boolean
  generalBlockCount: number
  onToggleBlock: (block: SponsorBlock) => void
  onGeneralCountChange: (count: number) => void
}

export const BlockSelectionStep = forwardRef<HTMLDivElement, BlockSelectionStepProps>(
  function BlockSelectionStep(
    { mode, blocks, selectedBlocks, isLoading, generalBlockCount, onToggleBlock, onGeneralCountChange },
    ref,
  ) {
    const availableBlockCount = blocks.length
    const canUseGeneralMode = generalBlockCount <= availableBlockCount

    return (
      <section className="mb-16" ref={ref}>
        <StepHeader
          step={2}
          title={mode === 'specific' ? 'Select your blocks' : 'How many blocks?'}
          description={mode === 'specific' ? 'Click blocks on the map or list below' : 'Choose how many blocks GCC should assign'}
        />

        {mode === 'specific' ? (
          <SpecificBlockPicker
            blocks={blocks}
            selectedBlocks={selectedBlocks}
            isLoading={isLoading}
            onToggleBlock={onToggleBlock}
          />
        ) : (
          <GeneralBlockPicker
            generalBlockCount={generalBlockCount}
            availableBlockCount={availableBlockCount}
            canUseGeneralMode={canUseGeneralMode}
            onCountChange={onGeneralCountChange}
          />
        )}
      </section>
    )
  }
)

/* ─── Specific mode: map + badges + list ─── */

function SpecificBlockPicker({
  blocks,
  selectedBlocks,
  isLoading,
  onToggleBlock,
}: {
  blocks: SponsorBlock[]
  selectedBlocks: SponsorBlock[]
  isLoading: boolean
  onToggleBlock: (block: SponsorBlock) => void
}) {
  if (isLoading) {
    return (
      <div className="bg-white rounded-xl border p-8 text-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-brand-green-600 mx-auto" />
        <p className="text-neutral-500 mt-3">Loading available blocks...</p>
      </div>
    )
  }

  // When the db has no blocks ready right now, fall back to the hardcoded
  // North Lawndale route polygons so sponsors can always see the map and
  // reserve an upcoming block. The empty-state banner above the map makes
  // clear these are route-reservations, not live-funded blocks.
  const isFallback = blocks.length === 0
  const displayBlocks = isFallback ? fallbackRouteBlocks() : blocks

  return (
    <div className="space-y-5">
      {isFallback && (
        <div className="rounded-xl border border-brand-green-200 bg-brand-green-50 p-4 flex items-start gap-3">
          <Calendar className="w-5 h-5 text-brand-green-700 flex-shrink-0 mt-0.5" />
          <div className="text-sm text-brand-green-900">
            <p className="font-semibold mb-1">New blocks are released on a rolling schedule</p>
            <p className="text-brand-green-800">
              These are the planned cleaning routes across our North Lawndale launch area. Select any block on the map
              to reserve your sponsorship for its next cleaning cycle — we schedule blocks weekly, and your sponsorship
              locks in the next available date once the block is activated.
            </p>
          </div>
        </div>
      )}

      {/* Map — same green polygon + route line visual as home page */}
      <div className="bg-white rounded-xl border overflow-hidden shadow-sm">
        <div className="px-4 py-3 border-b bg-neutral-50">
          <p className="text-sm text-neutral-600 flex items-center gap-2">
            <MapPin className="w-4 h-4 text-brand-green-600" />
            {isFallback
              ? 'Click a route polygon to reserve it — selected routes turn gold'
              : 'Click the checkboxes inside each block to select — selected blocks turn gold'}
          </p>
        </div>
        <SponsorBlockMap
          blocks={displayBlocks}
          selectedBlockIds={selectedBlocks.map(b => b.id)}
          onToggleBlock={onToggleBlock}
          height="450px"
        />
      </div>

      {/* Selected badges */}
      {selectedBlocks.length > 0 && (
        <div className="bg-brand-green-50 rounded-xl p-4 border border-brand-green-200">
          <p className="text-sm font-semibold text-brand-green-800 mb-2">
            {selectedBlocks.length} block{selectedBlocks.length !== 1 ? 's' : ''} selected — ${selectedBlocks.length * 100}
          </p>
          <div className="flex flex-wrap gap-2">
            {selectedBlocks.map((block) => (
              <span
                key={block.id}
                className="inline-flex items-center gap-2 bg-white text-brand-green-800 px-3 py-1.5 rounded-full text-sm border border-brand-green-200 shadow-sm"
              >
                <MapPin className="w-3 h-3" />
                {block.fromCorner} → {block.toCorner}
                <button
                  onClick={() => onToggleBlock(block)}
                  className="hover:text-red-500 transition-colors"
                  aria-label={`Remove block ${block.fromCorner} to ${block.toCorner}`}
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Block list */}
      <div className="space-y-2 max-h-[200px] overflow-y-auto bg-white rounded-xl border p-3" role="listbox" aria-label="Available blocks">
        {displayBlocks.map((block) => {
          const isSelected = selectedBlocks.some(b => b.id === block.id)
          return (
            <button
              key={block.id}
              onClick={() => onToggleBlock(block)}
              aria-pressed={isSelected}
              aria-label={`${isSelected ? 'Deselect' : 'Select'} block ${block.fromCorner} to ${block.toCorner} on ${formatStreetName(block.streetName)}`}
              className={`w-full text-left px-4 py-3 rounded-lg border transition-all duration-150 ${
                isSelected
                  ? 'border-brand-green-500 bg-brand-green-50 shadow-sm'
                  : 'border-neutral-100 bg-white hover:border-brand-green-300 hover:bg-neutral-50'
              }`}
            >
              <div className="flex items-center justify-between">
                <div>
                  <p className="font-medium text-neutral-900">{block.fromCorner} to {block.toCorner}</p>
                  <p className="text-xs text-neutral-500">{formatStreetName(block.streetName)}</p>
                </div>
                <div className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-all ${
                  isSelected ? 'border-brand-green-500 bg-brand-green-500' : 'border-neutral-300'
                }`}>
                  {isSelected && <Check className="w-3 h-3 text-white" />}
                </div>
              </div>
            </button>
          )
        })}
      </div>
    </div>
  )
}

/* ─── General mode: count picker ─── */

function GeneralBlockPicker({
  generalBlockCount,
  availableBlockCount,
  onCountChange,
}: {
  generalBlockCount: number
  availableBlockCount: number
  canUseGeneralMode: boolean
  onCountChange: (count: number) => void
}) {
  const needsScheduling = generalBlockCount > availableBlockCount
  return (
    <div className="bg-white rounded-xl border p-6 shadow-sm">
      <p className="text-sm text-neutral-600 mb-5">
        Choose how many blocks to sponsor. GCC assigns the blocks based on community need —
        we schedule new cleaning blocks every week, so any count locks in your sponsorship
        for the next available cycle.
      </p>
      <div className="grid grid-cols-4 gap-3 mb-5">
        {GENERAL_BLOCK_COUNTS.map((count) => (
          <button
            key={count}
            onClick={() => onCountChange(count)}
            aria-pressed={generalBlockCount === count}
            aria-label={`${count} block${count !== 1 ? 's' : ''} — $${count * 100}`}
            className={`py-4 rounded-xl font-bold text-xl transition-all ${
              generalBlockCount === count
                ? 'bg-brand-green-500 text-white shadow-lg scale-105'
                : 'bg-neutral-100 text-neutral-700 hover:bg-neutral-200'
            }`}
          >
            {count}
          </button>
        ))}
      </div>
      {needsScheduling && (
        <div className="bg-brand-green-50 border border-brand-green-200 rounded-lg p-3 text-sm text-brand-green-800 mb-4 flex items-start gap-2">
          <Calendar className="w-4 h-4 flex-shrink-0 mt-0.5" />
          <p>
            {availableBlockCount > 0
              ? `${availableBlockCount} block${availableBlockCount === 1 ? '' : 's'} ready to assign now. The other ${generalBlockCount - availableBlockCount} will roll into the next weekly cycle as new blocks are scheduled.`
              : `New blocks are released every week — your sponsorship reserves the next ${generalBlockCount} block${generalBlockCount === 1 ? '' : 's'} GCC schedules in your area.`}
          </p>
        </div>
      )}
      <div className="bg-brand-green-50 rounded-xl p-4 mt-4 flex items-center gap-3">
        <Users className="w-5 h-5 text-brand-green-700 flex-shrink-0" />
        <div>
          <p className="font-semibold text-brand-green-800">
            {generalBlockCount} block{generalBlockCount !== 1 ? 's' : ''} — ${generalBlockCount * 100}
          </p>
          <p className="text-sm text-brand-green-600">You&apos;ll see assigned blocks at checkout</p>
        </div>
      </div>
    </div>
  )
}
