'use client'

import Link from 'next/link'
import { useState, useEffect, useRef } from 'react'

export function HowItWorksSection() {
  const [visibleSteps, setVisibleSteps] = useState<number[]>([])
  const sectionRef = useRef<HTMLElement>(null)

  const steps = [
    {
      number: '01',
      title: 'Sign Up',
      description:
        'Create your account and verify your North Lawndale address. Quick ID check ensures community trust.',
      icon: (
        <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z"
          />
        </svg>
      ),
    },
    {
      number: '02',
      title: 'Claim a Block',
      description:
        'Browse available blocks on the map. Tap to claim—first come, first served. You have 24 hours to complete.',
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
      number: '03',
      title: 'Clean & Document',
      description:
        'Follow the guided route. Take before & after photos at each checkpoint. GPS verifies your location.',
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
    {
      number: '04',
      title: 'Get Paid',
      description:
        'Submit your work. AI reviews your photos. Approved? $100 hits your bank in 30 minutes or less.',
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
  ]

  // Intersection observer for staggered step reveal
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          // Stagger the reveal of each step
          steps.forEach((_, index) => {
            setTimeout(() => {
              setVisibleSteps((prev) => [...prev, index])
            }, index * 150) // 150ms stagger
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
    <section id="how-it-works" ref={sectionRef} className="py-28 lg:py-32 bg-white">
      <div className="section-container">
        {/* Section header - Golden ratio max-width */}
        <div className="text-center mb-16 lg:mb-20">
          <span className="inline-block px-4 py-2 rounded-full bg-brand-green-100 text-brand-green-700 text-sm font-medium mb-6">
            🧹 For Workers
          </span>
          <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-neutral-900 mb-6">
            How Workers Earn $100/Block
          </h2>
          <p className="text-lg text-neutral-600 max-w-2xl mx-auto leading-relaxed">
            Simple, transparent, and designed for your success. Start earning in your neighborhood
            today. The full $100 goes to <strong>you</strong>—no platform fees, ever.
          </p>
        </div>

        {/* Steps grid with improved gaps */}
        <div className="grid md:grid-cols-2 lg:grid-cols-4 gap-6 lg:gap-8">
          {steps.map((step, index) => (
            <div
              key={step.number}
              className={`relative group transition-all duration-500 ${
                visibleSteps.includes(index)
                  ? 'opacity-100 translate-y-0'
                  : 'opacity-0 translate-y-8'
              }`}
            >
              {/* Animated connector line (desktop only) */}
              {index < steps.length - 1 && (
                <div className="hidden lg:block absolute top-12 left-full w-full h-0.5 -translate-y-1/2 z-0 overflow-hidden">
                  <div
                    className={`h-full bg-gradient-to-r from-brand-gold-400 to-brand-gold-200 transition-all duration-700 ease-out ${
                      visibleSteps.includes(index + 1) ? 'w-full' : 'w-0'
                    }`}
                  />
                </div>
              )}

              <div className="card relative z-10 h-full p-golden-md hover-lift group-hover:border-brand-gold-200 border-2 border-transparent transition-all duration-300">
                {/* Step number badge with pulse */}
                <div className="absolute -top-3 -right-3 w-11 h-11 rounded-full bg-brand-gold-500 flex items-center justify-center shadow-lg shadow-brand-gold-200 group-hover:scale-110 transition-transform duration-300">
                  <span className="text-sm font-bold text-white">{step.number}</span>
                </div>

                {/* Icon with hover animation */}
                <div className="w-16 h-16 rounded-2xl bg-brand-brown-50 flex items-center justify-center text-brand-brown-600 mb-golden-md group-hover:bg-brand-gold-100 group-hover:text-brand-gold-700 group-hover:scale-105 transition-all duration-300">
                  {step.icon}
                </div>

                {/* Content with golden spacing */}
                <h3 className="text-xl font-semibold text-neutral-900 mb-golden-xs group-hover:text-brand-gold-700 transition-colors">
                  {step.title}
                </h3>
                <p className="text-neutral-600 leading-relaxed">{step.description}</p>
              </div>
            </div>
          ))}
        </div>

        {/* Requirements note with improved spacing */}
        <div className="mt-16 lg:mt-20 p-6 sm:p-8 rounded-2xl bg-brand-brown-50 border border-brand-brown-100 hover-lift">
          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-golden-md">
            <div className="w-14 h-14 rounded-full bg-brand-brown-100 flex items-center justify-center flex-shrink-0 group-hover:bg-brand-brown-200 transition-colors">
              <svg className="w-7 h-7 text-brand-brown-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <div>
              <h4 className="font-semibold text-neutral-900 mb-golden-xs text-lg">Requirements</h4>
              <p className="text-neutral-600 leading-relaxed">
                Must be 18+ with valid ID and proof of North Lawndale address (60623 or 60624 zip code).
                You&apos;ll need a smartphone with camera and a bank account for instant deposits.
              </p>
            </div>
          </div>
        </div>

      </div>
    </section>
  )
}
