'use client'

import { useState, useRef, useCallback, useEffect } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { toPng } from 'html-to-image'
import { cn } from '@/lib/utils'
import { Logo } from '@/components/brand/Logo'

interface WorkerProfile {
  id: string
  name: string
  email: string
  phone: string
  address?: {
    street: string
    city: string
    state: string
    zip: string
  }
  pendingAddressChange?: {
    street: string
    city: string
    state: string
    zip: string
    requestedAt: string
  } | null
  verificationStatus: 'pending' | 'approved' | 'rejected' | 'needs_info'
  stripeOnboardingStatus: string
  stats?: {
    jobsCompleted: number
    totalEarnings: number
    rating: number
    reviewCount: number
  }
  createdAt: string
}

interface AddressStatus {
  canRequestChange: boolean
  cooldownEndsAt: string | null
  pendingAddressChange: {
    street: string
    city: string
    state: string
    zip: string
    requestedAt: string
  } | null
}

const tierConfig = {
  founder: {
    label: 'Founder',
    color: 'text-yellow-400',
    bgColor: 'bg-yellow-500/10',
    borderColor: 'border-yellow-500/30',
    badge: '👑',
  },
  pioneer: {
    label: 'Pioneer',
    color: 'text-slate-300',
    bgColor: 'bg-slate-500/10',
    borderColor: 'border-slate-500/30',
    badge: '🥈',
  },
  early: {
    label: 'Early Adopter',
    color: 'text-amber-400',
    bgColor: 'bg-amber-500/10',
    borderColor: 'border-amber-500/30',
    badge: '🥉',
  },
  member: {
    label: 'Member',
    color: 'text-neutral-300',
    bgColor: 'bg-neutral-500/10',
    borderColor: 'border-neutral-500/30',
    badge: '🎫',
  },
}

