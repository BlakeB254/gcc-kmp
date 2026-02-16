'use client'

import Link from 'next/link'
import { useState, useEffect } from 'react'

/**
 * Waitlist Expansion Section
 * Invites users outside North Lawndale to join the global waitlist
 */
export function WaitlistExpansionSection() {
  const [currentCity, setCurrentCity] = useState(0)
  const [isHovered, setIsHovered] = useState(false)
  const expandingCities = ['Los Angeles', 'New York', 'Houston', 'Phoenix', 'Philadelphia', 'Atlanta', 'Miami', 'Seattle', 'Denver', 'Detroit']

  useEffect(() => {
    const interval = setInterval(() => {
      setCurrentCity((prev) => (prev + 1) % expandingCities.length)
    }, 2000)
    return () => clearInterval(interval)
  }, [expandingCities.length])

  return (
    <section className="py-16 lg:py-20 bg-neutral-50">
      <div className="section-container">
        <div className="relative overflow-hidden" onMouseEnter={() => setIsHovered(true)} onMouseLeave={() => setIsHovered(false)}>
          {/* Topographic background pattern */}
          <div className="absolute inset-0 opacity-[0.03]">
            <svg className="w-full h-full" viewBox="0 0 400 200" preserveAspectRatio="xMidYMid slice">
              <defs>
                <pattern id="topo-pattern-waitlist" x="0" y="0" width="100" height="100" patternUnits="userSpaceOnUse">
                  <circle cx="50" cy="50" r="45" fill="none" stroke="currentColor" strokeWidth="0.5" />
                  <circle cx="50" cy="50" r="35" fill="none" stroke="currentColor" strokeWidth="0.5" />
                  <circle cx="50" cy="50" r="25" fill="none" stroke="currentColor" strokeWidth="0.5" />
                  <circle cx="50" cy="50" r="15" fill="none" stroke="currentColor" strokeWidth="0.5" />
                </pattern>
              </defs>
              <rect width="100%" height="100%" fill="url(#topo-pattern-waitlist)" />
            </svg>
          </div>

          <div className={`relative rounded-3xl bg-gradient-to-br from-brand-green-900 via-brand-green-800 to-brand-brown-900 p-golden-lg md:p-golden-xl border border-brand-green-700/30 transition-all duration-500 ${isHovered ? 'scale-[1.01] shadow-2xl' : ''}`}>
            {/* Decorative globe element */}
            <div className="absolute top-4 right-4 md:top-8 md:right-8 w-24 h-24 md:w-32 md:h-32 opacity-10">
              <svg viewBox="0 0 100 100" className="w-full h-full text-brand-gold-400">
                <circle cx="50" cy="50" r="45" fill="none" stroke="currentColor" strokeWidth="1" />
                <ellipse cx="50" cy="50" rx="45" ry="18" fill="none" stroke="currentColor" strokeWidth="0.5" />
                <ellipse cx="50" cy="50" rx="18" ry="45" fill="none" stroke="currentColor" strokeWidth="0.5" />
                <line x1="5" y1="50" x2="95" y2="50" stroke="currentColor" strokeWidth="0.5" />
                <line x1="50" y1="5" x2="50" y2="95" stroke="currentColor" strokeWidth="0.5" />
              </svg>
            </div>

            <div className="relative z-10 max-w-3xl">
              {/* Badge */}
              <div className="inline-flex items-center gap-2 rounded-full bg-brand-gold-500/20 border border-brand-gold-500/30 px-golden-sm py-2 mb-golden-md">
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-brand-gold-400 opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-brand-gold-500"></span>
                </span>
                <span className="text-sm font-medium text-brand-gold-300 tracking-wide">
                  Expansion Coming Soon
                </span>
              </div>

              {/* Headline */}
              <h3 className="text-2xl sm:text-3xl md:text-4xl font-bold text-white mb-golden-sm leading-tight">
                Not in North Lawndale?
                <br />
                <span className="text-brand-gold-400 animate-glow">Secure your position now.</span>
              </h3>

              {/* Description */}
              <p className="text-lg text-brand-green-100/90 mb-golden-md max-w-golden-content leading-relaxed">
                We&apos;re starting in Chicago, but this is a <span className="font-semibold text-white">global movement</span>.
                Join the waitlist to lock in your permanent rank — by city, state, country, and worldwide.
                The earlier you join, the higher you stay forever.
              </p>

              {/* Animated city ticker */}
              <div className="flex items-center gap-golden-xs mb-golden-lg text-brand-green-200/70">
                <span className="text-sm">Coming soon to</span>
                <div className="relative h-6 overflow-hidden min-w-[120px]">
                  <span
                    key={currentCity}
                    className="absolute inset-0 flex items-center text-brand-gold-400 font-semibold animate-fade-in-up"
                  >
                    {expandingCities[currentCity]}
                  </span>
                </div>
                <span className="text-sm">and more...</span>
              </div>

              {/* Ranking preview cards with hover effects */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-golden-xs mb-golden-lg">
                {[
                  { label: 'Global', icon: '🌍', example: '#147' },
                  { label: 'Country', icon: '🇺🇸', example: '#89' },
                  { label: 'State', icon: '📍', example: '#23' },
                  { label: 'City', icon: '🏙️', example: '#7' },
                ].map((rank, index) => (
                  <div
                    key={rank.label}
                    className="bg-white/5 backdrop-blur-sm rounded-xl p-golden-xs border border-white/10 hover:border-brand-gold-500/40 hover:bg-white/10 transition-all duration-300 group hover-scale cursor-default"
                    style={{ animationDelay: `${index * 100}ms` }}
                  >
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-lg group-hover:scale-110 transition-transform">{rank.icon}</span>
                      <span className="text-xs text-brand-green-200/60 uppercase tracking-wider">{rank.label}</span>
                    </div>
                    <p className="text-xl font-bold text-white group-hover:text-brand-gold-400 transition-colors">
                      {rank.example}
                    </p>
                  </div>
                ))}
              </div>

              {/* CTA Buttons with enhanced interactions */}
              <div className="flex flex-col sm:flex-row gap-golden-sm">
                <Link
                  href="/join"
                  className="group inline-flex items-center justify-center gap-golden-xs bg-brand-gold-500 hover:bg-brand-gold-400 text-white font-bold text-lg px-golden-lg py-4 rounded-xl transition-all transform hover:scale-[1.02] active:scale-[0.98] shadow-lg shadow-brand-gold-500/20 hover:shadow-xl hover:shadow-brand-gold-500/30 animate-breathe hover:animate-none"
                >
                  <svg className="w-5 h-5 group-hover:rotate-12 transition-transform duration-300" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                  <span>Join the Global Waitlist</span>
                  <svg className="w-4 h-4 opacity-0 -translate-x-2 group-hover:opacity-100 group-hover:translate-x-0 transition-all duration-300" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                  </svg>
                </Link>
                <div className="flex items-center gap-2 text-brand-green-200/60 text-sm">
                  <svg className="w-4 h-4 animate-pulse" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
                  </svg>
                  <span>Your rank is permanent once locked</span>
                </div>
              </div>
            </div>

            {/* Decorative dots grid */}
            <div className="absolute bottom-4 left-4 md:bottom-8 md:left-8 grid grid-cols-5 gap-1 opacity-20">
              {Array.from({ length: 25 }).map((_, i) => (
                <div
                  key={i}
                  className="w-1 h-1 rounded-full bg-brand-gold-400"
                  style={{
                    opacity: Math.random() * 0.5 + 0.5,
                  }}
                />
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
