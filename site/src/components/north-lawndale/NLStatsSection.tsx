'use client'

import { useEffect, useRef, useState } from 'react'

const communityStats = [
  {
    value: '72%',
    label: 'Population Lost',
    detail: '125,000 → 34,477 since 1960',
    color: 'text-red-500',
  },
  {
    value: '27.6%',
    label: 'Homes Vacant',
    detail: 'Higher than 94% of US neighborhoods',
    color: 'text-orange-500',
  },
  {
    value: '63.9',
    label: 'Life Expectancy',
    detail: '20 years below wealthiest Chicago areas',
    color: 'text-red-500',
  },
  {
    value: '42%',
    label: 'Poverty Rate',
    detail: 'Nearly 4x the national average',
    color: 'text-orange-500',
  },
]

const researchStats = [
  {
    value: '29%',
    label: 'Less Gun Violence',
    detail: 'Near greened vacant lots (UPenn Study)',
    color: 'text-brand-green-500',
  },
  {
    value: '4.3%',
    label: 'Property Value Rise',
    detail: 'Year 1 — up to 13% by year 6 (Wharton)',
    color: 'text-brand-green-500',
  },
  {
    value: '69%',
    label: 'Less Depression',
    detail: 'Among residents below poverty line (JAMA)',
    color: 'text-brand-green-500',
  },
  {
    value: '77:1',
    label: 'Return on Investment',
    detail: '$2,500 invested → $193,500 community value',
    color: 'text-brand-green-500',
  },
]

function StatCard({
  stat,
  index,
  isVisible,
}: {
  stat: (typeof communityStats)[0]
  index: number
  isVisible: boolean
}) {
  return (
    <div
      className={`card text-center transition-all duration-500 ${
        isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
      }`}
      style={{ transitionDelay: `${index * 100}ms` }}
    >
      <p className={`text-3xl sm:text-4xl font-bold mb-2 ${stat.color}`}>
        {stat.value}
      </p>
      <p className="text-sm font-semibold text-neutral-800 mb-1">{stat.label}</p>
      <p className="text-xs text-neutral-500">{stat.detail}</p>
    </div>
  )
}

export function NLStatsSection() {
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
    <section ref={sectionRef} className="py-golden-2xl bg-white">
      <div className="section-container">
        {/* The Problem */}
        <div className="text-center mb-12">
          <p className="text-sm font-semibold text-red-500 uppercase tracking-[0.2em] mb-3">
            The Need
          </p>
          <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-neutral-900 mb-4">
            North Lawndale by the Numbers
          </h2>
          <p className="text-lg text-neutral-600 max-w-2xl mx-auto">
            Decades of disinvestment have taken their toll. But research proves that
            cleaning and greening neighborhoods reverses the damage.
          </p>
        </div>

        {/* Problem stats */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-16">
          {communityStats.map((stat, i) => (
            <StatCard key={stat.label} stat={stat} index={i} isVisible={isVisible} />
          ))}
        </div>

        {/* Divider with message */}
        <div className="text-center mb-12">
          <div className="inline-flex items-center gap-3 rounded-full bg-brand-green-50 border border-brand-green-200 px-6 py-3">
            <svg className="w-5 h-5 text-brand-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
            </svg>
            <span className="text-sm font-semibold text-brand-green-700">
              But research shows cleaning changes everything
            </span>
          </div>
        </div>

        {/* Solution stats */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {researchStats.map((stat, i) => (
            <StatCard key={stat.label} stat={stat} index={i + 4} isVisible={isVisible} />
          ))}
        </div>

        {/* Source attribution */}
        <p className="text-center text-xs text-neutral-400 mt-8">
          Sources: University of Pennsylvania, Wharton School, JAMA Network Open, Columbia University
        </p>
      </div>
    </section>
  )
}
