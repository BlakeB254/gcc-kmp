'use client'

import React, { useState, useEffect, useRef, useSyncExternalStore } from 'react'
import { Users, ArrowRight, CheckCircle2, DollarSign } from 'lucide-react'

const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'

/** Live prefers-reduced-motion, read without setting state inside an effect. */
function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const query = window.matchMedia(REDUCED_MOTION)
      query.addEventListener('change', onChange)
      return () => query.removeEventListener('change', onChange)
    },
    () => window.matchMedia(REDUCED_MOTION).matches,
    () => false,
  )
}

/** Counts up to `target` once, the first time it scrolls into view. */
function AnimatedCounter({
  target = 100,
  prefix = '$',
  duration = 1800,
  instant = false,
}: {
  target?: number
  prefix?: string
  duration?: number
  instant?: boolean
}) {
  const [displayValue, setDisplayValue] = useState(0)
  const ref = useRef<HTMLSpanElement>(null)

  useEffect(() => {
    const el = ref.current
    if (!el || instant) return
    let frame = 0
    let started = false
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting || started) return
        started = true
        observer.disconnect()
        let startTime: number | null = null
        const animate = (timestamp: number) => {
          if (startTime === null) startTime = timestamp
          const progress = Math.min((timestamp - startTime) / duration, 1)
          const eased = 1 - Math.pow(1 - progress, 3) // easeOutCubic
          setDisplayValue(progress < 1 ? Math.floor(target * eased) : target)
          if (progress < 1) frame = requestAnimationFrame(animate)
        }
        frame = requestAnimationFrame(animate)
      },
      { threshold: 0.6 },
    )
    observer.observe(el)
    return () => {
      observer.disconnect()
      cancelAnimationFrame(frame)
    }
  }, [target, duration, instant])

  return (
    <span ref={ref} className="tabular-nums">
      {prefix}{instant ? target : displayValue}
    </span>
  )
}

