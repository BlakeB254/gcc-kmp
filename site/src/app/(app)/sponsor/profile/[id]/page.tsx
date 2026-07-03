'use client'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Image from 'next/image'
import Link from 'next/link'
import {
  ArrowLeft,
  Crown,
  Trophy,
  Star,
  Sparkles,
  Globe,
  Twitter,
  Linkedin,
  Instagram,
  Calendar,
  TrendingUp,
  Loader2,
  Lock,
  MapPin,
  Camera,
} from 'lucide-react'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn, formatCurrency, formatRelativeTime } from '@/lib/utils'

type SponsorTier = 'bronze' | 'silver' | 'gold' | 'platinum'

// A sponsor's total (in dollars) at or above this line gets the extra
// full-page "Impact Story" section — the top rung of the recognition
// ladder (card -> on-page section -> full blog-style page).
const FOUNDING_PARTNER_THRESHOLD = 10000

interface SponsorProfile {
  id: number
  displayName: string
  akas: string[]
  type: string
  tier: SponsorTier
  logoUrl: string | null
  tagline: string | null
  bio: string | null
  website: string | null
  socials: {
    twitter: string | null
    linkedin: string | null
    instagram: string | null
  }
  stats: {
    totalContributed: number
    blocksSponsored: number
    transactionCount: number
    memberSince: string
    lastContribution: string | null
  }
  recentContributions: Array<{
    amount: number
    date: string
    type: string
  }>
}

const tierConfig: Record<
  SponsorTier,
  {
    label: string
    icon: React.ComponentType<{ className?: string }>
    color: string
    bgColor: string
    borderColor: string
    gradientFrom: string
    gradientTo: string
  }
> = {
  platinum: {
    label: 'Platinum Sponsor',
    icon: Trophy,
    color: 'text-violet-600',
    bgColor: 'bg-violet-50',
    borderColor: 'border-violet-200',
    gradientFrom: 'from-violet-500',
    gradientTo: 'to-purple-600',
  },
  gold: {
    label: 'Gold Sponsor',
    icon: Crown,
    color: 'text-brand-green-600',
    bgColor: 'bg-brand-green-50',
    borderColor: 'border-brand-green-200',
    gradientFrom: 'from-brand-green-500',
    gradientTo: 'to-amber-600',
  },
  silver: {
    label: 'Silver Sponsor',
    icon: Sparkles,
    color: 'text-slate-500',
    bgColor: 'bg-slate-50',
    borderColor: 'border-slate-200',
    gradientFrom: 'from-slate-400',
    gradientTo: 'to-slate-500',
  },
  bronze: {
    label: 'Bronze Sponsor',
    icon: Star,
    color: 'text-amber-700',
    bgColor: 'bg-amber-50',
    borderColor: 'border-amber-200',
    gradientFrom: 'from-amber-600',
    gradientTo: 'to-orange-700',
  },
}

