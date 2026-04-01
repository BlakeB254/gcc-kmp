'use client'

import { useState } from 'react'
import Link from 'next/link'

export function SponsorCTASection() {
  const [selectedOption, setSelectedOption] = useState<'single' | 'monthly'>('single')

  const benefits = [
    {
      icon: (
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M15 11a3 3 0 11-6 0 3 3 0 016 0z" />
        </svg>
      ),
      title: 'Choose Your Block',
      description: 'Sponsor the block outside your business, home, or anywhere in North Lawndale.',
    },
    {
      icon: (
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
      ),
      title: '100% Goes to Workers',
      description: 'Your full $100 becomes wages for a neighbor. Zero platform fees—ever.',
    },
    {
      icon: (
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
        </svg>
      ),
      title: 'Tax Deductible',
      description: 'GCC is a 501(c)(3) nonprofit. Your donation is fully tax deductible.',
    },
    {
      icon: (
        <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z" />
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M15 13a3 3 0 11-6 0 3 3 0 016 0z" />
        </svg>
      ),
      title: 'See the Impact',
      description: 'Receive before/after photos and updates when your block is cleaned.',
    },
  ]

  return (
    <section id="sponsor" className="py-28 lg:py-32 bg-gradient-to-br from-brand-green-50 to-white">
      <div className="section-container">
        <div className="grid lg:grid-cols-2 gap-12 lg:gap-20 items-start">
          {/* Left column - Content */}
          <div>
            <div className="inline-flex items-center gap-2 rounded-full bg-brand-green-100 px-4 py-2 mb-6">
              <svg className="w-4 h-4 text-brand-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
              </svg>
              <span className="text-sm font-medium text-brand-green-800">For Sponsors</span>
            </div>

            <h2 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-6">
              Fund a Cleaner Street
            </h2>
            <p className="text-lg text-neutral-600 mb-10">
              For $100, you fund a local worker to clean an entire block. It&apos;s direct,
              transparent, and creates real change you can see.
            </p>

            {/* Benefits */}
            <div className="grid sm:grid-cols-2 gap-6 mb-10">
              {benefits.map((benefit, index) => (
                <div key={index} className="flex gap-4">
                  <div className="flex-shrink-0 w-10 h-10 rounded-lg bg-brand-green-100 flex items-center justify-center text-brand-green-600">
                    {benefit.icon}
                  </div>
                  <div>
                    <h3 className="font-semibold text-neutral-900 mb-1">{benefit.title}</h3>
                    <p className="text-sm text-neutral-600">{benefit.description}</p>
                  </div>
                </div>
              ))}
            </div>

            {/* Transparency Highlight */}
            <div className="bg-brand-green-50 rounded-xl border border-brand-green-200 p-5">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-lg bg-brand-green-100 flex items-center justify-center flex-shrink-0">
                  <span className="text-xl">💚</span>
                </div>
                <div>
                  <h4 className="font-semibold text-brand-green-900 mb-1">Unique Transparency Model</h4>
                  <p className="text-sm text-brand-green-700">
                    Unlike other platforms, <strong>100% of every $100 goes directly to workers</strong>.
                    GCC is funded separately through grants and corporate partnerships—we never take a cut from worker pay.
                  </p>
                </div>
              </div>
            </div>
          </div>

          {/* Right column - Pricing cards */}
          <div className="space-y-6">
            {/* Basic Sponsorship Card */}
            <div className="relative">
              <div className="card p-8 border-2 border-brand-green-200">
                {/* Toggle */}
                <div className="flex rounded-lg bg-neutral-100 p-1 mb-8">
                  <button
                    onClick={() => setSelectedOption('single')}
                    className={`flex-1 py-2 px-4 rounded-md text-sm font-medium transition-all ${
                      selectedOption === 'single'
                        ? 'bg-white shadow-sm text-neutral-900'
                        : 'text-neutral-500'
                    }`}
                  >
                    One Block
                  </button>
                  <button
                    onClick={() => setSelectedOption('monthly')}
                    className={`flex-1 py-2 px-4 rounded-md text-sm font-medium transition-all ${
                      selectedOption === 'monthly'
                        ? 'bg-white shadow-sm text-neutral-900'
                        : 'text-neutral-500'
                    }`}
                  >
                    Monthly
                  </button>
                </div>

                {/* Pricing */}
                <div className="text-center mb-8">
                  <p className="text-5xl font-bold text-neutral-900">
                    $100
                    {selectedOption === 'monthly' && (
                      <span className="text-lg font-normal text-neutral-500">/month</span>
                    )}
                  </p>
                  <p className="text-neutral-600 mt-2">
                    {selectedOption === 'single'
                      ? 'One-time sponsorship for one block'
                      : 'Clean one block every month'}
                  </p>

                  {/* Breakdown */}
                  <div className="mt-4 p-3 bg-brand-green-50 rounded-lg">
                    <div className="flex items-center justify-center gap-2 text-sm">
                      <span className="text-brand-green-600 font-semibold">$100</span>
                      <span className="text-brand-green-700">→ Worker</span>
                      <span className="text-neutral-400 mx-1">|</span>
                      <span className="text-neutral-500">$0</span>
                      <span className="text-neutral-500">→ Platform</span>
                    </div>
                  </div>
                </div>

                {/* What's included */}
                <ul className="space-y-3 mb-8">
                  {[
                    'Choose any available block',
                    'Before & after photos',
                    'Email confirmation & receipt',
                    'Tax-deductible donation receipt',
                    selectedOption === 'monthly' && 'Cancel anytime',
                  ]
                    .filter(Boolean)
                    .map((item, index) => (
                      <li key={index} className="flex items-center gap-3">
                        <svg
                          className="w-5 h-5 text-brand-green-500 flex-shrink-0"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M5 13l4 4L19 7"
                          />
                        </svg>
                        <span className="text-neutral-700">{item}</span>
                      </li>
                    ))}
                </ul>

                {/* CTA */}
                <Link
                  href={`/sponsor/${selectedOption}`}
                  className="btn-primary w-full text-center block"
                >
                  {selectedOption === 'single' ? 'Sponsor a Block' : 'Start Monthly Sponsorship'}
                </Link>

                {/* Security note */}
                <p className="text-center text-sm text-neutral-500 mt-4 flex items-center justify-center gap-2">
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
                    />
                  </svg>
                  Secure payment via Stripe
                </p>
              </div>

              {/* Decorative elements */}
              <div className="absolute -z-10 -top-4 -right-4 w-full h-full rounded-2xl bg-brand-green-200/50" />
            </div>

            {/* Custom Package Card */}
            <div className="bg-gradient-to-br from-neutral-900 to-neutral-800 rounded-2xl p-6 text-white">
              <div className="flex items-center gap-3 mb-4">
                <span className="text-2xl">✨</span>
                <div>
                  <h3 className="font-semibold">Want More Than Basic?</h3>
                  <p className="text-sm text-neutral-400">For businesses & organizations</p>
                </div>
              </div>

              <p className="text-sm text-neutral-300 mb-4">
                Add branded recognition and premium services. The base $100/block always goes to workers—add-ons fund enhanced impact.
              </p>

              <div className="grid grid-cols-2 gap-2 mb-4 text-xs">
                <div className="flex items-center gap-2 text-neutral-300">
                  <span className="text-brand-green-400">+</span>
                  Branded vests
                </div>
                <div className="flex items-center gap-2 text-neutral-300">
                  <span className="text-brand-green-400">+</span>
                  Block signage
                </div>
                <div className="flex items-center gap-2 text-neutral-300">
                  <span className="text-brand-green-400">+</span>
                  Graffiti removal
                </div>
                <div className="flex items-center gap-2 text-neutral-300">
                  <span className="text-brand-green-400">+</span>
                  Impact reports
                </div>
              </div>

              <Link
                href="/sponsor/calculator"
                className="block w-full py-3 px-4 rounded-lg bg-brand-green-500 text-center font-semibold hover:bg-brand-green-600 transition-colors"
              >
                Build Custom Package →
              </Link>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
