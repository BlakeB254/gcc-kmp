'use client'

import {
  formatRank,
  getCountryFlag,
  getStateEmoji,
  getCityEmoji,
  getNeighborhoodEmoji,
  getStateName,
  type Rankings,
} from '@/lib/waitlist/rankings'

interface RankDisplayProps {
  rankings: Rankings
  location: {
    country: string
    countryCode: string
    state: string
    stateCode: string
    city: string
    neighborhood?: string
  }
  compact?: boolean
}

export function RankDisplay({ rankings, location, compact = false }: RankDisplayProps) {
  const ranks = [
    {
      label: 'Global',
      emoji: '🌍',
      value: rankings.global,
      sublabel: 'worldwide',
    },
    {
      label: location.country,
      emoji: getCountryFlag(location.countryCode),
      value: rankings.country,
      sublabel: `in ${location.country}`,
    },
    {
      label: location.stateCode,
      emoji: getStateEmoji(),
      value: rankings.state,
      sublabel: `in ${getStateName(location.stateCode)}`,
    },
    {
      label: location.city,
      emoji: getCityEmoji(),
      value: rankings.city,
      sublabel: `in ${location.city}`,
    },
  ]

  // Add neighborhood if available
  if (location.neighborhood && rankings.neighborhood) {
    ranks.push({
      label: location.neighborhood,
      emoji: getNeighborhoodEmoji(),
      value: rankings.neighborhood,
      sublabel: `in ${location.neighborhood}`,
    })
  }

  if (compact) {
    return (
      <div className="flex flex-wrap gap-3 justify-center">
        {ranks.map((rank) => (
          <div
            key={rank.label}
            className="flex items-center gap-1.5 text-sm bg-neutral-100 rounded-full px-3 py-1"
          >
            <span>{rank.emoji}</span>
            <span className="font-semibold">{formatRank(rank.value)}</span>
          </div>
        ))}
      </div>
    )
  }

  return (
    <div className="space-y-3">
      {ranks.map((rank) => (
        <div
          key={rank.label}
          className="flex items-center justify-between py-2 border-b border-neutral-100 last:border-0"
        >
          <div className="flex items-center gap-3">
            <span className="text-xl">{rank.emoji}</span>
            <span className="text-neutral-600">{rank.sublabel}</span>
          </div>
          <span className="text-xl font-bold text-neutral-900">
            {formatRank(rank.value)}
          </span>
        </div>
      ))}
    </div>
  )
}
