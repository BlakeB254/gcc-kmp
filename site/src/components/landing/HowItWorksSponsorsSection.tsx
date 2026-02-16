'use client'

import Link from 'next/link'
import { useState, useEffect, useRef } from 'react'

export function HowItWorksSponsorsSection() {
  const [visibleSteps, setVisibleSteps] = useState<number[]>([])
  const sectionRef = useRef<HTMLElement>(null)

  const steps = [
    {
      number: '01',
      title: 'Choose Your Block',
      description:
        'Browse the map and select any available block in North Lawndale. Sponsor near your business, your old neighborhood, or anywhere you want impact.',
      icon: (
        <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"
          />
        </svg>
      ),
    },
    {
      number: '02',
      title: 'Donate $100',
      description:
        'Secure checkout via Stripe. One-time or monthly recurring. 100% of your $100 goes directly to the worker—we operate on separate funding.',
      icon: (
        <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
          />
        </svg>
      ),
    },
    {
      number: '03',
      title: 'Worker Gets Paid',
      description:
        'A verified North Lawndale resident claims and cleans your block. They receive the full $100 deposited directly to their bank.',
      icon: (
        <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
          />
        </svg>
      ),
    },
    {
      number: '04',
      title: 'See Your Impact',
      description:
        'Receive before & after photos, a tax receipt, and updates on the community impact. Track your contribution in your sponsor dashboard.',
      icon: (
        <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z"
          />
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M15 13a3 3 0 11-6 0 3 3 0 016 0z"
          />
        </svg>
      ),
    },
  ]

  // Intersection observer for staggered step reveal
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          steps.forEach((_, index) => {
            setTimeout(() => {
              setVisibleSteps((prev) => [...prev, index])
            }, index * 150)
          })
          observer.disconnect()
        }
      },
      { threshold: 0.2 }
    )

    if (sectionRef.current) {
      observer.observe(sectionRef.current)
    }

    return () => observer.disconnect()
  }, [steps.length])

  return (
    <section id="how-sponsors-work" ref={sectionRef} className="py-28 lg:py-32 bg-gradient-to-br from-brand-gold-50 to-white">
      <div className="section-container">
        {/* Section header */}
        <div className="text-center mb-16 lg:mb-20">
          <span className="inline-block px-4 py-2 rounded-full bg-brand-gold-100 text-brand-gold-700 text-sm font-medium mb-6">
            💛 For Sponsors
          </span>
          <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-neutral-900 mb-6">
            How Sponsoring Works
          </h2>
          <p className="text-lg text-neutral-600 max-w-2xl mx-auto leading-relaxed">
            Your $100 becomes $100 in a neighbor's pocket. Direct, transparent, impactful—
            with a tax receipt to prove it.
          </p>
        </div>

        {/* Steps grid */}
        <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-6 lg:gap-8 mb-16 lg:mb-20">
          {steps.map((step, index) => (
            <div
              key={step.number}
              className={`relative group transition-all duration-500 ${
                visibleSteps.includes(index)
                  ? 'opacity-100 translate-y-0'
                  : 'opacity-0 translate-y-8'
              }`}
            >
              {/* Connector line */}
              {index < steps.length - 1 && (
                <div className="hidden lg:block absolute top-12 left-full w-full h-0.5 -translate-y-1/2 z-0 overflow-hidden">
                  <div
                    className={`h-full bg-gradient-to-r from-brand-gold-400 to-brand-gold-200 transition-all duration-700 ease-out ${
                      visibleSteps.includes(index + 1) ? 'w-full' : 'w-0'
                    }`}
                  />
                </div>
              )}

              <div className="card relative z-10 h-full p-6 hover:shadow-lg group-hover:border-brand-gold-200 border-2 border-transparent transition-all duration-300 bg-white">
                {/* Step number badge */}
                <div className="absolute -top-3 -right-3 w-11 h-11 rounded-full bg-brand-gold-500 flex items-center justify-center shadow-lg shadow-brand-gold-200 group-hover:scale-110 transition-transform duration-300">
                  <span className="text-sm font-bold text-white">{step.number}</span>
                </div>

                {/* Icon */}
                <div className="w-16 h-16 rounded-2xl bg-brand-gold-100 flex items-center justify-center text-brand-gold-600 mb-4 group-hover:bg-brand-gold-200 group-hover:scale-105 transition-all duration-300">
                  {step.icon}
                </div>

                {/* Content */}
                <h3 className="text-xl font-semibold text-neutral-900 mb-2 group-hover:text-brand-gold-700 transition-colors">
                  {step.title}
                </h3>
                <p className="text-neutral-600 leading-relaxed text-sm">{step.description}</p>
              </div>
            </div>
          ))}
        </div>

        {/* Transparency highlight */}
        <div className="max-w-4xl mx-auto mb-16 lg:mb-20">
          <div className="bg-gradient-to-r from-brand-green-500 to-brand-green-600 rounded-2xl p-6 sm:p-8 text-white">
            <div className="flex flex-col md:flex-row items-center gap-6">
              <div className="w-20 h-20 rounded-2xl bg-white/20 flex items-center justify-center flex-shrink-0">
                <span className="text-4xl">💚</span>
              </div>
              <div className="text-center md:text-left">
                <h3 className="text-xl font-bold mb-2">The GCC Transparency Promise</h3>
                <p className="text-brand-green-100 mb-4">
                  <strong className="text-white">100% of every $100 goes directly to a worker.</strong>{' '}
                  GCC operates on grants and corporate partnerships—we never take a cut from worker pay.
                  Your donation is fully tax-deductible as GCC is a 501(c)(3) nonprofit.
                </p>
                <div className="flex flex-wrap justify-center md:justify-start gap-4">
                  <div className="flex items-center gap-2 text-sm">
                    <svg className="w-5 h-5 text-brand-green-200" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                    <span>$100 → Worker</span>
                  </div>
                  <div className="flex items-center gap-2 text-sm">
                    <svg className="w-5 h-5 text-brand-green-200" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                    <span>$0 → Platform fees</span>
                  </div>
                  <div className="flex items-center gap-2 text-sm">
                    <svg className="w-5 h-5 text-brand-green-200" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                    </svg>
                    <span>Tax deductible</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* CTAs */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
          <Link
            href="/sponsor"
            className="btn-primary px-8 py-3 text-lg font-semibold"
          >
            💛 Sponsor a Block
          </Link>
          <Link
            href="/sponsor/enterprise"
            className="px-8 py-3 text-lg font-semibold rounded-lg border-2 border-brand-gold-500 text-brand-gold-700 hover:bg-brand-gold-50 transition-all"
          >
            Corporate Partnerships
          </Link>
        </div>
      </div>
    </section>
  )
}
