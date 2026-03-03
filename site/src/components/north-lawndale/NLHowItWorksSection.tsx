'use client'

import { useEffect, useRef, useState } from 'react'

const steps = [
  {
    number: '1',
    title: 'Meet at Community Center',
    description: 'Show up at the designated meeting point. No app download, no account — just come as you are.',
    icon: (
      <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4" />
      </svg>
    ),
  },
  {
    number: '2',
    title: 'Clean 1 Block Together',
    description: 'Work as a group. Pick up litter, clear debris, restore beauty. It\'s a community activity, not a solo gig.',
    icon: (
      <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
      </svg>
    ),
  },
  {
    number: '3',
    title: 'Eat Together',
    description: 'After the work is done, we share a meal. Building community is as important as cleaning it.',
    icon: (
      <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
    ),
  },
  {
    number: '4',
    title: 'Get Paid $100 Same Day',
    description: '1 person = 1 block = $100. No waiting. No runaround. Money in your hands before you go home.',
    icon: (
      <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M17 9V7a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2m2 4h10a2 2 0 002-2v-6a2 2 0 00-2-2H9a2 2 0 00-2 2v6a2 2 0 002 2zm7-5a2 2 0 11-4 0 2 2 0 014 0z" />
      </svg>
    ),
  },
]

export function NLHowItWorksSection() {
  const [isVisible, setIsVisible] = useState(false)
  const sectionRef = useRef<HTMLElement>(null)

  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setIsVisible(true)
          observer.disconnect()
        }
      },
      { threshold: 0.1 }
    )
    if (sectionRef.current) observer.observe(sectionRef.current)
    return () => observer.disconnect()
  }, [])

  return (
    <section ref={sectionRef} className="py-golden-2xl bg-neutral-50">
      <div className="section-container">
        <div className="text-center mb-16">
          <p className="text-sm font-semibold text-brand-gold-600 uppercase tracking-[0.2em] mb-3">
            How It Works
          </p>
          <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-neutral-900 mb-4">
            Simple. Together. Paid.
          </h2>
          <p className="text-lg text-neutral-600 max-w-2xl mx-auto">
            No app required. No phone necessary. Just show up, clean with your neighbors, and get paid.
          </p>
        </div>

        <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-8 max-w-5xl mx-auto">
          {steps.map((step, i) => (
            <div
              key={step.number}
              className={`text-center transition-all duration-500 ${
                isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
              }`}
              style={{ transitionDelay: `${i * 150}ms` }}
            >
              {/* Step number */}
              <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-brand-gold-100 text-brand-gold-700 mb-4">
                {step.icon}
              </div>

              {/* Step indicator */}
              <div className="inline-flex items-center gap-1.5 rounded-full bg-brand-brown-100 px-3 py-1 mb-3">
                <span className="text-xs font-bold text-brand-brown-600">Step {step.number}</span>
              </div>

              <h3 className="text-lg font-bold text-neutral-900 mb-2">{step.title}</h3>
              <p className="text-sm text-neutral-600 leading-relaxed">{step.description}</p>
            </div>
          ))}
        </div>

        {/* Callout badges */}
        <div className="flex flex-wrap justify-center gap-3 mt-12">
          {[
            { label: 'No phone necessary', bg: 'bg-brand-green-100', border: 'border-brand-green-200', icon: 'text-brand-green-600', text: 'text-brand-green-700' },
            { label: 'Group activity', bg: 'bg-brand-sky-100', border: 'border-brand-sky-200', icon: 'text-brand-sky-600', text: 'text-brand-sky-700' },
            { label: 'Same-day pay', bg: 'bg-brand-gold-100', border: 'border-brand-gold-200', icon: 'text-brand-gold-600', text: 'text-brand-gold-700' },
          ].map(({ label, bg, border, icon, text }) => (
            <div key={label} className={`inline-flex items-center gap-2 rounded-full ${bg} border ${border} px-4 py-2`}>
              <svg className={`w-4 h-4 ${icon}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
              <span className={`text-sm font-medium ${text}`}>{label}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
