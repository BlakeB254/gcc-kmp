'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

interface SafeAreaViewProps {
  children: React.ReactNode
  className?: string
  /** Include safe area inset at the top (default: true) */
  top?: boolean
  /** Include safe area inset at the bottom (default: true) */
  bottom?: boolean
  /** Add padding for bottom tab bar (default: false) */
  hasTabBar?: boolean
}

/**
 * SafeAreaView wraps content with proper padding for iOS notches, home indicators,
 * and Android cutouts. Essential for PWA/mobile-first layouts.
 *
 * Uses CSS env() for safe-area-inset values that work on all modern devices.
 */
export function SafeAreaView({
  children,
  className,
  top = true,
  bottom = true,
  hasTabBar = false,
}: SafeAreaViewProps) {
  return (
    <div
      className={cn(
        'min-h-screen',
        top && 'safe-top',
        bottom && 'safe-bottom',
        hasTabBar && 'pb-20',
        className
      )}
      style={{
        paddingTop: top ? 'env(safe-area-inset-top)' : undefined,
        paddingBottom: bottom
          ? hasTabBar
            ? 'calc(env(safe-area-inset-bottom) + 5rem)'
            : 'env(safe-area-inset-bottom)'
          : undefined,
      }}
    >
      {children}
    </div>
  )
}

interface SafeAreaSpacerProps {
  position: 'top' | 'bottom'
  className?: string
  /** Additional fixed height in pixels */
  additionalHeight?: number
}

/**
 * SafeAreaSpacer creates invisible space for safe areas.
 * Useful when you need to position elements relative to safe areas.
 */
export function SafeAreaSpacer({
  position,
  className,
  additionalHeight = 0,
}: SafeAreaSpacerProps) {
  const inset =
    position === 'top'
      ? 'env(safe-area-inset-top)'
      : 'env(safe-area-inset-bottom)'

  return (
    <div
      className={cn('flex-shrink-0', className)}
      style={{
        height:
          additionalHeight > 0
            ? `calc(${inset} + ${additionalHeight}px)`
            : inset,
      }}
    />
  )
}
