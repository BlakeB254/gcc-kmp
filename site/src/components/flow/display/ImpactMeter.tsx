'use client'

import * as React from 'react'
import { Leaf, Users, DollarSign, MapPin, Sparkles } from 'lucide-react'
import { Progress } from '@/components/ui/progress'
import { cn, formatCurrency } from '@/lib/utils'

interface ImpactMetric {
  id: string
  label: string
  current: number
  target: number
  unit?: string
  icon: React.ReactNode
  color: string
}

interface ImpactMeterProps {
  metrics: ImpactMetric[]
  title?: string
  className?: string
}

const defaultMetrics: ImpactMetric[] = [
  {
    id: 'blocks',
    label: 'Blocks Cleaned',
    current: 127,
    target: 200,
    icon: <MapPin className="h-5 w-5" />,
    color: 'bg-brand-gold-500',
  },
  {
    id: 'workers',
    label: 'Workers Employed',
    current: 34,
    target: 50,
    icon: <Users className="h-5 w-5" />,
    color: 'bg-brand-green-500',
  },
  {
    id: 'earnings',
    label: 'Total Raised',
    current: 12700,
    target: 20000,
    unit: '$',
    icon: <DollarSign className="h-5 w-5" />,
    color: 'bg-brand-sky-500',
  },
  {
    id: 'litter',
    label: 'Litter Removed (tons)',
    current: 8.5,
    target: 15,
    icon: <Leaf className="h-5 w-5" />,
    color: 'bg-brand-brown-500',
  },
]

export function ImpactMeter({
  metrics = defaultMetrics,
  title = 'Community Impact',
  className,
}: ImpactMeterProps) {
  return (
    <div className={cn('space-y-6', className)}>
      {title && (
        <div className="flex items-center gap-2">
          <Sparkles className="h-5 w-5 text-brand-gold-500" />
          <h3 className="text-lg font-semibold">{title}</h3>
        </div>
      )}

      <div className="space-y-5">
        {metrics.map((metric) => {
          const percentage = Math.min((metric.current / metric.target) * 100, 100)
          const formattedCurrent =
            metric.unit === '$'
              ? formatCurrency(metric.current * 100)
              : metric.current.toLocaleString()
          const formattedTarget =
            metric.unit === '$'
              ? formatCurrency(metric.target * 100)
              : metric.target.toLocaleString()

          return (
            <div key={metric.id} className="space-y-2">
              {/* Label row */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-sm">
                  <div className={cn('rounded-md p-1.5', `${metric.color}/10`)}>
                    <span className={metric.color.replace('bg-', 'text-')}>
                      {metric.icon}
                    </span>
                  </div>
                  <span className="font-medium">{metric.label}</span>
                </div>
                <span className="text-sm text-muted-foreground">
                  {formattedCurrent} / {formattedTarget}
                </span>
              </div>

              {/* Progress bar */}
              <Progress
                value={percentage}
                className="h-3"
                indicatorClassName={metric.color}
              />
            </div>
          )
        })}
      </div>

      {/* Overall progress message */}
      <div className="rounded-lg bg-muted/50 p-4 text-center">
        <p className="text-sm">
          <span className="font-semibold text-brand-green-600">Amazing progress!</span>{' '}
          Our community is making a real difference in North Lawndale.
        </p>
      </div>
    </div>
  )
}
