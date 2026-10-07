'use client'

import React, { useState, useEffect, useRef, useSyncExternalStore } from 'react'
import { Users, ArrowDown, CheckCircle2, DollarSign } from 'lucide-react'

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

  return (
    <div 
      ref={containerRef}
      className="mx-auto lg:mx-0 w-full max-w-sm bg-brand-green-950 border border-brand-green-700/80 rounded-3xl p-6 sm:p-8 shadow-2xl relative overflow-hidden"
    >
      {/* Subtle background pattern for life */}
      {!reducedMotion && (
        <div className="absolute inset-0 opacity-[0.03] bg-[radial-gradient(#22c55e_0.8px,transparent_1px)] bg-[length:20px_20px]" />
      )}

      <div className="relative z-10">
        {/* Header */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full bg-brand-green-900 border border-brand-green-600 mb-4">
            <div className="w-2 h-2 bg-brand-green-400 rounded-full animate-pulse" />
            <span className="text-xs font-mono tracking-[0.125em] text-brand-green-400 font-medium">100% TRANSPARENT</span>
          </div>
          <h4 className="text-2xl font-bold text-white tracking-tighter">WHERE THE $100 GOES</h4>
          <p className="text-brand-green-400 text-sm mt-1">Every donation. Every time.</p>
        </div>

        {/* Stacked: the panel is at most max-w-sm wide, too narrow for the two cards side by side. */}
        <div className="flex flex-col gap-3 relative">
          {/* Sponsor Pays */}
          <div className="relative bg-white/5 backdrop-blur-xl border border-brand-green-600 rounded-2xl p-6 text-center group">
            <div className="flex justify-center mb-4">
              <div className="w-11 h-11 rounded-2xl bg-brand-green-900 flex items-center justify-center border border-brand-green-700">
                <Users className="w-6 h-6 text-brand-green-400" />
              </div>
            </div>
            
            <div className="text-brand-green-300 text-sm font-semibold tracking-widest mb-2">SPONSOR PAYS</div>
            
            <div className={`text-[3.25rem] leading-none font-bold text-white transition-all duration-300 ${isInView ? 'scale-100' : 'scale-95'}`}>
              <AnimatedCounter target={100} prefix="$" duration={1400} instant={reducedMotion} />
            </div>
            
            <div className="mt-4 space-y-1 text-xs">
              <div className="text-brand-green-400">Card · ACH · Stripe</div>
              <div className="text-brand-green-500/80">Tax-deductible ready</div>
            </div>
            
            {/* Subtle live indicator */}
            {!reducedMotion && isInView && (
              <div className="absolute -top-1 -right-1 w-3 h-3 bg-emerald-400 rounded-full animate-ping" />
            )}
          </div>

          {/* Flow connector: money drops from the sponsor card to the neighbor card. */}
          <div className="relative h-12 flex justify-center" aria-hidden="true">
            <div className="h-full border-l-2 border-dashed border-brand-green-500/50" />
            {!reducedMotion && isInView && [0, 650, 1300].map((delay) => (
              <DollarSign
                key={delay}
                className="absolute top-0 left-1/2 -ml-2 w-4 h-4 text-brand-green-300 animate-money-drop"
                style={{ animationDelay: `${delay}ms` }}
              />
            ))}
            <ArrowDown className="absolute -bottom-1 left-1/2 -translate-x-1/2 w-5 h-5 text-brand-green-400" />
          </div>

          {/* Neighbor Earns */}
          <div className="bg-gradient-to-br from-brand-green-600 to-brand-green-700 border border-brand-green-400 rounded-2xl p-6 text-center text-white relative">
            <div className="flex justify-center mb-4">
              <div className="w-11 h-11 rounded-2xl bg-white/20 flex items-center justify-center border border-white/30">
                <CheckCircle2 className="w-6 h-6 text-white" />
              </div>
            </div>
            
            <div className="text-white/90 text-sm font-semibold tracking-widest mb-2">NEIGHBOR EARNS</div>
            
            <div className={`text-[3.25rem] leading-none font-bold text-white transition-all duration-300 ${isInView ? 'scale-100' : 'scale-95'}`}>
              <AnimatedCounter target={100} prefix="$" duration={1600} instant={reducedMotion} />
            </div>
            
            <div className="mt-4 space-y-1 text-xs text-white/80">
              <div>Same-day Stripe payout</div>
              <div className="text-white/60">Verified local resident</div>
            </div>
          </div>
        </div>

        {/* 0% Overhead Banner */}
        <div className="mt-8 bg-black/40 border border-brand-green-500/50 rounded-2xl p-5 text-center backdrop-blur">
          <div className="flex items-center justify-center gap-4">
            <div className={`text-6xl font-black text-brand-green-400 tabular-nums transition-all ${isInView ? '' : 'opacity-70'}`}>0</div>
            <div>
              <div className="text-4xl font-bold text-white -mt-2">%</div>
              <div className="uppercase text-xs tracking-[0.1em] text-brand-green-300 font-medium -mt-1">OVERHEAD</div>
            </div>
          </div>
          <p className="text-brand-green-200 text-sm mt-3 leading-tight">
            taken from the cleaning fee
          </p>
          <p className="text-[10px] text-brand-green-500 mt-4 border-t border-brand-green-700 pt-3">
            Add-ons (vests, signage, photos) priced separately
          </p>
        </div>

        {/* Footer note */}
        <div className="text-center mt-6 text-[10px] text-brand-green-500/75 font-medium">
          This is paid infrastructure.<br />Most cleanup programs are charity.
        </div>
      </div>

    </div>
  )
}
