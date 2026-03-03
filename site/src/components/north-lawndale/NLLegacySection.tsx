'use client'

import { useEffect, useRef, useState } from 'react'

const timelineEvents = [
  {
    year: '1966',
    title: 'Dr. King Moves to North Lawndale',
    description:
      'Martin Luther King Jr. and Coretta moved into a $90/month apartment at 1550 S. Hamlin Avenue — a building with no lock on the front door, dirt floors, and the overpowering smell of urine. He chose North Lawndale deliberately, to live among the people he was fighting for.',
  },
  {
    year: '1966',
    title: 'Tenants Paid to Fix Their Own Building',
    description:
      'King organized rent strikes against slumlords. The SCLC became trustees of the building — collecting rent and hiring the tenants themselves as laborers at $2.00/hour to make repairs. Residents invested in their own home.',
  },
  {
    year: '1968',
    title: 'The Fair Housing Act',
    description:
      'King\'s Chicago Freedom Movement — born in North Lawndale — led directly to the Fair Housing Act of 1968. The fight that started on Hamlin Avenue changed federal law.',
  },
  {
    year: '2026',
    title: 'GCC Carries the Torch',
    description:
      'Sixty years later, GCC brings the same idea full circle: pay residents to restore their own community. Same neighborhood. Same principle. New era.',
  },
]

export function NLLegacySection() {
  const [visibleItems, setVisibleItems] = useState<Set<number>>(new Set())
  const sectionRef = useRef<HTMLElement>(null)

  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries, obs) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            const index = Number(entry.target.getAttribute('data-index'))
            setVisibleItems((prev) => new Set(prev).add(index))
            obs.unobserve(entry.target)
          }
        })
      },
      { threshold: 0.2 }
    )

    const items = sectionRef.current?.querySelectorAll('[data-index]')
    items?.forEach((item) => observer.observe(item))
    return () => observer.disconnect()
  }, [])

  return (
    <section ref={sectionRef} className="py-golden-2xl bg-brand-brown-50">
      <div className="section-container">
        {/* Section header */}
        <div className="text-center mb-16">
          <p className="text-sm font-semibold text-brand-gold-600 uppercase tracking-[0.2em] mb-3">
            The Legacy
          </p>
          <h2 className="text-3xl sm:text-4xl lg:text-5xl font-bold text-brand-brown-900 mb-6">
            A Movement Born on Hamlin Avenue
          </h2>
          <blockquote className="max-w-3xl mx-auto">
            <p className="text-lg sm:text-xl text-brand-brown-700 italic leading-relaxed">
              "The slum of Lawndale was truly an island of poverty in the midst of an ocean of plenty."
            </p>
            <cite className="block mt-3 text-sm text-brand-brown-500 not-italic font-medium">
              — Dr. Martin Luther King Jr., 1966
            </cite>
          </blockquote>
        </div>

        {/* Timeline */}
        <ol aria-label="Historical timeline" className="max-w-3xl mx-auto list-none m-0 p-0">
          {timelineEvents.map((event, index) => (
            <li
              key={event.title}
              data-index={index}
              className={`relative pl-8 sm:pl-12 pb-12 last:pb-0 border-l-2 border-brand-gold-300 transition-all duration-700 ${
                visibleItems.has(index)
                  ? 'opacity-100 translate-x-0'
                  : 'opacity-0 -translate-x-4'
              }`}
              style={{ transitionDelay: `${index * 150}ms` }}
            >
              {/* Timeline dot */}
              <div className="absolute left-0 top-0 -translate-x-1/2 w-4 h-4 rounded-full bg-brand-gold-500 border-4 border-brand-brown-50" />

              {/* Year badge */}
              <div className="inline-flex items-center rounded-full bg-brand-gold-100 px-3 py-1 mb-3">
                <span className="text-sm font-bold text-brand-gold-700">{event.year}</span>
              </div>

              <h3 className="text-xl font-bold text-brand-brown-900 mb-2">
                {event.title}
              </h3>
              <p className="text-brand-brown-600 leading-relaxed">
                {event.description}
              </p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  )
}
