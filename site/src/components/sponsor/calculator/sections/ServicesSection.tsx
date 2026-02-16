'use client'

import { ChevronRight, ChevronLeft, Sparkles, Leaf, Snowflake, Briefcase } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { AddonCard } from '@/components/sponsor/calculator/cards/AddonCard'
import { CategoryShowcase, categoryShowcases } from '@/components/sponsor/calculator/CategoryShowcase'
import {
  enhancedCleaningServices,
  seasonalServices,
  greenServices,
  businessEngagementAddons,
  type SponsorPackageConfig,
} from '@/lib/sponsor-pricing'

interface ServicesSectionProps {
  config: SponsorPackageConfig
  onChange: (updates: Partial<SponsorPackageConfig>) => void
  onNext: () => void
  onBack: () => void
}

export function ServicesSection({ config, onChange, onNext, onBack }: ServicesSectionProps) {
  const toggleAddon = (
    category:
      | 'enhancedCleaning'
      | 'seasonalServices'
      | 'greenServices'
      | 'businessEngagement',
    key: string
  ) => {
    const current = config[category] || {}
    onChange({
      [category]: {
        ...current,
        [key]: !current[key as keyof typeof current],
      },
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <span className="w-8 h-8 rounded-full bg-brand-gold-100 text-brand-gold-600 flex items-center justify-center text-sm font-bold">
            3
          </span>
          Add Services (Optional)
        </CardTitle>
        <p className="text-sm text-neutral-600 mt-1">
          Expand the scope with additional cleaning and engagement services
        </p>
      </CardHeader>
      <CardContent className="space-y-8">
        {/* Enhanced Cleaning */}
        <div>
          <CategoryShowcase
            {...categoryShowcases.enhancedCleaning}
            imageSrc="/images/upsells/enhanced-cleaning.svg"
            imageAlt="Pressure washing a sidewalk showing dramatic before and after"
            icon={<Sparkles className="w-8 h-8 text-brand-green-600" />}
          />
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(enhancedCleaningServices).map(([key, service]) => (
              <AddonCard
                key={key}
                id={key}
                name={service.name}
                description={service.description}
                pricePerBlock={service.pricePerBlock}
                blockCount={config.blockCount}
                isSelected={!!config.enhancedCleaning[key as keyof typeof config.enhancedCleaning]}
                onToggle={() => toggleAddon('enhancedCleaning', key)}
              />
            ))}
          </div>
        </div>

        {/* Seasonal Services */}
        <div>
          <CategoryShowcase
            {...categoryShowcases.seasonalServices}
            imageSrc="/images/upsells/seasonal-services.svg"
            imageAlt="Workers doing snow removal in a Chicago neighborhood"
            icon={<Snowflake className="w-8 h-8 text-brand-sky-600" />}
          />
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(seasonalServices).map(([key, service]) => (
              <AddonCard
                key={key}
                id={key}
                name={service.name}
                description={service.description}
                pricePerBlock={service.pricePerBlock}
                blockCount={config.blockCount}
                isSelected={!!config.seasonalServices[key as keyof typeof config.seasonalServices]}
                onToggle={() => toggleAddon('seasonalServices', key)}
              />
            ))}
          </div>
        </div>

        {/* Green Services */}
        <div>
          <CategoryShowcase
            {...categoryShowcases.greenServices}
            imageSrc="/images/upsells/green-services.svg"
            imageAlt="Community garden and tree well care in an urban setting"
            icon={<Leaf className="w-8 h-8 text-brand-green-600" />}
          />
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(greenServices).map(([key, service]) => {
              const pricePerBlock =
                'pricePerBlock' in service
                  ? service.pricePerBlock
                  : 'pricePerLot' in service
                    ? service.pricePerLot
                    : 'pricePerPlot' in service
                      ? service.pricePerPlot
                      : 0
              return (
                <AddonCard
                  key={key}
                  id={key}
                  name={service.name}
                  description={service.description}
                  pricePerBlock={pricePerBlock}
                  blockCount={config.blockCount}
                  isSelected={!!config.greenServices[key as keyof typeof config.greenServices]}
                  onToggle={() => toggleAddon('greenServices', key)}
                />
              )
            })}
          </div>
        </div>

        {/* Business Engagement */}
        <div>
          <CategoryShowcase
            {...categoryShowcases.businessEngagement}
            imageSrc="/images/upsells/business-engagement.svg"
            imageAlt="Worker handing out promotional materials to a smiling resident"
            icon={<Briefcase className="w-8 h-8 text-brand-brown-600" />}
          />
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(businessEngagementAddons).map(([key, addon]) => (
              <AddonCard
                key={key}
                id={key}
                name={addon.name}
                description={addon.description}
                pricePerBlock={addon.pricePerBlock}
                blockCount={config.blockCount}
                isSelected={
                  !!config.businessEngagement[key as keyof typeof config.businessEngagement]
                }
                workerBonus={addon.workerBonus}
                onToggle={() => toggleAddon('businessEngagement', key)}
              />
            ))}
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
            Continue to Events
            <ChevronRight className="w-5 h-5" />
          </button>
        </div>
      </CardContent>
    </Card>
  )
}
