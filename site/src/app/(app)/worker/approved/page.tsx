'use client'

import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { useState, Suspense, useRef, useEffect, useCallback } from 'react'
import confetti from 'canvas-confetti'
import { toPng } from 'html-to-image'
import { Logo } from '@/components/brand/Logo'
import { Trophy, Users, MapPin, Crown, Star, Sparkles } from 'lucide-react'
import { cn, formatCurrency } from '@/lib/utils'

// Tier configuration for display
const tierConfig = {
  founder: {
    label: 'Founder',
    color: 'from-purple-500 to-violet-600',
    bgColor: 'bg-purple-100',
    textColor: 'text-purple-700',
    borderColor: 'border-purple-300',
    icon: Crown,
    description: 'Among the first 10 to join GCC',
  },
  pioneer: {
    label: 'Pioneer',
    color: 'from-brand-gold-400 to-amber-500',
    bgColor: 'bg-brand-gold-100',
    textColor: 'text-brand-gold-700',
    borderColor: 'border-brand-gold-300',
    icon: Star,
    description: 'Among the first 100 to join GCC',
  },
  early: {
    label: 'Early Adopter',
    color: 'from-brand-green-400 to-emerald-500',
    bgColor: 'bg-brand-green-100',
    textColor: 'text-brand-green-700',
    borderColor: 'border-brand-green-300',
    icon: Sparkles,
    description: 'Among the first 1,000 to join GCC',
  },
  standard: {
    label: 'Member',
    color: 'from-neutral-400 to-neutral-500',
    bgColor: 'bg-neutral-100',
    textColor: 'text-neutral-700',
    borderColor: 'border-neutral-300',
    icon: Users,
    description: 'Part of the GCC movement',
  },
}

