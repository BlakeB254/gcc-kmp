'use client'

import * as React from 'react'
import { MapPin, Check, Minus, Plus, DollarSign } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { cn, formatCurrency } from '@/lib/utils'
import { getTierFromBlockCount, getTierBenefits } from './TierCard'

/** Format street name to include 1500 block number for Roosevelt Rd */
function formatStreetName(streetName: string | undefined | null): string {
  if (!streetName) return ''
  if (streetName.includes('1500') || !streetName.toLowerCase().includes('roosevelt')) {
    return streetName
  }
  return streetName.replace(/^W\s+Roosevelt/i, '1500 W Roosevelt')
}

interface Block {
  id: string
  code: string
  location: {
    streetName: string
    fromCorner: string
    toCorner: string
    side: 'north' | 'south'
  }
  status: 'available' | 'unfunded' | 'claimed' | 'completed'
  costPerClean: number // in cents
}

interface BlockSelectorProps {
  blocks: Block[]
  selectedBlockIds: string[]
  onSelectionChange: (blockIds: string[]) => void
  pricePerBlock?: number // in cents, default $100
  className?: string
}

export function BlockSelector({
  blocks,
  selectedBlockIds,
  onSelectionChange,
  pricePerBlock = 10000,
  className,
}: BlockSelectorProps) {
  const availableBlocks = blocks.filter(
    (b) => b.status === 'unfunded' || b.status === 'available'
  )
  const selectedCount = selectedBlockIds.length
  const totalAmount = selectedCount * pricePerBlock
  const currentTier = getTierFromBlockCount(selectedCount)

  const toggleBlock = (blockId: string) => {
    if (selectedBlockIds.includes(blockId)) {
      onSelectionChange(selectedBlockIds.filter((id) => id !== blockId))
    } else {
      onSelectionChange([...selectedBlockIds, blockId])
    }
  }

  const selectAll = () => {
    onSelectionChange(availableBlocks.map((b) => b.id))
  }

  const clearAll = () => {
    onSelectionChange([])
  }

  const adjustCount = (delta: number) => {
    const newCount = Math.max(0, Math.min(availableBlocks.length, selectedCount + delta))
    if (newCount > selectedCount) {
      // Add more blocks
      const unselected = availableBlocks.filter((b) => !selectedBlockIds.includes(b.id))
      const toAdd = unselected.slice(0, newCount - selectedCount).map((b) => b.id)
      onSelectionChange([...selectedBlockIds, ...toAdd])
    } else if (newCount < selectedCount) {
      // Remove blocks
      onSelectionChange(selectedBlockIds.slice(0, newCount))
    }
  }

  return (
    <div className={cn('space-y-6', className)}>
      {/* Summary card */}
      <Card className="bg-gradient-to-br from-brand-green-50 to-amber-50">
        <CardContent className="p-6">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            {/* Counter controls */}
            <div className="flex items-center gap-4">
              <Button
                variant="outline"
                size="icon"
                onClick={() => adjustCount(-1)}
                disabled={selectedCount === 0}
              >
                <Minus className="h-4 w-4" />
              </Button>
              <div className="text-center">
                <span className="text-4xl font-bold">{selectedCount}</span>
                <p className="text-sm text-muted-foreground">
                  {selectedCount === 1 ? 'block' : 'blocks'} selected
                </p>
              </div>
              <Button
                variant="outline"
                size="icon"
                onClick={() => adjustCount(1)}
                disabled={selectedCount === availableBlocks.length}
              >
                <Plus className="h-4 w-4" />
              </Button>
            </div>

            {/* Total and tier */}
            <div className="text-center sm:text-right">
              <div className="flex items-center justify-center gap-2 sm:justify-end">
                <DollarSign className="h-5 w-5 text-brand-green-600" />
                <span className="text-3xl font-bold text-brand-green-700">
                  {formatCurrency(totalAmount)}
                </span>
              </div>
              {selectedCount > 0 && (
                <Badge className="mt-1 bg-brand-green-100 text-brand-green-800">
                  {currentTier.charAt(0).toUpperCase() + currentTier.slice(1)} Sponsor
                </Badge>
              )}
            </div>
          </div>

          {/* Quick actions */}
          <div className="mt-4 flex gap-2">
            <Button
              variant="ghost"
              size="sm"
              onClick={selectAll}
              disabled={selectedCount === availableBlocks.length}
            >
              Select All ({availableBlocks.length})
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={clearAll}
              disabled={selectedCount === 0}
            >
              Clear
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Block list */}
      <div className="space-y-2">
        <h3 className="font-medium">Available Blocks</h3>
        <div className="grid gap-2">
          {availableBlocks.map((block) => {
            const isSelected = selectedBlockIds.includes(block.id)

            return (
              <button
                key={block.id}
                onClick={() => toggleBlock(block.id)}
                className={cn(
                  'flex items-center gap-4 rounded-lg border p-4 text-left transition-all',
                  isSelected
                    ? 'border-primary bg-primary/5 ring-1 ring-primary'
                    : 'border-border hover:border-primary/50 hover:bg-muted/50'
                )}
              >
                {/* Checkbox indicator */}
                <div
                  className={cn(
                    'flex h-6 w-6 shrink-0 items-center justify-center rounded border-2 transition-colors',
                    isSelected
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-muted-foreground/30'
                  )}
                >
                  {isSelected && <Check className="h-4 w-4" />}
                </div>

                {/* Block info */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{block.code}</span>
                    <Badge
                      variant={block.status === 'unfunded' ? 'warning' : 'available'}
                    >
                      {block.status === 'unfunded' ? 'Needs Sponsor' : 'Available'}
                    </Badge>
                  </div>
                  <p className="text-sm text-muted-foreground truncate">
                    {formatStreetName(block.location.streetName)}: {block.location.fromCorner} to{' '}
                    {block.location.toCorner}
                  </p>
                </div>

                {/* Price */}
                <span className="font-semibold text-brand-green-700">
                  {formatCurrency(pricePerBlock)}
                </span>
              </button>
            )
          })}
        </div>
      </div>

      {availableBlocks.length === 0 && (
        <div className="rounded-lg border border-dashed p-8 text-center">
          <MapPin className="mx-auto h-12 w-12 text-muted-foreground/50" />
          <h3 className="mt-4 font-semibold">New blocks drop every week</h3>
          <p className="mt-2 text-sm text-muted-foreground max-w-md mx-auto">
            No blocks show up in this view right now, but we&apos;re always accepting new sponsorships —
            choose a flexible block count below to reserve the next available cycle, or contact us to schedule a
            custom sponsorship for a specific day, week, or month.
          </p>
        </div>
      )}
    </div>
  )
}
