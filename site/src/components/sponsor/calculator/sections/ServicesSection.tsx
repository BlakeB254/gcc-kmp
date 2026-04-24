'use client'

import { ChevronRight, ChevronLeft, Sparkles, Leaf, Snowflake, Briefcase } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { AddonCard } from '@/components/sponsor/calculator/cards/AddonCard'
import { categoryShowcases } from '@/components/sponsor/calculator/CategoryShowcase'
import { VariationShowcase } from '@/components/sponsor/calculator/VariationShowcase'
import { baseImageSrcFor, variationImageSrcsFor, zoomRegionFor } from '@/components/sponsor/calculator/variationData'
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
          <span className="w-8 h-8 rounded-full bg-brand-green-100 text-brand-green-600 flex items-center justify-center text-sm font-bold">
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
          <VariationShowcase
            {...categoryShowcases.enhancedCleaning}
            baseImageSrc={baseImageSrcFor('enhanced-cleaning')}
            variationImageSrcs={variationImageSrcsFor('enhanced-cleaning')}
          zoomRegion={zoomRegionFor('enhanced-cleaning')}
            imageAlt="Freshly pressure-washed brick wall with GCC plaque in a Chicago alley"
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
          <VariationShowcase
            {...categoryShowcases.seasonalServices}
            baseImageSrc={baseImageSrcFor('seasonal-services')}
            variationImageSrcs={variationImageSrcsFor('seasonal-services')}
          zoomRegion={zoomRegionFor('seasonal-services')}
            imageAlt="Cleared snowy sidewalk with GCC salt spreader"
            icon={<Snowflake className="w-8 h-8 text-brand-blue-600" />}
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
          <VariationShowcase
            {...categoryShowcases.greenServices}
            baseImageSrc={baseImageSrcFor('green-services')}
            variationImageSrcs={variationImageSrcsFor('green-services')}
          zoomRegion={zoomRegionFor('green-services')}
            imageAlt="Mulched urban tree well with GCC Planted-by marker"
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
          <VariationShowcase
            {...categoryShowcases.businessEngagement}
            baseImageSrc={baseImageSrcFor('business-engagement')}
            variationImageSrcs={variationImageSrcsFor('business-engagement')}
          zoomRegion={zoomRegionFor('business-engagement')}
            imageAlt="GCC worker handing a branded community flyer to a resident"
            icon={<Briefcase className="w-8 h-8 text-brand-green-600" />}
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
