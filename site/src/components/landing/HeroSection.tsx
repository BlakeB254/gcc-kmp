'use client'

import Link from 'next/link'
import Image from 'next/image'
import { useEffect, useState } from 'react'
import { MobileAppAnnouncementBanner } from './MobileAppAnnouncementBanner'

export function HeroSection() {
  const [isVisible, setIsVisible] = useState(false)

  useEffect(() => {
    // Trigger entrance animation
    setIsVisible(true)
  }, [])

  return (
    <section className="relative min-h-[100svh] sm:min-h-screen flex items-end sm:items-center justify-center pt-16 pb-20 sm:pb-0 overflow-hidden">
      {/* Background image - full background for both mobile and desktop */}
      <div className="absolute inset-0 bg-black">
        {/* Mobile: Portrait image showing 4 community cleaners */}
        <picture className="sm:hidden">
          <source
            srcSet="/images/optimized/hero-mobile-portrait.webp"
            type="image/webp"
          />
          <img
            src="/images/optimized/hero-mobile-portrait.jpg"
            alt="Young Black and Latino workers cleaning North Lawndale streets with elder supervisor"
            className="absolute inset-0 w-full h-full object-cover object-top"
            fetchPriority="high"
          />
        </picture>
        {/* Mobile gradient overlays — stronger bottom for text readability */}
        <div className="absolute inset-0 bg-gradient-to-b from-black/30 via-black/30 to-black/85 sm:hidden" />
        <div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/40 to-transparent sm:hidden" />

        {/* Tablet and Desktop: Full cover image */}
        <picture className="hidden sm:block">
          <source
            media="(max-width: 1024px)"
            srcSet="/images/optimized/hero-community-tablet.webp"
            type="image/webp"
          />
          <source
            srcSet="/images/optimized/hero-community-desktop.webp"
            type="image/webp"
          />
          <source
            media="(max-width: 1024px)"
            srcSet="/images/optimized/hero-community-tablet.jpg"
          />
          <Image
            src="/images/optimized/hero-community-desktop.jpg"
            alt="Diverse community cleaners working together on Chicago streets"
            fill
            className="object-cover object-center"
            priority
            sizes="100vw"
          />
        </picture>
        {/* Desktop gradient overlays for depth and readability */}
        <div className="absolute inset-0 bg-gradient-to-r from-black/80 via-black/50 to-black/30 hidden sm:block" />
        <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-black/30 hidden sm:block" />
      </div>

      <div className="section-container relative z-10 py-golden-2xl">
        <div className="max-w-4xl mx-auto text-center bg-black/30 backdrop-blur-[2px] sm:bg-transparent sm:backdrop-blur-none rounded-3xl p-5 sm:p-0">
          {/* Full brand name for recognition */}
          <p
            className={`text-sm sm:text-base font-semibold text-brand-gold-300 uppercase tracking-[0.2em] mb-4 transition-all duration-700 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
            }`}
          >
            Global Community Cleaners
          </p>

          {/* Badge with pulse animation */}
          <div
            className={`inline-flex items-center gap-2 rounded-full bg-brand-gold-500/90 px-4 py-2 mb-6 sm:mb-8 transition-all duration-700 delay-75 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
            }`}
          >
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-white opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-white" />
            </span>
            <span className="text-sm font-medium text-white tracking-wide">
              🔥 Launching in Chicago • Expanding Nationwide
            </span>
          </div>

          {/* Headline with staggered reveal */}
          <h1
            className={`text-4xl sm:text-5xl lg:text-7xl font-bold tracking-tight text-white mb-6 sm:mb-8 drop-shadow-[0_2px_8px_rgba(0,0,0,0.8)] sm:drop-shadow-lg transition-all duration-700 delay-100 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
            }`}
          >
            Clean Streets.{' '}
            <span className="text-brand-gold-400 animate-glow">Real Money.</span>
            <br />
            <span className="inline-block mt-3">Your Neighborhood.</span>
          </h1>

          {/* Subheadline - Golden ratio max-width for optimal readability */}
          <p
            className={`text-lg sm:text-xl text-neutral-200 max-w-3xl mx-auto mb-10 sm:mb-12 leading-relaxed transition-all duration-700 delay-200 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
            }`}
          >
            We're launching in Chicago and expanding fast.{' '}
            <span className="font-semibold text-brand-gold-300">Earn $100+ per block</span> while
            making your community shine. Whether you're in North Lawndale or anywhere else—
            <span className="text-white font-semibold"> the earlier you sign up, the better your position.</span>
          </p>

          {/* CTA Buttons with micro-interactions */}
          <div
            className={`flex flex-col items-center gap-4 mb-12 sm:mb-16 transition-all duration-700 delay-300 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
            }`}
          >
            {/* Primary CTAs */}
            <div className="flex flex-col sm:flex-row items-center justify-center gap-4 sm:gap-5 w-full">
              <Link
                href="/worker/signup"
                className="group btn-primary w-full sm:w-auto text-lg py-4 px-8 animate-breathe hover:animate-none relative overflow-hidden"
              >
                <span className="relative z-10 flex items-center justify-center gap-2">
                  🏠 I'm in Chicago — Start Earning
                  <svg
                    className="w-5 h-5 transition-transform duration-300 group-hover:translate-x-1"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                  </svg>
                </span>
              </Link>
              <Link
                href="/join"
                className="group w-full sm:w-auto text-lg py-4 px-8 hover-lift inline-flex items-center justify-center rounded-lg border-2 border-brand-gold-400/80 bg-brand-gold-500/20 backdrop-blur-sm font-semibold text-white transition-all hover:bg-brand-gold-500/30 hover:border-brand-gold-400"
              >
                <span className="flex items-center justify-center gap-2">
                  🌍 I'm Elsewhere — Join Waitlist
                  <svg
                    className="w-5 h-5 transition-transform duration-300 group-hover:translate-x-1"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                  </svg>
                </span>
              </Link>
            </div>

            {/* Secondary CTA for sponsors */}
            <Link
              href="/sponsor"
              className="group text-sm py-2 px-4 inline-flex items-center justify-center rounded-full bg-white/10 backdrop-blur-sm font-medium text-neutral-300 transition-all hover:bg-white/20 hover:text-white"
            >
              <span className="flex items-center justify-center gap-2">
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
                </svg>
                Or sponsor a block ($100 → worker)
              </span>
            </Link>
          </div>

          {/* Stats — hidden on mobile to keep hero focused, visible on tablet+ */}
          <div
            className={`hidden sm:block max-w-2xl mx-auto bg-black/40 backdrop-blur-md rounded-2xl p-golden-md border border-white/10 transition-all duration-700 delay-400 ${
              isVisible ? 'opacity-100 translate-y-0 scale-100' : 'opacity-0 translate-y-8 scale-95'
            }`}
          >
            <div className="grid grid-cols-3 gap-4 sm:gap-6">
              {/* Earnings */}
              <div className="text-center group hover-scale cursor-default">
                <p className="text-2xl sm:text-4xl font-bold text-brand-gold-400 mb-1 transition-transform duration-300 group-hover:scale-110">
                  $100
                </p>
                <p className="text-xs sm:text-sm text-neutral-300 font-medium">Minimum</p>
                <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">per block</p>
              </div>

              {/* Time */}
              <div className="text-center border-x border-white/10 px-2 group hover-scale cursor-default">
                <p className="text-2xl sm:text-4xl font-bold text-brand-green-400 mb-1 transition-transform duration-300 group-hover:scale-110">
                  2-3h
                </p>
                <p className="text-xs sm:text-sm text-neutral-300 font-medium">Typical</p>
                <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">4 hours max</p>
              </div>

              {/* Payout */}
              <div className="text-center group hover-scale cursor-default">
                <p className="text-2xl sm:text-4xl font-bold text-brand-sky-400 mb-1 transition-transform duration-300 group-hover:scale-110">
                  15-30m
                </p>
                <p className="text-xs sm:text-sm text-neutral-300 font-medium">Payout</p>
                <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">after approval</p>
              </div>
            </div>

            {/* Instant payout note */}
            <div className="mt-4 pt-4 border-t border-white/10 text-center">
              <p className="text-xs sm:text-sm text-neutral-300">
                <span className="text-brand-gold-400 font-semibold">Instant payouts</span> unlocked after completing your probationary period
              </p>
              <p className="text-[10px] sm:text-xs text-neutral-500 mt-1">
                Build trust, verify quality, then get paid the moment your work is approved
              </p>
            </div>
          </div>

          {/* Mobile App Announcement — hidden on small screens for cleaner hero */}
          <div
            className={`hidden sm:flex mt-10 sm:mt-12 flex-col items-center gap-4 transition-all duration-700 delay-500 ${
              isVisible ? 'opacity-100' : 'opacity-0'
            }`}
          >
            <MobileAppAnnouncementBanner variant="hero" />
          </div>
        </div>

        {/* Scroll indicator with bounce */}
        <div
          className={`absolute bottom-8 left-1/2 -translate-x-1/2 transition-all duration-700 delay-700 ${
            isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
          }`}
        >
          <div className="flex flex-col items-center gap-2 text-neutral-400 hover:text-white transition-colors cursor-pointer group">
            <span className="text-xs uppercase tracking-widest">Scroll</span>
            <svg
              className="w-5 h-5 animate-bounce group-hover:animate-none group-hover:translate-y-1 transition-transform"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 14l-7 7m0 0l-7-7m7 7V3" />
            </svg>
          </div>
        </div>
      </div>
    </section>
  )
}
