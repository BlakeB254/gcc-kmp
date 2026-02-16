'use client'

import { useEffect, useState, Suspense, useRef, useCallback } from 'react'
import { useSearchParams } from 'next/navigation'
import Link from 'next/link'
import confetti from 'canvas-confetti'
import { toPng } from 'html-to-image'
import { Header } from '@/components/landing/Header'
import {
  getTierInfo,
  formatRank,
  getStateName,
  getCountryInfo,
  type EarlyAdopterTier,
  type Rankings,
} from '@/lib/waitlist/rankings'

interface Profile {
  id: string
  name?: string
  location: {
    country: string
    countryCode: string
    state: string
    stateCode: string
    city: string
    neighborhood?: string
  }
  rankings: Rankings
  earlyAdopterTier: EarlyAdopterTier
  referralCode: string
  referralCount: number
}

// Get card tier based on global rank
function getCardTier(rank: number): 'founder' | 'pioneer' | 'early' | 'member' {
  if (rank <= 100) return 'founder'
  if (rank <= 1000) return 'pioneer'
  if (rank <= 10000) return 'early'
  return 'member'
}

const cardTierConfig = {
  founder: {
    label: 'FOUNDER',
    badge: '👑',
    bgGradient: 'from-amber-900 via-yellow-600 to-amber-700',
    borderColor: 'border-yellow-400',
    textColor: 'text-yellow-100',
    accentColor: 'text-yellow-400',
    hologramClass: 'holographic-gold',
  },
  pioneer: {
    label: 'PIONEER',
    badge: '🥈',
    bgGradient: 'from-slate-700 via-gray-500 to-slate-600',
    borderColor: 'border-slate-300',
    textColor: 'text-slate-100',
    accentColor: 'text-slate-300',
    hologramClass: 'holographic-silver',
  },
  early: {
    label: 'EARLY ADOPTER',
    badge: '🥉',
    bgGradient: 'from-amber-800 via-orange-600 to-amber-700',
    borderColor: 'border-amber-400',
    textColor: 'text-amber-100',
    accentColor: 'text-amber-400',
    hologramClass: 'holographic-bronze',
  },
  member: {
    label: 'MEMBER',
    badge: '🎫',
    bgGradient: 'from-neutral-800 via-neutral-700 to-neutral-800',
    borderColor: 'border-neutral-500',
    textColor: 'text-neutral-100',
    accentColor: 'text-neutral-300',
    hologramClass: 'holographic-standard',
  },
}

