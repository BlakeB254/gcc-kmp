'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { formatDistanceToNow } from 'date-fns'
import {
  MapPin,
  Users,
  DollarSign,
  Heart,
  TrendingUp,
  ArrowRight,
  Building,
  Palette,
  Shield,
  Handshake,
  Store,
  Loader2,
  Target,
  Leaf,
  Trophy,
  Clock,
  MessageSquare,
  Crown,
  Medal,
  Award,
  Star,
  RefreshCw,
  Building2,
  Mail,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn, formatCurrency } from '@/lib/utils'
import { SupporterRecognitionWall } from '@/components/shared/SupporterRecognitionWall'

interface ImpactStats {
  workers: {
    total: number
    approved: number
    jobsCompleted: number
    totalEarnings: number
  }
  sponsors: {
    total: number
    totalContributed: number
    blocksFunded: number
  }
  jobs: {
    total: number
    completed: number
    active: number
  }
  blocks: {
    total: number
    cleaned: number
    funded: number
    available: number
  }
  movement: {
    waitlistCount: number
  }
}

type ContributorsView = 'recent' | 'leaderboard'

interface RecentContribution {
  id: number
  type: 'donation' | 'sponsorship'
  amount: number
  displayName: string
  isAnonymous: boolean
  message: string | null
  blockName: string | null
  createdAt: string
}

interface LeaderboardEntry {
  rank: number
  displayName: string
  isAnonymous: boolean
  totalAmount: number
  tier: 'platinum' | 'gold' | 'silver' | 'bronze'
  contributionCount: number
  blocksSponsored: number
}

interface SupportWallStats {
  totalRaised: number
  blocksFunded: number
  totalContributors: number
  recentContributors: number
}

const tierConfig = {
  platinum: { icon: Crown, color: 'text-purple-500', bg: 'bg-purple-100', border: 'border-purple-200', label: 'Platinum' },
  gold: { icon: Trophy, color: 'text-amber-500', bg: 'bg-amber-100', border: 'border-amber-200', label: 'Gold' },
  silver: { icon: Medal, color: 'text-neutral-400', bg: 'bg-neutral-100', border: 'border-neutral-200', label: 'Silver' },
  bronze: { icon: Award, color: 'text-orange-400', bg: 'bg-orange-100', border: 'border-orange-200', label: 'Bronze' },
}

