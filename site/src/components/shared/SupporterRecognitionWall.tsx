'use client'

import { useEffect, useState } from 'react'
import Image from 'next/image'
import Link from 'next/link'
import { Crown, Trophy, Medal, Award, Building2, ExternalLink, Plus } from 'lucide-react'
import { cn } from '@/lib/utils'

interface RecognitionEntry {
  rank: number
  sponsorId: number | null
  displayName: string
  isAnonymous: boolean
  totalAmount: number
  tier: 'platinum' | 'gold' | 'silver' | 'bronze'
  contributionCount: number
  blocksSponsored: number
  logoUrl: string | null
  tagline: string | null
  bio: string | null
  website: string | null
}

const tierConfig = {
  platinum: { icon: Crown, label: 'Platinum', ring: 'ring-purple-200', badgeBg: 'bg-purple-100', badgeText: 'text-purple-600' },
  gold: { icon: Trophy, label: 'Gold', ring: 'ring-amber-200', badgeBg: 'bg-amber-100', badgeText: 'text-amber-600' },
  silver: { icon: Medal, label: 'Silver', ring: 'ring-neutral-200', badgeBg: 'bg-neutral-100', badgeText: 'text-neutral-500' },
  bronze: { icon: Award, label: 'Bronze', ring: 'ring-orange-100', badgeBg: 'bg-orange-100', badgeText: 'text-orange-500' },
}

function websiteHref(url: string) {
  return url.startsWith('http') ? url : `https://${url}`
}

