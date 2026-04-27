'use client'

import Link from 'next/link'
import { ImpactCounter } from '@/components/shared/ImpactCounter'

export function ImpactCounterSection() {
  return (
    <section id="impact" className="py-28 lg:py-32 bg-gradient-to-br from-brand-green-800 to-brand-green-900 text-white">
      <div className="section-container">
        {/* Section header */}
        <div className="text-center mb-16 lg:mb-20">
          <div className="inline-flex items-center gap-2 rounded-full bg-brand-green-500/90 px-4 py-2 mb-6">
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-white opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-white" />
            </span>
            <span className="text-sm font-medium text-white tracking-wide">Live Impact</span>
          </div>
          <h2 className="text-3xl sm:text-4xl font-bold mb-6">Building a Movement</h2>
          <p className="text-lg text-brand-green-200 max-w-2xl mx-auto">
            Real-time numbers showing how our community is transforming North Lawndale, one block at a time.
          </p>
        </div>

        {/* Live counters (shared component) */}
        <div className="mb-16 lg:mb-20">
          <ImpactCounter layout="grid" />
        </div>

        {/* Transparency callout */}
        <div className="max-w-3xl mx-auto mb-16 lg:mb-20">
          <div className="bg-gradient-to-r from-brand-green-600/30 to-brand-green-500/30 rounded-2xl p-6 sm:p-8 border border-brand-green-500/30">
            <div className="flex flex-col sm:flex-row items-center gap-4 text-center sm:text-left">
              <div className="w-16 h-16 rounded-2xl bg-brand-green-500/30 flex items-center justify-center flex-shrink-0">
                <span className="text-3xl">💚</span>
              </div>
              <div>
                <h3 className="text-xl font-bold text-white mb-2">100% Transparent</h3>
                <p className="text-brand-green-100">
                  <strong>Every $100 donated goes directly to a worker.</strong> GCC operates on separate funding—
                  your sponsorship is 100% wages for the resident cleaning that block.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* CTAs */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
          <Link
            href="/impact"
            className="px-8 py-3 text-lg font-semibold rounded-lg border-2 border-white/80 bg-white/10 text-white hover:bg-white/20 transition-all"
          >
            See Full Impact
          </Link>
          <Link
            href="/donate"
            className="btn-primary px-8 py-3 text-lg font-semibold"
          >
            Contribute Now
          </Link>
        </div>
      </div>
    </section>
  )
}
