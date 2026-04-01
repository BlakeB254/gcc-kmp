'use client'

import * as React from 'react'
import { MapPin, Clock, DollarSign, Navigation } from 'lucide-react'
import { Card, CardContent, CardFooter, CardHeader } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { cn, formatCurrency } from '@/lib/utils'

/** Format street name to include 1500 block number for Roosevelt Rd */
function formatStreetName(streetName: string | undefined | null): string {
  if (!streetName) return ''
  if (streetName.includes('1500') || !streetName.toLowerCase().includes('roosevelt')) {
    return streetName
  }
  return streetName.replace(/^W\s+Roosevelt/i, '1500 W Roosevelt')
}

interface JobClaimCardProps {
  block: {
    id: string
    code: string
    location: {
      streetName: string
      fromCorner: string
      toCorner: string
      side: 'north' | 'south'
    }
    status: 'available' | 'claimed' | 'in_progress' | 'completed'
    payoutAmount: number // in cents
    difficulty: 'easy' | 'standard' | 'challenging'
    checkpoints?: { name: string }[]
  }
  distance?: number // in miles
  estimatedTime?: number // in minutes
  onClaim?: () => void
  onNavigate?: () => void
  isLoading?: boolean
  className?: string
}

const difficultyConfig = {
  easy: { label: 'Easy', color: 'bg-brand-green-100 text-brand-green-800' },
  standard: { label: 'Standard', color: 'bg-brand-green-100 text-brand-green-800' },
  challenging: { label: 'Challenging', color: 'bg-brand-green-100 text-brand-green-800' },
}

export function JobClaimCard({
  block,
  distance,
  estimatedTime = 45,
  onClaim,
  onNavigate,
  isLoading,
  className,
}: JobClaimCardProps) {
  const isClaimed = block.status === 'claimed' || block.status === 'in_progress'
  const isAvailable = block.status === 'available'
  const diffConfig = difficultyConfig[block.difficulty]

  return (
    <Card className={cn('overflow-hidden', className)}>
      <CardHeader className="pb-3">
        <div className="flex items-start justify-between">
          <div>
            <h3 className="text-lg font-semibold">{block.code}</h3>
            <p className="text-sm text-muted-foreground">
              {formatStreetName(block.location.streetName)}
            </p>
          </div>
          <Badge className={diffConfig.color}>{diffConfig.label}</Badge>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {/* Location details */}
        <div className="flex items-start gap-3">
          <MapPin className="mt-0.5 h-5 w-5 flex-shrink-0 text-muted-foreground" />
          <div className="text-sm">
            <p className="font-medium">
              {block.location.fromCorner} to {block.location.toCorner}
            </p>
            <p className="text-muted-foreground">
              {block.location.side === 'north' ? 'North side' : 'South side'} of street
            </p>
          </div>
        </div>

        {/* Stats row */}
        <div className="flex items-center gap-6 text-sm">
          {distance !== undefined && (
            <div className="flex items-center gap-1.5 text-muted-foreground">
              <Navigation className="h-4 w-4" />
              <span>{distance.toFixed(1)} mi away</span>
            </div>
          )}
          <div className="flex items-center gap-1.5 text-muted-foreground">
            <Clock className="h-4 w-4" />
            <span>~{estimatedTime} min</span>
          </div>
        </div>

        {/* Payout highlight */}
        <div className="rounded-lg bg-brand-green-50 p-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <DollarSign className="h-6 w-6 text-brand-green-600" />
              <span className="text-sm font-medium text-brand-green-800">
                Earn for this job
              </span>
            </div>
            <span className="text-2xl font-bold text-brand-green-700">
              {formatCurrency(block.payoutAmount)}
            </span>
          </div>
        </div>

        {/* Checkpoints preview */}
        {block.checkpoints && block.checkpoints.length > 0 && (
          <div className="text-sm text-muted-foreground">
            <span className="font-medium">{block.checkpoints.length} checkpoints</span>
            {' • '}
            {block.checkpoints.slice(0, 3).map((cp) => cp.name).join(', ')}
            {block.checkpoints.length > 3 && '...'}
          </div>
        )}
      </CardContent>

      <CardFooter className="flex gap-3 bg-muted/30 px-6 py-4">
        {isAvailable ? (
          <>
            <Button
              variant="secondary"
              className="flex-1"
              onClick={onNavigate}
            >
              View Map
            </Button>
            <Button
              className="flex-1"
              onClick={onClaim}
              disabled={isLoading}
            >
              {isLoading ? 'Claiming...' : 'Claim Job'}
            </Button>
          </>
        ) : isClaimed ? (
          <Button className="w-full" onClick={onNavigate}>
            Continue Job
          </Button>
        ) : (
          <div className="w-full text-center text-sm text-muted-foreground">
            This job has been completed
          </div>
        )}
      </CardFooter>
    </Card>
  )
}