function websiteLabel(url: string) {
  return url.replace(/^https?:\/\//, '').replace(/\/$/, '')
}

interface SupporterRecognitionWallProps {
  /** Max number of supporters to display */
  limit?: number
  /** Only show supporters whose total contribution is at least this many dollars (e.g. a homepage teaser showing only major donors) */
  minAmount?: number
  /** Show an empty "become a partner" card at the end of the grid, inviting future sponsors */
  showOpenSlot?: boolean
  title?: string
  description?: string
  className?: string
}

export function SupporterRecognitionWall({
  limit = 8,
  minAmount,
  showOpenSlot = true,
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

  if (!isLoading && entries.length === 0 && !showOpenSlot) {
    return (
      <div className={cn('text-center py-10', className)}>
        <Building2 className="w-12 h-12 mx-auto mb-3 text-neutral-200" />
        <p className="text-neutral-500">
          Be the first name on our honor wall — sponsor a block today.
        </p>
      </div>
    )
  }

  const platinumEntries = entries.filter((e) => e.tier === 'platinum')
  const otherEntries = entries.filter((e) => e.tier !== 'platinum')

  return (
    <div className={className}>
      {(title || description) && (
        <div className="text-center mb-8">
          {title && <h2 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-2">{title}</h2>}
          {description && <p className="text-neutral-600 max-w-2xl mx-auto">{description}</p>}
        </div>
      )}

      {isLoading ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4 sm:gap-5">
          {Array.from({ length: limit }).map((_, i) => (
            <div key={i} className="aspect-[4/3] rounded-2xl bg-neutral-100 animate-pulse" />
          ))}
        </div>
      ) : (
        <div className="space-y-8">
          {/* Platinum ($5,000+) — featured, larger cards, one per row on desktop */}
          {platinumEntries.length > 0 && (
            <div className="space-y-4">
              {platinumEntries.map((entry) => (
                <PlatinumCard key={entry.rank} entry={entry} />
              ))}
            </div>
          )}

          {/* Gold ($2,000+), Silver, Bronze — standard grid; Gold gets the writeup + link treatment */}
          {(otherEntries.length > 0 || showOpenSlot) && (
            <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4 sm:gap-5">
              {otherEntries.map((entry) =>
                entry.tier === 'gold' ? (
                  <GoldCard key={entry.rank} entry={entry} />
                ) : (
                  <StandardCard key={entry.rank} entry={entry} />
                )
              )}
              {showOpenSlot && <OpenSlotCard />}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function TierBadge({ tier }: { tier: RecognitionEntry['tier'] }) {
  const config = tierConfig[tier]
  const TierIcon = config.icon
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold',
        config.badgeBg,
        config.badgeText
      )}
    >
      <TierIcon className="w-3 h-3" />
      {config.label}
    </span>
  )
}

function Logo({ entry, size }: { entry: RecognitionEntry; size: number }) {
  const initial = entry.displayName.trim().charAt(0).toUpperCase() || '?'
  return (
    <div
      className="flex items-center justify-center rounded-xl bg-neutral-50 overflow-hidden flex-shrink-0"
      style={{ width: size, height: size }}
    >
      {entry.logoUrl ? (
        <Image
          src={entry.logoUrl}
          alt={entry.displayName}
          width={size}
          height={size}
          className="h-full w-full object-contain p-2"
        />
      ) : (
        <span className="text-xl font-bold text-brand-green-600">{initial}</span>
      )}
    </div>
  )
}

/** Standard card — Silver/Bronze: logo, name, tagline. No writeup or outbound link (that's reserved for Gold+). */
function StandardCard({ entry }: { entry: RecognitionEntry }) {
  const config = tierConfig[entry.tier]
  return (
    <div
      className={cn(
        'group relative flex flex-col items-center justify-center gap-3 rounded-2xl border border-neutral-100 bg-white p-5 text-center shadow-sm ring-1 transition-all hover:-translate-y-0.5 hover:shadow-md',
        config.ring
      )}
    >
      <div className="absolute top-3 right-3">
        <TierBadge tier={entry.tier} />
      </div>
      <Logo entry={entry} size={64} />
      <div className="min-w-0">
        <p className="font-semibold text-neutral-900 text-sm leading-tight line-clamp-2">{entry.displayName}</p>
        {entry.tagline && <p className="text-xs text-neutral-500 mt-1 line-clamp-2">{entry.tagline}</p>}
      </div>
    </div>
  )
}

/** Gold ($2,000+) card — logo, a short writeup (tagline or bio), and a button out to their site — the "card display with a write-up and button" tier. */
function GoldCard({ entry }: { entry: RecognitionEntry }) {
  const config = tierConfig.gold
  const writeup = entry.bio || entry.tagline
  return (
    <div
      className={cn(
        'group relative col-span-2 flex flex-col gap-3 rounded-2xl border border-neutral-100 bg-white p-6 shadow-sm ring-1 transition-all hover:-translate-y-0.5 hover:shadow-md sm:col-span-1',
        config.ring
      )}
    >
      <div className="absolute top-4 right-4">
        <TierBadge tier="gold" />
      </div>
      <div className="flex items-center gap-3">
        <Logo entry={entry} size={56} />
        <p className="font-semibold text-neutral-900 leading-tight">{entry.displayName}</p>
      </div>
      {writeup && <p className="text-sm text-neutral-600 leading-relaxed line-clamp-4">{writeup}</p>}
      {entry.website && (
        <a
          href={websiteHref(entry.website)}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-auto inline-flex items-center justify-center gap-1.5 rounded-lg border border-brand-green-200 bg-brand-green-50 px-3 py-2 text-xs font-semibold text-brand-green-700 hover:bg-brand-green-100 transition-colors"
        >
          Visit {websiteLabel(entry.website)}
          <ExternalLink className="w-3 h-3" />
        </a>
      )}
    </div>
  )
}

/** Platinum ($5,000+) — a full-width featured section, not just a grid card. Links out to the sponsor's full profile page (which additionally gets a blog-style "Impact Story" section once they cross $10,000). */
function PlatinumCard({ entry }: { entry: RecognitionEntry }) {
  const writeup = entry.bio || entry.tagline
  return (
    <div className="relative overflow-hidden rounded-2xl border border-purple-200 bg-gradient-to-br from-purple-50 via-white to-white p-6 sm:p-8 shadow-sm ring-1 ring-purple-100">
      <div className="flex flex-col sm:flex-row sm:items-center gap-6">
        <Logo entry={entry} size={88} />
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-2 mb-1.5">
            <p className="text-lg font-bold text-neutral-900">{entry.displayName}</p>
            <TierBadge tier="platinum" />
          </div>
          {writeup && <p className="text-sm text-neutral-600 leading-relaxed max-w-2xl">{writeup}</p>}
        </div>
        <div className="flex flex-col gap-2 sm:flex-shrink-0">
          {entry.website && (
            <a
              href={websiteHref(entry.website)}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center justify-center gap-1.5 rounded-lg border border-purple-200 bg-white px-4 py-2 text-xs font-semibold text-purple-700 hover:bg-purple-50 transition-colors"
            >
              Visit site
              <ExternalLink className="w-3 h-3" />
            </a>
          )}
          {entry.sponsorId && (
            <Link
              href={`/sponsor/profile/${entry.sponsorId}`}
              className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-purple-600 px-4 py-2 text-xs font-semibold text-white hover:bg-purple-700 transition-colors"
            >
              Full profile
            </Link>
          )}
        </div>
      </div>
    </div>
  )
}

/** An open slot in the grid, inviting the next major sponsor — keeps visible room on the wall rather than a cramped, "full" grid. */
function OpenSlotCard() {
  return (
    <Link
      href="/sponsor"
      className="flex flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-neutral-200 p-5 text-center text-neutral-400 transition-colors hover:border-brand-green-300 hover:text-brand-green-600"
    >
      <div className="flex h-16 w-16 items-center justify-center rounded-xl border-2 border-dashed border-current">
        <Plus className="w-6 h-6" />
      </div>
      <p className="text-xs font-semibold">Your logo here</p>
    </Link>
  )
}