function ApprovedContent() {
  const searchParams = useSearchParams()
  const onboardingUrl = searchParams.get('stripe_url')
  const workerName = searchParams.get('name') || 'Worker'
  const workerId = searchParams.get('worker_id') || 'GCC-NL-0042'
  const tier = (searchParams.get('tier') || 'early') as keyof typeof tierConfig
  const globalRank = searchParams.get('global_rank') || '42'
  const cityRank = searchParams.get('city_rank') || '15'
  const neighborhoodRank = searchParams.get('neighborhood_rank') || '3'

  const [isRedirecting, setIsRedirecting] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [isSaved, setIsSaved] = useState(false)
  const [showTierReveal, setShowTierReveal] = useState(true)
  const [tierRevealed, setTierRevealed] = useState(false)

  const certificateRef = useRef<HTMLDivElement>(null)
  const tierInfo = tierConfig[tier]
  const TierIcon = tierInfo.icon

  useEffect(() => {
    // Tier reveal animation sequence
    const revealTimer = setTimeout(() => {
      setTierRevealed(true)
      // Extra confetti burst for tier reveal
      confetti({
        particleCount: 100,
        spread: 70,
        origin: { y: 0.4 },
        colors: tier === 'founder' ? ['#a855f7', '#8b5cf6'] :
                tier === 'pioneer' ? ['#ffd700', '#daa520'] :
                tier === 'early' ? ['#22c55e', '#16a34a'] : ['#6b7280', '#9ca3af'],
      })
    }, 1500)

    // Hide tier reveal after animation
    const hideTimer = setTimeout(() => {
      setShowTierReveal(false)
    }, 4000)

    // Celebration confetti
    setTimeout(() => {
      // Gold and green confetti burst
      confetti({
        particleCount: 150,
        spread: 100,
        origin: { y: 0.6 },
        colors: ['#ffd700', '#daa520', '#22c55e', '#16a34a', '#b8860b'],
        ticks: 200,
      })
    }, 300)

    // Side bursts
    setTimeout(() => {
      confetti({
        particleCount: 75,
        angle: 60,
        spread: 65,
        origin: { x: 0, y: 0.7 },
        colors: ['#22c55e', '#16a34a'],
      })
      confetti({
        particleCount: 75,
        angle: 120,
        spread: 65,
        origin: { x: 1, y: 0.7 },
        colors: ['#ffd700', '#daa520'],
      })
    }, 600)

    return () => {
      clearTimeout(revealTimer)
      clearTimeout(hideTimer)
    }
  }, [tier])

  const handleStripeOnboarding = () => {
    if (onboardingUrl) {
      setIsRedirecting(true)
      window.location.href = onboardingUrl
    }
  }

  const saveCertificate = useCallback(async () => {
    if (!certificateRef.current) return

    setIsSaving(true)
    try {
      const dataUrl = await toPng(certificateRef.current, {
        cacheBust: true,
        pixelRatio: 2,
        backgroundColor: '#fefce8', // Light yellow/cream background
      })

      const link = document.createElement('a')
      link.download = `gcc-certificate-${workerId}.png`
      link.href = dataUrl
      link.click()

      setIsSaved(true)
      setTimeout(() => setIsSaved(false), 3000)
    } catch (err) {
      console.error('Failed to save certificate:', err)
    } finally {
      setIsSaving(false)
    }
  }, [workerId])

  const shareToTwitter = () => {
    const tierEmoji = tier === 'founder' ? '👑' : tier === 'pioneer' ? '⭐' : tier === 'early' ? '✨' : '🧹'
    const text = `I just got certified as a GCC ${tierInfo.label}! ${tierEmoji}\n\n🏆 #${globalRank} globally\n📍 #${neighborhoodRank} in North Lawndale\n💰 Earning $100/block\n\nJoin the movement at @GCCmovement!`
    window.open(
      `https://twitter.com/intent/tweet?text=${encodeURIComponent(text)}`,
      '_blank'
    )
  }

  const today = new Date().toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white py-8 px-4">
      {/* Tier Reveal Overlay */}
      {showTierReveal && (
        <div
          className={cn(
            'fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm transition-opacity duration-500',
            tierRevealed ? 'opacity-0 pointer-events-none' : 'opacity-100'
          )}
        >
          <div
            className={cn(
              'text-center transform transition-all duration-700',
              tierRevealed ? 'scale-150 opacity-0' : 'scale-100 opacity-100'
            )}
          >
            <div
              className={cn(
                'w-32 h-32 mx-auto rounded-full bg-gradient-to-br flex items-center justify-center mb-6 animate-pulse',
                tierInfo.color
              )}
            >
              <TierIcon className="w-16 h-16 text-white" />
            </div>
            <p className="text-white/60 text-sm uppercase tracking-widest mb-2">Your Tier</p>
            <h2 className="text-4xl font-bold text-white mb-2">{tierInfo.label}</h2>
            <p className="text-white/80">{tierInfo.description}</p>
          </div>
        </div>
      )}

      <div className="max-w-lg mx-auto">
        {/* Logo */}
        <div className="flex justify-center mb-6">
          <Logo variant="short" size="md" />
        </div>

        {/* Header */}
        <div className="text-center mb-6">
          <div className="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-brand-green-100 border border-brand-green-200 mb-4">
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-brand-green-500 opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-brand-green-600" />
            </span>
            <span className="text-brand-green-700 text-sm font-medium">Approved!</span>
          </div>
          <h1 className="text-2xl font-bold text-neutral-900 mb-2">
            You're Officially Certified
          </h1>
          <p className="text-neutral-600">
            Welcome to the GCC family. Save your certificate below.
          </p>
        </div>

        {/* Movement Rank Card */}
        <div className="mb-6">
          <div className={cn('rounded-xl border p-4', tierInfo.borderColor, tierInfo.bgColor)}>
            <div className="flex items-center gap-3 mb-4">
              <div className={cn('w-12 h-12 rounded-full bg-gradient-to-br flex items-center justify-center', tierInfo.color)}>
                <TierIcon className="w-6 h-6 text-white" />
              </div>
              <div>
                <p className={cn('font-bold text-lg', tierInfo.textColor)}>{tierInfo.label}</p>
                <p className="text-sm text-neutral-600">{tierInfo.description}</p>
              </div>
            </div>

            <div className="bg-white/80 rounded-lg p-4">
              <p className="text-sm text-neutral-600 mb-3">You're part of the GCC movement</p>
              <div className="grid grid-cols-3 gap-3">
                <div className="text-center">
                  <div className="flex items-center justify-center gap-1 mb-1">
                    <Trophy className="w-4 h-4 text-brand-gold-500" />
                    <span className="text-2xl font-bold text-neutral-900">#{globalRank}</span>
                  </div>
                  <p className="text-xs text-neutral-500">Global</p>
                </div>
                <div className="text-center border-x border-neutral-200">
                  <div className="flex items-center justify-center gap-1 mb-1">
                    <MapPin className="w-4 h-4 text-brand-green-500" />
                    <span className="text-2xl font-bold text-neutral-900">#{cityRank}</span>
                  </div>
                  <p className="text-xs text-neutral-500">Chicago</p>
                </div>
                <div className="text-center">
                  <div className="flex items-center justify-center gap-1 mb-1">
                    <Users className="w-4 h-4 text-brand-sky-500" />
                    <span className="text-2xl font-bold text-neutral-900">#{neighborhoodRank}</span>
                  </div>
                  <p className="text-xs text-neutral-500">N. Lawndale</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* THE CERTIFICATE - Main Screenshot Moment */}
        <div className="mb-6">
          <div
            ref={certificateRef}
            className="bg-gradient-to-b from-amber-50 to-yellow-50 rounded-xl border-4 border-double border-brand-gold-400 p-6 shadow-lg"
            style={{ minHeight: '400px' }}
          >
            {/* Certificate header decoration */}
            <div className="flex justify-center mb-4">
              <div className="flex items-center gap-2">
                <div className="h-px w-12 bg-gradient-to-r from-transparent to-brand-gold-400" />
                <span className="text-brand-gold-500 text-2xl">🏆</span>
                <div className="h-px w-12 bg-gradient-to-l from-transparent to-brand-gold-400" />
              </div>
            </div>

            {/* Title */}
            <div className="text-center mb-6">
              <h2 className="text-xs uppercase tracking-[0.3em] text-brand-gold-600 mb-1">
                Certificate of
              </h2>
              <h3
                className="text-3xl font-bold text-brand-brown-800"
                style={{ fontFamily: 'Georgia, serif' }}
              >
                Certification
              </h3>
            </div>

            {/* Decorative line */}
            <div className="flex items-center justify-center mb-6">
              <div className="flex-1 h-px bg-gradient-to-r from-transparent via-brand-gold-300 to-transparent" />
            </div>

            {/* Main content */}
            <div className="text-center mb-6">
              <p className="text-sm text-neutral-600 mb-3">This certifies that</p>
              <p
                className="text-2xl font-bold text-brand-brown-900 mb-3"
                style={{ fontFamily: 'Georgia, serif' }}
              >
                {workerName}
              </p>
              <p className="text-sm text-neutral-600 mb-4">
                has been verified and approved as an official
              </p>
              <div className="inline-block px-4 py-2 bg-brand-green-100 rounded-lg border border-brand-green-200 mb-2">
                <p className="text-lg font-bold text-brand-green-700">
                  Certified Community Cleaner
                </p>
              </div>
              {/* Tier Badge */}
              <div className={cn('inline-flex items-center gap-2 px-3 py-1 rounded-full mb-4', tierInfo.bgColor)}>
                <TierIcon className={cn('w-4 h-4', tierInfo.textColor)} />
                <span className={cn('text-sm font-medium', tierInfo.textColor)}>{tierInfo.label}</span>
              </div>
              <p className="text-sm text-neutral-600">
                for <span className="font-semibold">North Lawndale, Chicago</span>
              </p>
            </div>

            {/* Worker ID */}
            <div className="text-center mb-6">
              <div className="inline-block px-4 py-2 bg-neutral-100 rounded-lg">
                <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1">Worker ID</p>
                <p className="text-lg font-mono font-bold text-neutral-800">{workerId}</p>
              </div>
            </div>

            {/* Date and seal row */}
            <div className="flex items-end justify-between mt-8 pt-4 border-t border-brand-gold-200">
              <div>
                <p className="text-xs text-neutral-500 mb-1">Issued on</p>
                <p className="text-sm font-medium text-neutral-700">{today}</p>
              </div>

              {/* Official seal */}
              <div className="relative">
                <div className="w-20 h-20 rounded-full border-4 border-brand-gold-400 bg-gradient-to-br from-brand-gold-100 to-amber-100 flex items-center justify-center">
                  <div className="text-center">
                    <span className="text-2xl">🧹</span>
                    <p className="text-[8px] font-bold text-brand-gold-700 uppercase">GCC</p>
                  </div>
                </div>
                <div className="absolute -bottom-1 -right-1 w-6 h-6 bg-brand-green-500 rounded-full flex items-center justify-center">
                  <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                  </svg>
                </div>
              </div>

              <div className="text-right">
                <p className="text-xs text-neutral-500 mb-1">Authorized by</p>
                <p className="text-sm font-medium text-neutral-700">GCC Operations</p>
              </div>
            </div>

            {/* Footer */}
            <div className="mt-4 pt-3 border-t border-brand-gold-200/50 text-center">
              <p className="text-[10px] text-neutral-400">
                Global Community Cleaners • globalcommunitycleaners.org
              </p>
            </div>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="space-y-3 mb-6">
          {/* Download certificate */}
          <button
            onClick={saveCertificate}
            disabled={isSaving}
            className={`
              w-full flex items-center justify-center gap-2 py-4 px-6 rounded-xl
              font-semibold text-lg transition-all
              ${isSaved
                ? 'bg-brand-green-500 text-white'
                : 'bg-brand-gold-500 hover:bg-brand-gold-400 text-neutral-900'
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
                Certificate Saved!
              </>
            ) : (
              <>
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                </svg>
                Download Certificate
              </>
            )}
          </button>

          {/* Share */}
          <button
            onClick={shareToTwitter}
            className="w-full flex items-center justify-center gap-2 bg-black hover:bg-neutral-800 text-white font-medium py-3 px-6 rounded-xl transition-colors"
          >
            <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
              <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
            </svg>
            Share on X
          </button>
        </div>

        {/* Final step: Stripe onboarding */}
        <div className="bg-white rounded-xl border border-brand-gold-200 shadow-sm p-6">
          <div className="flex items-center gap-3 mb-4">
            <div className="w-10 h-10 bg-brand-gold-100 rounded-full flex items-center justify-center">
              <svg className="w-5 h-5 text-brand-gold-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
              </svg>
            </div>
            <div>
              <h2 className="font-semibold text-neutral-900">One Final Step</h2>
              <p className="text-sm text-neutral-600">Set up instant payouts</p>
            </div>
          </div>

          <p className="text-sm text-neutral-600 mb-4">
            Connect your bank account through Stripe to receive <span className="font-semibold text-brand-gold-600">$100 per block</span> within 30 minutes of job completion.
          </p>

          {/* Benefits */}
          <div className="flex gap-4 mb-4 text-xs text-neutral-600">
            <span className="flex items-center gap-1">
              <svg className="w-4 h-4 text-brand-green-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
              30-min payouts
            </span>
            <span className="flex items-center gap-1">
              <svg className="w-4 h-4 text-brand-green-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
              Secure deposits
            </span>
            <span className="flex items-center gap-1">
              <svg className="w-4 h-4 text-brand-green-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
              Free transfers
            </span>
          </div>

          {onboardingUrl ? (
            <button
              onClick={handleStripeOnboarding}
              disabled={isRedirecting}
              className="w-full btn-primary py-4 text-lg disabled:opacity-50"
            >
              {isRedirecting ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="animate-spin h-5 w-5" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                  </svg>
                  Redirecting to Stripe...
                </span>
              ) : (
                'Set Up Instant Payouts →'
              )}
            </button>
          ) : (
            <Link href="/worker/dashboard" className="btn-primary w-full py-4 text-lg block text-center">
              Go to Dashboard →
            </Link>
          )}

          <Link
            href="/worker/dashboard"
            className="block text-center text-sm text-neutral-500 hover:text-neutral-700 mt-3"
          >
            Skip for now (complete later in settings)
          </Link>
        </div>
      </div>
    </div>
  )
}

export default function ApprovedPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white flex items-center justify-center">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-brand-green-600" />
        </div>
      }
    >
      <ApprovedContent />
    </Suspense>
  )
}