export function MoneyBreakdown() {
  const containerRef = useRef<HTMLDivElement>(null)
  const [isInView, setIsInView] = useState(false)
  const reducedMotion = usePrefersReducedMotion()

  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => setIsInView(entry.isIntersecting),
      { threshold: 0.3 },
    )
    if (containerRef.current) observer.observe(containerRef.current)
    return () => observer.disconnect()
  }, [])

  const pop = isInView ? 'scale-100' : 'scale-95'

  return (
    <div
      ref={containerRef}
      className="w-full bg-brand-green-950 border border-brand-green-700/80 rounded-3xl p-5 sm:p-6 shadow-2xl relative overflow-hidden"
    >
      {/* Subtle background pattern for life */}
      {!reducedMotion && (
        <div className="absolute inset-0 opacity-[0.03] bg-[radial-gradient(#22c55e_0.8px,transparent_1px)] bg-[length:20px_20px]" />
      )}

      <div className="relative z-10">
        {/* Header */}
        <div className="text-center md:text-left md:flex md:items-end md:justify-between md:gap-4 mb-5">
          <div>
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-brand-green-900 border border-brand-green-600 mb-3">
              <div className="w-2 h-2 bg-brand-green-400 rounded-full animate-pulse" />
              <span className="text-[11px] font-mono tracking-[0.125em] text-brand-green-400 font-medium">100% TRANSPARENT</span>
            </div>
            <h4 className="text-2xl font-bold text-white tracking-tighter">WHERE THE $100 GOES</h4>
          </div>
          <p className="text-brand-green-400 text-sm mt-1 md:mt-0 md:pb-1">Every donation. Every time.</p>
        </div>

        {/* Side by side at every width, with the money flowing between the two cards. */}
        <div className="grid grid-cols-[1fr_auto_1fr] items-stretch">
          {/* Sponsor Pays */}
          <div className="relative bg-white/5 backdrop-blur-xl border border-brand-green-600 rounded-2xl px-2 py-4 sm:p-5 text-center">
            <div className="flex justify-center mb-2 sm:mb-3">
              <div className="w-8 h-8 sm:w-10 sm:h-10 rounded-xl bg-brand-green-900 flex items-center justify-center border border-brand-green-700">
                <Users className="w-5 h-5 text-brand-green-400" />
              </div>
            </div>
            <div className="text-brand-green-300 text-[10px] sm:text-xs font-semibold tracking-wider sm:tracking-widest mb-1">SPONSOR PAYS</div>
            <div className={`text-4xl sm:text-5xl leading-none font-bold text-white transition-transform duration-300 ${pop}`}>
              <AnimatedCounter target={100} prefix="$" duration={1400} instant={reducedMotion} />
            </div>
            <div className="mt-2 sm:mt-3 text-[11px] sm:text-xs text-brand-green-400">Card · ACH · Stripe</div>
            <div className="text-[11px] sm:text-xs text-brand-green-500/80">Tax-deductible ready</div>
            {!reducedMotion && isInView && (
              <div className="absolute top-2 right-2 w-2.5 h-2.5 bg-emerald-400 rounded-full animate-ping" />
            )}
          </div>

          {/* Flow */}
          <div className="relative flex w-7 sm:w-14 items-center [--money-hop:12px] sm:[--money-hop:34px]" aria-hidden="true">
            <div className="w-full border-t-2 border-dashed border-brand-green-500/50" />
            {!reducedMotion && isInView && [0, 650, 1300].map((delay) => (
              <DollarSign
                key={delay}
                className="absolute left-0 top-1/2 -mt-1.5 w-3 h-3 sm:-mt-2 sm:w-4 sm:h-4 text-brand-green-300 animate-money-slide"
                style={{ animationDelay: `${delay}ms` }}
              />
            ))}
            <ArrowRight className="absolute -right-0.5 top-1/2 -translate-y-1/2 w-4 h-4 sm:w-5 sm:h-5 text-brand-green-400" />
          </div>

          {/* Neighbor Earns */}
          <div className="bg-gradient-to-br from-brand-green-600 to-brand-green-700 border border-brand-green-400 rounded-2xl px-2 py-4 sm:p-5 text-center text-white">
            <div className="flex justify-center mb-2 sm:mb-3">
              <div className="w-8 h-8 sm:w-10 sm:h-10 rounded-xl bg-white/20 flex items-center justify-center border border-white/30">
                <CheckCircle2 className="w-5 h-5 text-white" />
              </div>
            </div>
            <div className="text-white/90 text-[10px] sm:text-xs font-semibold tracking-wider sm:tracking-widest mb-1">NEIGHBOR EARNS</div>
            <div className={`text-4xl sm:text-5xl leading-none font-bold text-white transition-transform duration-300 ${pop}`}>
              <AnimatedCounter target={100} prefix="$" duration={1600} instant={reducedMotion} />
            </div>
            <div className="mt-2 sm:mt-3 text-[11px] sm:text-xs text-white/80">Same-day Stripe payout</div>
            <div className="text-[11px] sm:text-xs text-white/60">Verified local resident</div>
          </div>
        </div>

        {/* 0% Overhead: one compact row */}
        <div className="mt-4 bg-black/40 border border-brand-green-500/50 rounded-2xl px-5 py-4 backdrop-blur flex flex-col sm:flex-row items-center gap-3 sm:gap-5 text-center sm:text-left">
          <div className="flex items-baseline gap-1 shrink-0">
            <span className={`text-5xl font-black text-brand-green-400 tabular-nums transition-opacity ${isInView ? '' : 'opacity-70'}`}>0%</span>
            <span className="uppercase text-xs tracking-[0.1em] text-brand-green-300 font-medium">overhead</span>
          </div>
          <div className="text-sm text-brand-green-200 leading-snug">
            taken from the cleaning fee.
            <span className="block text-[11px] text-brand-green-500 mt-1">Add-ons (vests, signage, photos) priced separately.</span>
          </div>
        </div>

        <p className="text-center mt-4 text-[11px] text-brand-green-500/80 font-medium">
          This is paid infrastructure. Most cleanup programs are charity.
        </p>
      </div>
    </div>
  )
}
