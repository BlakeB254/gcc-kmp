'use client'

import { useRef, useState, useCallback } from 'react'
import { toPng } from 'html-to-image'
import { cn } from '@/lib/utils'

// Tier configuration for card styling
export type CardTier = 'founder' | 'pioneer' | 'early' | 'member' | 'worker' | 'certified'

const tierConfig: Record<
  CardTier,
  {
    bgGradient: string
    borderColor: string
    hologramStyle: string
    label: string
    labelBg: string
  }
> = {
  founder: {
    bgGradient: 'bg-gradient-to-br from-amber-900 via-yellow-700 to-amber-800',
    borderColor: 'border-yellow-400',
    hologramStyle: 'holographic-gold',
    label: 'FOUNDER',
    labelBg: 'bg-gradient-to-r from-yellow-400 to-amber-500 text-black',
  },
  pioneer: {
    bgGradient: 'bg-gradient-to-br from-slate-700 via-gray-500 to-slate-600',
    borderColor: 'border-slate-300',
    hologramStyle: 'holographic-silver',
    label: 'PIONEER',
    labelBg: 'bg-gradient-to-r from-slate-300 to-gray-400 text-black',
  },
  early: {
    bgGradient: 'bg-gradient-to-br from-amber-700 via-orange-600 to-amber-800',
    borderColor: 'border-amber-400',
    hologramStyle: 'holographic-bronze',
    label: 'EARLY ADOPTER',
    labelBg: 'bg-gradient-to-r from-amber-400 to-orange-500 text-black',
  },
  member: {
    bgGradient: 'bg-gradient-to-br from-neutral-800 via-neutral-700 to-neutral-900',
    borderColor: 'border-neutral-500',
    hologramStyle: 'holographic-standard',
    label: 'MEMBER',
    labelBg: 'bg-gradient-to-r from-neutral-400 to-neutral-500 text-black',
  },
  worker: {
    bgGradient: 'bg-gradient-to-br from-brand-green-800 via-brand-green-700 to-brand-green-900',
    borderColor: 'border-brand-green-400',
    hologramStyle: 'holographic-green',
    label: 'CERTIFIED CLEANER',
    labelBg: 'bg-gradient-to-r from-brand-green-400 to-brand-green-500 text-black',
  },
  certified: {
    bgGradient: 'bg-gradient-to-br from-brand-green-700 via-brand-green-600 to-brand-green-800',
    borderColor: 'border-brand-green-300',
    hologramStyle: 'holographic-gold',
    label: 'CERTIFIED',
    labelBg: 'bg-gradient-to-r from-brand-green-300 to-brand-green-400 text-black',
  },
}

interface ShareableCardProps {
  tier?: CardTier
  children: React.ReactNode
  className?: string
  showHologram?: boolean
  showShareButton?: boolean
  filename?: string
  onShare?: () => void
}

export function ShareableCard({
  tier = 'member',
  children,
  className = '',
  showHologram = true,
  showShareButton = true,
  filename = 'gcc-card',
  onShare,
}: ShareableCardProps) {
  const cardRef = useRef<HTMLDivElement>(null)
  const [isDownloading, setIsDownloading] = useState(false)
  const [isShared, setIsShared] = useState(false)

  const config = tierConfig[tier]

  const handleDownload = useCallback(async () => {
    if (!cardRef.current) return

    setIsDownloading(true)
    try {
      const dataUrl = await toPng(cardRef.current, {
        cacheBust: true,
        pixelRatio: 2,
        backgroundColor: '#18181b', // Dark bg for PNG
      })

      const link = document.createElement('a')
      link.download = `${filename}.png`
      link.href = dataUrl
      link.click()

      setIsShared(true)
      setTimeout(() => setIsShared(false), 2000)
      onShare?.()
    } catch (err) {
      console.error('Failed to generate image:', err)
    } finally {
      setIsDownloading(false)
    }
  }, [filename, onShare])

  const handleNativeShare = useCallback(async () => {
    if (!cardRef.current) return

    setIsDownloading(true)
    try {
      const dataUrl = await toPng(cardRef.current, {
        cacheBust: true,
        pixelRatio: 2,
        backgroundColor: '#18181b',
      })

      // Convert to blob for native sharing
      const response = await fetch(dataUrl)
      const blob = await response.blob()
      const file = new File([blob], `${filename}.png`, { type: 'image/png' })

      if (navigator.share && navigator.canShare({ files: [file] })) {
        await navigator.share({
          files: [file],
          title: 'My GCC Card',
          text: 'Check out my Global Community Cleaners membership card!',
        })
      } else {
        // Fallback to download
        handleDownload()
      }

      setIsShared(true)
      setTimeout(() => setIsShared(false), 2000)
      onShare?.()
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        console.error('Failed to share:', err)
        handleDownload()
      }
    } finally {
      setIsDownloading(false)
    }
  }, [filename, onShare, handleDownload])

  return (
    <div className={cn('flex flex-col items-center gap-4', className)}>
      {/* The card itself */}
      <div
        ref={cardRef}
        className={cn(
          'relative overflow-hidden rounded-2xl border-2 p-6',
          config.bgGradient,
          config.borderColor,
          showHologram && config.hologramStyle
        )}
        style={{ minWidth: '320px', maxWidth: '400px' }}
      >
        {/* Holographic overlay */}
        {showHologram && (
          <div className="holographic-overlay pointer-events-none absolute inset-0" />
        )}

        {/* Card content */}
        <div className="relative z-10">{children}</div>

        {/* GCC Logo watermark */}
        <div className="absolute bottom-3 right-3 opacity-20">
          <span className="text-4xl">🧹</span>
        </div>
      </div>

      {/* Share buttons */}
      {showShareButton && (
        <div className="flex gap-3">
          <button
            onClick={handleDownload}
            disabled={isDownloading}
            className={cn(
              'flex items-center gap-2 rounded-lg px-4 py-2 font-medium transition-all',
              isShared
                ? 'bg-brand-green-500 text-white'
                : 'bg-neutral-700 text-white hover:bg-neutral-600'
            )}
          >
            {isDownloading ? (
              <>
                <svg className="h-5 w-5 animate-spin" viewBox="0 0 24 24">
                  <circle
                    className="opacity-25"
                    cx="12"
                    cy="12"
                    r="10"
                    stroke="currentColor"
                    strokeWidth="4"
                    fill="none"
                  />
                  <path
                    className="opacity-75"
                    fill="currentColor"
                    d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                  />
                </svg>
                <span>Saving...</span>
              </>
            ) : isShared ? (
              <>
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M5 13l4 4L19 7"
                  />
                </svg>
                <span>Saved!</span>
              </>
            ) : (
              <>
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                  />
                </svg>
                <span>Save Card</span>
              </>
            )}
          </button>

          {typeof navigator !== 'undefined' && 'share' in navigator && (
            <button
              onClick={handleNativeShare}
              disabled={isDownloading}
              className="flex items-center gap-2 rounded-lg bg-brand-green-500 px-4 py-2 font-medium text-black transition-all hover:bg-brand-green-400"
            >
              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"
                />
              </svg>
              <span>Share</span>
            </button>
          )}
        </div>
      )}
    </div>
  )
}

// Export config for use in other components
export { tierConfig }