function StatCard({
  icon: Icon,
  value,
  label,
  color,
  isLoading,
}: {
  icon: React.ComponentType<{ className?: string }>
  value: string | number
  label: string
  color: string
  isLoading?: boolean
}) {
  const colorClasses: Record<string, { border: string; bg: string; text: string }> = {
    gold: { border: 'border-brand-green-500', bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
    green: { border: 'border-brand-green-500', bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
    sky: { border: 'border-brand-blue-500', bg: 'bg-brand-blue-100', text: 'text-brand-blue-600' },
    brown: { border: 'border-brand-green-500', bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
  }
  const classes = colorClasses[color] || colorClasses.green

  return (
    <Card className={cn('border-l-4', classes.border)}>
      <CardContent className="pt-6">
        <div className="flex items-center gap-4">
          <div className={cn('rounded-full p-3', classes.bg)}>
            <Icon className={cn('h-6 w-6', classes.text)} />
          </div>
          <div>
            {isLoading ? (
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            ) : (
              <p className="text-3xl font-bold">{value}</p>
            )}
            <p className="text-sm text-muted-foreground">{label}</p>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

function ImpactPillar({
  icon: Icon,
  title,
  description,
  color,
}: {
  icon: React.ComponentType<{ className?: string }>
  title: string
  description: string
  color: string
}) {
  const colorClasses: Record<string, { bg: string; text: string }> = {
    gold: { bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
    green: { bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
    sky: { bg: 'bg-brand-blue-100', text: 'text-brand-blue-600' },
    brown: { bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
  }
  const classes = colorClasses[color] || colorClasses.green

  return (
    <div className="text-center">
      <div className={cn('mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full', classes.bg)}>
        <Icon className={cn('h-8 w-8', classes.text)} />
      </div>
      <h3 className="mb-2 text-xl font-bold">{title}</h3>
      <p className="text-muted-foreground leading-relaxed">{description}</p>
    </div>
  )
}

export function ImpactSupportersContent() {
  const [stats, setStats] = useState<ImpactStats | null>(null)
  const [isStatsLoading, setIsStatsLoading] = useState(true)

  const [view, setView] = useState<ContributorsView>('leaderboard')
  const [recent, setRecent] = useState<RecentContribution[]>([])
  const [leaderboard, setLeaderboard] = useState<LeaderboardEntry[]>([])
  const [wallStats, setWallStats] = useState<SupportWallStats | null>(null)
  const [isWallLoading, setIsWallLoading] = useState(true)
  const [wallError, setWallError] = useState<string | null>(null)

  useEffect(() => {
    async function fetchStats() {
      try {
        const response = await fetch('/api/public/impact-stats')
        const data = await response.json()
        setStats(data)
      } catch (error) {
        console.error('Failed to fetch impact stats:', error)
      } finally {
        setIsStatsLoading(false)
      }
    }
    fetchStats()
  }, [])

  const fetchWallData = async (selectedView: ContributorsView) => {
    setIsWallLoading(true)
    setWallError(null)
    try {
      const response = await fetch(`/api/public/support-wall?view=${selectedView}&limit=50`)
      if (!response.ok) throw new Error('Failed to fetch data')
      const data = await response.json()

      setWallStats(data.stats)
      if (selectedView === 'recent') {
        setRecent(data.recent || [])
      } else {
        setLeaderboard(data.leaderboard || [])
      }
    } catch (err) {
      setWallError('Failed to load supporter data')
      console.error(err)
    } finally {
      setIsWallLoading(false)
    }
  }

  useEffect(() => {
    fetchWallData(view)
  }, [view])

  const totalMovementMembers =
    (stats?.workers.total || 0) + (stats?.sponsors.total || 0) + (stats?.movement.waitlistCount || 0)

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Header */}
      <section className="section-container py-12 text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-brand-green-100">
          <TrendingUp className="h-8 w-8 text-brand-green-600" />
        </div>
        <h1 className="mb-2 text-3xl font-bold">Our Impact &amp; Supporters</h1>
        <p className="text-muted-foreground max-w-2xl mx-auto">
          Every block cleaned is a step toward transformation, funded by people and organizations who
          believe in North Lawndale. Here&apos;s the progress we&apos;re making, together.
        </p>
      </section>

      {/* Live Stats */}
      <section className="section-container pb-12">
        <h2 className="mb-6 text-xl font-semibold text-center">Live Progress</h2>
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
          <StatCard
            icon={MapPin}
            value={stats?.blocks.cleaned || 0}
            label="Blocks Cleaned"
            color="green"
            isLoading={isStatsLoading}
          />
          <StatCard
            icon={Users}
            value={stats?.workers.approved || 0}
            label="Active Workers"
            color="sky"
            isLoading={isStatsLoading}
          />
          <StatCard
            icon={DollarSign}
            value={formatCurrency(stats?.sponsors.totalContributed || 0)}
            label="Total Raised"
            color="gold"
            isLoading={isStatsLoading}
          />
          <StatCard
            icon={Heart}
            value={stats?.sponsors.total || 0}
            label="Sponsors"
            color="brown"
            isLoading={isStatsLoading}
          />
        </div>
      </section>

      {/* Milestone Progress */}
      {!isStatsLoading && (
        <section className="section-container pb-12">
          <Card className="bg-gradient-to-r from-brand-green-50 to-brand-green-50 border-brand-green-200">
            <CardContent className="pt-6">
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-2">
                  <Target className="h-5 w-5 text-brand-green-600" />
                  <span className="font-semibold">First 100 Blocks Goal</span>
                </div>
                <span className="text-sm text-muted-foreground">{stats?.blocks.cleaned || 0} / 100</span>
              </div>
              <div className="w-full bg-brand-green-200 rounded-full h-3">
                <div
                  className="bg-brand-green-600 h-3 rounded-full transition-all duration-500"
                  style={{ width: `${Math.min(((stats?.blocks.cleaned || 0) / 100) * 100, 100)}%` }}
                />
              </div>
              <p className="mt-3 text-sm text-muted-foreground">
                {(stats?.blocks.cleaned || 0) >= 100
                  ? "We've reached our first milestone! Next goal: 500 blocks."
                  : `${100 - (stats?.blocks.cleaned || 0)} more blocks until our first major milestone.`}
              </p>
            </CardContent>
          </Card>
        </section>
      )}

      {/* Recognition Wall */}
      <section className="bg-white py-16">
        <div className="section-container">
          <SupporterRecognitionWall
            limit={24}
            title="Our Honor Wall"
            description="The organizations and individuals whose generosity fuels every block we clean."
          />
        </div>
      </section>

      {/* Recent / Leaderboard */}
      <section className="section-container py-16">
        <div className="max-w-4xl mx-auto">
          {/* Impact Stats (support-wall specific: this-month contributors) */}
          {wallStats && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-10">
              <div className="bg-white rounded-xl p-5 shadow-sm border border-neutral-100 text-center">
                <DollarSign className="w-6 h-6 text-brand-green-600 mx-auto mb-2" />
                <p className="text-2xl font-bold text-brand-green-600">{formatCurrency(wallStats.totalRaised * 100)}</p>
                <p className="text-sm text-neutral-600">Total Raised</p>
              </div>
              <div className="bg-white rounded-xl p-5 shadow-sm border border-neutral-100 text-center">
                <MapPin className="w-6 h-6 text-brand-green-600 mx-auto mb-2" />
                <p className="text-2xl font-bold text-brand-green-600">{wallStats.blocksFunded}</p>
                <p className="text-sm text-neutral-600">Blocks Funded</p>
              </div>
              <div className="bg-white rounded-xl p-5 shadow-sm border border-neutral-100 text-center">
                <Users className="w-6 h-6 text-brand-green-600 mx-auto mb-2" />
                <p className="text-2xl font-bold text-brand-green-600">{wallStats.totalContributors}</p>
                <p className="text-sm text-neutral-600">Total Supporters</p>
              </div>
              <div className="bg-white rounded-xl p-5 shadow-sm border border-neutral-100 text-center">
                <Heart className="w-6 h-6 text-brand-blue-600 mx-auto mb-2" />
                <p className="text-2xl font-bold text-brand-blue-600">{wallStats.recentContributors}</p>
                <p className="text-sm text-neutral-600">This Month</p>
              </div>
            </div>
          )}

          {/* View Toggle */}
          <div className="flex bg-white p-1 rounded-xl shadow-sm border border-neutral-100 mb-8 max-w-sm mx-auto">
            <button
              onClick={() => setView('recent')}
              className={`flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg font-medium transition-all text-sm ${
                view === 'recent' ? 'bg-brand-green-500 text-white shadow-sm' : 'text-neutral-600 hover:text-neutral-900'
              }`}
            >
              <Clock className="w-4 h-4" />
              Recent
            </button>
            <button
              onClick={() => setView('leaderboard')}
              className={`flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg font-medium transition-all text-sm ${
                view === 'leaderboard' ? 'bg-brand-green-500 text-white shadow-sm' : 'text-neutral-600 hover:text-neutral-900'
              }`}
            >
              <Trophy className="w-4 h-4" />
              Top Supporters
            </button>
          </div>

          {/* Content */}
          <div className="bg-white rounded-2xl shadow-lg border border-neutral-100 overflow-hidden">
            {isWallLoading ? (
              <div className="p-12 text-center">
                <RefreshCw className="w-8 h-8 animate-spin mx-auto text-brand-green-500 mb-3" />
                <p className="text-neutral-500">Loading supporters...</p>
              </div>
            ) : wallError ? (
              <div className="p-12 text-center">
                <p className="text-red-500 mb-3">{wallError}</p>
                <button
                  onClick={() => fetchWallData(view)}
                  className="text-brand-green-600 hover:text-brand-green-700 font-medium"
                >
                  Try again
                </button>
              </div>
            ) : view === 'recent' ? (
              /* Recent Feed */
              <div className="divide-y divide-neutral-100">
                {recent.length === 0 ? (
                  <div className="p-12 text-center">
                    <Heart className="w-14 h-14 mx-auto mb-4 text-neutral-200" />
                    <h3 className="text-lg font-semibold text-neutral-900 mb-2">Be the First Supporter</h3>
                    <p className="text-neutral-500 mb-6">No contributions yet. Your donation will kickstart the movement.</p>
                    <Link href="/donate" className="btn-primary inline-flex items-center gap-2">
                      <Heart className="w-4 h-4" />
                      Make a Donation
                    </Link>
                  </div>
                ) : (
                  recent.map((contribution) => (
                    <div key={contribution.id} className="p-5 hover:bg-neutral-50 transition-colors">
                      <div className="flex items-start gap-4">
                        <div className="w-11 h-11 rounded-full flex items-center justify-center flex-shrink-0 bg-brand-green-100">
                          {contribution.type === 'sponsorship' ? (
                            <MapPin className="w-5 h-5 text-brand-green-600" />
                          ) : (
                            <Heart className="w-5 h-5 text-brand-green-600" />
                          )}
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center justify-between gap-2">
                            <p className="font-semibold text-neutral-900 truncate">{contribution.displayName}</p>
                            <span className="font-bold text-lg text-brand-green-600">${contribution.amount}</span>
                          </div>
                          <p className="text-sm text-neutral-500 mt-0.5">
                            {contribution.type === 'sponsorship' && contribution.blockName ? (
                              <>Sponsored {contribution.blockName}</>
                            ) : (
                              <>Donated to General Fund</>
                            )}
                            <span className="mx-1.5">&middot;</span>
                            <span title={new Date(contribution.createdAt).toLocaleString()}>
                              {formatDistanceToNow(new Date(contribution.createdAt), { addSuffix: true })}
                            </span>
                          </p>
                          {contribution.message && (
                            <div className="mt-3 bg-neutral-50 rounded-lg px-4 py-3 flex items-start gap-2">
                              <MessageSquare className="w-4 h-4 flex-shrink-0 mt-0.5 text-brand-green-400" />
                              <p className="text-sm text-neutral-700 italic leading-relaxed">
                                &ldquo;{contribution.message}&rdquo;
                              </p>
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  ))
                )}
              </div>
            ) : (
              /* Leaderboard */
              <div className="divide-y divide-neutral-100">
                {leaderboard.length === 0 ? (
                  <div className="p-12 text-center">
                    <Trophy className="w-14 h-14 mx-auto mb-4 text-neutral-200" />
                    <h3 className="text-lg font-semibold text-neutral-900 mb-2">Leaderboard Coming Soon</h3>
                    <p className="text-neutral-500 mb-6">Be the first to make the supporters leaderboard!</p>
                    <Link href="/donate" className="btn-primary inline-flex items-center gap-2">
                      <Heart className="w-4 h-4" />
                      Make a Donation
                    </Link>
                  </div>
                ) : (
                  leaderboard.map((entry) => {
                    const config = tierConfig[entry.tier]
                    const TierIcon = config.icon
                    const isTopThree = entry.rank <= 3

                    return (
                      <div
                        key={entry.rank}
                        className={`p-5 hover:bg-neutral-50 transition-colors ${
                          isTopThree ? 'bg-gradient-to-r from-amber-50/50 to-transparent' : ''
                        }`}
                      >
                        <div className="flex items-center gap-4">
                          <div
                            className={`w-11 h-11 rounded-full flex items-center justify-center font-bold text-lg ${
                              entry.rank === 1
                                ? 'bg-amber-100 text-amber-600'
                                : entry.rank === 2
                                  ? 'bg-neutral-200 text-neutral-600'
                                  : entry.rank === 3
                                    ? 'bg-orange-100 text-orange-600'
                                    : 'bg-neutral-100 text-neutral-500'
                            }`}
                          >
                            {entry.rank <= 3 ? (
                              <Star
                                className={`w-5 h-5 ${
                                  entry.rank === 1
                                    ? 'text-amber-500'
                                    : entry.rank === 2
                                      ? 'text-neutral-400'
                                      : 'text-orange-400'
                                }`}
                              />
                            ) : (
                              entry.rank
                            )}
                          </div>

                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2">
                              <p className="font-semibold text-neutral-900 truncate">{entry.displayName}</p>
                              <span
                                className={`px-2 py-0.5 rounded-full text-xs font-medium ${config.bg} ${config.color} ${config.border} border`}
                              >
                                <TierIcon className="w-3 h-3 inline mr-1" />
                                {config.label}
                              </span>
                            </div>
                            <p className="text-sm text-neutral-500">
                              {entry.contributionCount} contribution{entry.contributionCount !== 1 ? 's' : ''}
                              {entry.blocksSponsored > 0 && (
                                <>
                                  <span className="mx-1.5">&middot;</span>
                                  <span>
                                    {entry.blocksSponsored} block{entry.blocksSponsored !== 1 ? 's' : ''} sponsored
                                  </span>
                                </>
                              )}
                            </p>
                          </div>

                          <div className="text-right">
                            <p className="font-bold text-lg text-brand-green-600">
                              {entry.totalAmount >= 1000
                                ? `$${(entry.totalAmount / 1000).toFixed(1)}K`
                                : `$${entry.totalAmount}`}
                            </p>
                            <p className="text-xs text-neutral-500">total</p>
                          </div>
                        </div>
                      </div>
                    )
                  })
                )}
              </div>
            )}
          </div>

          {/* Tier Legend */}
          {view === 'leaderboard' && (
            <div className="mt-6 bg-white rounded-xl p-5 shadow-sm border border-neutral-100">
              <p className="text-sm font-medium text-neutral-700 mb-3">Supporter Tiers</p>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                {Object.entries(tierConfig).map(([tier, config]) => {
                  const TierIcon = config.icon
                  const thresholds: Record<string, string> = {
                    platinum: '$10,000+',
                    gold: '$2,500+',
                    silver: '$500+',
                    bronze: '< $500',
                  }
                  return (
                    <div key={tier} className={`flex items-center gap-2 p-2.5 rounded-lg ${config.bg} ${config.border} border`}>
                      <TierIcon className={`w-4 h-4 ${config.color}`} />
                      <div>
                        <p className={`text-sm font-medium ${config.color}`}>{config.label}</p>
                        <p className="text-xs text-neutral-500">{thresholds[tier]}</p>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}
        </div>
      </section>

      {/* The Broken Windows Theory */}
      <section className="bg-white py-16">
        <div className="section-container">
          <div className="max-w-3xl mx-auto text-center mb-12">
            <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-brand-green-100">
              <Shield className="h-8 w-8 text-brand-green-600" />
            </div>
            <h2 className="text-2xl font-bold mb-4">The Science of Clean Streets</h2>
            <p className="text-muted-foreground leading-relaxed">
              The &ldquo;Broken Windows Theory&rdquo; demonstrates that visible signs of neglect—litter,
              graffiti, abandoned debris—signal that an area is uncared for, inviting further disorder.
              But the opposite is equally true: <strong>well-maintained spaces signal pride, care,
              and community investment</strong>.
            </p>
          </div>

          <div className="grid gap-8 md:grid-cols-2 lg:grid-cols-3">
            <Card className="border-0 shadow-sm hover:shadow-md transition-shadow">
              <CardContent className="pt-6">
                <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-red-100">
                  <Shield className="h-6 w-6 text-red-600" />
                </div>
                <h3 className="font-semibold mb-2">Reduced Crime</h3>
                <p className="text-sm text-muted-foreground">
                  Studies show that clean, well-lit streets experience significantly lower crime rates.
                  When people see a cared-for environment, they&apos;re less likely to vandalize it.
                </p>
              </CardContent>
            </Card>

            <Card className="border-0 shadow-sm hover:shadow-md transition-shadow">
              <CardContent className="pt-6">
                <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-green-100">
                  <Leaf className="h-6 w-6 text-green-600" />
                </div>
                <h3 className="font-semibold mb-2">Environmental Health</h3>
                <p className="text-sm text-muted-foreground">
                  Litter doesn&apos;t just look bad—it contaminates water, harms wildlife, and creates
                  health hazards. Clean blocks mean healthier families and a healthier planet.
                </p>
              </CardContent>
            </Card>

            <Card className="border-0 shadow-sm hover:shadow-md transition-shadow">
              <CardContent className="pt-6">
                <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-blue-100">
                  <Building className="h-6 w-6 text-blue-600" />
                </div>
                <h3 className="font-semibold mb-2">Property Values</h3>
                <p className="text-sm text-muted-foreground">
                  Clean neighborhoods attract investment. When streets are maintained, property values
                  stabilize and grow—building generational wealth for homeowners.
                </p>
              </CardContent>
            </Card>
          </div>
        </div>
      </section>

      {/* Four Pillars of Impact */}
      <section className="section-container py-16">
        <h2 className="text-2xl font-bold text-center mb-12">Four Pillars of Transformation</h2>
        <div className="grid gap-12 md:grid-cols-2 lg:grid-cols-4">
          <ImpactPillar
            icon={DollarSign}
            title="Economic Empowerment"
            description="Every dollar stays in the community. Workers earn fair wages that support their families, pay local bills, and circulate back into neighborhood businesses."
            color="gold"
          />
          <ImpactPillar
            icon={Palette}
            title="Inspiring Our Youth"
            description="Children raised in clean, beautiful environments develop higher self-esteem and greater aspirations. Beauty nurtures creativity and shows kids they matter."
            color="sky"
          />
          <ImpactPillar
            icon={Handshake}
            title="Community Bonds"
            description="Shared pride in clean streets brings neighbors together. When we invest in our blocks, we invest in each other—building trust and collaboration."
            color="green"
          />
          <ImpactPillar
            icon={Store}
            title="Local Business Growth"
            description="Clean streets attract foot traffic, encourage shopping local, and signal to entrepreneurs that this is a neighborhood worth investing in."
            color="brown"
          />
        </div>
      </section>

      {/* The Local Jobs Mission */}
      <section className="bg-gradient-to-r from-brand-green-800 to-brand-green-900 py-16 text-white">
        <div className="section-container">
          <div className="max-w-3xl mx-auto">
            <div className="bg-white/10 rounded-xl p-6 border border-white/20">
              <h3 className="text-white font-semibold mb-3 flex items-center gap-2">
                <DollarSign className="h-5 w-5" />
                Our Pay Promise
              </h3>
              <p className="text-brand-green-100">
                <strong className="text-white">100% of every sponsorship goes directly to workers</strong>.
                No administrative fees. No overhead deductions. A $100 block sponsorship means $100
                in a worker&apos;s pocket. This is radical transparency in action.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* Movement Stats */}
      {!isStatsLoading && totalMovementMembers > 0 && (
        <section className="bg-brand-green-800 py-12 text-white">
          <div className="section-container">
            <div className="text-center mb-8">
              <h2 className="text-2xl font-bold mb-2">The Movement is Growing</h2>
              <p className="text-brand-green-200">
                Join {totalMovementMembers.toLocaleString()} people building a cleaner future
              </p>
            </div>
            <div className="grid gap-6 md:grid-cols-3 text-center">
              <div>
                <p className="text-4xl font-bold text-brand-green-400">{stats?.workers.total || 0}</p>
                <p className="text-brand-green-200">Registered Workers</p>
              </div>
              <div>
                <p className="text-4xl font-bold text-brand-green-400">{stats?.sponsors.total || 0}</p>
                <p className="text-brand-green-200">Sponsors</p>
              </div>
              <div>
                <p className="text-4xl font-bold text-brand-green-400">{stats?.movement.waitlistCount || 0}</p>
                <p className="text-brand-green-200">On Waitlist</p>
              </div>
            </div>
          </div>
        </section>
      )}

      {/* Partnerships Section */}
      <section className="section-container py-16">
        <div className="max-w-4xl mx-auto bg-white rounded-2xl shadow-lg border border-neutral-100 overflow-hidden">
          <div className="bg-gradient-to-r from-brand-green-800 to-brand-green-900 px-8 py-6">
            <div className="flex items-center gap-3">
              <Handshake className="w-7 h-7 text-brand-green-400" />
              <div>
                <h2 className="text-xl font-bold text-white">Partnerships</h2>
                <p className="text-brand-green-200 text-sm">Organizations and businesses supporting our mission</p>
              </div>
            </div>
          </div>

          <div className="p-8">
            <div className="text-center py-6">
              <Building2 className="w-14 h-14 mx-auto mb-4 text-neutral-200" />
              <h3 className="text-lg font-semibold text-neutral-900 mb-2">Partner With GCC</h3>
              <p className="text-neutral-600 max-w-lg mx-auto mb-6">
                We&apos;re building partnerships with local businesses, organizations, and foundations
                who share our vision for cleaner, safer neighborhoods. Corporate sponsorships include
                branding, impact reports, and community recognition on this page.
              </p>
              <div className="flex flex-col sm:flex-row gap-3 justify-center">
                <Link
                  href="/sponsor/enterprise"
                  className="inline-flex items-center justify-center gap-2 bg-brand-green-700 text-white font-medium px-6 py-3 rounded-xl hover:bg-brand-green-800 transition-colors"
                >
                  <Building2 className="w-4 h-4" />
                  Enterprise Packages
                  <ArrowRight className="w-4 h-4" />
                </Link>
                <a
                  href="mailto:partnerships@globalcommunitycleaners.org?subject=Partnership%20Inquiry"
                  className="inline-flex items-center justify-center gap-2 border-2 border-brand-green-300 text-brand-green-700 font-medium px-6 py-3 rounded-xl hover:bg-brand-green-50 transition-colors"
                >
                  <Mail className="w-4 h-4" />
                  Contact Us
                </a>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Call to Action */}
      <section className="section-container py-16">
        <div className="rounded-2xl bg-gradient-to-r from-brand-green-100 to-brand-green-100 p-8 text-center">
          <h2 className="mb-4 text-2xl font-bold">Be Part of the Transformation</h2>
          <p className="mb-6 text-muted-foreground max-w-2xl mx-auto">
            Every clean block is a vote for the future we want. Whether you clean, sponsor,
            or spread the word—you&apos;re part of something bigger.
          </p>
          <div className="flex flex-col gap-4 sm:flex-row sm:justify-center">
            <Button size="lg" asChild>
              <Link href="/worker/signup">
                Become a Worker
                <ArrowRight className="ml-2 h-4 w-4" />
              </Link>
            </Button>
            <Button size="lg" variant="secondary" asChild>
              <Link href="/sponsor">Sponsor a Block</Link>
            </Button>
            <Button size="lg" variant="outline" asChild>
              <Link href="/donate">Make a Donation</Link>
            </Button>
          </div>
          <p className="mt-6 text-sm text-muted-foreground">
            New here?{' '}
            <Link href="/how-it-works" className="font-semibold text-brand-green-700 hover:underline">
              See how it all works →
            </Link>
          </p>
        </div>
      </section>
    </div>
  )
}
