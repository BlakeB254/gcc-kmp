'use client'

import { EarlyAdopterBadge } from './EarlyAdopterBadge'
import { RankDisplay } from './RankDisplay'
import { ShareButtons } from './ShareButtons'
import { formatLocation, type EarlyAdopterTier, type Rankings } from '@/lib/waitlist/rankings'

interface ProfileCardProps {
  rankings: Rankings
  location: {
    country: string
    countryCode: string
    state: string
    stateCode: string
    city: string
    neighborhood?: string
  }
  tier: EarlyAdopterTier
  referralCode: string
  name?: string
  showShareButtons?: boolean
  className?: string
}

export function ProfileCard({
  rankings,
  location,
  tier,
  referralCode,
  name,
  showShareButtons = true,
  className = '',
}: ProfileCardProps) {
  return (
    <div
      className={`
        bg-white rounded-2xl shadow-xl overflow-hidden
        border-2 border-neutral-100
        ${className}
      `}
    >
      {/* Header with logo */}
      <div className="bg-gradient-to-r from-brand-green-700 to-brand-green-800 px-6 py-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-white/20 rounded-full flex items-center justify-center">
            <span className="text-xl">🧹</span>
          </div>
          <span className="text-white font-bold text-lg">GCC</span>
        </div>
        <span className="text-brand-green-300 font-medium text-sm uppercase tracking-wide">
          Early Supporter
        </span>
      </div>

      {/* Badge section */}
      <div className="py-6 px-6 flex flex-col items-center border-b border-neutral-100">
        <EarlyAdopterBadge tier={tier} size="lg" showDescription />
        {name && (
          <p className="mt-3 text-lg font-medium text-neutral-700">{name}</p>
        )}
      </div>

      {/* Rankings section */}
      <div className="p-6 border-b border-neutral-100">
        <RankDisplay rankings={rankings} location={location} />
      </div>

      {/* Quote */}
      <div className="px-6 py-4 bg-neutral-50">
        <p className="text-center text-neutral-600 text-sm italic">
          "I'm helping bring paid street cleaning to my community!"
        </p>
        <p className="text-center text-neutral-400 text-xs mt-1">
          {formatLocation(location)}
        </p>
      </div>

      {/* Share section */}
      {showShareButtons && (
        <div className="p-6 bg-gradient-to-b from-white to-neutral-50">
          <p className="text-center text-sm text-neutral-500 mb-4">
            Share to climb the leaderboard
          </p>
          <ShareButtons
            shareData={{
              globalRank: rankings.global,
              referralCode,
              city: location.city,
              name,
            }}
          />
        </div>
      )}
    </div>
  )
}

/**
 * Mini version for embedding in other pages
 */
export function ProfileCardMini({
  rankings,
  tier,
  referralCode,
}: {
  rankings: Rankings
  tier: EarlyAdopterTier
  referralCode: string
}) {
  return (
    <div className="bg-white rounded-xl shadow-lg p-4 inline-flex flex-col items-center gap-3">
      <EarlyAdopterBadge tier={tier} size="sm" />
      <div className="flex items-center gap-4 text-sm">
        <div className="text-center">
          <p className="text-neutral-500">Global</p>
          <p className="font-bold text-lg">#{rankings.global.toLocaleString()}</p>
        </div>
        <div className="h-8 w-px bg-neutral-200" />
        <div className="text-center">
          <p className="text-neutral-500">City</p>
          <p className="font-bold text-lg">#{rankings.city.toLocaleString()}</p>
        </div>
      </div>
      <a
        href={`/join?ref=${referralCode}`}
        className="text-sm text-brand-green-600 hover:text-brand-green-700 font-medium"
      >
        Join the movement →
      </a>
    </div>
  )
}
