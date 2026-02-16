'use client'

import { ChevronRight, ChevronLeft, Calendar, Users, Trash2 } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { CategoryShowcase, categoryShowcases } from '@/components/sponsor/calculator/CategoryShowcase'
import { cn } from '@/lib/utils'
import {
  formatPrice,
  eventPackages,
  eventTypes,
  type SponsorPackageConfig,
  type EventPackage,
} from '@/lib/sponsor-pricing'

interface EventsSectionProps {
  config: SponsorPackageConfig
  onChange: (updates: Partial<SponsorPackageConfig>) => void
  onNext: () => void
  onBack: () => void
}

export function EventsSection({ config, onChange, onNext, onBack }: EventsSectionProps) {
  const addEvent = (packageId: EventPackage) => {
    const newEvent = {
      packageId,
      quantity: 1,
    }
    onChange({ events: [...config.events, newEvent] })
  }

  const removeEvent = (index: number) => {
    const newEvents = config.events.filter((_, i) => i !== index)
    onChange({ events: newEvents })
  }

  const updateEventQuantity = (index: number, quantity: number) => {
    const newEvents = config.events.map((event, i) =>
      i === index ? { ...event, quantity: Math.max(1, quantity) } : event
    )
    onChange({ events: newEvents })
  }

  const totalEventsPrice = config.events.reduce((total, event) => {
    const pkg = eventPackages[event.packageId]
    return total + (pkg?.price || 0) * (event.quantity || 1)
  }, 0)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <span className="w-8 h-8 rounded-full bg-brand-gold-100 text-brand-gold-600 flex items-center justify-center text-sm font-bold">
            4
          </span>
          Add Events (Optional)
        </CardTitle>
        <p className="text-sm text-neutral-600 mt-1">
          Book cleaning crews for special events and occasions
        </p>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* Events Showcase */}
        <CategoryShowcase
          {...categoryShowcases.events}
          imageSrc="/images/upsells/events.svg"
          imageAlt="GCC cleaning crew at a block party event with branded equipment"
          icon={<Calendar className="w-8 h-8 text-brand-gold-600" />}
        />

        {/* Event Packages */}
        <div>
          <div className="flex items-center gap-2 mb-4">
            <Users className="w-5 h-5 text-brand-gold-600" />
            <h3 className="font-semibold text-neutral-900">Crew Packages</h3>
          </div>
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(eventPackages).map(([key, pkg]) => {
              const isAdded = config.events.some((e) => e.packageId === key)
              return (
                <button
                  key={key}
                  onClick={() => !isAdded && addEvent(key as EventPackage)}
                  disabled={isAdded}
                  className={cn(
                    'text-left p-4 rounded-xl border-2 transition-all',
                    isAdded
                      ? 'border-brand-green-500 bg-brand-green-50 opacity-60'
                      : 'border-neutral-200 hover:border-brand-gold-300 hover:bg-brand-gold-50'
                  )}
                >
                  <div className="flex items-start justify-between mb-2">
                    <h4 className="font-semibold text-neutral-900">{pkg.name}</h4>
                    <span className="font-bold text-brand-gold-600">{formatPrice(pkg.price)}</span>
                  </div>
                  <p className="text-sm text-neutral-600 mb-2">{pkg.description}</p>
                  <div className="flex items-center gap-3 text-xs text-neutral-500">
                    <span>{pkg.workers} workers</span>
                    <span>•</span>
                    <span>{pkg.duration} hours</span>
                  </div>
                  {isAdded && (
                    <div className="mt-2 text-xs text-brand-green-600 font-medium">Added below</div>
                  )}
                </button>
              )
            })}
          </div>
        </div>

        {/* Selected Events */}
        {config.events.length > 0 && (
          <div className="border-t pt-6">
            <div className="flex items-center justify-between mb-4">
              <h3 className="font-semibold text-neutral-900">Selected Events</h3>
              <span className="text-sm text-neutral-600">
                Total: <span className="font-semibold text-brand-gold-600">{formatPrice(totalEventsPrice)}</span>
              </span>
            </div>
            <div className="space-y-3">
              {config.events.map((event, index) => {
                const pkg = eventPackages[event.packageId]
                if (!pkg) return null
                return (
                  <div
                    key={index}
                    className="flex items-center justify-between p-4 bg-neutral-50 rounded-xl"
                  >
                    <div>
                      <h4 className="font-medium text-neutral-900">{pkg.name}</h4>
                      <p className="text-sm text-neutral-600">
                        {formatPrice(pkg.price)} × {event.quantity || 1} ={' '}
                        <span className="font-semibold">
                          {formatPrice(pkg.price * (event.quantity || 1))}
                        </span>
                      </p>
                    </div>
                    <div className="flex items-center gap-3">
                      <div className="flex items-center gap-2">
                        <button
                          onClick={() => updateEventQuantity(index, (event.quantity || 1) - 1)}
                          className="w-8 h-8 rounded-lg border border-neutral-300 flex items-center justify-center hover:bg-neutral-100"
                        >
                          -
                        </button>
                        <span className="w-8 text-center font-medium">{event.quantity || 1}</span>
                        <button
                          onClick={() => updateEventQuantity(index, (event.quantity || 1) + 1)}
                          className="w-8 h-8 rounded-lg border border-neutral-300 flex items-center justify-center hover:bg-neutral-100"
                        >
                          +
                        </button>
                      </div>
                      <button
                        onClick={() => removeEvent(index)}
                        className="p-2 text-red-500 hover:bg-red-50 rounded-lg transition-colors"
                      >
                        <Trash2 className="w-5 h-5" />
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        )}

        {/* Event Types Info */}
        <div className="bg-brand-gold-50 border border-brand-gold-200 rounded-xl p-4">
          <div className="flex items-start gap-3">
            <Calendar className="w-5 h-5 text-brand-gold-600 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-medium text-brand-gold-800 mb-1">Popular Event Types</h4>
              <div className="flex flex-wrap gap-2">
                {Object.values(eventTypes)
                  .slice(0, 4)
                  .map((type) => (
                    <span
                      key={type.id}
                      className="text-xs px-2 py-1 bg-white rounded-full text-brand-gold-700"
                    >
                      {type.name}
                    </span>
                  ))}
              </div>
            </div>
          </div>
        </div>

        {/* Navigation */}
        <div className="flex gap-3 pt-4">
          <button
            onClick={onBack}
            className="flex-1 py-4 px-6 rounded-xl border-2 border-neutral-200 font-semibold text-neutral-700 hover:bg-neutral-50 transition-colors flex items-center justify-center gap-2"
          >
            <ChevronLeft className="w-5 h-5" />
            Back
          </button>
          <button
            onClick={onNext}
            className="flex-1 btn-primary py-4 text-lg flex items-center justify-center gap-2"
          >
            Continue to Media
            <ChevronRight className="w-5 h-5" />
          </button>
        </div>
      </CardContent>
    </Card>
  )
}
