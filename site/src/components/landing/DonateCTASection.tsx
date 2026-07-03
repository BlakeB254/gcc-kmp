'use client'

import { useState, useEffect } from 'react'
import Image from 'next/image'
import { DonateCard } from '@/components/shared/DonateCard'

export function DonateCTASection() {
  const [recentDonorCount, setRecentDonorCount] = useState<number | null>(null)

  useEffect(() => {
    async function fetchDonorCount() {
      try {
        const res = await fetch('/api/public/support-wall?view=recent&limit=1')
        if (res.ok) {
          const data = await res.json()
          setRecentDonorCount(data.stats?.recentContributors || 0)
        }
      } catch {
        // Silently fail
      }
    }
    fetchDonorCount()
  }, [])

  return (
    <section id="donate" className="relative py-28 lg:py-32 bg-brand-green-900 text-white overflow-hidden">
      {/* Subtle background photo — ties the ask back to the people it funds without competing with the donation form */}
      <div className="absolute inset-0">
        <Image
          src="/images/optimized/photo-community-celebration.webp"
          alt=""
          fill
          className="object-cover opacity-[0.12]"
          sizes="100vw"
        />
        <div className="absolute inset-0 bg-gradient-to-b from-brand-green-900 via-brand-green-900/95 to-brand-green-900" />
      </div>

      <div className="section-container relative">
        {/* Two columns on desktop: the pitch + proof on the left, the actual ask on the right — instead of one narrow centered stack */}
        <div className="grid lg:grid-cols-[1.05fr_0.95fr] gap-12 lg:gap-16 items-start">
          <div className="text-center lg:text-left">
            {/* Badge */}
            <div className="inline-flex items-center gap-2 rounded-full bg-brand-green-800 px-4 py-2 mb-6">
              <svg className="w-4 h-4 text-brand-green-300" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span className="text-sm font-medium text-brand-green-200">General Fund</span>
            </div>

            <h2 className="text-3xl sm:text-4xl font-bold mb-4">
              Every Dollar Builds Community
            </h2>
            <p className="text-lg text-brand-green-200 mb-4 max-w-xl mx-auto lg:mx-0">
              $1 from 100 people cleans a block. That&apos;s the power of us.
              Every single dollar goes directly to paying workers.
            </p>
            <p className="text-base text-brand-green-300 mb-6 max-w-xl mx-auto lg:mx-0 font-medium">
              No donation is too small—$1, $5, whatever you can give matters. When hundreds of us each give a little, we move mountains together. Our collective power is our superpower.
            </p>
            {recentDonorCount !== null && recentDonorCount > 0 && (
              <div className="inline-flex items-center gap-2 bg-brand-green-800/60 rounded-full px-4 py-2 mb-8">
                <span className="text-2xl">🤝</span>
                <span className="text-sm text-brand-green-100">
                  <strong>{recentDonorCount} {recentDonorCount === 1 ? 'person' : 'people'}</strong> donated this month — join them
                </span>
              </div>
            )}

            {/* Where the $100 goes — real GCC brand graphic, not a generated one */}
            <div className="mx-auto lg:mx-0 max-w-xs lg:max-w-sm">
              <Image
                src="/images/optimized/money-breakdown.webp"
                alt="Where the $100 goes: sponsor pays $100, neighbor earns $100, 0% overhead taken from the cleaning fee"
                width={1080}
                height={1080}
                className="w-full rounded-xl"
              />
            </div>
          </div>

          {/* Reusable DonateCard */}
          <div className="lg:sticky lg:top-24">
            <DonateCard presets={[5, 25, 50, 100]} showCustom showFrequency={false} />
          </div>
        </div>

        {/* Other ways to give — a wide row instead of stacked in a narrow column */}
        <div className="mt-10 grid lg:grid-cols-3 gap-6">
          <div className="lg:col-span-1 bg-brand-green-800/70 rounded-2xl p-6 text-left">
            <div className="flex items-start gap-4">
              <div className="w-12 h-12 rounded-full bg-brand-green-700 flex items-center justify-center flex-shrink-0">
                <svg className="w-6 h-6 text-brand-green-300" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4" />
                </svg>
              </div>
              <div className="flex-1">
                <h3 className="font-semibold text-lg mb-2">Donate Supplies & Equipment</h3>
                <p className="text-brand-green-200 text-sm mb-3">
                  We gratefully accept donations of cleaning supplies and equipment! Your material contributions directly support our workers.
                </p>
                <div className="flex flex-wrap gap-2 mb-4">
                  {['Gloves', 'Brooms', 'Rakes', 'Trash Bags', 'Pressure Washers', 'Wheelbarrows', 'Safety Vests', 'Shovels'].map((item) => (
                    <span key={item} className="text-xs bg-brand-green-700 text-brand-green-200 px-2 py-1 rounded-full">
                      {item}
                    </span>
                  ))}
                </div>
                <a
                  href="mailto:donate@globalcommunitycleaners.org?subject=Material%20Donation%20Inquiry"
                  className="inline-flex items-center gap-2 text-brand-green-300 text-sm font-medium hover:text-white transition-colors"
                >
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                  </svg>
                  donate@globalcommunitycleaners.org
                </a>
              </div>
            </div>
          </div>

          <div className="bg-brand-green-800/50 rounded-xl p-6 text-left">
            <h3 className="font-semibold mb-2">Corporate Matching</h3>
            <p className="text-brand-green-200 text-sm mb-4">
              Many employers match charitable donations. Check if your company participates
              to double your impact.
            </p>
            <a href="mailto:partnerships@globalcommunitycleaners.org?subject=Corporate%20Matching%20Inquiry" className="text-brand-green-300 text-sm font-medium hover:text-white transition-colors">
              Learn more →
            </a>
          </div>
          <div className="bg-brand-green-800/50 rounded-xl p-6 text-left">
            <h3 className="font-semibold mb-2">Legacy Giving</h3>
            <p className="text-brand-green-200 text-sm mb-4">
              Include GCC in your estate planning to create lasting impact for
              North Lawndale for generations to come.
            </p>
            <a href="mailto:legacy@globalcommunitycleaners.org?subject=Legacy%20Giving%20Inquiry" className="text-brand-green-300 text-sm font-medium hover:text-white transition-colors">
              Contact us →
            </a>
          </div>
        </div>
      </div>
    </section>
  )
}
