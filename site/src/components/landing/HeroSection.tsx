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
    <section className="relative h-[100svh] sm:h-screen flex items-center justify-center pt-16 overflow-hidden">
      {/* Background image - full background for both mobile and desktop */}
      <div className="absolute inset-0 bg-neutral-800">
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
        {/* Mobile gradient overlays — light touch, just enough for the text card to sit on */}
        <div className="absolute inset-0 bg-gradient-to-b from-black/10 via-black/5 to-black/45 sm:hidden" />
        <div className="absolute inset-0 bg-gradient-to-t from-black/55 via-black/10 to-transparent sm:hidden" />

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
            className="object-cover object-center brightness-110"
            priority
            sizes="100vw"
          />
        </picture>
        {/* Desktop gradient overlays — much lighter than before; the text card (not the whole image) carries contrast now */}
        <div className="absolute inset-0 bg-gradient-to-r from-black/45 via-black/20 to-black/10 hidden sm:block" />
        <div className="absolute inset-0 bg-gradient-to-t from-black/35 via-transparent to-black/10 hidden sm:block" />
      </div>

      <div className="section-container relative z-10 h-full flex items-center py-[clamp(1rem,3vh,2rem)]">
        <div className="grid lg:grid-cols-[1.1fr_0.9fr] gap-[clamp(1rem,3vh,2.5rem)] lg:gap-8 items-center max-w-6xl mx-auto w-full">
          {/* Left column: copy */}
          <div className="text-center lg:text-left bg-black/40 backdrop-blur-[2px] rounded-3xl p-5 sm:p-6 lg:p-7">
            {/* Full brand name for recognition */}
            <p
              className={`text-xs sm:text-sm font-semibold text-brand-green-300 uppercase tracking-[0.2em] mb-[clamp(0.5rem,1.5vh,1rem)] transition-all duration-700 ${
                isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
              }`}
            >
              Global Community Cleaners
            </p>

            {/* Badge with pulse animation */}
            <div
              className={`inline-flex items-center gap-2 rounded-full bg-brand-green-500/90 px-4 py-1.5 sm:py-2 mb-[clamp(0.75rem,2vh,1.5rem)] transition-all duration-700 delay-75 ${
                isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
              }`}
            >
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-white opacity-75" />
                <span className="relative inline-flex rounded-full h-2 w-2 bg-white" />
              </span>
              <span className="text-xs sm:text-sm font-medium text-white tracking-wide">
                🔥 Launching in Chicago • Expanding Worldwide
              </span>
            </div>

            {/* Headline with staggered reveal — fluid sizing so it scales down on short viewports instead of overflowing */}
            <h1
              className={`text-[clamp(1.75rem,4.5vh,4.5rem)] font-bold tracking-tight leading-[1.05] text-white mb-[clamp(0.5rem,1.5vh,1.25rem)] drop-shadow-[0_2px_10px_rgba(0,0,0,0.9)] transition-all duration-700 delay-100 ${
                isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
              }`}
            >
              Clean Streets. <span className="text-brand-green-400 animate-glow">Real Money.</span>{' '}
              Your Neighborhood.
            </h1>

            {/* Subheadline - Golden ratio max-width for optimal readability */}
            <p
              className={`text-sm sm:text-base lg:text-lg text-neutral-100 max-w-3xl mx-auto lg:mx-0 mb-[clamp(0.75rem,2.5vh,2rem)] leading-relaxed transition-all duration-700 delay-200 ${
                isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
              }`}
            >
              <span className="font-semibold text-brand-green-300">Earn $100+ per block</span> cleaning
              your own neighborhood — wherever you live. Sign up now, verify your ID, and we'll
              unlock paid blocks in your area as we expand.{' '}
              <span className="text-white font-semibold hidden sm:inline">The earlier you sign up, the better your position.</span>
            </p>

            {/* CTA Buttons with micro-interactions */}
            <div
              className={`flex flex-col items-center lg:items-start gap-3 mb-[clamp(0.5rem,2vh,1.5rem)] transition-all duration-700 delay-300 ${
                isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
              }`}
            >
              {/* Primary CTA — single onboarding entry for all users */}
              <div className="flex justify-center lg:justify-start w-full">
                <Link
                  href="/worker/signup"
                  className="group btn-primary w-full sm:w-auto text-base sm:text-lg py-3 sm:py-4 px-8 sm:px-10 animate-breathe hover:animate-none relative overflow-hidden"
                >
                  <span className="relative z-10 flex items-center justify-center gap-2">
                    🧹 Start Earning — Sign Up
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

            {/* Stats — hidden on mobile width AND on short viewports (short laptops), so the hero never needs to scroll to reveal the CTA */}
            <div
              className={`hidden sm:block [@media(max-height:800px)]:sm:hidden max-w-2xl mx-auto lg:mx-0 bg-black/40 backdrop-blur-md rounded-2xl p-4 lg:p-5 border border-white/10 transition-all duration-700 delay-400 ${
                isVisible ? 'opacity-100 translate-y-0 scale-100' : 'opacity-0 translate-y-8 scale-95'
              }`}
            >
              <div className="grid grid-cols-3 gap-4 sm:gap-6">
                {/* Earnings */}
                <div className="text-center group hover-scale cursor-default">
                  <p className="text-xl sm:text-2xl lg:text-3xl font-bold text-brand-green-400 mb-1 transition-transform duration-300 group-hover:scale-110">
                    $100
                  </p>
                  <p className="text-xs sm:text-sm text-neutral-300 font-medium">Minimum</p>
                  <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">per block</p>
                </div>

                {/* Time */}
                <div className="text-center border-x border-white/10 px-2 group hover-scale cursor-default">
                  <p className="text-xl sm:text-2xl lg:text-3xl font-bold text-brand-green-400 mb-1 transition-transform duration-300 group-hover:scale-110">
                    2-3h
                  </p>
                  <p className="text-xs sm:text-sm text-neutral-300 font-medium">Typical</p>
                  <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">4 hours max</p>
                </div>

                {/* Payout */}
                <div className="text-center group hover-scale cursor-default">
                  <p className="text-xl sm:text-2xl lg:text-3xl font-bold text-brand-blue-400 mb-1 transition-transform duration-300 group-hover:scale-110">
                    15-30m
                  </p>
                  <p className="text-xs sm:text-sm text-neutral-300 font-medium">Payout</p>
                  <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">after approval</p>
                </div>
              </div>
            </div>

            {/* Mobile App Announcement — hidden on small screens and short viewports for the same reason as the stats card */}
            <div
              className={`hidden sm:flex [@media(max-height:800px)]:sm:hidden mt-4 flex-col items-center lg:items-start gap-4 transition-all duration-700 delay-500 ${
                isVisible ? 'opacity-100' : 'opacity-0'
              }`}
            >
              <MobileAppAnnouncementBanner variant="hero" />
            </div>
          </div>

          {/* Right column: phone mockup — desktop/tablet only, sized off viewport height so it always fits beside the copy */}
          <div
            className={`hidden lg:flex justify-center transition-all duration-700 delay-300 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
            }`}
          >
            <PhoneMockup />
          </div>
        </div>

        {/* Scroll indicator with bounce — hint that there's more content below the hero, not that the hero itself scrolls */}
        <div
          className={`absolute bottom-3 left-1/2 -translate-x-1/2 transition-all duration-700 delay-700 ${
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

/** CSS/SVG-only iPhone frame with a stylized in-app heatmap — no image asset required. */
function PhoneMockup() {
  const heatCells = [
    0.15, 0.3, 0.55, 0.25, 0.1, 0.4,
    0.3, 0.75, 0.9, 0.6, 0.35, 0.2,
    0.5, 0.85, 1, 0.7, 0.45, 0.15,
    0.2, 0.5, 0.65, 0.4, 0.25, 0.1,
    0.1, 0.2, 0.35, 0.2, 0.15, 0.05,
  ]

  return (
    <div className="relative">
      {/* Ambient glow behind the phone */}
      <div className="absolute inset-0 scale-90 bg-brand-green-400/20 blur-3xl rounded-full" />

      {/* Phone body — width capped as a fraction of viewport height so it always fits beside the copy column, even on short laptop screens */}
      <div className="relative w-[min(280px,34vh)] aspect-[9/19.5] rounded-[2.75rem] bg-neutral-900 border-[6px] border-neutral-800 shadow-2xl ring-1 ring-white/10 overflow-hidden">
        {/* Dynamic island */}
        <div className="absolute top-2.5 left-1/2 -translate-x-1/2 z-20 w-24 h-6 rounded-full bg-black" />

        {/* Screen */}
        <div className="absolute inset-0 rounded-[2.2rem] overflow-hidden bg-gradient-to-b from-neutral-950 via-brand-green-900 to-neutral-950">
          {/* Status bar */}
          <div className="flex items-center justify-between px-6 pt-4 text-[10px] font-semibold text-white/80">
            <span>9:41</span>
            <span className="flex items-center gap-1">
              <span className="w-3 h-2 rounded-sm border border-white/60" />
            </span>
          </div>

          {/* App header */}
          <div className="flex items-center justify-between px-4 mt-4">
            <span className="text-xs font-bold text-white tracking-wide">GCC</span>
            <span className="inline-flex items-center gap-1 rounded-full bg-brand-green-500/20 px-2 py-0.5">
              <span className="relative flex h-1.5 w-1.5">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-brand-green-400 opacity-75" />
                <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-brand-green-400" />
              </span>
              <span className="text-[9px] font-semibold text-brand-green-300">Live</span>
            </span>
          </div>

          {/* Mini heatmap card */}
          <div className="mx-4 mt-3 rounded-2xl bg-white/5 border border-white/10 p-2.5">
            <p className="text-[9px] font-medium text-neutral-300 mb-2 px-0.5">
              Blocks near North Lawndale
            </p>
            <div className="grid grid-cols-6 gap-1">
              {heatCells.map((intensity, i) => (
                <div
                  key={i}
                  className="aspect-square rounded-[3px]"
                  style={{
                    backgroundColor: `rgba(74, 222, 128, ${0.15 + intensity * 0.75})`,
                  }}
                />
              ))}
            </div>
            <div className="flex items-center gap-1.5 mt-2 px-0.5">
              <span className="w-1.5 h-1.5 rounded-full bg-brand-blue-400" />
              <span className="text-[8px] text-neutral-400">3 blocks open now</span>
            </div>
          </div>

          {/* Stat chips */}
          <div className="flex gap-2 mx-4 mt-3">
            <div className="flex-1 rounded-xl bg-white/5 border border-white/10 px-2.5 py-2">
              <p className="text-sm font-bold text-brand-green-400">$100</p>
              <p className="text-[8px] text-neutral-400">min / block</p>
            </div>
            <div className="flex-1 rounded-xl bg-white/5 border border-white/10 px-2.5 py-2">
              <p className="text-sm font-bold text-brand-blue-400">15-30m</p>
              <p className="text-[8px] text-neutral-400">to payout</p>
            </div>
          </div>

          {/* CTA button */}
          <div className="mx-4 mt-4">
            <div className="rounded-xl bg-brand-green-500 text-center py-2.5">
              <span className="text-[10px] font-bold text-neutral-900">🧹 Start a Block Scan</span>
            </div>
          </div>

          {/* Bottom home indicator */}
          <div className="absolute bottom-2 left-1/2 -translate-x-1/2 w-24 h-1 rounded-full bg-white/30" />
        </div>
      </div>
    </div>
  )
}
