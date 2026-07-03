'use client'

import { useEffect, useState } from 'react'
import Image from 'next/image'
import { Crown, Trophy, Medal, Award, Building2 } from 'lucide-react'
import { cn } from '@/lib/utils'

interface RecognitionEntry {
  rank: number
  displayName: string
  isAnonymous: boolean
  totalAmount: number
  tier: 'platinum' | 'gold' | 'silver' | 'bronze'
  contributionCount: number
  blocksSponsored: number
  logoUrl: string | null
  tagline: string | null
}

const tierConfig = {
  platinum: { icon: Crown, label: 'Platinum', ring: 'ring-purple-200', badgeBg: 'bg-purple-100', badgeText: 'text-purple-600' },
  gold: { icon: Trophy, label: 'Gold', ring: 'ring-amber-200', badgeBg: 'bg-amber-100', badgeText: 'text-amber-600' },
  silver: { icon: Medal, label: 'Silver', ring: 'ring-neutral-200', badgeBg: 'bg-neutral-100', badgeText: 'text-neutral-500' },
  bronze: { icon: Award, label: 'Bronze', ring: 'ring-orange-100', badgeBg: 'bg-orange-100', badgeText: 'text-orange-500' },
}

interface SupporterRecognitionWallProps {
  /** Max number of supporters to display */
  limit?: number
  /** Only show supporters whose total contribution is at least this many dollars (e.g. a homepage teaser showing only major donors) */
  minAmount?: number
  title?: string
  description?: string
  className?: string
}

export function SupporterRecognitionWall({
  limit = 8,
  minAmount,
  title = 'Our Honor Wall',
  description = 'The organizations and individuals whose generosity fuels every block we clean.',
  className,
}: SupporterRecognitionWallProps) {
  const [entries, setEntries] = useState<RecognitionEntry[]>([])
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function fetchEntries() {
      try {
        // Fetch a wider pool than `limit` when filtering by minAmount, so the
        // dollar-threshold filter isn't starved by an early page-size cutoff —
        // the leaderboard endpoint is sorted by amount descending, so this is
        // still exactly the top `limit` qualifying supporters after filtering.
        const fetchLimit = minAmount ? Math.max(limit * 3, 24) : limit
        const res = await fetch(`/api/public/support-wall?view=leaderboard&limit=${fetchLimit}`)
        if (!res.ok) throw new Error('Failed to fetch recognition wall')
        const data = await res.json()
        const all: RecognitionEntry[] = data.leaderboard || []
        const filtered = minAmount ? all.filter((e) => e.totalAmount >= minAmount) : all
        if (!cancelled) setEntries(filtered.slice(0, limit))
      } catch (error) {
        console.error('Failed to fetch recognition wall:', error)
      } finally {
        if (!cancelled) setIsLoading(false)
      }
    }
    fetchEntries()
    return () => {
      cancelled = true
    }
  }, [limit, minAmount])

  if (!isLoading && entries.length === 0) {
    return (
      <div className={cn('text-center py-10', className)}>
        <Building2 className="w-12 h-12 mx-auto mb-3 text-neutral-200" />
        <p className="text-neutral-500">
          Be the first name on our honor wall — sponsor a block today.
        </p>
      </div>
    )
  }

  return (
    <div className={className}>
      {(title || description) && (
        <div className="text-center mb-8">
          {title && <h2 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-2">{title}</h2>}
          {description && <p className="text-neutral-600 max-w-2xl mx-auto">{description}</p>}
        </div>
      )}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4 sm:gap-5">
        {isLoading
          ? Array.from({ length: limit }).map((_, i) => (
              <div key={i} className="aspect-[4/3] rounded-2xl bg-neutral-100 animate-pulse" />
            ))
          : entries.map((entry) => {
              const config = tierConfig[entry.tier]
              const TierIcon = config.icon
              const initial = entry.displayName.trim().charAt(0).toUpperCase() || '?'

              return (
                <div
                  key={entry.rank}
                  className={cn(
                    'group relative flex flex-col items-center justify-center gap-3 rounded-2xl border border-neutral-100 bg-white p-5 text-center shadow-sm ring-1 transition-all hover:-translate-y-0.5 hover:shadow-md',
                    config.ring
                  )}
                >
                  <span
                    className={cn(
                      'absolute top-3 right-3 flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold',
                      config.badgeBg,
                      config.badgeText
                    )}
                  >
                    <TierIcon className="w-3 h-3" />
                    {config.label}
                  </span>

                  <div className="flex h-16 w-16 items-center justify-center rounded-xl bg-neutral-50 overflow-hidden">
                    {entry.logoUrl ? (
                      <Image
                        src={entry.logoUrl}
                        alt={entry.displayName}
                        width={64}
                        height={64}
                        className="h-full w-full object-contain p-1.5"
                      />
                    ) : (
                      <span className="text-xl font-bold text-brand-green-600">{initial}</span>
                    )}
                  </div>

                  <div className="min-w-0">
                    <p className="font-semibold text-neutral-900 text-sm leading-tight line-clamp-2">
                      {entry.displayName}
                    </p>
                    {entry.tagline && (
                      <p className="text-xs text-neutral-500 mt-1 line-clamp-2">{entry.tagline}</p>
                    )}
                  </div>
                </div>
              )
            })}
      </div>
    </div>
  )
}
