'use client'

import Link from 'next/link'
import Image from 'next/image'
import { useEffect, useRef, useState } from 'react'
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
        {/* Desktop gradient overlays — stronger on the right now so the device scene
            reads off a calm backdrop instead of fighting the busy photo for edges */}
        <div className="absolute inset-0 bg-gradient-to-r from-black/50 via-black/30 to-black/55 hidden sm:block" />
        <div className="absolute inset-0 bg-gradient-to-t from-black/40 via-transparent to-black/15 hidden sm:block" />
        {/* Faint street-grid motif — texture that ties photo, copy and device together */}
        <svg
          aria-hidden
          className="absolute inset-0 w-full h-full opacity-[0.07] hidden sm:block mix-blend-screen"
          preserveAspectRatio="xMidYMid slice"
          viewBox="0 0 1440 900"
        >
          <defs>
            <pattern id="hero-grid" width="90" height="90" patternUnits="userSpaceOnUse">
              <path d="M 90 0 L 0 0 0 90" fill="none" stroke="white" strokeWidth="1" />
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#hero-grid)" />
        </svg>
      </div>

      <div className="section-container relative z-10 h-full flex items-center py-[clamp(1rem,3vh,2rem)]">
        <div className="grid lg:grid-cols-[1.05fr_0.95fr] gap-[clamp(1rem,3vh,2.5rem)] lg:gap-4 items-center max-w-6xl mx-auto w-full">
          {/* Left column: copy — glass card with a real border + blur so it reads as a surface, not a gray slab */}
          <div className="text-center lg:text-left bg-black/45 backdrop-blur-md rounded-3xl p-5 sm:p-6 lg:p-8 border border-white/10 shadow-2xl shadow-black/40 lg:relative lg:z-20">
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
                Now live in Chicago &middot; Expanding worldwide
              </span>
            </div>

            {/* Headline with staggered reveal — fluid sizing so it scales down on short viewports instead of overflowing */}
            <h1
              className={`text-[clamp(1.75rem,4.5vh,4.5rem)] font-bold tracking-tight leading-[1.05] text-white mb-[clamp(0.5rem,1.5vh,1.25rem)] drop-shadow-[0_2px_10px_rgba(0,0,0,0.9)] transition-all duration-700 delay-100 ${
                isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
              }`}
            >
              Clean Streets.{' '}
              <span className="bg-gradient-to-r from-brand-green-300 via-emerald-300 to-brand-sky-300 bg-clip-text text-transparent">
                Real Money.
              </span>{' '}
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
                  className="group btn-primary w-full sm:w-auto text-base sm:text-lg py-3 sm:py-4 px-8 sm:px-10 relative overflow-hidden"
                >
                  <span className="relative z-10 flex items-center justify-center gap-2">
                    Start Earning — Sign Up
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
                  Or sponsor a block — $100 goes straight to a worker
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
                  <p className="font-display text-xl sm:text-2xl lg:text-3xl font-bold text-brand-green-400 mb-1 transition-transform duration-300 group-hover:scale-110">
                    $100
                  </p>
                  <p className="text-xs sm:text-sm text-neutral-300 font-medium">Minimum</p>
                  <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">per block</p>
                </div>

                {/* Time */}
                <div className="text-center border-x border-white/10 px-2 group hover-scale cursor-default">
                  <p className="font-display text-xl sm:text-2xl lg:text-3xl font-bold text-brand-green-400 mb-1 transition-transform duration-300 group-hover:scale-110">
                    2-3h
                  </p>
                  <p className="text-xs sm:text-sm text-neutral-300 font-medium">Typical</p>
                  <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">4 hours max</p>
                </div>

                {/* Payout */}
                <div className="text-center group hover-scale cursor-default">
                  <p className="font-display text-xl sm:text-2xl lg:text-3xl font-bold text-brand-sky-300 mb-1 transition-transform duration-300 group-hover:scale-110">
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

          {/* Right column: composed device scene — desktop/tablet only */}
          <div
            className={`hidden lg:flex justify-center transition-all duration-700 delay-300 lg:relative lg:z-10 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
            }`}
          >
            <DeviceScene isVisible={isVisible} />
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

/**
 * The product showcase: a 3D-tilted phone with floating UI callout cards that read as
 * live moments from the app (claim, payout, scan progress), each tied to the device by a
 * thin connector line. Gentle idle float + desktop mouse parallax sell depth; everything
 * is transform-only and fully disabled under prefers-reduced-motion. Content mirrors the
 * site's real numbers ($100/block, 15-30m payout, scan-to-clean flow) — no invented data.
 */
