'use client'

import Image from 'next/image'
import { cn } from '@/lib/utils'

interface CategoryShowcaseProps {
  title: string
  subtitle: string
  description: string
  benefits: string[]
  imageSrc?: string
  imageAlt?: string
  accentColor?: 'gold' | 'green' | 'brown' | 'sky'
  icon?: React.ReactNode
}

const accentStyles = {
  gold: {
    bg: 'bg-gradient-to-br from-brand-green-100 to-amber-100',
    border: 'border-brand-green-200',
    text: 'text-brand-green-800',
    badge: 'bg-brand-green-500 text-white',
  },
  green: {
    bg: 'bg-gradient-to-br from-brand-green-100 to-emerald-100',
    border: 'border-brand-green-200',
    text: 'text-brand-green-800',
    badge: 'bg-brand-green-500 text-white',
  },
  brown: {
    bg: 'bg-gradient-to-br from-brand-green-100 to-amber-100',
    border: 'border-brand-green-200',
    text: 'text-brand-green-800',
    badge: 'bg-brand-green-700 text-white',
  },
  sky: {
    bg: 'bg-gradient-to-br from-brand-blue-100 to-blue-100',
    border: 'border-brand-blue-200',
    text: 'text-brand-blue-800',
    badge: 'bg-brand-blue-500 text-white',
  },
}

