'use client'

import { ChevronRight, ChevronLeft, Shirt, Wrench, Megaphone, Globe } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { AddonCard } from '@/components/sponsor/calculator/cards/AddonCard'
import { CategoryShowcase, categoryShowcases } from '@/components/sponsor/calculator/CategoryShowcase'
import { VariationShowcase } from '@/components/sponsor/calculator/VariationShowcase'
import { baseImageSrcFor, variationImageSrcsFor, zoomRegionFor } from '@/components/sponsor/calculator/variationData'
import {
  workerBrandingAddons,
  equipmentBrandingAddons,
  streetAdvertisingAddons,
  digitalPresenceAddons,
  type SponsorPackageConfig,
} from '@/lib/sponsor-pricing'

interface BrandingSectionProps {
  config: SponsorPackageConfig
  onChange: (updates: Partial<SponsorPackageConfig>) => void
  onNext: () => void
  onBack: () => void
}

// Addon data with bundle information
type AddonData = {
  isBundle?: boolean
  includes?: readonly string[]
  [key: string]: unknown
}

// Map categories to their addon definitions
const categoryAddons: Record<string, Record<string, AddonData>> = {
  workerBranding: workerBrandingAddons,
  equipmentBranding: equipmentBrandingAddons,
  streetAdvertising: streetAdvertisingAddons,
  digitalPresence: digitalPresenceAddons,
}

// Get addon data for a specific category and key
function getAddonData(category: string, key: string): AddonData | undefined {
  return categoryAddons[category]?.[key]
}

// Find all bundles in a category that include a specific item
function getBundlesContaining(category: string, itemKey: string): string[] {
  const addons = categoryAddons[category]
  if (!addons) return []

  return Object.entries(addons)
    .filter(([, addon]) => addon.isBundle && addon.includes?.includes(itemKey))
    .map(([bundleKey]) => bundleKey)
}

export function BrandingSection({ config, onChange, onNext, onBack }: BrandingSectionProps) {
  const toggleAddon = (
    category: 'workerBranding' | 'equipmentBranding' | 'streetAdvertising' | 'digitalPresence',
    key: string
  ) => {
    const current = config[category] || {}
    const isSelecting = !current[key as keyof typeof current]
    const updates: Record<string, boolean> = { ...current, [key]: isSelecting }

    if (isSelecting) {
      const addonData = getAddonData(category, key)

      if (addonData?.isBundle && addonData.includes) {
        // Selecting a bundle: auto-deselect all included individual items
        for (const includedKey of addonData.includes) {
          updates[includedKey] = false
        }
      } else {
        // Selecting an individual item: auto-deselect any bundle that includes it
        const bundles = getBundlesContaining(category, key)
        for (const bundleKey of bundles) {
          updates[bundleKey] = false
        }
      }
    }

    onChange({ [category]: updates })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <span className="w-8 h-8 rounded-full bg-brand-green-100 text-brand-green-600 flex items-center justify-center text-sm font-bold">
            2
          </span>
          Add Branding (Optional)
        </CardTitle>
        <p className="text-sm text-neutral-600 mt-1">
          Amplify your impact with branded gear and signage
        </p>
      </CardHeader>
      <CardContent className="space-y-8">
        {/* Worker Branding */}
        <div>
          <VariationShowcase
            {...categoryShowcases.workerBranding}
            baseImageSrc={baseImageSrcFor('worker-branding')}
            variationImageSrcs={variationImageSrcsFor('worker-branding')}
          zoomRegion={zoomRegionFor('worker-branding')}
            imageAlt="Community worker in a green GCC vest sweeping a Chicago sidewalk"
            icon={<Shirt className="w-8 h-8 text-brand-green-600" />}
          />
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(workerBrandingAddons).map(([key, addon]) => (
              <AddonCard
                key={key}
                id={key}
                name={addon.name}
                description={addon.description}
                pricePerBlock={addon.pricePerBlock}
                blockCount={config.blockCount}
                isSelected={!!config.workerBranding[key as keyof typeof config.workerBranding]}
                isBundle={'isBundle' in addon && addon.isBundle}
                onToggle={() => toggleAddon('workerBranding', key)}
              />
            ))}
          </div>
        </div>

        {/* Equipment Branding */}
        <div>
          <VariationShowcase
            {...categoryShowcases.equipmentBranding}
            baseImageSrc={baseImageSrcFor('equipment-branding')}
            variationImageSrcs={variationImageSrcsFor('equipment-branding')}
          zoomRegion={zoomRegionFor('equipment-branding')}
            imageAlt="GCC-branded cleaning cart with broom and rake on a Chicago sidewalk"
            icon={<Wrench className="w-8 h-8 text-brand-green-600" />}
          />
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(equipmentBrandingAddons).map(([key, addon]) => (
              <AddonCard
                key={key}
                id={key}
                name={addon.name}
                description={addon.description}
                pricePerBlock={addon.pricePerBlock}
                blockCount={config.blockCount}
                isSelected={!!config.equipmentBranding[key as keyof typeof config.equipmentBranding]}
                isBundle={'isBundle' in addon && addon.isBundle}
                onToggle={() => toggleAddon('equipmentBranding', key)}
              />
            ))}
          </div>
        </div>

        {/* Street Advertising */}
        <div>
          <VariationShowcase
            {...categoryShowcases.streetAdvertising}
            baseImageSrc={baseImageSrcFor('street-advertising')}
            variationImageSrcs={variationImageSrcsFor('street-advertising')}
          zoomRegion={zoomRegionFor('street-advertising')}
            imageAlt="Cleaned by GCC yard sign in a North Lawndale parkway"
            icon={<Megaphone className="w-8 h-8 text-brand-green-600" />}
          />
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(streetAdvertisingAddons).map(([key, addon]) => (
              <AddonCard
                key={key}
                id={key}
                name={addon.name}
                description={addon.description}
                pricePerBlock={addon.pricePerBlock}
                blockCount={config.blockCount}
                isSelected={!!config.streetAdvertising[key as keyof typeof config.streetAdvertising]}
                onToggle={() => toggleAddon('streetAdvertising', key)}
              />
            ))}
          </div>
        </div>

        {/* Digital Presence */}
        <div>
          <VariationShowcase
            {...categoryShowcases.digitalPresence}
            baseImageSrc={baseImageSrcFor('digital-presence')}
            variationImageSrcs={variationImageSrcsFor('digital-presence')}
          zoomRegion={zoomRegionFor('digital-presence')}
            imageAlt="Smartphone showing a GCC social media post"
            icon={<Globe className="w-8 h-8 text-brand-blue-600" />}
          />
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(digitalPresenceAddons).map(([key, addon]) => (
              <AddonCard
                key={key}
                id={key}
                name={addon.name}
                description={addon.description}
                pricePerBlock={addon.pricePerBlock}
                blockCount={config.blockCount}
                isSelected={!!config.digitalPresence[key as keyof typeof config.digitalPresence]}
                onToggle={() => toggleAddon('digitalPresence', key)}
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
            Continue to Services
            <ChevronRight className="w-5 h-5" />
          </button>
        </div>
      </CardContent>
    </Card>
  )
}