function DeviceScene({ isVisible }: { isVisible: boolean }) {
  const sceneRef = useRef<HTMLDivElement>(null)
  const phoneRef = useRef<HTMLDivElement>(null)
  const cardsRef = useRef<HTMLDivElement>(null)
  const [reducedMotion, setReducedMotion] = useState(false)

  useEffect(() => {
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    setReducedMotion(mq.matches)
    const onChange = (e: MediaQueryListEvent) => setReducedMotion(e.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  // Mouse parallax — phone and cards drift at different rates. rAF-lerped, transform-only.
  useEffect(() => {
    if (reducedMotion) return
    const scene = sceneRef.current
    const phone = phoneRef.current
    const cards = cardsRef.current
    if (!scene || !phone || !cards) return

    let tx = 0, ty = 0, cx = 0, cy = 0, raf = 0
    const onMove = (e: MouseEvent) => {
      const r = scene.getBoundingClientRect()
      tx = ((e.clientX - r.left) / r.width - 0.5) * 2
      ty = ((e.clientY - r.top) / r.height - 0.5) * 2
    }
    const tick = () => {
      cx += (tx - cx) * 0.06
      cy += (ty - cy) * 0.06
      phone.style.transform = `rotateY(calc(-14deg + ${cx * 4}deg)) rotateX(calc(5deg + ${cy * -3}deg)) translate3d(${cx * 10}px, ${cy * 8}px, 0)`
      cards.style.transform = `translate3d(${cx * -14}px, ${cy * -10}px, 0)`
      raf = requestAnimationFrame(tick)
    }
    const section = scene.closest('section')
    section?.addEventListener('mousemove', onMove)
    raf = requestAnimationFrame(tick)
    return () => {
      section?.removeEventListener('mousemove', onMove)
      cancelAnimationFrame(raf)
    }
  }, [reducedMotion])

  return (
    <div ref={sceneRef} className="relative w-[min(390px,44vh)] aspect-[1536/2752]" style={{ perspective: '1400px' }}>
      {/* soft brand glow anchoring the device to the backdrop */}
      <div className="absolute inset-0 -m-10 rounded-full bg-brand-green-500/20 blur-3xl" aria-hidden />

      <div
        ref={phoneRef}
        className={`relative w-full h-full will-change-transform ${reducedMotion ? '' : 'hero-device-float'}`}
        style={{
          transform: 'rotateY(-14deg) rotateX(5deg)',
          transformStyle: 'preserve-3d',
          transition: 'transform 120ms linear',
        }}
      >
        <Image
          src="/images/optimized/hero-app-phone-mockup.webp"
          alt="The GCC app's live map showing the North Lawndale cleanliness heatmap and open block routes"
          fill
          className="object-contain drop-shadow-[0_40px_60px_rgba(0,0,0,0.6)]"
          sizes="(min-width: 1280px) 430px, 380px"
          priority
        />
      </div>

      {/* Floating callout cards — counter-parallaxed against the phone for depth */}
      <div ref={cardsRef} className="absolute inset-0 z-10 will-change-transform pointer-events-none" style={{ transition: 'transform 120ms linear' }}>
        <FloatCard
          isVisible={isVisible}
          delay={600}
          className="absolute -left-6 top-[10%]"
          lineClass="left-full top-1/2 w-6"
          reducedMotion={reducedMotion}
        >
          <p className="text-[10px] font-semibold uppercase tracking-widest text-brand-green-300">Block claimed</p>
          <p className="text-sm font-bold text-white mt-0.5">1500 S Pulaski Rd</p>
          <p className="text-[11px] text-neutral-400 mt-0.5">Route 07 · boundary lap next</p>
        </FloatCard>

        <FloatCard
          isVisible={isVisible}
          delay={750}
          className="absolute -right-24 top-[42%]"
          lineClass="right-full top-1/2 w-24"
          reducedMotion={reducedMotion}
        >
          <div className="flex items-center gap-2">
            <span className="flex h-7 w-7 items-center justify-center rounded-full bg-brand-green-500/25 text-brand-green-300 text-sm font-bold">$</span>
            <div>
              <p className="text-sm font-bold text-white leading-tight">$100 payout sent</p>
              <p className="text-[11px] text-neutral-400">approved 15 min ago</p>
            </div>
          </div>
        </FloatCard>

        <FloatCard
          isVisible={isVisible}
          delay={900}
          className="absolute -left-5 bottom-[8%]"
          lineClass="left-full top-1/2 w-5"
          reducedMotion={reducedMotion}
        >
          <p className="text-[10px] font-semibold uppercase tracking-widest text-brand-sky-300">Scan progress</p>
          <div className="mt-1.5 h-1.5 w-36 rounded-full bg-white/10 overflow-hidden">
            <div className="h-full w-[83%] rounded-full bg-gradient-to-r from-brand-green-400 to-brand-sky-400" />
          </div>
          <p className="text-[11px] text-neutral-400 mt-1">10 of 12 checkpoints mapped</p>
        </FloatCard>
      </div>

      {/* idle float keyframes, compiled out under reduced motion */}
      <style jsx>{`
        @keyframes hero-device-float {
          0%, 100% { translate: 0 0; }
          50% { translate: 0 -10px; }
        }
        .hero-device-float {
          animation: hero-device-float 6s ease-in-out infinite;
        }
        @media (prefers-reduced-motion: reduce) {
          .hero-device-float { animation: none !important; }
        }
      `}</style>
    </div>
  )
}

/** One floating callout card with a connector line back to the device. */
function FloatCard({
  isVisible,
  delay,
  className,
  lineClass,
  reducedMotion,
  children,
}: {
  isVisible: boolean
  delay: number
  className: string
  lineClass: string
  reducedMotion: boolean
  children: React.ReactNode
}) {
  return (
    <div
      className={`${className} transition-all duration-700 ${
        isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
      }`}
      style={{ transitionDelay: reducedMotion ? '0ms' : `${delay}ms` }}
    >
      {/* connector line to the phone edge */}
      <span
        aria-hidden
        className={`absolute ${lineClass} h-px bg-gradient-to-r from-white/40 to-transparent`}
      >
        <span className="absolute -top-[2px] left-0 h-[5px] w-[5px] rounded-full bg-white/60" />
      </span>
      <div className="rounded-2xl bg-black/55 backdrop-blur-md border border-white/15 px-3.5 py-2.5 shadow-xl shadow-black/40">
        {children}
      </div>
    </div>
  )
}
