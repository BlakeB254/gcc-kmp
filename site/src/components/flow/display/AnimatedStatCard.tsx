'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

interface AnimatedStatCardProps {
  value: number
  label: string
  prefix?: string
  suffix?: string
  icon?: React.ReactNode
  trend?: {
    value: number
    isPositive: boolean
  }
  animationDuration?: number
  className?: string
}

function useAnimatedCounter(
  target: number,
  duration: number = 2000,
  startOnMount: boolean = true
) {
  const [count, setCount] = React.useState(0)
  const [hasStarted, setHasStarted] = React.useState(false)
  const ref = React.useRef<HTMLDivElement>(null)

  React.useEffect(() => {
    if (!startOnMount) return

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && !hasStarted) {
          setHasStarted(true)
        }
      },
      { threshold: 0.3 }
    )

    if (ref.current) {
      observer.observe(ref.current)
    }

    return () => observer.disconnect()
  }, [hasStarted, startOnMount])

  React.useEffect(() => {
    if (!hasStarted) return

    let startTime: number
    let animationFrame: number

    const animate = (currentTime: number) => {
      if (!startTime) startTime = currentTime
      const elapsed = currentTime - startTime
      const progress = Math.min(elapsed / duration, 1)

      // Easing function (ease-out-cubic)
      const easedProgress = 1 - Math.pow(1 - progress, 3)
      setCount(Math.floor(easedProgress * target))

      if (progress < 1) {
        animationFrame = requestAnimationFrame(animate)
      } else {
        setCount(target)
      }
    }

    animationFrame = requestAnimationFrame(animate)

    return () => cancelAnimationFrame(animationFrame)
  }, [hasStarted, target, duration])

  return { count, ref }
}

function formatNumber(num: number): string {
  if (num >= 1000000) {
    return (num / 1000000).toFixed(1) + 'M'
  }
  if (num >= 1000) {
    return (num / 1000).toFixed(1) + 'K'
  }
  return num.toLocaleString()
}

export function AnimatedStatCard({
  value,
  label,
  prefix = '',
  suffix = '',
  icon,
  trend,
  animationDuration = 2000,
  className,
}: AnimatedStatCardProps) {
  const { count, ref } = useAnimatedCounter(value, animationDuration)

  return (
    <div
      ref={ref}
      className={cn(
        'rounded-2xl border bg-card p-6 text-center transition-shadow hover:shadow-md',
        className
      )}
    >
      {/* Icon */}
      {icon && (
        <div className="mb-4 flex justify-center">
          <div className="rounded-full bg-primary/10 p-3 text-primary">
            {icon}
          </div>
        </div>
      )}

      {/* Value */}
      <div className="mb-2">
        <span className="text-4xl font-bold tracking-tight">
          {prefix}
          {formatNumber(count)}
          {suffix}
        </span>
      </div>

      {/* Label */}
      <p className="text-sm text-muted-foreground">{label}</p>

      {/* Trend indicator */}
      {trend && (
        <div
          className={cn(
            'mt-3 inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium',
            trend.isPositive
              ? 'bg-brand-green-100 text-brand-green-800'
              : 'bg-red-100 text-red-800'
          )}
        >
          <span>{trend.isPositive ? '+' : ''}{trend.value}%</span>
          <span className="text-muted-foreground">vs last month</span>
        </div>
      )}
    </div>
  )
}

// Grid layout for multiple stat cards
interface StatGridProps {
  children: React.ReactNode
  columns?: 2 | 3 | 4
  className?: string
}

export function StatGrid({ children, columns = 3, className }: StatGridProps) {
  return (
    <div
      className={cn(
        'grid gap-4',
        columns === 2 && 'grid-cols-2',
        columns === 3 && 'grid-cols-1 sm:grid-cols-3',
        columns === 4 && 'grid-cols-2 sm:grid-cols-4',
        className
      )}
    >
      {children}
    </div>
  )
}
