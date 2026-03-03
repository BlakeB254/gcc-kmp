'use client'

import { useEffect, useRef, useState } from 'react'

const impacts = [
  {
    title: 'Property Values Rise',
    description:
      'Studies show property values near cleaned lots increase 4.3% in year one and up to 13% by year six. Your home is worth more when your block is clean.',
    icon: (
      <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
      </svg>
    ),
    color: 'bg-brand-green-100 text-brand-green-600',
  },
  {
    title: 'Crime Drops',
    description:
      'Philadelphia research found 29% less gun violence near greened lots. Clean blocks signal that a community is cared for and watched over.',
    icon: (
      <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
      </svg>
    ),
    color: 'bg-brand-sky-100 text-brand-sky-600',
  },
  {
    title: 'Pride Returns',
    description:
      'When residents see their neighbors investing in the block, it sparks a cycle of care. Pride is contagious — and it starts with one clean block.',
    icon: (
      <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
      </svg>
    ),
    color: 'bg-red-100 text-red-500',
  },
  {
    title: 'Jobs Created Locally',
    description:
      'Every dollar paid to a GCC worker stays in North Lawndale. Real money for real work — no middleman, no waiting, no corporate overhead.',
    icon: (
      <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
    ),
    color: 'bg-brand-gold-100 text-brand-gold-600',
  },
]

export function NLImpactSection() {
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
    <section ref={sectionRef} className="py-golden-2xl bg-brand-brown-900 text-white">
      <div className="section-container">
        <div className="text-center mb-16">
          <p className="text-sm font-semibold text-brand-gold-400 uppercase tracking-[0.2em] mb-3">
            The Vision
          </p>
          <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold mb-4">
            What Clean Blocks Mean for{' '}
            <span className="text-brand-gold-400">North Lawndale</span>
          </h2>
          <p className="text-lg text-neutral-300 max-w-2xl mx-auto">
            This isn't just about picking up trash. It's about reversing decades of
            disinvestment — one block at a time.
          </p>
        </div>

        <div className="grid sm:grid-cols-2 gap-6 max-w-4xl mx-auto">
          {impacts.map((impact, i) => (
            <div
              key={impact.title}
              className={`rounded-2xl bg-white/5 backdrop-blur-sm border border-white/10 p-6 transition-all duration-500 hover-lift ${
                isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8'
              }`}
              style={{ transitionDelay: `${i * 150}ms` }}
            >
              <div className={`inline-flex items-center justify-center w-12 h-12 rounded-xl ${impact.color} mb-4`}>
                {impact.icon}
              </div>
              <h3 className="text-xl font-bold text-white mb-2">{impact.title}</h3>
              <p className="text-neutral-300 leading-relaxed text-sm">{impact.description}</p>
            </div>
          ))}
        </div>

        {/* King quote */}
        <blockquote className="max-w-2xl mx-auto text-center mt-16 pt-12 border-t border-white/10">
          <p className="text-xl sm:text-2xl text-neutral-200 italic leading-relaxed">
            "The social planning that might reverse this trend of degradation… will bring new life and new hope to the slums of this city."
          </p>
          <cite className="block mt-4 text-sm text-brand-gold-400 not-italic font-medium">
            — Dr. Martin Luther King Jr., North Lawndale, 1966
          </cite>
        </blockquote>
      </div>
    </section>
  )
}
