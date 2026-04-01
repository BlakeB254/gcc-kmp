'use client'

import * as React from 'react'
import { MapPin, Check, Camera, Navigation, AlertCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { cn } from '@/lib/utils'

interface Checkpoint {
  id: string
  name: string
  type: 'start' | 'corner' | 'midpoint' | 'end'
  coordinates: [number, number] // [lng, lat]
  radius: number // meters
  instructions?: string
  completed?: boolean
  photoRequired?: boolean
  photo?: string
}

interface CheckpointNavigatorProps {
  checkpoints: Checkpoint[]
  currentIndex: number
  userLocation?: { lat: number; lng: number }
  onCheckpointComplete: (checkpointId: string) => void
  onTakePhoto: (checkpointId: string) => void
  className?: string
}

function getDistanceInMeters(
  lat1: number,
  lng1: number,
  lat2: number,
  lng2: number
): number {
  const R = 6371000 // Earth's radius in meters
  const dLat = ((lat2 - lat1) * Math.PI) / 180
  const dLng = ((lng2 - lng1) * Math.PI) / 180
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLng / 2) *
      Math.sin(dLng / 2)
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  return R * c
}

const checkpointTypeConfig = {
  start: { label: 'Start Point', icon: MapPin },
  corner: { label: 'Corner', icon: MapPin },
  midpoint: { label: 'Midpoint', icon: MapPin },
  end: { label: 'End Point', icon: Check },
}

export function CheckpointNavigator({
  checkpoints,
  currentIndex,
  userLocation,
  onCheckpointComplete,
  onTakePhoto,
  className,
}: CheckpointNavigatorProps) {
  const completedCount = checkpoints.filter((cp) => cp.completed).length
  const progress = (completedCount / checkpoints.length) * 100
  const currentCheckpoint = checkpoints[currentIndex]

  const distanceToCheckpoint = React.useMemo(() => {
    if (!userLocation || !currentCheckpoint) return null
    return getDistanceInMeters(
      userLocation.lat,
      userLocation.lng,
      currentCheckpoint.coordinates[1],
      currentCheckpoint.coordinates[0]
    )
  }, [userLocation, currentCheckpoint])

  const isWithinRadius =
    distanceToCheckpoint !== null &&
    distanceToCheckpoint <= currentCheckpoint?.radius

  const needsPhoto =
    currentCheckpoint?.photoRequired && !currentCheckpoint?.photo

  return (
    <div className={cn('space-y-6', className)}>
      {/* Progress header */}
      <div className="space-y-2">
        <div className="flex items-center justify-between text-sm">
          <span className="font-medium">
            Checkpoint {currentIndex + 1} of {checkpoints.length}
          </span>
          <span className="text-muted-foreground">
            {completedCount} completed
          </span>
        </div>
        <Progress value={progress} className="h-2" />
      </div>

      {/* Checkpoint timeline */}
      <div className="relative space-y-4">
        {checkpoints.map((checkpoint, index) => {
          const config = checkpointTypeConfig[checkpoint.type]
          const Icon = config.icon
          const isActive = index === currentIndex
          const isPast = checkpoint.completed
          const isFuture = index > currentIndex && !checkpoint.completed

          return (
            <div
              key={checkpoint.id}
              className={cn(
                'relative flex gap-4 rounded-lg border p-4 transition-all',
                isActive && 'border-primary bg-primary/5',
                isPast && 'border-brand-green-200 bg-brand-green-50',
                isFuture && 'opacity-50'
              )}
            >
              {/* Connector line */}
              {index < checkpoints.length - 1 && (
                <div
                  className={cn(
                    'absolute left-7 top-16 h-4 w-0.5',
                    isPast ? 'bg-brand-green-400' : 'bg-muted'
                  )}
                />
              )}

              {/* Icon */}
              <div
                className={cn(
                  'flex h-8 w-8 shrink-0 items-center justify-center rounded-full',
                  isPast && 'bg-brand-green-500 text-white',
                  isActive && 'bg-primary text-primary-foreground',
                  isFuture && 'bg-muted text-muted-foreground'
                )}
              >
                {isPast ? (
                  <Check className="h-4 w-4" />
                ) : (
                  <Icon className="h-4 w-4" />
                )}
              </div>

              {/* Content */}
              <div className="flex-1 space-y-2">
                <div>
                  <p className="font-medium">{checkpoint.name}</p>
                  <p className="text-sm text-muted-foreground">
                    {config.label}
                    {checkpoint.instructions && ` • ${checkpoint.instructions}`}
                  </p>
                </div>

                {/* Active checkpoint actions */}
                {isActive && !isPast && (
                  <div className="space-y-3">
                    {/* Distance indicator */}
                    {distanceToCheckpoint !== null && (
                      <div
                        className={cn(
                          'flex items-center gap-2 rounded-md p-2 text-sm',
                          isWithinRadius
                            ? 'bg-brand-green-100 text-brand-green-800'
                            : 'bg-amber-100 text-amber-800'
                        )}
                      >
                        {isWithinRadius ? (
                          <Check className="h-4 w-4" />
                        ) : (
                          <Navigation className="h-4 w-4" />
                        )}
                        <span>
                          {isWithinRadius
                            ? 'You are at this checkpoint!'
                            : `${Math.round(distanceToCheckpoint)}m away`}
                        </span>
                      </div>
                    )}

                    {/* Photo requirement */}
                    {needsPhoto && (
                      <div className="flex items-center gap-2 rounded-md bg-brand-blue-100 p-2 text-sm text-brand-blue-800">
                        <Camera className="h-4 w-4" />
                        <span>Photo required at this checkpoint</span>
                      </div>
                    )}

                    {/* Action buttons */}
                    <div className="flex gap-2">
                      {checkpoint.photoRequired && (
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => onTakePhoto(checkpoint.id)}
                          className="gap-2"
                        >
                          <Camera className="h-4 w-4" />
                          {checkpoint.photo ? 'Retake' : 'Take Photo'}
                        </Button>
                      )}
                      <Button
                        size="sm"
                        onClick={() => onCheckpointComplete(checkpoint.id)}
                        disabled={!isWithinRadius || needsPhoto}
                        className="gap-2"
                      >
                        <Check className="h-4 w-4" />
                        Complete
                      </Button>
                    </div>

                    {!isWithinRadius && (
                      <p className="flex items-center gap-1 text-xs text-amber-600">
                        <AlertCircle className="h-3 w-3" />
                        Get closer to the checkpoint to mark it complete
                      </p>
                    )}
                  </div>
                )}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
