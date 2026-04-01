'use client'

import { useEffect, useState, useRef, useCallback } from 'react'
import { useParams, useRouter } from 'next/navigation'
import Link from 'next/link'
import confetti from 'canvas-confetti'
import { toPng } from 'html-to-image'

interface JobCompletionData {
  id: string
  jobNumber: string
  block: {
    code: string
    displayName: string
  }
  completedAt: string
  duration: string // e.g., "2h 34m"
  photoCount: number
  payoutAmount: number // in cents
  payoutStatus: 'pending' | 'processing' | 'completed'
  payoutEta: string // e.g., "~30 minutes"
  worker: {
    name: string
    totalJobs: number
    totalEarnings: number // in cents
    averageRating: number
    currentStreak: number
  }
}

export default function JobCompletedPage() {
  const params = useParams()
  const router = useRouter()
  const jobId = params.id as string

  const [job, setJob] = useState<JobCompletionData | null>(null)
  const [loading, setLoading] = useState(true)
  const [isSaving, setIsSaving] = useState(false)
  const [isSaved, setIsSaved] = useState(false)
  const [showingMoney, setShowingMoney] = useState(false)

  const cardRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    // Fetch job completion data
    // TODO: Replace with actual API call
    const fetchJob = async () => {
      // Simulate API delay
      await new Promise((resolve) => setTimeout(resolve, 500))

      setJob({
        id: jobId,
        jobNumber: 'JOB-20260122-001',
        block: {
          code: 'NL-002',
          displayName: 'Roosevelt Rd: Spaulding to Sawyer',
        },
        completedAt: new Date().toISOString(),
        duration: '2h 34m',
        photoCount: 8,
        payoutAmount: 10000, // $100.00
        payoutStatus: 'processing',
        payoutEta: '~30 minutes',
        worker: {
          name: 'Marcus J.',
          totalJobs: 12,
          totalEarnings: 120000, // $1,200.00
          averageRating: 4.9,
          currentStreak: 3,
        },
      })

      setLoading(false)
    }

    fetchJob()
  }, [jobId])

  useEffect(() => {
    if (!job) return

    // Epic celebration sequence
    setShowingMoney(true)

    // Initial burst
    setTimeout(() => {
      // Gold confetti explosion
      confetti({
        particleCount: 200,
        spread: 120,
        origin: { y: 0.5 },
        colors: ['#ffd700', '#daa520', '#126431', '#22c55e', '#16a34a'],
        ticks: 300,
      })
    }, 300)

    // Money rain effect (green confetti)
    setTimeout(() => {
      const duration = 3000
      const animationEnd = Date.now() + duration
      const defaults = { startVelocity: 30, spread: 360, ticks: 60, zIndex: 0 }

      const randomInRange = (min: number, max: number) => Math.random() * (max - min) + min

      const interval = setInterval(() => {
        const timeLeft = animationEnd - Date.now()

        if (timeLeft <= 0) {
          clearInterval(interval)
          return
        }

        const particleCount = 50 * (timeLeft / duration)

        confetti({
          ...defaults,
          particleCount,
          origin: { x: randomInRange(0.1, 0.3), y: Math.random() - 0.2 },
          colors: ['#22c55e', '#16a34a', '#16763a'],
        })
        confetti({
          ...defaults,
          particleCount,
          origin: { x: randomInRange(0.7, 0.9), y: Math.random() - 0.2 },
          colors: ['#22c55e', '#16a34a', '#16763a'],
        })
      }, 250)
    }, 800)
  }, [job])

  const saveAchievementCard = useCallback(async () => {
    if (!cardRef.current || !job) return

    setIsSaving(true)
    try {
      const dataUrl = await toPng(cardRef.current, {
        cacheBust: true,
        pixelRatio: 2,
        backgroundColor: '#18181b',
      })

      const link = document.createElement('a')
      link.download = `gcc-job-${job.block.code}-complete.png`
      link.href = dataUrl
      link.click()

      setIsSaved(true)
      setTimeout(() => setIsSaved(false), 3000)
    } catch (err) {
      console.error('Failed to save card:', err)
    } finally {
      setIsSaving(false)
    }
  }, [job])

  const shareToTwitter = () => {
    if (!job) return
    const earned = (job.payoutAmount / 100).toFixed(0)
    const total = (job.worker.totalEarnings / 100).toFixed(0)
    const text = `Just completed job #${job.worker.totalJobs} with @GCCmovement! 🧹💰 Earned $${earned} cleaning ${job.block.code} in North Lawndale. $${total} total earned helping my community!`
    window.open(
      `https://twitter.com/intent/tweet?text=${encodeURIComponent(text)}`,
      '_blank'
    )
  }

  if (loading || !job) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-neutral-900">
        <div className="flex flex-col items-center gap-4">
          <div className="animate-spin rounded-full h-12 w-12 border-4 border-brand-green-500 border-t-transparent" />
          <p className="text-neutral-400">Processing your submission...</p>
        </div>
      </div>
    )
  }

  const formatCurrency = (cents: number) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 0,
    }).format(cents / 100)
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-neutral-900 via-neutral-900 to-neutral-950 text-white">
      {/* Background decorations */}
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-brand-green-500/10 rounded-full blur-3xl" />
        <div className="absolute bottom-1/4 right-1/4 w-80 h-80 bg-brand-green-500/10 rounded-full blur-3xl" />
      </div>

      <div className="relative min-h-screen flex flex-col">
        {/* Header */}
        <header className="p-4 flex items-center justify-between">
          <Link href="/worker/dashboard" className="p-2 -ml-2 text-neutral-400 hover:text-white">
            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </Link>
          <div className="text-center">
            <p className="text-xs text-neutral-400">Job Complete</p>
            <p className="font-medium text-brand-green-400">{job.block.code}</p>
          </div>
          <div className="w-10" /> {/* Spacer for alignment */}
        </header>

        {/* Main celebration content */}
        <main className="flex-1 flex flex-col items-center justify-center px-4 pb-8">
          {/* Big celebration heading */}
          <div className="text-center mb-6 animate-bounce-in">
            <div className="text-5xl mb-4">🎉</div>
            <h1 className="text-3xl sm:text-4xl font-bold mb-2">
              Job Complete!
            </h1>
            <p className="text-neutral-400">
              Amazing work on {job.block.displayName}
            </p>
          </div>

          {/* Money display - the hero moment */}
          <div
            className={`
              text-center mb-8 transition-all duration-500
              ${showingMoney ? 'opacity-100 scale-100' : 'opacity-0 scale-90'}
            `}
          >
            <div className="relative inline-block">
              <div className="text-6xl sm:text-7xl font-bold text-brand-green-400 animate-glow">
                {formatCurrency(job.payoutAmount)}
              </div>
              <div className="absolute -top-2 -right-2 animate-bounce">
                <span className="text-2xl">💰</span>
              </div>
            </div>
            <p className="text-neutral-400 mt-2 flex items-center justify-center gap-2">
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-brand-green-400 opacity-75" />
                <span className="relative inline-flex rounded-full h-2 w-2 bg-brand-green-500" />
              </span>
              Coming to your bank • ETA {job.payoutEta}
            </p>
          </div>

          {/* Achievement Card - Screenshottable */}
          <div ref={cardRef} className="w-full max-w-sm mb-6">
            <div className="bg-gradient-to-br from-brand-green-800 via-brand-green-700 to-brand-green-900 rounded-2xl border-2 border-brand-green-500 p-5 shadow-2xl">
              {/* Card header */}
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-2">
                  <span className="text-2xl">✅</span>
                  <div>
                    <p className="text-xs text-brand-green-300 font-medium">GCC JOB COMPLETE</p>
                    <p className="text-sm font-bold text-white">{job.block.code}</p>
                  </div>
                </div>
                <div className="text-right">
                  <p className="text-2xl font-bold text-brand-green-400">{formatCurrency(job.payoutAmount)}</p>
                </div>
              </div>

              {/* Job details */}
              <div className="grid grid-cols-3 gap-3 mb-4 py-3 border-t border-b border-white/10">
                <div className="text-center">
                  <p className="text-xl font-bold text-white">{job.photoCount}</p>
                  <p className="text-xs text-brand-green-300">Photos</p>
                </div>
                <div className="text-center">
                  <p className="text-xl font-bold text-white">{job.duration}</p>
                  <p className="text-xs text-brand-green-300">Time</p>
                </div>
                <div className="text-center">
                  <p className="text-xl font-bold text-white">⭐ {job.worker.averageRating}</p>
                  <p className="text-xs text-brand-green-300">Rating</p>
                </div>
              </div>

              {/* Worker stats */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-semibold text-white">{job.worker.name}</p>
                  <p className="text-xs text-brand-green-300">
                    {job.worker.totalJobs} jobs • {formatCurrency(job.worker.totalEarnings)} earned
                  </p>
                </div>
                {job.worker.currentStreak > 1 && (
                  <div className="flex items-center gap-1 px-2 py-1 bg-orange-500/20 rounded-lg">
                    <span className="text-orange-400">🔥</span>
                    <span className="text-sm font-bold text-orange-400">{job.worker.currentStreak}</span>
                  </div>
                )}
              </div>

              {/* Footer */}
              <div className="mt-4 pt-3 border-t border-white/10 flex items-center justify-between">
                <p className="text-[10px] text-brand-green-400/60">
                  {new Date(job.completedAt).toLocaleDateString()}
                </p>
                <span className="text-2xl">🧹</span>
              </div>
            </div>
          </div>

          {/* Action buttons */}
          <div className="w-full max-w-sm space-y-3">
            {/* Save card */}
            <button
              onClick={saveAchievementCard}
              disabled={isSaving}
              className={`
                w-full flex items-center justify-center gap-2 py-3 px-6 rounded-xl
                font-semibold transition-all
                ${isSaved
                  ? 'bg-brand-green-500 text-white'
                  : 'bg-brand-green-500 hover:bg-brand-green-400 text-neutral-900'
                }
              `}
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
                  Save Achievement Card
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

            {/* Back to dashboard */}
            <Link
              href="/worker/dashboard"
              className="w-full flex items-center justify-center gap-2 bg-neutral-700 hover:bg-neutral-600 text-white font-medium py-3 px-6 rounded-xl transition-colors"
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
              </svg>
              Back to Dashboard
            </Link>
          </div>

          {/* Streak encouragement */}
          {job.worker.currentStreak >= 2 && (
            <div className="mt-6 p-4 rounded-xl bg-orange-500/10 border border-orange-500/20 text-center max-w-sm">
              <p className="text-orange-400 font-medium">
                🔥 {job.worker.currentStreak} day streak! Keep it going tomorrow!
              </p>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
