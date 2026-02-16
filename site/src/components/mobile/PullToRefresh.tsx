'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'
import { Loader2 } from 'lucide-react'

interface PullToRefreshProps {
  children: React.ReactNode
  onRefresh: () => Promise<void>
  className?: string
  /** Distance in pixels to trigger refresh (default: 80) */
  threshold?: number
  /** Maximum pull distance in pixels (default: 150) */
  maxPull?: number
}

/**
 * PullToRefresh provides native-feeling pull-to-refresh functionality.
 * Works well on mobile PWAs where overscroll-behavior is properly set.
 */
export function PullToRefresh({
  children,
  onRefresh,
  className,
  threshold = 80,
  maxPull = 150,
}: PullToRefreshProps) {
  const [isPulling, setIsPulling] = React.useState(false)
  const [isRefreshing, setIsRefreshing] = React.useState(false)
  const [pullDistance, setPullDistance] = React.useState(0)
  const startY = React.useRef(0)
  const containerRef = React.useRef<HTMLDivElement>(null)

  const handleTouchStart = (e: React.TouchEvent) => {
    // Only start tracking if we're at the top of the scroll container
    if (containerRef.current?.scrollTop === 0) {
      startY.current = e.touches[0].clientY
      setIsPulling(true)
    }
  }

  const handleTouchMove = (e: React.TouchEvent) => {
    if (!isPulling || isRefreshing) return

    const currentY = e.touches[0].clientY
    const diff = currentY - startY.current

    // Only allow pulling down
    if (diff > 0) {
      // Apply resistance - pull distance is less than actual touch movement
      const resistance = 0.5
      const distance = Math.min(diff * resistance, maxPull)
      setPullDistance(distance)
    }
  }

  const handleTouchEnd = async () => {
    if (!isPulling) return
    setIsPulling(false)

    if (pullDistance >= threshold && !isRefreshing) {
      setIsRefreshing(true)
      setPullDistance(threshold) // Hold at threshold during refresh

      try {
        await onRefresh()
      } finally {
        setIsRefreshing(false)
        setPullDistance(0)
      }
    } else {
      setPullDistance(0)
    }
  }

  const progress = Math.min(pullDistance / threshold, 1)

  return (
    <div
      ref={containerRef}
      className={cn('relative overflow-auto', className)}
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
      onTouchCancel={handleTouchEnd}
    >
      {/* Pull indicator */}
      <div
        className={cn(
          'pointer-events-none absolute left-1/2 -translate-x-1/2 transition-transform',
          isPulling || isRefreshing ? 'opacity-100' : 'opacity-0'
        )}
        style={{
          top: -60 + pullDistance,
          transition: isPulling ? 'none' : 'all 0.3s ease-out',
        }}
      >
        <div
          className={cn(
            'flex h-10 w-10 items-center justify-center rounded-full bg-background shadow-lg',
            isRefreshing && 'animate-pulse'
          )}
        >
          <Loader2
            className={cn(
              'h-5 w-5 text-primary',
              isRefreshing && 'animate-spin'
            )}
            style={{
              opacity: progress,
              transform: `rotate(${progress * 360}deg)`,
            }}
          />
        </div>
      </div>

      {/* Content with pull transform */}
      <div
        style={{
          transform: `translateY(${pullDistance}px)`,
          transition: isPulling ? 'none' : 'transform 0.3s ease-out',
        }}
      >
        {children}
      </div>
    </div>
  )
}