function SuccessContent() {
  const searchParams = useSearchParams()
  const id = searchParams.get('id')
  const isExisting = searchParams.get('existing') === 'true'

  const [profile, setProfile] = useState<Profile | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [isSaved, setIsSaved] = useState(false)

  const cardRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!id) {
      setError('No profile ID provided')
      setLoading(false)
      return
    }

    const fetchProfile = async () => {
      try {
        const response = await fetch(`/api/waitlist/profile?id=${id}`)
        const data = await response.json()

        if (!response.ok) {
          throw new Error(data.error || 'Failed to load profile')
        }

        setProfile(data.profile)

        // Trigger epic confetti for new signups
        if (!isExisting) {
          setTimeout(() => {
            // Gold confetti burst
            confetti({
              particleCount: 150,
              spread: 100,
              origin: { y: 0.6 },
              colors: ['#D4A574', '#ffd700', '#daa520', '#f59e0b', '#d4a017'],
              ticks: 200,
            })
            // Side bursts
            setTimeout(() => {
              confetti({
                particleCount: 75,
                angle: 60,
                spread: 65,
                origin: { x: 0, y: 0.6 },
                colors: ['#D4A574', '#d4a017', '#f59e0b'],
              })
              confetti({
                particleCount: 75,
                angle: 120,
                spread: 65,
                origin: { x: 1, y: 0.6 },
                colors: ['#4A7C59', '#16a34a', '#22c55e'],
              })
            }, 200)
            // Final burst from top
            setTimeout(() => {
              confetti({
                particleCount: 100,
                spread: 160,
                startVelocity: 25,
                origin: { y: 0 },
                colors: ['#ffd700', '#daa520', '#b8860b'],
              })
            }, 400)
          }, 300)
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load profile')
      } finally {
        setLoading(false)
      }
    }

    fetchProfile()
  }, [id, isExisting])

  const copyReferralLink = async () => {
    if (!profile) return
    const link = `${window.location.origin}/join?ref=${profile.referralCode}`
    await navigator.clipboard.writeText(link)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const saveCardAsImage = useCallback(async () => {
    if (!cardRef.current || !profile) return

    setIsSaving(true)
    try {
      const dataUrl = await toPng(cardRef.current, {
        cacheBust: true,
        pixelRatio: 2,
        backgroundColor: '#18181b',
      })

      const link = document.createElement('a')
      link.download = `gcc-membership-${profile.referralCode}.png`
      link.href = dataUrl
      link.click()

      setIsSaved(true)
      setTimeout(() => setIsSaved(false), 3000)
    } catch (err) {
      console.error('Failed to save card:', err)
    } finally {
      setIsSaving(false)
    }
  }, [profile])

  const shareToTwitter = () => {
    if (!profile) return
    const cardTier = getCardTier(profile.rankings.global)
    const tierLabel = cardTierConfig[cardTier].label
    const text = `I'm ${tierLabel} #${profile.rankings.global} in the @GCCmovement! 🌍 Helping bring paid street cleaning to communities everywhere. Join me:`
    const url = `${window.location.origin}/join?ref=${profile.referralCode}`
    window.open(
      `https://twitter.com/intent/tweet?text=${encodeURIComponent(text)}&url=${encodeURIComponent(url)}`,
      '_blank'
    )
  }

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-b from-neutral-900 to-neutral-950">
        <div className="flex flex-col items-center gap-4">
          <div className="relative">
            <div className="w-16 h-16 border-4 border-brand-gold-500/30 rounded-full" />
            <div className="absolute inset-0 w-16 h-16 border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
          </div>
          <p className="text-neutral-400">Loading your membership...</p>
        </div>
      </div>
    )
  }

  if (error || !profile) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-b from-neutral-900 to-neutral-950 px-4">
        <div className="text-center">
          <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-red-500/10 flex items-center justify-center">
            <svg className="w-8 h-8 text-red-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
            </svg>
          </div>
          <p className="text-red-400 mb-4">{error || 'Profile not found'}</p>
          <Link href="/join" className="text-brand-gold-400 hover:text-brand-gold-300 font-medium">
            ← Back to signup
          </Link>
        </div>
      </div>
    )
  }

  const tierInfo = getTierInfo(profile.earlyAdopterTier)
  const countryInfo = getCountryInfo(profile.location.countryCode)
  const isChicago = profile.location.city.toLowerCase().includes('chicago')
  const isUSA = profile.location.countryCode === 'US'
  const cardTier = getCardTier(profile.rankings.global)
  const cardConfig = cardTierConfig[cardTier]

  // Build compact ranking display
  const topRankings = [
    { emoji: '🌍', label: 'Global', rank: profile.rankings.global },
    { emoji: countryInfo.flag, label: profile.location.countryCode, rank: profile.rankings.country },
    { emoji: '🏙️', label: profile.location.city.split(',')[0], rank: profile.rankings.city },
  ]

  return (
    <div className="min-h-screen bg-gradient-to-b from-neutral-900 via-neutral-900 to-neutral-950">
      {/* Decorative background elements */}
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute top-0 left-1/4 w-96 h-96 bg-brand-gold-500/5 rounded-full blur-3xl" />
        <div className="absolute bottom-1/4 right-1/4 w-80 h-80 bg-brand-green-500/5 rounded-full blur-3xl" />
      </div>

      <Header />

      <div className="relative pt-golden-xl pb-golden-lg px-4">
        <div className="max-w-lg mx-auto">
          {/* Success Header */}
          <div className="text-center mb-golden-lg animate-fade-in-down">
            {isExisting ? (
              <>
                <div className="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-brand-gold-500/10 border border-brand-gold-500/20 mb-4">
                  <span className="text-brand-gold-400 text-sm font-medium">Welcome Back</span>
                </div>
                <h1 className="text-2xl sm:text-3xl font-bold text-white mb-2">
                  Your Membership Card
                </h1>
              </>
            ) : (
              <>
                <div className="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-brand-green-500/10 border border-brand-green-500/20 mb-4">
                  <span className="relative flex h-2 w-2">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-brand-green-400 opacity-75" />
                    <span className="relative inline-flex rounded-full h-2 w-2 bg-brand-green-500" />
                  </span>
                  <span className="text-brand-green-400 text-sm font-medium">Position Locked Forever</span>
                </div>
                <h1 className="text-2xl sm:text-3xl font-bold text-white mb-2">
                  Welcome to the <span className="text-brand-gold-400">Movement!</span>
                </h1>
              </>
            )}
          </div>

          {/* THE MEMBERSHIP CARD - Main Screenshot Moment */}
          <div className="mb-golden-lg animate-scale-in" style={{ animationDelay: '0.2s' }}>
            <div
              ref={cardRef}
              className={`
                relative overflow-hidden rounded-2xl border-2 ${cardConfig.borderColor}
                bg-gradient-to-br ${cardConfig.bgGradient} ${cardConfig.hologramClass}
                p-golden-md shadow-2xl animate-celebrate-pulse hover:scale-[1.01] transition-transform duration-300
              `}
              style={{ aspectRatio: '1.6' }}
            >
              {/* Holographic overlay */}
              <div className="holographic-overlay pointer-events-none absolute inset-0" />

              {/* Card content */}
              <div className="relative z-10 h-full flex flex-col justify-between">
                {/* Header row */}
                <div className="flex items-start justify-between">
                  <div className="flex items-center gap-3">
                    <span className="text-4xl">{cardConfig.badge}</span>
                    <div>
                      <p className={`text-xs font-bold tracking-widest ${cardConfig.accentColor}`}>
                        GCC MEMBERSHIP
                      </p>
                      <p className={`text-sm font-semibold ${cardConfig.textColor}`}>
                        {cardConfig.label}
                      </p>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className={`text-xs ${cardConfig.textColor} opacity-70`}>Global Rank</p>
                    <p className={`text-2xl font-bold ${cardConfig.accentColor}`}>
                      #{profile.rankings.global}
                    </p>
                  </div>
                </div>

                {/* Member info */}
                <div className="mt-4">
                  {profile.name ? (
                    <p className={`text-xl font-bold ${cardConfig.textColor} tracking-wide`}>
                      {profile.name.toUpperCase()}
                    </p>
                  ) : (
                    <p className={`text-xl font-bold ${cardConfig.textColor} tracking-wide`}>
                      MEMBER
                    </p>
                  )}
                  <p className={`text-sm ${cardConfig.textColor} opacity-70`}>
                    {profile.location.city}, {isUSA ? profile.location.stateCode : profile.location.country}
                  </p>
                </div>

                {/* Rankings row */}
                <div className="mt-4 flex items-center gap-4">
                  {topRankings.map((r, i) => (
                    <div key={i} className="flex items-center gap-1.5">
                      <span className="text-lg">{r.emoji}</span>
                      <span className={`text-sm font-semibold ${cardConfig.accentColor}`}>
                        #{formatRank(r.rank)}
                      </span>
                    </div>
                  ))}
                </div>

                {/* Footer row */}
                <div className="mt-4 pt-4 border-t border-white/10 flex items-end justify-between">
                  <div>
                    <p className={`text-xs ${cardConfig.textColor} opacity-50 font-mono`}>
                      REF: {profile.referralCode}
                    </p>
                    <p className={`text-[10px] ${cardConfig.textColor} opacity-40`}>
                      globalcommunitycleaners.org
                    </p>
                  </div>
                  <div className="text-right">
                    <p className="text-3xl">🧹</p>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Save & Share Buttons */}
          <div className="space-y-golden-xs mb-golden-lg animate-fade-in-up" style={{ animationDelay: '0.4s' }}>
            {/* Primary: Save Card */}
            <button
              onClick={saveCardAsImage}
              disabled={isSaving}
              className={`
                w-full flex items-center justify-center gap-golden-xs py-4 px-golden-md rounded-xl
                font-semibold text-lg transition-all hover-lift
                ${isSaved
                  ? 'bg-brand-green-500 text-white'
                  : 'bg-brand-gold-500 hover:bg-brand-gold-400 text-neutral-900 animate-breathe hover:animate-none'
                }
              `}
            >
              {isSaving ? (
                <>
                  <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Generating...
                </>
              ) : isSaved ? (
                <>
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                  </svg>
                  Card Saved to Photos!
                </>
              ) : (
                <>
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                  </svg>
                  Save Membership Card
                </>
              )}
            </button>

            {/* Social Share Buttons */}
            <div className="grid grid-cols-2 gap-golden-xs">
              <button
                onClick={shareToTwitter}
                className="group flex items-center justify-center gap-2 bg-black hover:bg-neutral-800 text-white font-medium py-3 px-golden-sm rounded-xl transition-all hover-lift"
              >
                <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                  <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
                </svg>
                <span>Share on X</span>
              </button>
              <button
                onClick={copyReferralLink}
                className={`
                  flex items-center justify-center gap-2 font-medium py-3 px-4 rounded-xl transition-colors
                  ${copied
                    ? 'bg-brand-green-500 text-white'
                    : 'bg-neutral-700 hover:bg-neutral-600 text-white'
                  }
                `}
              >
                {copied ? (
                  <>
                    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                    <span>Copied!</span>
                  </>
                ) : (
                  <>
                    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
                    </svg>
                    <span>Copy Link</span>
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Tier Info Badge */}
          <div className="flex justify-center mb-golden-lg animate-fade-in-up" style={{ animationDelay: '0.5s' }}>
            <div className={`px-golden-md py-golden-xs rounded-xl ${tierInfo.bgColor} border ${tierInfo.borderColor} hover-scale cursor-default`}>
              <div className="flex items-center gap-golden-xs">
                <span className="text-2xl">{tierInfo.emoji}</span>
                <div>
                  <p className={`font-semibold ${tierInfo.color}`}>{tierInfo.label}</p>
                  <p className="text-xs text-neutral-400">{tierInfo.description}</p>
                </div>
              </div>
            </div>
          </div>

          {/* Referral Stats */}
          {profile.referralCount > 0 && (
            <div className="mb-golden-md p-golden-sm rounded-xl bg-brand-gold-500/10 border border-brand-gold-500/20 text-center animate-fade-in-up hover-scale" style={{ animationDelay: '0.55s' }}>
              <p className="text-brand-gold-400 font-medium">
                🌟 You've brought {profile.referralCount} {profile.referralCount === 1 ? 'person' : 'people'} to the movement!
              </p>
            </div>
          )}

          {/* What's Next */}
          <div className="rounded-xl bg-neutral-800/50 border border-neutral-700/50 p-golden-md mb-golden-lg animate-fade-in-up hover-lift" style={{ animationDelay: '0.6s' }}>
            <h3 className="font-semibold text-white mb-golden-xs flex items-center gap-2">
              <span>📋</span> What's Next?
            </h3>
            <ul className="space-y-golden-xs text-sm">
              <li className="flex items-start gap-golden-xs text-neutral-300 group">
                <span className="flex-shrink-0 w-6 h-6 rounded-full bg-brand-gold-500/20 flex items-center justify-center text-xs text-brand-gold-400 group-hover:bg-brand-gold-500/30 transition-colors">1</span>
                <span>We'll email you when we expand to {isChicago ? 'your neighborhood' : profile.location.city}</span>
              </li>
              <li className="flex items-start gap-golden-xs text-neutral-300 group">
                <span className="flex-shrink-0 w-6 h-6 rounded-full bg-brand-gold-500/20 flex items-center justify-center text-xs text-brand-gold-400 group-hover:bg-brand-gold-500/30 transition-colors">2</span>
                <span>Share your card to help us grow and climb the leaderboard</span>
              </li>
              <li className="flex items-start gap-golden-xs text-neutral-300 group">
                <span className="flex-shrink-0 w-6 h-6 rounded-full bg-brand-gold-500/20 flex items-center justify-center text-xs text-brand-gold-400 group-hover:bg-brand-gold-500/30 transition-colors">3</span>
                <span>More signups in your city = faster expansion to your area</span>
              </li>
            </ul>
          </div>

          {/* Footer Links */}
          <div className="flex flex-col sm:flex-row items-center justify-center gap-4 text-sm">
            <Link
              href={`/join/update?id=${profile.id}`}
              className="text-neutral-400 hover:text-brand-gold-400 transition-colors"
            >
              Moved? Update your location →
            </Link>
            <span className="hidden sm:block text-neutral-700">•</span>
            <Link
              href="/"
              className="text-brand-gold-400 hover:text-brand-gold-300 font-medium transition-colors"
            >
              ← Back to homepage
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}

export default function JoinSuccessPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center bg-gradient-to-b from-neutral-900 to-neutral-950">
          <div className="relative">
            <div className="w-16 h-16 border-4 border-brand-gold-500/30 rounded-full" />
            <div className="absolute inset-0 w-16 h-16 border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
          </div>
        </div>
      }
    >
      <SuccessContent />
    </Suspense>
  )
}
