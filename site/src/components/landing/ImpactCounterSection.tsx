'use client'

import Link from 'next/link'
import { HeartHandshake } from 'lucide-react'
import { ImpactCounter } from '@/components/shared/ImpactCounter'
import { SectionHeading } from './SectionHeading'

export function ImpactCounterSection() {
  return (
    <section id="impact" className="py-28 lg:py-32 bg-gradient-to-br from-brand-green-800 to-brand-green-900 text-white">
      <div className="section-container">
        {/* Section header */}
        <div className="mb-16 lg:mb-20">
          <SectionHeading
            tone="dark"
            eyebrow="Live impact"
            eyebrowIcon={
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-brand-green-300 opacity-75" />
                <span className="relative inline-flex rounded-full h-2 w-2 bg-brand-green-300" />
              </span>
            }
            title="Building a Movement"
            description="Real-time numbers showing how our community is transforming North Lawndale, one block at a time."
          />
        </div>

        {/* Live counters (shared component) */}
        <div className="mb-16 lg:mb-20">
          <ImpactCounter layout="grid" />
        </div>

        {/* 100-block pilot progress — milestone figures are Blake-reported field totals
            (cleanups + payouts run through the worker app, not the site's sponsor DB),
            so they're curated here rather than computed. Update as the pilot advances. */}
        <div className="max-w-3xl mx-auto mb-16 lg:mb-20">
          <div className="bg-black/25 backdrop-blur-sm rounded-2xl p-6 sm:p-8 border border-white/15">
            <div className="flex flex-col sm:flex-row sm:items-end sm:justify-between gap-2 mb-4">
              <div>
                <p className="text-xs font-bold uppercase tracking-[0.2em] text-brand-green-300 mb-1">100-block pilot</p>
                <p className="text-2xl sm:text-3xl font-bold text-white">
                  20<span className="text-brand-green-300">/100</span> blocks cleaned
                </p>
              </div>
              <p className="text-brand-green-100 text-sm sm:text-base font-medium sm:text-right">
                <span className="font-bold text-white">$2,000</span> paid to residents so far
              </p>
            </div>
            <div
              className="h-3 w-full rounded-full bg-white/10 overflow-hidden"
              role="progressbar"
              aria-valuenow={20}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label="100-block pilot progress: 20 of 100 blocks cleaned"
            >
              <div className="h-full w-[20%] rounded-full bg-gradient-to-r from-brand-green-400 to-brand-sky-400" />
            </div>
            <p className="text-xs text-brand-green-200/80 mt-3">
              Every block is before-and-after documented by the workers who cleaned it.
            </p>
          </div>
        </div>

        {/* Transparency callout */}
        <div className="max-w-3xl mx-auto mb-16 lg:mb-20">
          <div className="bg-gradient-to-r from-brand-green-600/30 to-brand-green-500/30 rounded-2xl p-6 sm:p-8 border border-brand-green-500/30">
            <div className="flex flex-col sm:flex-row items-center gap-4 text-center sm:text-left">
              <div className="w-16 h-16 rounded-2xl bg-brand-green-500/30 flex items-center justify-center flex-shrink-0">
                <HeartHandshake className="w-8 h-8 text-brand-green-200" aria-hidden="true" />
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
            className="px-8 py-3 text-lg font-semibold rounded-full border-2 border-white/80 bg-white/10 text-white hover:bg-white/20 transition-all"
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
