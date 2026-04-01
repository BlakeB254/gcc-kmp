'use client'

import Link from 'next/link'
import Image from 'next/image'

export function WaitlistCTASection() {
  return (
    <section className="relative py-24 overflow-hidden">
      {/* Background */}
      <div className="absolute inset-0 bg-gradient-to-br from-brand-green-800 via-brand-green-900 to-neutral-950" />

      {/* Decorative elements */}
      <div className="absolute inset-0 opacity-10">
        <div className="absolute top-10 left-10 w-64 h-64 bg-brand-green-500 rounded-full blur-3xl" />
        <div className="absolute bottom-10 right-10 w-96 h-96 bg-brand-green-500 rounded-full blur-3xl" />
      </div>

      <div className="section-container relative z-10">
        <div className="max-w-4xl mx-auto text-center">
          {/* Logo */}
          <div className="mb-8 flex justify-center">
            <div className="relative w-24 h-24 overflow-hidden shadow-2xl ring-4 ring-brand-green-500/30 rounded-2xl">
              <Image
                src="/images/gcc-logo.png"
                alt="GCC Logo"
                fill
                className="object-contain"
              />
            </div>
          </div>

          {/* Headline */}
          <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-white mb-6">
            Be Part of Something{' '}
            <span className="text-brand-green-400">Bigger</span>
          </h2>

          {/* Description */}
          <p className="text-lg sm:text-xl text-neutral-300 max-w-2xl mx-auto mb-4">
            We're starting in North Lawndale, Chicago — but this is just the beginning.
            Join the waitlist to bring paid street cleaning to your community.
          </p>

          <p className="text-neutral-400 mb-10">
            Your rank is permanent. The earlier you join, the higher it stays forever.
          </p>

          {/* CTA Button */}
          <Link
            href="/join"
            className="inline-flex items-center gap-3 bg-brand-green-500 text-white font-bold text-lg px-8 py-4 rounded-xl hover:bg-brand-green-600 transition-all transform hover:scale-105 shadow-lg hover:shadow-xl"
          >
            <span>Join the Movement</span>
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
            </svg>
          </Link>

          {/* Social proof */}
          <div className="mt-12 flex flex-wrap justify-center gap-8 text-neutral-400">
            <div className="flex items-center gap-2">
              <span className="text-2xl">🌍</span>
              <span>Global Movement</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-2xl">🏆</span>
              <span>Permanent Rank</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-2xl">📲</span>
              <span>Get Notified First</span>
            </div>
          </div>

          {/* Early adopter tiers */}
          <div className="mt-12 grid grid-cols-2 sm:grid-cols-4 gap-4 max-w-2xl mx-auto">
            <div className="bg-gradient-to-br from-amber-400/20 to-yellow-600/20 rounded-lg p-4 border border-amber-400/30">
              <span className="text-2xl">👑</span>
              <p className="text-amber-400 font-bold mt-1">Founder</p>
              <p className="text-neutral-500 text-xs">First 100</p>
            </div>
            <div className="bg-gradient-to-br from-slate-300/20 to-slate-500/20 rounded-lg p-4 border border-slate-400/30">
              <span className="text-2xl">🚀</span>
              <p className="text-slate-300 font-bold mt-1">Pioneer</p>
              <p className="text-neutral-500 text-xs">First 1,000</p>
            </div>
            <div className="bg-gradient-to-br from-orange-400/20 to-amber-700/20 rounded-lg p-4 border border-orange-500/30">
              <span className="text-2xl">⭐</span>
              <p className="text-orange-400 font-bold mt-1">Early</p>
              <p className="text-neutral-500 text-xs">First 10,000</p>
            </div>
            <div className="bg-gradient-to-br from-green-400/20 to-green-600/20 rounded-lg p-4 border border-green-500/30">
              <span className="text-2xl">🌱</span>
              <p className="text-green-400 font-bold mt-1">Supporter</p>
              <p className="text-neutral-500 text-xs">Everyone</p>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
