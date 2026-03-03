'use client'

import { useEffect, useState } from 'react'

export function NLHeroSection() {
  const [isVisible, setIsVisible] = useState(false)

  useEffect(() => {
    setIsVisible(true)
  }, [])

  return (
    <section className="relative min-h-[100svh] sm:min-h-screen flex items-end sm:items-center justify-center pt-16 pb-20 sm:pb-0 overflow-hidden">
      {/* Background */}
      <div className="absolute inset-0 bg-black">
        <div className="absolute inset-0 bg-gradient-to-b from-brand-brown-900/90 via-brand-brown-800/70 to-black/95" />
        {/* Subtle pattern overlay */}
        <div className="absolute inset-0 opacity-[0.03]" style={{
          backgroundImage: `url("data:image/svg+xml,%3Csvg width='60' height='60' viewBox='0 0 60 60' xmlns='http://www.w3.org/2000/svg'%3E%3Cg fill='none' fill-rule='evenodd'%3E%3Cg fill='%23ffffff' fill-opacity='1'%3E%3Cpath d='M36 34v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zm0-30V0h-2v4h-4v2h4v4h2V6h4V4h-4zM6 34v-4H4v4H0v2h4v4h2v-4h4v-2H6zM6 4V0H4v4H0v2h4v4h2V6h4V4H6z'/%3E%3C/g%3E%3C/g%3E%3C/svg%3E")`
        }} />
      </div>

      <div className="section-container relative z-10 py-golden-2xl">
        <div className="max-w-4xl mx-auto text-center bg-black/30 backdrop-blur-[2px] sm:bg-transparent sm:backdrop-blur-none rounded-3xl p-5 sm:p-0">
          {/* Badge */}
          <div
            className={`inline-flex items-center gap-2 rounded-full bg-brand-gold-500/90 px-4 py-2 mb-6 sm:mb-8 transition-all duration-700 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
            }`}
          >
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-white opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-white" />
            </span>
            <span className="text-sm font-medium text-white tracking-wide">
              Now Serving North Lawndale
            </span>
          </div>

          {/* Headline */}
          <h1
            className={`text-4xl sm:text-5xl lg:text-7xl font-bold tracking-tight text-white mb-6 sm:mb-8 transition-all duration-700 delay-100 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
            }`}
          >
            Dr. King Chose{' '}
            <span className="text-brand-gold-400 animate-glow">North Lawndale.</span>
            <br />
            <span className="inline-block mt-3">So Did We.</span>
          </h1>

          {/* Subheadline */}
          <p
            className={`text-lg sm:text-xl text-neutral-200 max-w-3xl mx-auto mb-10 sm:mb-12 leading-relaxed transition-all duration-700 delay-200 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
            }`}
          >
            In 1966, Dr. King moved here to fight for dignity. Today, GCC is paying residents
            to restore pride in their own blocks.{' '}
            <span className="font-semibold text-brand-gold-300">
              1 person. 1 block. $100. Same day.
            </span>
          </p>

          {/* CTA */}
          <div
            className={`flex flex-col items-center gap-4 transition-all duration-700 delay-300 ${
              isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
            }`}
          >
            <a
              href="#join"
              className="group btn-primary text-lg py-4 px-8 animate-breathe hover:animate-none relative overflow-hidden"
            >
              <span className="relative z-10 flex items-center justify-center gap-2">
                Join the Movement
                <svg
                  className="w-5 h-5 transition-transform duration-300 group-hover:translate-x-1"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 14l-7 7m0 0l-7-7m7 7V3" />
                </svg>
              </span>
            </a>
            <p className="text-sm text-neutral-400">
              No phone necessary &middot; Group activity &middot; Food provided
            </p>
          </div>

          {/* Stats bar */}
          <div
            className={`hidden sm:block mt-12 max-w-2xl mx-auto bg-black/40 backdrop-blur-md rounded-2xl p-golden-md border border-white/10 transition-all duration-700 delay-400 ${
              isVisible ? 'opacity-100 translate-y-0 scale-100' : 'opacity-0 translate-y-8 scale-95'
            }`}
          >
            <div className="grid grid-cols-3 gap-4 sm:gap-6">
              <div className="text-center group hover-scale cursor-default">
                <p className="text-2xl sm:text-4xl font-bold text-brand-gold-400 mb-1">$100</p>
                <p className="text-xs sm:text-sm text-neutral-300 font-medium">Per Block</p>
                <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">paid same day</p>
              </div>
              <div className="text-center border-x border-white/10 px-2 group hover-scale cursor-default">
                <p className="text-2xl sm:text-4xl font-bold text-brand-green-400 mb-1">Group</p>
                <p className="text-xs sm:text-sm text-neutral-300 font-medium">Activity</p>
                <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">no phone needed</p>
              </div>
              <div className="text-center group hover-scale cursor-default">
                <p className="text-2xl sm:text-4xl font-bold text-brand-sky-400 mb-1">Free</p>
                <p className="text-xs sm:text-sm text-neutral-300 font-medium">Food After</p>
                <p className="text-[10px] sm:text-xs text-neutral-400 mt-1">community meal</p>
              </div>
            </div>
          </div>
        </div>

        {/* Scroll indicator */}
        <div
          className={`absolute bottom-8 left-1/2 -translate-x-1/2 transition-all duration-700 delay-700 ${
            isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
          }`}
        >
          <div className="flex flex-col items-center gap-2 text-neutral-400 hover:text-white transition-colors cursor-pointer group">
            <span className="text-xs uppercase tracking-widest">Discover</span>
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
