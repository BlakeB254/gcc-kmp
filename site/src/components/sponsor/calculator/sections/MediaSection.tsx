'use client'

import { ChevronLeft, Camera, Video, Check, Building2 } from 'lucide-react'
import Link from 'next/link'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { CategoryShowcase, categoryShowcases } from '@/components/sponsor/calculator/CategoryShowcase'
import { VariationShowcase } from '@/components/sponsor/calculator/VariationShowcase'
import { baseImageSrcFor, variationImageSrcsFor, zoomRegionFor } from '@/components/sponsor/calculator/variationData'
import { cn } from '@/lib/utils'
import {
  formatPrice,
  mediaPackages,
  videoPackages,
  requiresCustomQuote,
  type SponsorPackageConfig,
  type PriceBreakdown,
  type MediaPackage,
  type VideoPackage,
} from '@/lib/sponsor-pricing'

interface MediaSectionProps {
  config: SponsorPackageConfig
  onChange: (updates: Partial<SponsorPackageConfig>) => void
  onBack: () => void
  /** Opens the checkout form in the quote summary. */
  onCheckout: () => void
  priceBreakdown: PriceBreakdown
}

export function MediaSection({ config, onChange, onBack, onCheckout, priceBreakdown }: MediaSectionProps) {
  const selectPhotoPackage = (packageId: MediaPackage | undefined) => {
    onChange({ photoPackage: packageId })
  }

  const selectVideoPackage = (packageId: VideoPackage | undefined) => {
    onChange({ videoPackage: packageId })
  }

  const needsCustomQuote = requiresCustomQuote(priceBreakdown.total)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <span className="w-8 h-8 rounded-full bg-brand-green-100 text-brand-green-600 flex items-center justify-center text-sm font-bold">
            5
          </span>
          Add Media (Optional)
        </CardTitle>
        <p className="text-sm text-neutral-600 mt-1">
          Capture your impact with professional photography and video
        </p>
      </CardHeader>
      <CardContent className="space-y-8">
        {/* Media Showcase */}
        <VariationShowcase
          {...categoryShowcases.media}
          baseImageSrc={baseImageSrcFor('media')}
          variationImageSrcs={variationImageSrcsFor('media')}
          zoomRegion={zoomRegionFor('media')}
          imageAlt="Professional photographer and drone capturing a GCC cleaning crew"
          icon={<Camera className="w-8 h-8 text-brand-blue-600" />}
        />

        {/* Photo Packages */}
        <div>
          <div className="flex items-center gap-2 mb-4">
            <Camera className="w-5 h-5 text-brand-green-600" />
            <h3 className="font-semibold text-neutral-900">Photography</h3>
          </div>
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(mediaPackages).map(([key, pkg]) => {
              const isSelected = config.photoPackage === key
              return (
                <button
                  key={key}
                  onClick={() => selectPhotoPackage(isSelected ? undefined : (key as MediaPackage))}
                  className={cn(
                    'text-left p-4 rounded-xl border-2 transition-all',
                    isSelected
                      ? 'border-brand-green-500 bg-brand-green-50'
                      : 'border-neutral-200 hover:border-brand-green-300'
                  )}
                >
                  <div className="flex items-start justify-between mb-2">
                    <h4 className="font-semibold text-neutral-900">{pkg.name}</h4>
                    <div className="flex items-center gap-2">
                      <span className="font-bold text-brand-green-600">{formatPrice(pkg.price)}</span>
                      {isSelected && (
                        <div className="w-5 h-5 rounded-full bg-brand-green-500 flex items-center justify-center">
                          <Check className="w-3 h-3 text-white" />
                        </div>
                      )}
                    </div>
                  </div>
                  <p className="text-sm text-neutral-600">{pkg.description}</p>
                </button>
              )
            })}
          </div>
        </div>

        {/* Video Packages */}
        <div>
          <div className="flex items-center gap-2 mb-4">
            <Video className="w-5 h-5 text-brand-green-600" />
            <h3 className="font-semibold text-neutral-900">Video Production</h3>
          </div>
          <div className="grid sm:grid-cols-2 gap-3">
            {Object.entries(videoPackages).map(([key, pkg]) => {
              const isSelected = config.videoPackage === key
              return (
                <button
                  key={key}
                  onClick={() => selectVideoPackage(isSelected ? undefined : (key as VideoPackage))}
                  className={cn(
                    'text-left p-4 rounded-xl border-2 transition-all',
                    isSelected
                      ? 'border-brand-green-500 bg-brand-green-50'
                      : 'border-neutral-200 hover:border-brand-green-300'
                  )}
                >
                  <div className="flex items-start justify-between mb-2">
                    <h4 className="font-semibold text-neutral-900">{pkg.name}</h4>
                    <div className="flex items-center gap-2">
                      <span className="font-bold text-brand-green-600">{formatPrice(pkg.price)}</span>
                      {isSelected && (
                        <div className="w-5 h-5 rounded-full bg-brand-green-500 flex items-center justify-center">
                          <Check className="w-3 h-3 text-white" />
                        </div>
                      )}
                    </div>
                  </div>
                  <p className="text-sm text-neutral-600">{pkg.description}</p>
                </button>
              )
            })}
          </div>
        </div>

        {/* Enterprise CTA */}
        {priceBreakdown.isEnterprise && (
          <div className="bg-gradient-to-r from-brand-green-800 to-brand-green-900 rounded-xl p-6 text-white">
            <div className="flex items-start gap-4">
              <div className="w-12 h-12 rounded-full bg-brand-green-500 flex items-center justify-center flex-shrink-0">
                <Building2 className="w-6 h-6 text-brand-green-900" />
              </div>
              <div className="flex-1">
                <h3 className="font-bold text-lg mb-1">Enterprise Partnership Unlocked</h3>
                <p className="text-white/80 text-sm mb-3">
                  Your package qualifies for our {priceBreakdown.enterpriseTier?.replace(/_/g, ' ')} tier
                  with exclusive benefits including dedicated account management, priority scheduling,
                  and enhanced recognition.
                </p>
                <Link
                  href="/sponsor/enterprise"
                  className="inline-flex items-center gap-2 px-4 py-2 bg-brand-green-500 text-brand-green-900 font-semibold rounded-lg hover:bg-brand-green-400 transition-colors"
                >
                  Learn About Enterprise Benefits
                </Link>
              </div>
            </div>
          </div>
        )}

        {/* Navigation */}
        <div className="flex gap-3 pt-4">
          <button
            onClick={onBack}
            className="flex-1 py-4 px-6 rounded-xl border-2 border-neutral-200 font-semibold text-neutral-700 hover:bg-neutral-50 transition-colors flex items-center justify-center gap-2"
          >
            <ChevronLeft className="w-5 h-5" />
            Back
          </button>

          {needsCustomQuote ? (
            <Link
              href="/sponsor/enterprise"
              className="flex-1 bg-gradient-to-r from-brand-green-700 to-brand-green-800 text-white font-bold py-4 text-lg rounded-xl flex items-center justify-center gap-2 hover:from-brand-green-600 hover:to-brand-green-700 transition-all"
            >
              Request Custom Quote
            </Link>
          ) : (
            <button
              type="button"
              onClick={onCheckout}
              className="flex-1 btn-primary py-4 text-lg flex items-center justify-center gap-2"
            >
              Proceed to Checkout
            </button>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