export default function SponsorProfilePage() {
  const params = useParams()
  const [sponsor, setSponsor] = useState<SponsorProfile | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    async function fetchProfile() {
      try {
        const response = await fetch(`/api/public/sponsor/${params.id}`)
        const data = await response.json()

        if (!response.ok) {
          setError(data.error || 'Failed to load profile')
          return
        }

        setSponsor(data.sponsor)
      } catch (err) {
        setError('Failed to load sponsor profile')
      } finally {
        setIsLoading(false)
      }
    }

    if (params.id) {
      fetchProfile()
    }
  }, [params.id])

  if (isLoading) {
    return (
      <div className="min-h-screen bg-neutral-50 flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <Loader2 className="h-8 w-8 animate-spin text-brand-green-600" />
          <p className="text-muted-foreground">Loading profile...</p>
        </div>
      </div>
    )
  }

  if (error || !sponsor) {
    return (
      <div className="min-h-screen bg-neutral-50 flex items-center justify-center p-4">
        <div className="max-w-md w-full bg-white rounded-2xl shadow-lg p-8 text-center">
          <div className="w-16 h-16 bg-neutral-100 rounded-full flex items-center justify-center mx-auto mb-6">
            <Lock className="w-8 h-8 text-neutral-400" />
          </div>
          <h1 className="text-2xl font-bold text-neutral-900 mb-2">Profile Not Available</h1>
          <p className="text-neutral-600 mb-6">{error || 'This sponsor profile is private or does not exist.'}</p>
          <Button asChild>
            <Link href="/sponsor/wall">
              <ArrowLeft className="w-4 h-4 mr-2" />
              Back to Sponsor Wall
            </Link>
          </Button>
        </div>
      </div>
    )
  }

  const config = tierConfig[sponsor.tier]
  const Icon = config.icon
  const initials = sponsor.displayName
    .split(' ')
    .map((n) => n[0])
    .join('')
    .slice(0, 2)
    .toUpperCase()

  const memberSinceDate = new Date(sponsor.stats.memberSince)
  const memberSinceFormatted = memberSinceDate.toLocaleDateString('en-US', {
    month: 'long',
    year: 'numeric',
  })

  return (
    <div className="min-h-screen bg-neutral-50">
      {/* Header with gradient */}
      <div className={cn('bg-gradient-to-r py-16', config.gradientFrom, config.gradientTo)}>
        <div className="section-container">
          <Link
            href="/sponsor/wall"
            className="inline-flex items-center gap-2 text-white/80 hover:text-white mb-8 transition-colors"
          >
            <ArrowLeft className="w-4 h-4" />
            Back to Sponsor Wall
          </Link>

          <div className="flex flex-col md:flex-row items-start md:items-center gap-6">
            {sponsor.logoUrl ? (
              <div className="h-24 w-24 rounded-full border-4 border-white bg-white shadow-lg overflow-hidden flex items-center justify-center">
                <Image
                  src={sponsor.logoUrl}
                  alt={sponsor.displayName}
                  width={96}
                  height={96}
                  className="h-full w-full object-contain p-2"
                />
              </div>
            ) : (
              <Avatar className="h-24 w-24 border-4 border-white shadow-lg">
                <AvatarFallback className="text-3xl font-bold bg-white text-neutral-800">
                  {initials}
                </AvatarFallback>
              </Avatar>
            )}

            <div className="flex-1">
              <div className="flex items-center gap-3 flex-wrap mb-2">
                <h1 className="text-3xl font-bold text-white">{sponsor.displayName}</h1>
                <Badge className="bg-white/20 text-white border-white/30 gap-1">
                  <Icon className="h-4 w-4" />
                  {config.label}
                </Badge>
              </div>

              {sponsor.akas.length > 0 && (
                <p className="text-white/70 text-sm mb-2">
                  aka {sponsor.akas.join(', ')}
                </p>
              )}

              {sponsor.tagline && (
                <p className="text-white/90 text-lg">{sponsor.tagline}</p>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Main content */}
      <div className="section-container py-12">
        <div className="grid lg:grid-cols-3 gap-8">
          {/* Left column - Stats & Info */}
          <div className="lg:col-span-2 space-y-8">
            {/* Stats cards */}
            <div className="grid sm:grid-cols-3 gap-4">
              <div className="bg-white rounded-xl p-6 shadow-sm border">
                <div className="flex items-center gap-3 mb-2">
                  <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', config.bgColor)}>
                    <TrendingUp className={cn('w-5 h-5', config.color)} />
                  </div>
                  <span className="text-sm text-muted-foreground">Total Contributed</span>
                </div>
                <p className="text-2xl font-bold">{formatCurrency(sponsor.stats.totalContributed)}</p>
              </div>

              <div className="bg-white rounded-xl p-6 shadow-sm border">
                <div className="flex items-center gap-3 mb-2">
                  <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', config.bgColor)}>
                    <MapPin className={cn('w-5 h-5', config.color)} />
                  </div>
                  <span className="text-sm text-muted-foreground">Blocks Sponsored</span>
                </div>
                <p className="text-2xl font-bold">{sponsor.stats.blocksSponsored}</p>
              </div>

              <div className="bg-white rounded-xl p-6 shadow-sm border">
                <div className="flex items-center gap-3 mb-2">
                  <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', config.bgColor)}>
                    <Calendar className={cn('w-5 h-5', config.color)} />
                  </div>
                  <span className="text-sm text-muted-foreground">Member Since</span>
                </div>
                <p className="text-2xl font-bold">{memberSinceFormatted}</p>
              </div>
            </div>

            {/* Bio section */}
            {sponsor.bio && (
              <div className="bg-white rounded-xl p-6 shadow-sm border">
                <h2 className="font-semibold text-lg mb-4">About</h2>
                <p className="text-neutral-600 whitespace-pre-wrap">{sponsor.bio}</p>
              </div>
            )}

            {/* Impact Story — reserved for our top recognition tier ($10,000+):
                a full, blog-style feature on what this partnership funds,
                with before/after documentation as the blocks it supports
                are completed. This is the top rung of card -> on-page
                section -> full-page story. */}
            {sponsor.stats.totalContributed / 100 >= FOUNDING_PARTNER_THRESHOLD && (
              <div className={cn('rounded-xl p-8 text-white shadow-sm', `bg-gradient-to-br ${config.gradientFrom} ${config.gradientTo}`)}>
                <p className="text-xs font-bold uppercase tracking-widest text-white/70 mb-2">Founding Partner Spotlight</p>
                <h2 className="font-serif text-2xl font-bold mb-4">
                  What {sponsor.displayName}&rsquo;s support builds
                </h2>
                <p className="text-white/90 leading-relaxed mb-6">
                  {sponsor.bio || `${sponsor.displayName}'s partnership funds real blocks, real paychecks, and real before-and-after documentation — the kind of transformation that only happens when a community has the resources to act on what it already knows.`}
                </p>
                <div className="rounded-lg bg-white/10 border border-white/20 p-6 flex items-start gap-3">
                  <Camera className="w-5 h-5 flex-shrink-0 mt-0.5 text-white/70" />
                  <p className="text-sm text-white/80">
                    Before-and-after photos from the blocks {sponsor.displayName} funds will appear here as cleanups are completed and verified — the same GPS-tagged, timestamped documentation every sponsor receives, featured in full.
                  </p>
                </div>
              </div>
            )}

            {/* Recent contributions */}
            {sponsor.recentContributions.length > 0 && (
              <div className="bg-white rounded-xl p-6 shadow-sm border">
                <h2 className="font-semibold text-lg mb-4">Recent Contributions</h2>
                <div className="space-y-3">
                  {sponsor.recentContributions.map((contribution, index) => (
                    <div
                      key={index}
                      className="flex items-center justify-between py-2 border-b last:border-0"
                    >
                      <div className="flex items-center gap-3">
                        <div className={cn('w-8 h-8 rounded-full flex items-center justify-center', config.bgColor)}>
                          <Star className={cn('w-4 h-4', config.color)} />
                        </div>
                        <div>
                          <p className="font-medium">{formatCurrency(contribution.amount)}</p>
                          <p className="text-xs text-muted-foreground capitalize">
                            {contribution.type?.replace(/_/g, ' ') || 'Donation'}
                          </p>
                        </div>
                      </div>
                      <span className="text-sm text-muted-foreground">
                        {formatRelativeTime(contribution.date)}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* Right column - Links & Socials */}
          <div className="space-y-6">
            {/* Website & Socials */}
            {(sponsor.website || sponsor.socials.twitter || sponsor.socials.linkedin || sponsor.socials.instagram) && (
              <div className="bg-white rounded-xl p-6 shadow-sm border">
                <h2 className="font-semibold text-lg mb-4">Connect</h2>
                <div className="space-y-3">
                  {sponsor.website && (
                    <a
                      href={sponsor.website.startsWith('http') ? sponsor.website : `https://${sponsor.website}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="flex items-center gap-3 text-neutral-600 hover:text-brand-green-600 transition-colors"
                    >
                      <Globe className="w-5 h-5" />
                      <span className="truncate">{sponsor.website.replace(/^https?:\/\//, '')}</span>
                    </a>
                  )}

                  {sponsor.socials.twitter && (
                    <a
                      href={sponsor.socials.twitter.startsWith('http')
                        ? sponsor.socials.twitter
                        : `https://twitter.com/${sponsor.socials.twitter.replace('@', '')}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="flex items-center gap-3 text-neutral-600 hover:text-sky-500 transition-colors"
                    >
                      <Twitter className="w-5 h-5" />
                      <span>{sponsor.socials.twitter.startsWith('@') ? sponsor.socials.twitter : `@${sponsor.socials.twitter}`}</span>
                    </a>
                  )}

                  {sponsor.socials.linkedin && (
                    <a
                      href={sponsor.socials.linkedin.startsWith('http')
                        ? sponsor.socials.linkedin
                        : `https://linkedin.com/in/${sponsor.socials.linkedin}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="flex items-center gap-3 text-neutral-600 hover:text-blue-600 transition-colors"
                    >
                      <Linkedin className="w-5 h-5" />
                      <span>LinkedIn</span>
                    </a>
                  )}

                  {sponsor.socials.instagram && (
                    <a
                      href={sponsor.socials.instagram.startsWith('http')
                        ? sponsor.socials.instagram
                        : `https://instagram.com/${sponsor.socials.instagram.replace('@', '')}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="flex items-center gap-3 text-neutral-600 hover:text-pink-500 transition-colors"
                    >
                      <Instagram className="w-5 h-5" />
                      <span>{sponsor.socials.instagram.startsWith('@') ? sponsor.socials.instagram : `@${sponsor.socials.instagram}`}</span>
                    </a>
                  )}
                </div>
              </div>
            )}

            {/* CTA */}
            <div className={cn('rounded-xl p-6 text-white', `bg-gradient-to-br ${config.gradientFrom} ${config.gradientTo}`)}>
              <h3 className="font-bold text-lg mb-2">Join {sponsor.displayName}</h3>
              <p className="text-white/80 text-sm mb-4">
                Become a sponsor and help keep North Lawndale clean.
              </p>
              <Button asChild className="w-full bg-white text-neutral-900 hover:bg-white/90">
                <Link href="/sponsor">
                  Sponsor a Block
                </Link>
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
