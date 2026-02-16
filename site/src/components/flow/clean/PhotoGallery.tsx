'use client'

import * as React from 'react'
import Image from 'next/image'
import { Camera, X, Check, AlertCircle, ZoomIn } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

interface Photo {
  id: string
  url: string
  type: 'before' | 'after'
  checkpoint?: string
  timestamp: Date
  status?: 'pending' | 'approved' | 'rejected'
}

interface PhotoSlot {
  id: string
  label: string
  type: 'before' | 'after'
  checkpoint?: string
  required: boolean
  photo?: Photo
}

interface PhotoGalleryProps {
  slots: PhotoSlot[]
  onTakePhoto: (slotId: string) => void
  onRemovePhoto: (slotId: string) => void
  maxPhotos?: number
  className?: string
}

const statusConfig = {
  pending: { icon: AlertCircle, label: 'Pending review', color: 'text-amber-500' },
  approved: { icon: Check, label: 'Approved', color: 'text-brand-green-500' },
  rejected: { icon: X, label: 'Please retake', color: 'text-destructive' },
}

export function PhotoGallery({
  slots,
  onTakePhoto,
  onRemovePhoto,
  maxPhotos = 8,
  className,
}: PhotoGalleryProps) {
  const filledSlots = slots.filter((s) => s.photo)
  const requiredSlots = slots.filter((s) => s.required)
  const filledRequired = requiredSlots.filter((s) => s.photo)
  const allRequiredFilled = filledRequired.length === requiredSlots.length

  return (
    <div className={cn('space-y-4', className)}>
      {/* Progress summary */}
      <div className="flex items-center justify-between">
        <div className="text-sm">
          <span className="font-medium">{filledSlots.length}</span>
          <span className="text-muted-foreground">
            {' '}
            of {maxPhotos} photos
          </span>
        </div>
        {allRequiredFilled ? (
          <div className="flex items-center gap-1.5 text-sm text-brand-green-600">
            <Check className="h-4 w-4" />
            All required photos taken
          </div>
        ) : (
          <div className="text-sm text-amber-600">
            {requiredSlots.length - filledRequired.length} required remaining
          </div>
        )}
      </div>

      {/* Photo grid */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {slots.map((slot) => {
          const photo = slot.photo
          const status = photo?.status ? statusConfig[photo.status] : null
          const StatusIcon = status?.icon

          return (
            <div key={slot.id} className="space-y-1.5">
              {/* Photo container */}
              <div
                className={cn(
                  'relative aspect-square overflow-hidden rounded-lg border-2 border-dashed transition-colors',
                  photo
                    ? 'border-transparent'
                    : slot.required
                      ? 'border-amber-300 bg-amber-50'
                      : 'border-muted bg-muted/30'
                )}
              >
                {photo ? (
                  <>
                    {/* Photo preview with zoom dialog */}
                    <Dialog>
                      <DialogTrigger asChild>
                        <button className="group relative h-full w-full focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2">
                          <Image
                            src={photo.url}
                            alt={slot.label}
                            fill
                            className="object-cover"
                          />
                          <div className="absolute inset-0 flex items-center justify-center bg-black/0 transition-colors group-hover:bg-black/40">
                            <ZoomIn className="h-6 w-6 text-white opacity-0 transition-opacity group-hover:opacity-100" />
                          </div>
                        </button>
                      </DialogTrigger>
                      <DialogContent className="max-w-3xl p-0">
                        <Image
                          src={photo.url}
                          alt={slot.label}
                          width={1200}
                          height={900}
                          className="rounded-lg"
                        />
                      </DialogContent>
                    </Dialog>

                    {/* Remove button */}
                    <button
                      onClick={() => onRemovePhoto(slot.id)}
                      className="absolute right-1 top-1 rounded-full bg-black/60 p-1 text-white transition-colors hover:bg-black/80"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>

                    {/* Status indicator */}
                    {status && StatusIcon && (
                      <div
                        className={cn(
                          'absolute bottom-1 left-1 flex items-center gap-1 rounded-full bg-white/90 px-2 py-0.5 text-xs',
                          status.color
                        )}
                      >
                        <StatusIcon className="h-3 w-3" />
                        {status.label}
                      </div>
                    )}

                    {/* Type badge */}
                    <div
                      className={cn(
                        'absolute left-1 top-1 rounded-full px-2 py-0.5 text-xs font-medium',
                        slot.type === 'before'
                          ? 'bg-brand-brown-500 text-white'
                          : 'bg-brand-green-500 text-white'
                      )}
                    >
                      {slot.type === 'before' ? 'Before' : 'After'}
                    </div>
                  </>
                ) : (
                  <button
                    onClick={() => onTakePhoto(slot.id)}
                    className="flex h-full w-full flex-col items-center justify-center gap-2 text-muted-foreground transition-colors hover:bg-muted/50 hover:text-foreground"
                  >
                    <Camera className="h-8 w-8" />
                    <span className="text-xs font-medium">Tap to capture</span>
                  </button>
                )}
              </div>

              {/* Label */}
              <div className="text-center">
                <p className="text-xs font-medium">{slot.label}</p>
                {slot.required && !photo && (
                  <p className="text-xs text-amber-600">Required</p>
                )}
              </div>
            </div>
          )
        })}
      </div>

      {/* Helper text */}
      <p className="text-center text-xs text-muted-foreground">
        Take clear photos showing the condition before and after cleaning.
        Good photos help verify your work and build your reputation.
      </p>
    </div>
  )
}
