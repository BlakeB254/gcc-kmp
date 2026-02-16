'use client'

import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { Suspense, useEffect, useState } from 'react'
import confetti from 'canvas-confetti'

function SuccessContent() {
  const searchParams = useSearchParams()
  const blockCode = searchParams.get('block')
  const [showConfetti, setShowConfetti] = useState(false)

  useEffect(() => {
    // Trigger confetti
    setShowConfetti(true)
    const duration = 3 * 1000
    const animationEnd = Date.now() + duration

    const randomInRange = (min: number, max: number) => {
      return Math.random() * (max - min) + min
    }

    const interval = setInterval(() => {
      const timeLeft = animationEnd - Date.now()
      if (timeLeft <= 0) {
        clearInterval(interval)
        return
      }

      confetti({
        particleCount: 3,
        startVelocity: 30,
        spread: 360,
        origin: {
          x: randomInRange(0.1, 0.9),
          y: Math.random() - 0.2,
        },
        colors: ['#D4A853', '#2D5A3D', '#8B6914'],
      })
    }, 50)

    return () => clearInterval(interval)
  }, [])

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-gold-50 to-white flex items-center justify-center p-4">
      <div className="max-w-md w-full bg-white rounded-2xl shadow-xl p-8 text-center">
        {/* Success Icon */}
        <div className="w-24 h-24 bg-brand-gold-100 rounded-full flex items-center justify-center mx-auto mb-6 animate-bounce">
          <svg className="w-12 h-12 text-brand-gold-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
          </svg>
        </div>

        {/* Heading */}
        <h1 className="text-3xl font-bold text-neutral-900 mb-3">
          Thank You!
        </h1>

        {/* Description */}
        <p className="text-lg text-neutral-600 mb-6">
          Your sponsorship of <span className="font-semibold text-brand-gold-700">{blockCode || 'this block'}</span> will
          directly employ a North Lawndale resident and create a cleaner neighborhood.
        </p>

        {/* Impact stats */}
        <div className="bg-neutral-50 rounded-xl p-6 mb-6">
          <h2 className="font-semibold text-neutral-900 mb-4">Your Impact</h2>
          <div className="grid grid-cols-3 gap-4 text-center">
            <div>
              <p className="text-2xl font-bold text-brand-gold-600">$100</p>
              <p className="text-xs text-neutral-500">To Worker</p>
            </div>
            <div>
              <p className="text-2xl font-bold text-brand-green-600">1</p>
              <p className="text-xs text-neutral-500">Block Cleaned</p>
            </div>
            <div>
              <p className="text-2xl font-bold text-brand-brown-600">1</p>
              <p className="text-xs text-neutral-500">Job Created</p>
            </div>
          </div>
        </div>

        {/* What happens next */}
        <div className="text-left bg-brand-brown-50 rounded-xl p-4 mb-6">
          <h3 className="font-semibold text-neutral-900 mb-2 text-sm">What happens next?</h3>
          <ol className="text-sm text-neutral-600 space-y-2">
            <li className="flex items-start gap-2">
              <span className="w-5 h-5 rounded-full bg-brand-gold-500 text-white text-xs flex items-center justify-center flex-shrink-0 mt-0.5">1</span>
              <span>Your block is now marked as &quot;available&quot; for workers</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="w-5 h-5 rounded-full bg-brand-gold-500 text-white text-xs flex items-center justify-center flex-shrink-0 mt-0.5">2</span>
              <span>A local resident will claim and clean the block</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="w-5 h-5 rounded-full bg-brand-gold-500 text-white text-xs flex items-center justify-center flex-shrink-0 mt-0.5">3</span>
              <span>You&apos;ll receive a completion email with before/after photos</span>
            </li>
          </ol>
        </div>

        {/* Tax receipt notice */}
        <p className="text-sm text-neutral-500 mb-6">
          A tax receipt has been sent to your email. GCC is a registered 501(c)(3) nonprofit.
        </p>

        {/* Actions */}
        <div className="space-y-3">
          <Link
            href="/sponsor"
            className="btn-primary w-full py-3 block"
          >
            Sponsor Another Block
          </Link>
          <Link
            href="/"
            className="text-sm text-neutral-500 hover:text-neutral-700 block"
          >
            Return to Homepage
          </Link>
        </div>

        {/* Share */}
        <div className="mt-8 pt-6 border-t border-neutral-200">
          <p className="text-sm text-neutral-500 mb-3">Share your impact:</p>
          <div className="flex justify-center gap-4">
            <a
              href={`https://twitter.com/intent/tweet?text=I just sponsored a block cleaning in North Lawndale through @GCCChicago! $100 = 1 job created + 1 cleaner block.&url=${encodeURIComponent('https://globalcommunitycleaners.org')}`}
              target="_blank"
              rel="noopener noreferrer"
              className="w-10 h-10 rounded-full bg-neutral-100 flex items-center justify-center hover:bg-neutral-200 transition"
            >
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
              </svg>
            </a>
            <a
              href={`https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent('https://globalcommunitycleaners.org')}`}
              target="_blank"
              rel="noopener noreferrer"
              className="w-10 h-10 rounded-full bg-neutral-100 flex items-center justify-center hover:bg-neutral-200 transition"
            >
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M24 12.073c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.99 4.388 10.954 10.125 11.854v-8.385H7.078v-3.47h3.047V9.43c0-3.007 1.792-4.669 4.533-4.669 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.925-1.956 1.874v2.25h3.328l-.532 3.47h-2.796v8.385C19.612 23.027 24 18.062 24 12.073z" />
              </svg>
            </a>
            <a
              href={`https://www.linkedin.com/shareArticle?mini=true&url=${encodeURIComponent('https://globalcommunitycleaners.org')}&title=I sponsored a block cleaning in North Lawndale`}
              target="_blank"
              rel="noopener noreferrer"
              className="w-10 h-10 rounded-full bg-neutral-100 flex items-center justify-center hover:bg-neutral-200 transition"
            >
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433c-1.144 0-2.063-.926-2.063-2.065 0-1.138.92-2.063 2.063-2.063 1.14 0 2.064.925 2.064 2.063 0 1.139-.925 2.065-2.064 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.222 0h.003z" />
              </svg>
            </a>
          </div>
        </div>
      </div>
    </div>
  )
}

export default function SponsorSuccessPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen bg-gradient-to-b from-brand-gold-50 to-white flex items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-brand-gold-600" />
      </div>
    }>
      <SuccessContent />
    </Suspense>
  )
}
