'use client'

import { useEffect, useState } from 'react'
import { Metadata } from 'next'
import Link from 'next/link'
import { Heart, Crown, Trophy, Star, Sparkles, ArrowRight, Users, Loader2, Rocket, MessageCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { cn, formatCurrency, formatRelativeTime } from '@/lib/utils'

type SponsorTier = 'bronze' | 'silver' | 'gold' | 'platinum'

interface Sponsor {
  id: number
  name: string
  akas?: string[] // Other names used by this account
  tier: SponsorTier
  blocksSponsored: number
  totalContribution: number
  date: string
  isAnonymous?: boolean
  type?: string
}

interface Stats {
  totalBlocks: number
  totalRaised: number
  sponsorCount: number
}

interface RecentContribution {
  id: number
  name: string
  amount: number
  message: string | null
  isAnonymous: boolean
  createdAt: string
}

const tierConfig: Record<
  SponsorTier,
  {
    label: string
    icon: React.ComponentType<{ className?: string }>
    color: string
    bgColor: string
    borderColor: string
  }
> = {
  platinum: {
    label: 'Platinum',
    icon: Trophy,
    color: 'text-violet-600',
    bgColor: 'bg-violet-50',
    borderColor: 'border-violet-200',
  },
  gold: {
    label: 'Gold',
    icon: Crown,
    color: 'text-brand-gold-600',
    bgColor: 'bg-brand-gold-50',
    borderColor: 'border-brand-gold-200',
  },
  silver: {
    label: 'Silver',
    icon: Sparkles,
    color: 'text-slate-500',
    bgColor: 'bg-slate-50',
    borderColor: 'border-slate-200',
  },
  bronze: {
    label: 'Bronze',
    icon: Star,
    color: 'text-amber-700',
    bgColor: 'bg-amber-50',
    borderColor: 'border-amber-200',
  },
}

function SponsorCard({ sponsor }: { sponsor: Sponsor }) {
  const config = tierConfig[sponsor.tier]
  const Icon = config.icon
  const initials = sponsor.isAnonymous
    ? '?'
    : sponsor.name
        .split(' ')
        .map((n) => n[0])
        .join('')
        .slice(0, 2)
        .toUpperCase()

  // Anonymous sponsors don't have clickable profiles
  const CardWrapper = sponsor.isAnonymous
    ? ({ children }: { children: React.ReactNode }) => <div>{children}</div>
    : ({ children }: { children: React.ReactNode }) => (
        <Link href={`/sponsor/profile/${sponsor.id}`}>{children}</Link>
      )

  return (
    <CardWrapper>
      <div
        className={cn(
          'rounded-xl border-2 p-5 transition-all',
          config.bgColor,
          config.borderColor,
          !sponsor.isAnonymous && 'hover:shadow-md hover:scale-[1.02] cursor-pointer'
        )}
      >
        <div className="flex items-start gap-4">
          <Avatar className="h-14 w-14 border-2 border-white shadow-sm">
            <AvatarFallback className={cn('text-lg font-semibold', config.color)}>
              {initials}
            </AvatarFallback>
          </Avatar>

          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className="font-semibold truncate">{sponsor.name}</h3>
              <Badge className={cn('gap-1', config.color, config.bgColor)}>
                <Icon className="h-3 w-3" />
                {config.label}
              </Badge>
            </div>

            {/* Show AKAs if this sponsor has used multiple names */}
            {sponsor.akas && sponsor.akas.length > 0 && (
              <p className="text-xs text-muted-foreground mt-0.5">
                aka {sponsor.akas.join(', ')}
              </p>
            )}

            <div className="mt-1 flex items-center gap-3 text-sm text-muted-foreground">
              <span>{sponsor.blocksSponsored} block{sponsor.blocksSponsored !== 1 ? 's' : ''}</span>
              <span>•</span>
              <span className="font-medium">{formatCurrency(sponsor.totalContribution)}</span>
            </div>
          </div>
        </div>
      </div>
    </CardWrapper>
  )
}

function MilestoneMessage({ stats }: { stats: Stats }) {
  const { sponsorCount, totalBlocks, totalRaised } = stats

  // Different messages based on milestones
  if (sponsorCount === 0) {
    return (
      <div className="max-w-2xl mx-auto text-center py-16">
        <div className="mx-auto mb-6 flex h-24 w-24 items-center justify-center rounded-full bg-gradient-to-br from-brand-gold-100 to-brand-green-100">
          <Rocket className="h-12 w-12 text-brand-gold-600" />
        </div>
        <h2 className="text-3xl font-bold mb-4 text-neutral-800">Be the First Sponsor</h2>
        <p className="text-lg text-muted-foreground mb-6">
          This wall is waiting for its first name. Your $100 sponsorship will kickstart the movement
          and earn you a permanent place as <strong>Founding Sponsor #1</strong>.
        </p>
        <div className="bg-brand-gold-50 border border-brand-gold-200 rounded-xl p-6 mb-8">
          <p className="text-brand-gold-800 font-medium">
            ✨ First 10 sponsors receive <strong>Founding Sponsor</strong> status —
            permanently displayed at the top of this wall.
          </p>
        </div>
        <Button size="lg" className="bg-brand-gold-600 hover:bg-brand-gold-700" asChild>
          <Link href="/sponsor">
            Become Founding Sponsor #1
            <ArrowRight className="ml-2 h-5 w-5" />
          </Link>
        </Button>
      </div>
    )
  }

  if (sponsorCount < 10) {
    const remaining = 10 - sponsorCount
    return (
      <div className="bg-gradient-to-r from-brand-gold-50 to-brand-green-50 rounded-2xl p-8 mb-12 text-center">
        <div className="flex items-center justify-center gap-2 mb-4">
          <Sparkles className="h-6 w-6 text-brand-gold-600" />
          <span className="text-lg font-semibold text-brand-gold-800">Founding Sponsors</span>
        </div>
        <p className="text-muted-foreground mb-4">
          Only <strong>{remaining}</strong> founding sponsor spot{remaining !== 1 ? 's' : ''} remaining!
          Join now to be permanently featured at the top of this wall.
        </p>
        <Button asChild>
          <Link href="/sponsor">
            Claim Your Spot
            <ArrowRight className="ml-2 h-4 w-4" />
          </Link>
        </Button>
      </div>
    )
  }

  if (totalBlocks < 100) {
    const progress = Math.round((totalBlocks / 100) * 100)
    return (
      <div className="bg-brand-green-50 border border-brand-green-200 rounded-2xl p-8 mb-12">
        <h3 className="text-xl font-bold text-brand-green-800 mb-2">
          🎯 Road to 100 Blocks
        </h3>
        <p className="text-brand-green-700 mb-4">
          We're {progress}% of the way to our first major milestone!
        </p>
        <div className="w-full bg-brand-green-200 rounded-full h-3 mb-4">
          <div
            className="bg-brand-green-600 h-3 rounded-full transition-all duration-500"
            style={{ width: `${progress}%` }}
          />
        </div>
        <p className="text-sm text-brand-green-600">
          {100 - totalBlocks} more blocks to go. Every sponsor brings us closer!
        </p>
      </div>
    )
  }

  if (totalBlocks >= 100 && totalBlocks < 500) {
    return (
      <div className="bg-gradient-to-r from-violet-50 to-brand-gold-50 rounded-2xl p-8 mb-12 text-center">
        <Trophy className="h-12 w-12 text-violet-600 mx-auto mb-4" />
        <h3 className="text-xl font-bold mb-2">🏆 100 Blocks Milestone Reached!</h3>
        <p className="text-muted-foreground">
          Together we've funded {totalBlocks} blocks! Next goal: 500 blocks for city-wide recognition.
        </p>
      </div>
    )
  }

  return null
}

export default function SponsorWallPage() {
  const [sponsors, setSponsors] = useState<Sponsor[]>([])
  const [recent, setRecent] = useState<RecentContribution[]>([])
  const [stats, setStats] = useState<Stats>({ totalBlocks: 0, totalRaised: 0, sponsorCount: 0 })
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    async function fetchData() {
      try {
        const response = await fetch('/api/public/sponsor-wall')
        const data = await response.json()
        setSponsors(data.sponsors || [])
        setRecent(data.recent || [])
        setStats(data.stats || { totalBlocks: 0, totalRaised: 0, sponsorCount: 0 })
      } catch (error) {
        console.error('Failed to fetch sponsor wall data:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchData()
  }, [])

  const platinumSponsors = sponsors.filter((s) => s.tier === 'platinum')
  const goldSponsors = sponsors.filter((s) => s.tier === 'gold')
  const silverSponsors = sponsors.filter((s) => s.tier === 'silver')
  const bronzeSponsors = sponsors.filter((s) => s.tier === 'bronze')

  if (isLoading) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <Loader2 className="h-8 w-8 animate-spin text-brand-green-600" />
          <p className="text-muted-foreground">Loading sponsor wall...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Header */}
      <section className="section-container py-12 text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-brand-green-100">
          <Heart className="h-8 w-8 text-brand-green-600" />
        </div>
        <h1 className="mb-2 text-3xl font-bold">Our Sponsor Wall</h1>
        <p className="text-muted-foreground">
          {stats.sponsorCount > 0
            ? 'Thank you to everyone who makes clean streets possible'
            : 'Be the first to make clean streets possible'}
        </p>

        {/* Summary stats - only show if we have sponsors */}
        {stats.sponsorCount > 0 && (
          <div className="mt-8 flex justify-center gap-8">
            <div className="text-center">
              <p className="text-3xl font-bold text-brand-green-600">{stats.totalBlocks}</p>
              <p className="text-sm text-muted-foreground">Blocks Sponsored</p>
            </div>
            <div className="text-center">
              <p className="text-3xl font-bold text-brand-green-600">
                {formatCurrency(stats.totalRaised)}
              </p>
              <p className="text-sm text-muted-foreground">Total Raised</p>
            </div>
            <div className="text-center">
              <p className="text-3xl font-bold text-brand-green-600">{stats.sponsorCount}</p>
              <p className="text-sm text-muted-foreground">Sponsor{stats.sponsorCount !== 1 ? 's' : ''}</p>
            </div>
          </div>
        )}
      </section>

      {/* Milestone Message */}
      <section className="section-container">
        <MilestoneMessage stats={stats} />
      </section>

      {/* Sponsors by tier */}
      {sponsors.length > 0 ? (
        <>
          {/* Platinum Sponsors */}
          {platinumSponsors.length > 0 && (
            <section className="section-container pb-8">
              <h2 className="mb-4 flex items-center gap-2 text-xl font-bold">
                <Trophy className="h-5 w-5 text-violet-600" />
                Platinum Sponsors
              </h2>
              <div className="grid gap-4 md:grid-cols-2">
                {platinumSponsors.map((sponsor) => (
                  <SponsorCard key={sponsor.id} sponsor={sponsor} />
                ))}
              </div>
            </section>
          )}

          {/* Gold Sponsors */}
          {goldSponsors.length > 0 && (
            <section className="section-container pb-8">
              <h2 className="mb-4 flex items-center gap-2 text-xl font-bold">
                <Crown className="h-5 w-5 text-brand-gold-600" />
                Gold Sponsors
              </h2>
              <div className="grid gap-4 md:grid-cols-2">
                {goldSponsors.map((sponsor) => (
                  <SponsorCard key={sponsor.id} sponsor={sponsor} />
                ))}
              </div>
            </section>
          )}

          {/* Silver Sponsors */}
          {silverSponsors.length > 0 && (
            <section className="section-container pb-8">
              <h2 className="mb-4 flex items-center gap-2 text-xl font-bold">
                <Sparkles className="h-5 w-5 text-slate-500" />
                Silver Sponsors
              </h2>
              <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
                {silverSponsors.map((sponsor) => (
                  <SponsorCard key={sponsor.id} sponsor={sponsor} />
                ))}
              </div>
            </section>
          )}

          {/* Bronze Sponsors */}
          {bronzeSponsors.length > 0 && (
            <section className="section-container pb-16">
              <h2 className="mb-4 flex items-center gap-2 text-xl font-bold">
                <Star className="h-5 w-5 text-amber-700" />
                Bronze Sponsors
              </h2>
              <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
                {bronzeSponsors.map((sponsor) => (
                  <SponsorCard key={sponsor.id} sponsor={sponsor} />
                ))}
              </div>
            </section>
          )}
        </>
      ) : null}

      {/* Recent Activity with Messages */}
      {recent.length > 0 && (
        <section className="section-container pb-12">
          <h2 className="mb-6 flex items-center gap-2 text-xl font-bold">
            <MessageCircle className="h-5 w-5 text-brand-green-600" />
            Recent Activity
          </h2>
          <div className="space-y-4">
            {recent.map((contribution) => (
              <div
                key={contribution.id}
                className="rounded-xl border bg-white p-4 shadow-sm"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="flex items-center gap-3">
                    <Avatar className="h-10 w-10 border border-brand-green-100">
                      <AvatarFallback className="bg-brand-green-50 text-brand-green-700 text-sm font-medium">
                        {contribution.isAnonymous
                          ? '?'
                          : contribution.name
                              .split(' ')
                              .map((n) => n[0])
                              .join('')
                              .slice(0, 2)
                              .toUpperCase()}
                      </AvatarFallback>
                    </Avatar>
                    <div>
                      <p className="font-medium">{contribution.name}</p>
                      <p className="text-sm text-muted-foreground">
                        donated {formatCurrency(contribution.amount)}
                      </p>
                    </div>
                  </div>
                  <span className="text-xs text-muted-foreground whitespace-nowrap">
                    {formatRelativeTime(contribution.createdAt)}
                  </span>
                </div>
                {contribution.message && (
                  <div className="mt-3 rounded-lg bg-brand-green-50 p-3 text-sm text-brand-green-800">
                    "{contribution.message}"
                  </div>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {/* CTA */}
      <section className="bg-brand-green-800 py-16 text-white">
        <div className="section-container text-center">
          <h2 className="mb-4 text-2xl font-bold">
            {stats.sponsorCount === 0 ? 'Start the Movement' : 'Join Our Sponsors'}
          </h2>
          <p className="mb-8 text-white/80">
            {stats.sponsorCount === 0
              ? 'Be the first to put your name on the wall and fund cleaner streets'
              : 'Add your name to the wall and help make North Lawndale cleaner'}
          </p>
          <Button size="lg" className="bg-white text-brand-green-800 hover:bg-white/90" asChild>
            <Link href="/sponsor">
              Sponsor a Block — $100
              <ArrowRight className="ml-2 h-5 w-5" />
            </Link>
          </Button>
        </div>
      </section>
    </div>
  )
}
