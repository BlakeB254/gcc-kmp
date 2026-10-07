'use client'

import React, { useState, useEffect, useRef } from 'react'
import { Users, ArrowRight, CheckCircle2, DollarSign } from 'lucide-react'

function AnimatedCounter({ 
  target = 100, 
  prefix = '$', 
  duration = 1800 
}: { 
  target?: number
  prefix?: string
  duration?: number 
}) {
  const [displayValue, setDisplayValue] = useState(0)
  const [hasAnimated, setHasAnimated] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const inViewRef = useRef(false)

  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && !hasAnimated) {
          inViewRef.current = true
          startAnimation()
        }
      },
      { threshold: 0.6 }
    )

    if (ref.current) {
      observer.observe(ref.current)
    }

    return () => observer.disconnect()
  }, [hasAnimated])

  const startAnimation = () => {
    if (hasAnimated) return

    let startTime: number | null = null
    const animate = (timestamp: number) => {
      if (!startTime) startTime = timestamp
      const progress = Math.min((timestamp - startTime) / duration, 1)
      const eased = 1 - Math.pow(1 - progress, 3) // easeOutCubic
      const current = Math.floor(target * eased)
      
      setDisplayValue(current)

      if (progress < 1) {
        requestAnimationFrame(animate)
      } else {
        setDisplayValue(target)
        setHasAnimated(true)
      }
    }

    requestAnimationFrame(animate)
  }

  return (
    <span ref={ref} className="tabular-nums">
      {prefix}{displayValue}
    </span>
  )
}

export function MoneyBreakdown() {
  const containerRef = useRef<HTMLDivElement>(null)
  const [isInView, setIsInView] = useState(false)
  const [reducedMotion, setReducedMotion] = useState(false)

  useEffect(() => {
    const mediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    setReducedMotion(mediaQuery.matches)

    const handleChange = (e: MediaQueryListEvent) => setReducedMotion(e.matches)
    mediaQuery.addEventListener('change', handleChange)

    const observer = new IntersectionObserver(
      ([entry]) => setIsInView(entry.isIntersecting),
      { threshold: 0.3 }
    )

    if (containerRef.current) {
      observer.observe(containerRef.current)
    }

    return () => {
      mediaQuery.removeEventListener('change', handleChange)
      observer.disconnect()
    }
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
              <AnimatedCounter target={100} prefix="$" duration={1400} />
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

          {/* Flow connector */}
          <div className="flex items-center justify-center gap-1 pointer-events-none" aria-hidden="true">
            <div className="relative flex items-center justify-center w-16 h-16">
              {/* Flowing path SVG */}
              <svg 
                width="64" 
                height="64" 
                viewBox="0 0 64 64" 
                className="absolute text-brand-green-400/30"
                style={{ transform: 'rotate(-12deg)' }}
              >
                <path 
                  d="M12 52 Q 26 38, 38 26 Q 50 14, 52 12" 
                  fill="none" 
                  stroke="currentColor" 
                  strokeWidth="3" 
                  strokeDasharray="8 6"
                  className={isInView && !reducedMotion ? 'animate-dash' : ''}
                />
              </svg>
              
              {/* Flying dollars */}
              {!reducedMotion && isInView && (
                <>
                  <DollarSign 
                    className="absolute w-5 h-5 text-brand-green-300 animate-money-flow drop-shadow-sm" 
                    style={{ animationDelay: '0ms', animationDuration: '2.2s', left: '20%', top: '30%' }} 
                  />
                  <DollarSign 
                    className="absolute w-4 h-4 text-brand-green-400 animate-money-flow drop-shadow-sm" 
                    style={{ animationDelay: '600ms', animationDuration: '2.8s', left: '45%', top: '55%' }} 
                  />
                  <DollarSign 
                    className="absolute w-6 h-6 text-brand-green-200 animate-money-flow drop-shadow-sm" 
                    style={{ animationDelay: '1200ms', animationDuration: '1.9s', left: '65%', top: '25%' }} 
                  />
                </>
              )}
            </div>
            <ArrowRight className="w-6 h-6 text-brand-green-400 rotate-90" />
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
              <AnimatedCounter target={100} prefix="$" duration={1600} />
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
