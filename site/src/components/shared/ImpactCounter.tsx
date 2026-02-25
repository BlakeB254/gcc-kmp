'use client'

import { useState, useEffect } from 'react'

interface Stats {
  workers: { approved: number; pending: number; total: number }
  sponsors: { total: number }
  blocks: { cleaned: number; funded: number; available: number }
  financial: { totalRaised: number; workerPayouts: number }
  waitlist: { total: number }
  movement: { total: number }
}

function AnimatedCounter({
  value,
  prefix = '',
  suffix = '',
  duration = 1500,
  delay = 0,
}: {
  value: number
  prefix?: string
  suffix?: string
  duration?: number
  delay?: number
}) {
  const [displayValue, setDisplayValue] = useState(0)
  const [hasAnimated, setHasAnimated] = useState(false)

  useEffect(() => {
    if (hasAnimated || value === 0) return

    const timer = setTimeout(() => {
      const steps = 40
      let step = 0

      const interval = setInterval(() => {
        step++
        const progress = step / steps
        const eased = 1 - Math.pow(1 - progress, 3)
        setDisplayValue(Math.floor(value * eased))

        if (step >= steps) {
          setDisplayValue(value)
          setHasAnimated(true)
          clearInterval(interval)
        }
      }, duration / steps)
    }, delay)

    return () => clearTimeout(timer)
  }, [value, duration, delay, hasAnimated])

  return (
    <span>
      {prefix}
      {displayValue.toLocaleString()}
      {suffix}
    </span>
  )
}

interface ImpactCounterProps {
  /** Layout variant */
  layout?: 'horizontal' | 'grid'
}

export function ImpactCounter({ layout = 'grid' }: ImpactCounterProps) {
  const [stats, setStats] = useState<Stats | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    async function fetchStats() {
      try {
        const res = await fetch('/api/public/stats')
        const data = await res.json()
        if (data.success) {
          setStats(data.stats)
        }
      } catch (error) {
        console.error('Failed to fetch stats:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchStats()
  }, [])

  const workersTotal = stats?.workers?.total || 0
  const sponsorsTotal = stats?.sponsors?.total || 0
  const blocksCleanedOrFunded = (stats?.blocks?.cleaned || 0) + (stats?.blocks?.funded || 0)
  const totalRaised = stats?.financial?.totalRaised || 0

  const counters = [
    {
      icon: '🧹',
      value: workersTotal,
      label: 'Workers Enrolled',
      color: 'text-brand-green-400',
      delay: 0,
    },
    {
      icon: '🤝',
      value: sponsorsTotal,
      label: 'Sponsors',
      color: 'text-brand-gold-400',
      delay: 100,
    },
    // Show Blocks Cleaned card only once blocks are actually funded/cleaned
    ...(blocksCleanedOrFunded > 0
      ? [
          {
            icon: '📍',
            value: blocksCleanedOrFunded,
            label: 'Blocks Cleaned',
            color: 'text-brand-sky-400',
            delay: 200,
          },
        ]
      : []),
    {
      icon: '💰',
      value: totalRaised / 100,
      prefix: '$',
      label: 'Total Raised',
      color: 'text-white',
      delay: blocksCleanedOrFunded > 0 ? 300 : 200,
    },
  ]

  const colCount = counters.length
  const gridClass = layout === 'horizontal'
    ? `grid grid-cols-${colCount} gap-4`
    : `grid grid-cols-${colCount === 3 ? '3' : '2 md:grid-cols-4'} gap-4 sm:gap-6`

  return (
    <div className={`${gridClass} max-w-5xl mx-auto`}>
      {counters.map((counter) => (
        <div
          key={counter.label}
          className="text-center bg-white/10 rounded-2xl p-6 sm:p-8 backdrop-blur hover:bg-white/15 transition-colors"
        >
          <div className="text-3xl sm:text-4xl mb-3">{counter.icon}</div>
          <p className={`text-3xl sm:text-4xl font-bold ${counter.color} mb-2`}>
            {isLoading ? (
              <span className="inline-block w-16 h-8 bg-white/20 rounded animate-pulse" />
            ) : (
              <AnimatedCounter
                value={counter.value}
                prefix={counter.prefix || ''}
                delay={counter.delay}
              />
            )}
          </p>
          <p className="text-brand-brown-200 text-sm sm:text-base">{counter.label}</p>
        </div>
      ))}
    </div>
  )
}