// Address Change Modal Component
function AddressChangeModal({
  isOpen,
  onClose,
  onSuccess,
}: {
  isOpen: boolean
  onClose: () => void
  onSuccess: () => void
}) {
  const [formData, setFormData] = useState({
    street: '',
    city: 'Chicago',
    state: 'IL',
    zip: '',
  })
  const [proofFile, setProofFile] = useState<File | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsSubmitting(true)
    setError(null)

    if (!proofFile) {
      setError('Please upload proof of address')
      setIsSubmitting(false)
      return
    }

    try {
      const formDataToSend = new FormData()
      formDataToSend.append('street', formData.street)
      formDataToSend.append('city', formData.city)
      formDataToSend.append('state', formData.state)
      formDataToSend.append('zip', formData.zip)
      formDataToSend.append('proofOfAddress', proofFile)

      const response = await fetch('/api/workers/address-change', {
        method: 'POST',
        body: formDataToSend,
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.error || 'Failed to submit request')
      }

      onSuccess()
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to submit request')
    } finally {
      setIsSubmitting(false)
    }
  }

  if (!isOpen) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 backdrop-blur-sm">
      <div className="w-full max-w-md bg-neutral-800 rounded-2xl border border-neutral-700 overflow-hidden">
        <div className="p-4 border-b border-neutral-700 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-white">Request Address Change</h2>
          <button onClick={onClose} className="p-1 hover:bg-neutral-700 rounded-lg transition-colors">
            <svg className="w-5 h-5 text-neutral-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-4 space-y-4">
          {error && (
            <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/30 text-red-400 text-sm">
              {error}
            </div>
          )}

          <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-3 text-amber-300 text-xs">
            <strong>Note:</strong> GCC is currently only available in North Lawndale (zip codes 60623 and 60624).
            Address changes are limited to one per 24 hours and require manual approval.
          </div>

          <div>
            <label className="block text-sm font-medium text-neutral-300 mb-1">Street Address</label>
            <input
              type="text"
              required
              value={formData.street}
              onChange={(e) => setFormData({ ...formData, street: e.target.value })}
              className="w-full px-3 py-2 bg-neutral-700 border border-neutral-600 rounded-lg text-white placeholder-neutral-400 focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent"
              placeholder="1234 W Example St"
            />
          </div>

          <div className="grid grid-cols-3 gap-3">
            <div className="col-span-1">
              <label className="block text-sm font-medium text-neutral-300 mb-1">City</label>
              <input
                type="text"
                required
                value={formData.city}
                onChange={(e) => setFormData({ ...formData, city: e.target.value })}
                className="w-full px-3 py-2 bg-neutral-700 border border-neutral-600 rounded-lg text-white focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-neutral-300 mb-1">State</label>
              <input
                type="text"
                required
                value={formData.state}
                onChange={(e) => setFormData({ ...formData, state: e.target.value })}
                className="w-full px-3 py-2 bg-neutral-700 border border-neutral-600 rounded-lg text-white focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-neutral-300 mb-1">Zip Code</label>
              <input
                type="text"
                required
                pattern="[0-9]{5}"
                value={formData.zip}
                onChange={(e) => setFormData({ ...formData, zip: e.target.value.replace(/\D/g, '').slice(0, 5) })}
                className="w-full px-3 py-2 bg-neutral-700 border border-neutral-600 rounded-lg text-white focus:ring-2 focus:ring-brand-gold-500 focus:border-transparent"
                placeholder="60623"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-neutral-300 mb-1">
              Proof of Address <span className="text-red-400">*</span>
            </label>
            <p className="text-xs text-neutral-400 mb-2">
              Upload a utility bill, bank statement, or government mail showing your new address
            </p>
            <input
              type="file"
              accept="image/*,.pdf"
              required
              onChange={(e) => setProofFile(e.target.files?.[0] || null)}
              className="w-full px-3 py-2 bg-neutral-700 border border-neutral-600 rounded-lg text-white file:mr-3 file:py-1 file:px-3 file:rounded-lg file:border-0 file:bg-brand-gold-500 file:text-neutral-900 file:font-medium file:cursor-pointer"
            />
            {proofFile && (
              <p className="text-xs text-brand-green-400 mt-1">Selected: {proofFile.name}</p>
            )}
          </div>

          <div className="flex gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 py-2.5 px-4 rounded-xl font-medium bg-neutral-700 hover:bg-neutral-600 text-white transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="flex-1 py-2.5 px-4 rounded-xl font-medium bg-brand-gold-500 hover:bg-brand-gold-400 text-neutral-900 transition-colors disabled:opacity-50"
            >
              {isSubmitting ? 'Submitting...' : 'Submit Request'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function WorkerProfilePage() {
  const router = useRouter()
  const [isSaving, setIsSaving] = useState(false)
  const [isSaved, setIsSaved] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [profile, setProfile] = useState<WorkerProfile | null>(null)
  const [addressStatus, setAddressStatus] = useState<AddressStatus | null>(null)
  const [showAddressModal, setShowAddressModal] = useState(false)

  const profileCardRef = useRef<HTMLDivElement>(null)

  // Fetch profile and address status on mount
  useEffect(() => {
    async function fetchData() {
      try {
        const [profileRes, addressRes] = await Promise.all([
          fetch('/api/workers/me'),
          fetch('/api/workers/address-change'),
        ])

        if (profileRes.status === 401) {
          router.push('/worker/login')
          return
        }

        if (profileRes.ok) {
          const profileData = await profileRes.json()
          if (profileData.success) {
            setProfile(profileData.user)
          }
        }

        if (addressRes.ok) {
          const addressData = await addressRes.json()
          setAddressStatus(addressData)
        }
      } catch (error) {
        console.error('Failed to fetch data:', error)
      } finally {
        setIsLoading(false)
      }
    }

    fetchData()
  }, [router])

  const handleAddressChangeSuccess = () => {
    // Refresh data
    fetch('/api/workers/address-change')
      .then((res) => res.json())
      .then((data) => setAddressStatus(data))
      .catch(console.error)
  }

  const formatCurrency = (cents: number) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    }).format(cents / 100)
  }

  // Create workerData from profile for display
  const workerData = profile ? {
    id: `GCC-NL-${String(profile.id).slice(-4).toUpperCase().padStart(4, '0')}`,
    name: profile.name,
    username: profile.email.split('@')[0],
    avatar: null,
    tier: profile.verificationStatus === 'approved' && new Date(profile.createdAt) < new Date('2025-06-01')
      ? 'founder' as const
      : profile.verificationStatus === 'approved' && new Date(profile.createdAt) < new Date('2026-01-01')
      ? 'pioneer' as const
      : profile.verificationStatus === 'approved'
      ? 'early' as const
      : 'member' as const,
    location: profile.address
      ? `${profile.address.city}, ${profile.address.state}`
      : 'North Lawndale, Chicago',
    joinedDate: profile.createdAt,
    stats: {
      totalJobs: profile.stats?.jobsCompleted || 0,
      totalEarnings: profile.stats?.totalEarnings || 0,
      averageRating: profile.stats?.rating || 0,
      currentStreak: 0,
      bestStreak: 0,
      blocksThisWeek: 0,
      weeklyRank: 0,
      allTimeRank: 0,
    },
    badges: [
      { id: 'community_hero', name: 'Community Hero', emoji: '🦸', earned: profile.verificationStatus === 'approved' ? profile.createdAt : null },
    ],
  } : null

  const tier = workerData ? tierConfig[workerData.tier] : tierConfig.member
  const earnedBadges = workerData?.badges.filter((b) => b.earned) || []
  const lockedBadges = workerData?.badges.filter((b) => !b.earned) || []

  const saveProfileCard = useCallback(async () => {
    if (!profileCardRef.current || !workerData) return

    setIsSaving(true)
    try {
      const dataUrl = await toPng(profileCardRef.current, {
        cacheBust: true,
        pixelRatio: 2,
        backgroundColor: '#18181b',
      })

      const link = document.createElement('a')
      link.download = `gcc-profile-${workerData.username}.png`
      link.href = dataUrl
      link.click()

      setIsSaved(true)
      setTimeout(() => setIsSaved(false), 3000)
    } catch (err) {
      console.error('Failed to save profile:', err)
    } finally {
      setIsSaving(false)
    }
  }, [])

  const shareToTwitter = () => {
    if (!workerData) return
    const text = `I'm part of @GCCmovement! 🧹💪\n\n${workerData.stats.totalJobs} blocks cleaned\n${formatCurrency(workerData.stats.totalEarnings)} earned\n⭐ ${workerData.stats.averageRating} average rating\n\nJoin us in cleaning up our communities!`
    window.open(
      `https://twitter.com/intent/tweet?text=${encodeURIComponent(text)}`,
      '_blank'
    )
  }

  // Loading state
  if (isLoading) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-neutral-900 via-neutral-900 to-neutral-950 text-white flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-neutral-400">Loading profile...</p>
        </div>
      </div>
    )
  }

  // No profile state
  if (!profile || !workerData) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-neutral-900 via-neutral-900 to-neutral-950 text-white flex items-center justify-center">
        <div className="text-center">
          <p className="text-neutral-400 mb-4">Failed to load profile</p>
          <Link href="/worker/dashboard" className="text-brand-gold-500 hover:underline">
            Return to Dashboard
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-neutral-900 via-neutral-900 to-neutral-950 text-white">
      {/* Header */}
      <header className="p-4 flex items-center justify-between">
        <Link href="/worker/dashboard" className="p-2 -ml-2 text-neutral-400 hover:text-white flex items-center gap-2">
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          <Logo variant="short" size="sm" theme="dark" linkToHome={false} />
        </Link>
        <h1 className="font-semibold">My Profile</h1>
        <button
          onClick={shareToTwitter}
          className="p-2 -mr-2 text-neutral-400 hover:text-white"
        >
          <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" />
          </svg>
        </button>
      </header>

      <main className="px-4 pb-8">
        {/* Profile Card - Screenshottable */}
        <div ref={profileCardRef} className="mb-6">
          <div className="relative overflow-hidden rounded-2xl bg-gradient-to-br from-neutral-800 to-neutral-900 border border-neutral-700">
            {/* Cover area with gradient */}
            <div className="h-24 bg-gradient-to-r from-brand-gold-600 via-brand-gold-500 to-brand-green-600" />

            {/* Profile content */}
            <div className="px-5 pb-5">
              {/* Avatar section */}
              <div className="relative -mt-12 mb-4 flex items-end gap-4">
                {/* Avatar */}
                <div className={cn(
                  'w-20 h-20 rounded-full border-4 border-neutral-800 flex items-center justify-center',
                  tier.bgColor
                )}>
                  {workerData.avatar ? (
                    <img src={workerData.avatar} alt={workerData.name} className="w-full h-full rounded-full object-cover" />
                  ) : (
                    <span className="text-3xl font-bold text-white">
                      {workerData.name.split(' ').map(n => n[0]).join('')}
                    </span>
                  )}
                </div>

                {/* Tier badge */}
                <div className={cn(
                  'px-3 py-1 rounded-full text-sm font-medium',
                  tier.bgColor,
                  tier.borderColor,
                  tier.color,
                  'border'
                )}>
                  {tier.badge} {tier.label}
                </div>
              </div>

              {/* Name and handle */}
              <div className="mb-4">
                <h2 className="text-xl font-bold text-white">{workerData.name}</h2>
                <p className="text-neutral-400 text-sm">@{workerData.username} • {workerData.location}</p>
              </div>

              {/* Stats row */}
              <div className="grid grid-cols-3 gap-3 mb-4 p-3 rounded-xl bg-neutral-800/50">
                <div className="text-center">
                  <p className="text-2xl font-bold text-brand-gold-400">{workerData.stats.totalJobs}</p>
                  <p className="text-xs text-neutral-400">Blocks Cleaned</p>
                </div>
                <div className="text-center border-l border-r border-neutral-700">
                  <p className="text-2xl font-bold text-brand-green-400">{formatCurrency(workerData.stats.totalEarnings)}</p>
                  <p className="text-xs text-neutral-400">Total Earned</p>
                </div>
                <div className="text-center">
                  <p className="text-2xl font-bold text-white">#{workerData.stats.allTimeRank}</p>
                  <p className="text-xs text-neutral-400">All-Time Rank</p>
                </div>
              </div>

              {/* Badges row (first 5 earned) */}
              <div className="flex items-center justify-center gap-2">
                {earnedBadges.slice(0, 5).map((badge) => (
                  <div
                    key={badge.id}
                    className="w-10 h-10 rounded-full bg-neutral-800 border border-neutral-600 flex items-center justify-center"
                    title={badge.name}
                  >
                    <span className="text-lg">{badge.emoji}</span>
                  </div>
                ))}
                {earnedBadges.length > 5 && (
                  <div className="w-10 h-10 rounded-full bg-neutral-700 flex items-center justify-center">
                    <span className="text-xs text-neutral-300">+{earnedBadges.length - 5}</span>
                  </div>
                )}
              </div>

              {/* Worker ID footer */}
              <div className="mt-4 pt-3 border-t border-neutral-700/50 flex items-center justify-between">
                <p className="text-xs text-neutral-500 font-mono">{workerData.id}</p>
                <span className="text-xl">🧹</span>
              </div>
            </div>
          </div>
        </div>

        {/* Save/Share buttons */}
        <div className="flex gap-3 mb-8">
          <button
            onClick={saveProfileCard}
            disabled={isSaving}
            className={cn(
              'flex-1 flex items-center justify-center gap-2 py-3 px-4 rounded-xl font-medium transition-all',
              isSaved
                ? 'bg-brand-green-500 text-white'
                : 'bg-brand-gold-500 hover:bg-brand-gold-400 text-neutral-900'
            )}
          >
            {isSaving ? (
              <>
                <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                Saving...
              </>
            ) : isSaved ? (
              <>
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                </svg>
                Saved!
              </>
            ) : (
              <>
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                </svg>
                Save Profile
              </>
            )}
          </button>

          <button
            onClick={shareToTwitter}
            className="flex-1 flex items-center justify-center gap-2 bg-black hover:bg-neutral-800 text-white font-medium py-3 px-4 rounded-xl transition-colors"
          >
            <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
              <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
            </svg>
            Share
          </button>
        </div>

        {/* Detailed Stats */}
        <section className="mb-8">
          <h3 className="text-sm font-semibold text-neutral-400 uppercase tracking-wider mb-3">
            This Week
          </h3>
          <div className="grid grid-cols-2 gap-3">
            <div className="p-4 rounded-xl bg-neutral-800/50 border border-neutral-700/50">
              <p className="text-sm text-neutral-400 mb-1">Blocks Cleaned</p>
              <p className="text-2xl font-bold text-white">{workerData.stats.blocksThisWeek}</p>
            </div>
            <div className="p-4 rounded-xl bg-neutral-800/50 border border-neutral-700/50">
              <p className="text-sm text-neutral-400 mb-1">Weekly Rank</p>
              <div className="flex items-center gap-2">
                <p className="text-2xl font-bold text-brand-gold-400">#{workerData.stats.weeklyRank}</p>
                {workerData.stats.weeklyRank === 1 && <span className="text-xl">👑</span>}
              </div>
            </div>
            <div className="p-4 rounded-xl bg-neutral-800/50 border border-neutral-700/50">
              <p className="text-sm text-neutral-400 mb-1">Current Streak</p>
              <div className="flex items-center gap-2">
                <p className="text-2xl font-bold text-orange-400">{workerData.stats.currentStreak}</p>
                <span className="text-xl">🔥</span>
              </div>
            </div>
            <div className="p-4 rounded-xl bg-neutral-800/50 border border-neutral-700/50">
              <p className="text-sm text-neutral-400 mb-1">Average Rating</p>
              <div className="flex items-center gap-2">
                <p className="text-2xl font-bold text-white">{workerData.stats.averageRating}</p>
                <span className="text-xl">⭐</span>
              </div>
            </div>
          </div>
        </section>

        {/* Badges Section */}
        <section className="mb-8">
          <h3 className="text-sm font-semibold text-neutral-400 uppercase tracking-wider mb-3">
            Badges ({earnedBadges.length})
          </h3>

          {/* Earned badges */}
          <div className="grid grid-cols-3 gap-3 mb-4">
            {earnedBadges.map((badge) => (
              <div
                key={badge.id}
                className="p-4 rounded-xl bg-gradient-to-br from-neutral-800 to-neutral-800/50 border border-neutral-700 text-center"
              >
                <span className="text-3xl block mb-2 animate-badge-unlock">{badge.emoji}</span>
                <p className="text-xs font-medium text-white truncate">{badge.name}</p>
              </div>
            ))}
          </div>

          {/* Locked badges */}
          {lockedBadges.length > 0 && (
            <>
              <h4 className="text-xs text-neutral-500 mb-2">Coming Soon</h4>
              <div className="grid grid-cols-3 gap-3">
                {lockedBadges.map((badge) => (
                  <div
                    key={badge.id}
                    className="p-4 rounded-xl bg-neutral-800/30 border border-neutral-800 text-center opacity-50"
                  >
                    <span className="text-3xl block mb-2 grayscale">{badge.emoji}</span>
                    <p className="text-xs font-medium text-neutral-500 truncate">{badge.name}</p>
                  </div>
                ))}
              </div>
            </>
          )}
        </section>

        {/* Address Settings */}
        <section className="mb-8">
          <h3 className="text-sm font-semibold text-neutral-400 uppercase tracking-wider mb-3">
            Address Settings
          </h3>

          {/* Current Address */}
          {profile.address && (
            <div className="p-4 rounded-xl bg-neutral-800/50 border border-neutral-700/50 mb-3">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs text-neutral-400 mb-1">Current Address</p>
                  <p className="font-medium text-white">
                    {profile.address.street}
                  </p>
                  <p className="text-sm text-neutral-300">
                    {profile.address.city}, {profile.address.state} {profile.address.zip}
                  </p>
                </div>
                <div className="w-10 h-10 rounded-lg bg-brand-green-500/20 flex items-center justify-center">
                  <svg className="w-5 h-5 text-brand-green-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 11a3 3 0 11-6 0 3 3 0 016 0z" />
                  </svg>
                </div>
              </div>
            </div>
          )}

          {/* Pending Address Change */}
          {addressStatus?.pendingAddressChange && (
            <div className="p-4 rounded-xl bg-purple-500/10 border border-purple-500/30 mb-3">
              <div className="flex items-center gap-2 mb-2">
                <div className="w-2 h-2 rounded-full bg-purple-400 animate-pulse" />
                <p className="text-xs font-medium text-purple-300">Pending Address Change</p>
              </div>
              <p className="font-medium text-white">
                {addressStatus.pendingAddressChange.street}
              </p>
              <p className="text-sm text-neutral-300">
                {addressStatus.pendingAddressChange.city}, {addressStatus.pendingAddressChange.state} {addressStatus.pendingAddressChange.zip}
              </p>
              <p className="text-xs text-purple-300 mt-2">
                Requested {new Date(addressStatus.pendingAddressChange.requestedAt).toLocaleDateString()}
              </p>
            </div>
          )}

          {/* Address Change Button */}
          {profile.verificationStatus === 'approved' && (
            <button
              onClick={() => setShowAddressModal(true)}
              disabled={!addressStatus?.canRequestChange || !!addressStatus?.pendingAddressChange}
              className={cn(
                'w-full flex items-center justify-between p-4 rounded-xl transition-colors',
                addressStatus?.canRequestChange && !addressStatus?.pendingAddressChange
                  ? 'bg-neutral-800/50 border border-neutral-700/50 hover:bg-neutral-700/50'
                  : 'bg-neutral-800/30 border border-neutral-800 opacity-50 cursor-not-allowed'
              )}
            >
              <div className="flex items-center gap-3">
                <span className="text-xl">📍</span>
                <div className="text-left">
                  <span className="font-medium block">Request Address Change</span>
                  {!addressStatus?.canRequestChange && addressStatus?.cooldownEndsAt && (
                    <span className="text-xs text-neutral-500">
                      Available {new Date(addressStatus.cooldownEndsAt).toLocaleString()}
                    </span>
                  )}
                  {addressStatus?.pendingAddressChange && (
                    <span className="text-xs text-purple-400">
                      Pending request in review
                    </span>
                  )}
                </div>
              </div>
              <svg className="w-5 h-5 text-neutral-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </button>
          )}
        </section>

        {/* Quick Links */}
        <section>
          <h3 className="text-sm font-semibold text-neutral-400 uppercase tracking-wider mb-3">
            Quick Links
          </h3>
          <div className="space-y-2">
            <Link
              href="/worker/leaderboard"
              className="flex items-center justify-between p-4 rounded-xl bg-neutral-800/50 border border-neutral-700/50 hover:bg-neutral-700/50 transition-colors"
            >
              <div className="flex items-center gap-3">
                <span className="text-xl">🏆</span>
                <span className="font-medium">Leaderboard</span>
              </div>
              <svg className="w-5 h-5 text-neutral-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </Link>
            <Link
              href="/worker/benefits"
              className="flex items-center justify-between p-4 rounded-xl bg-neutral-800/50 border border-neutral-700/50 hover:bg-neutral-700/50 transition-colors"
            >
              <div className="flex items-center gap-3">
                <span className="text-xl">🎁</span>
                <span className="font-medium">Worker Benefits</span>
              </div>
              <svg className="w-5 h-5 text-neutral-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </Link>
            <Link
              href="/worker/dashboard"
              className="flex items-center justify-between p-4 rounded-xl bg-neutral-800/50 border border-neutral-700/50 hover:bg-neutral-700/50 transition-colors"
            >
              <div className="flex items-center gap-3">
                <span className="text-xl">🏠</span>
                <span className="font-medium">Dashboard</span>
              </div>
              <svg className="w-5 h-5 text-neutral-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </Link>
          </div>
        </section>
      </main>

      {/* Address Change Modal */}
      <AddressChangeModal
        isOpen={showAddressModal}
        onClose={() => setShowAddressModal(false)}
        onSuccess={handleAddressChangeSuccess}
      />
    </div>
  )
}