export function CategoryShowcase({
  title,
  subtitle,
  description,
  benefits,
  imageSrc,
  imageAlt,
  accentColor = 'gold',
  icon,
}: CategoryShowcaseProps) {
  const styles = accentStyles[accentColor]

  return (
    <div className={cn('rounded-2xl border overflow-hidden mb-6', styles.border, styles.bg)}>
      <div className="flex flex-col lg:flex-row">
        {/* Image Section - Falls back to gradient with icon if image doesn't load */}
        <div className="relative lg:w-2/5 h-48 lg:h-auto min-h-[12rem]">
          {imageSrc ? (
            <>
              <Image
                src={imageSrc}
                alt={imageAlt || title}
                fill
                className="object-cover"
                sizes="(max-width: 1024px) 100vw, 40vw"
                onError={(e) => {
                  // Hide the image on error, showing the fallback
                  e.currentTarget.style.display = 'none'
                }}
              />
              <div className="absolute inset-0 bg-gradient-to-t from-black/50 via-transparent to-transparent lg:bg-gradient-to-r" />
            </>
          ) : null}
          {/* Fallback/overlay with icon - always visible as backup */}
          <div className={cn(
            'absolute inset-0 flex items-center justify-center',
            imageSrc ? 'bg-transparent' : `bg-gradient-to-br ${styles.bg}`
          )}>
            {icon && (
              <div className="w-16 h-16 lg:w-20 lg:h-20 rounded-full bg-white/90 backdrop-blur-sm flex items-center justify-center shadow-lg">
                {icon}
              </div>
            )}
          </div>
        </div>

        {/* Content Section */}
        <div className="flex-1 p-6">
          <div className="flex items-start justify-between mb-3">
            <div>
              <span className={cn('text-xs font-semibold uppercase tracking-wider', styles.text)}>
                {subtitle}
              </span>
              <h3 className="text-xl font-bold text-neutral-900 mt-1">{title}</h3>
            </div>
            <span className={cn('text-xs font-bold px-3 py-1 rounded-full', styles.badge)}>
              High Impact
            </span>
          </div>

          <p className="text-neutral-700 mb-4">{description}</p>

          <div className="space-y-2">
            <p className="text-sm font-semibold text-neutral-900">Why sponsors love this:</p>
            <ul className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {benefits.map((benefit, index) => (
                <li key={index} className="flex items-start gap-2 text-sm text-neutral-700">
                  <span className={cn('flex-shrink-0 w-5 h-5 rounded-full flex items-center justify-center text-xs', styles.badge)}>
                    {index + 1}
                  </span>
                  {benefit}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Pre-defined category showcases with compelling copy
 */
export const categoryShowcases = {
  workerBranding: {
    title: 'Your Brand on the Frontlines',
    subtitle: 'Worker Branding',
    description:
      'Turn every worker into a walking billboard for your business. Custom vests, shirts, and caps with your logo create thousands of impressions per cleaning session.',
    benefits: [
      'Street-level visibility to locals',
      'Positive brand association',
      'Photo-ready for social media',
      'Workers keep gear (extended exposure)',
    ],
    accentColor: 'gold' as const,
  },
  equipmentBranding: {
    title: 'Professional Tools, Your Colors',
    subtitle: 'Equipment Branding',
    description:
      'Branded cleaning equipment makes an immediate impression. Custom brooms, rakes, carts, and even trash bags showcase your commitment to the community.',
    benefits: [
      'Highly visible during cleaning',
      'Professional appearance',
      'Great for photos & video',
      'Long-lasting brand presence',
    ],
    accentColor: 'green' as const,
  },
  streetAdvertising: {
    title: 'Own the Block',
    subtitle: 'Street-Level Advertising',
    description:
      'From yard signs to chalk art, make your presence known on every block you sponsor. These high-visibility placements turn cleaned blocks into branded spaces.',
    benefits: [
      'Immediate neighborhood impact',
      'Multi-day visibility',
      'Shareable moments',
      'Community appreciation',
    ],
    accentColor: 'brown' as const,
  },
  digitalPresence: {
    title: 'Amplify Your Impact Online',
    subtitle: 'Digital Presence',
    description:
      'Get featured across our social media channels with before/after photos, worker testimonials, and impact stories. Your sponsorship becomes shareable content.',
    benefits: [
      'Social proof for your brand',
      'Content for your own channels',
      'Tagged in all posts',
      'Video testimonials',
    ],
    accentColor: 'sky' as const,
  },
  services: {
    title: 'Beyond Basic Cleaning',
    subtitle: 'Enhanced Services',
    description:
      'From pressure washing to graffiti removal, offer your community the deep clean it deserves. Enhanced services create dramatic before/after transformations.',
    benefits: [
      'Visible transformation',
      'Addresses real problems',
      'Media-worthy results',
      'Lasting community impact',
    ],
    accentColor: 'green' as const,
  },
  events: {
    title: 'Make It an Event',
    subtitle: 'Event Packages',
    description:
      'From grand openings to block parties, our crews turn any event into a clean, safe, and memorable experience. Perfect for PR opportunities and community engagement.',
    benefits: [
      'Photo opportunities galore',
      'Community goodwill',
      'Press-worthy moments',
      'Scalable crew sizes',
    ],
    accentColor: 'gold' as const,
  },
  media: {
    title: 'Capture Your Story',
    subtitle: 'Media Production',
    description:
      'Professional photography and video that tells your impact story. From social clips to full documentaries, create content that resonates for years.',
    benefits: [
      'Professional quality',
      'Full usage rights',
      'Multiple formats',
      'Ready for any platform',
    ],
    accentColor: 'sky' as const,
  },
  enhancedCleaning: {
    title: 'Deep Clean Transformation',
    subtitle: 'Enhanced Cleaning Services',
    description:
      'Go beyond basic cleaning with pressure washing, graffiti removal, and illegal dumping cleanup. Create dramatic before/after transformations that make headlines.',
    benefits: [
      'Dramatic visual impact',
      'Tackles tough problems',
      'News-worthy results',
      'Long-lasting effects',
    ],
    accentColor: 'green' as const,
  },
  seasonalServices: {
    title: 'Year-Round Community Care',
    subtitle: 'Seasonal Services',
    description:
      'From snow removal to fall leaf collection, show your community commitment throughout the year. Seasonal services demonstrate ongoing investment in neighborhood well-being.',
    benefits: [
      'Continuous visibility',
      'Practical community help',
      'Seasonal PR moments',
      'Recurring impact stories',
    ],
    accentColor: 'sky' as const,
  },
  greenServices: {
    title: 'Greening the Neighborhood',
    subtitle: 'Green Services',
    description:
      'Landscaping, tree well care, and community garden support transform vacant spaces into community assets. Green spaces increase property values and community pride.',
    benefits: [
      'Environmental impact',
      'Community beautification',
      'Long-term transformation',
      'ESG story material',
    ],
    accentColor: 'green' as const,
  },
  businessEngagement: {
    title: 'Direct Community Outreach',
    subtitle: 'Business Engagement',
    description:
      'Put your materials directly in community hands. Flyers, samples, QR codes, and business cards distributed by trusted local workers. Workers receive bonus pay for this work.',
    benefits: [
      'Trusted local delivery',
      'Worker endorsement',
      'Direct engagement',
      'Measurable distribution',
    ],
    accentColor: 'brown' as const,
  },
}
