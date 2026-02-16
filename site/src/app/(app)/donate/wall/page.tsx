'use client'

import { useState, useEffect } from 'react'
import Link from 'next/link'
import { formatDistanceToNow } from 'date-fns'
import {
  Heart,
  MapPin,
  Users,
  Trophy,
  Clock,
  MessageSquare,
  Crown,
  Medal,
  Award,
  Star,
  ChevronRight,
  RefreshCw,
} from 'lucide-react'

type ViewType = 'recent' | 'leaderboard'

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

interface ImpactStats {
  totalRaised: number
  blocksFunded: number
  totalContributors: number
  recentContributors: number
}

const tierConfig = {
  platinum: { icon: Crown, color: 'text-purple-500', bg: 'bg-purple-100', border: 'border-purple-200' },
  gold: { icon: Trophy, color: 'text-amber-500', bg: 'bg-amber-100', border: 'border-amber-200' },
  silver: { icon: Medal, color: 'text-neutral-400', bg: 'bg-neutral-100', border: 'border-neutral-200' },
  bronze: { icon: Award, color: 'text-orange-400', bg: 'bg-orange-100', border: 'border-orange-200' },
}

export default function SupportWallPage() {
  const [view, setView] = useState<ViewType>('recent')
  const [recent, setRecent] = useState<RecentContribution[]>([])
  const [leaderboard, setLeaderboard] = useState<LeaderboardEntry[]>([])
  const [stats, setStats] = useState<ImpactStats | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchData = async (selectedView: ViewType) => {
    setIsLoading(true)
    setError(null)
    try {
      const response = await fetch(`/api/public/support-wall?view=${selectedView}&limit=50`)
      if (!response.ok) throw new Error('Failed to fetch data')
      const data = await response.json()

      setStats(data.stats)
      if (selectedView === 'recent') {
        setRecent(data.recent || [])
      } else {
        setLeaderboard(data.leaderboard || [])
      }
    } catch (err) {
      setError('Failed to load support wall data')
      console.error(err)
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    fetchData(view)
  }, [view])

  const formatAmount = (amount: number) => {
    if (amount >= 10000) return `$${(amount / 1000).toFixed(0)}K`
    if (amount >= 1000) return `$${(amount / 1000).toFixed(1)}K`
    return `$${amount}`
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      <div className="section-container py-12 lg:py-16">
        <div className="max-w-4xl mx-auto">
          {/* Header */}
          <div className="text-center mb-8">
            <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-3">
              Support Wall
            </h1>
            <p className="text-lg text-neutral-600">
              Thank you to everyone who makes clean streets possible
            </p>
          </div>

          {/* Impact Stats */}
          {stats && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
              <div className="bg-white rounded-xl p-4 shadow-sm text-center">
                <p className="text-2xl font-bold text-brand-green-600">{formatAmount(stats.totalRaised)}</p>
                <p className="text-sm text-neutral-600">Total Raised</p>
              </div>
              <div className="bg-white rounded-xl p-4 shadow-sm text-center">
                <p className="text-2xl font-bold text-brand-gold-600">{stats.blocksFunded}</p>
                <p className="text-sm text-neutral-600">Blocks Funded</p>
              </div>
              <div className="bg-white rounded-xl p-4 shadow-sm text-center">
                <p className="text-2xl font-bold text-brand-brown-600">{stats.totalContributors}</p>
                <p className="text-sm text-neutral-600">Total Supporters</p>
              </div>
              <div className="bg-white rounded-xl p-4 shadow-sm text-center">
                <p className="text-2xl font-bold text-blue-600">{stats.recentContributors}</p>
                <p className="text-sm text-neutral-600">Last 30 Days</p>
              </div>
            </div>
          )}

          {/* View Toggle */}
          <div className="flex bg-white p-1 rounded-xl shadow-sm mb-6 max-w-xs mx-auto">
            <button
              onClick={() => setView('recent')}
              className={`flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg font-medium transition-all text-sm ${
                view === 'recent'
                  ? 'bg-brand-green-600 text-white'
                  : 'text-neutral-600 hover:text-neutral-900'
              }`}
            >
              <Clock className="w-4 h-4" />
              Recent
            </button>
            <button
              onClick={() => setView('leaderboard')}
              className={`flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg font-medium transition-all text-sm ${
                view === 'leaderboard'
                  ? 'bg-brand-green-600 text-white'
                  : 'text-neutral-600 hover:text-neutral-900'
              }`}
            >
              <Trophy className="w-4 h-4" />
              Top Supporters
            </button>
          </div>

          {/* Content */}
          <div className="bg-white rounded-2xl shadow-lg overflow-hidden">
            {isLoading ? (
              <div className="p-8 text-center">
                <RefreshCw className="w-8 h-8 animate-spin mx-auto text-brand-green-500 mb-3" />
                <p className="text-neutral-500">Loading...</p>
              </div>
            ) : error ? (
              <div className="p-8 text-center">
                <p className="text-red-500 mb-3">{error}</p>
                <button
                  onClick={() => fetchData(view)}
                  className="text-brand-green-600 hover:text-brand-green-700 font-medium"
                >
                  Try again
                </button>
              </div>
            ) : view === 'recent' ? (
              // Recent Feed
              <div className="divide-y divide-neutral-100">
                {recent.length === 0 ? (
                  <div className="p-8 text-center text-neutral-500">
                    <Heart className="w-12 h-12 mx-auto mb-3 text-neutral-300" />
                    <p>No contributions yet. Be the first!</p>
                  </div>
                ) : (
                  recent.map((contribution) => (
                    <div key={contribution.id} className="p-4 hover:bg-neutral-50 transition-colors">
                      <div className="flex items-start gap-3">
                        <div className={`w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 ${
                          contribution.type === 'sponsorship' ? 'bg-brand-gold-100' : 'bg-brand-green-100'
                        }`}>
                          {contribution.type === 'sponsorship' ? (
                            <MapPin className="w-5 h-5 text-brand-gold-600" />
                          ) : (
                            <Heart className="w-5 h-5 text-brand-green-600" />
                          )}
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center justify-between gap-2">
                            <p className="font-medium text-neutral-900 truncate">
                              {contribution.displayName}
                            </p>
                            <span className={`font-semibold ${
                              contribution.type === 'sponsorship' ? 'text-brand-gold-600' : 'text-brand-green-600'
                            }`}>
                              ${contribution.amount}
                            </span>
                          </div>
                          <p className="text-sm text-neutral-500">
                            {contribution.type === 'sponsorship' && contribution.blockName ? (
                              <>Sponsored {contribution.blockName}</>
                            ) : (
                              <>Donated to General Fund</>
                            )}
                            <span className="mx-1.5">·</span>
                            <span title={new Date(contribution.createdAt).toLocaleString()}>
                              {formatDistanceToNow(new Date(contribution.createdAt), { addSuffix: true })}
                            </span>
                          </p>
                          {contribution.message && (
                            <div className="mt-2 flex items-start gap-1.5 text-sm text-neutral-600">
                              <MessageSquare className="w-4 h-4 flex-shrink-0 mt-0.5 text-neutral-400" />
                              <p className="italic">&quot;{contribution.message}&quot;</p>
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  ))
                )}
              </div>
            ) : (
              // Leaderboard
              <div className="divide-y divide-neutral-100">
                {leaderboard.length === 0 ? (
                  <div className="p-8 text-center text-neutral-500">
                    <Trophy className="w-12 h-12 mx-auto mb-3 text-neutral-300" />
                    <p>No contributions yet. Be the first!</p>
                  </div>
                ) : (
                  leaderboard.map((entry) => {
                    const config = tierConfig[entry.tier]
                    const TierIcon = config.icon
                    const isTopThree = entry.rank <= 3

                    return (
                      <div
                        key={entry.rank}
                        className={`p-4 hover:bg-neutral-50 transition-colors ${
                          isTopThree ? 'bg-gradient-to-r from-amber-50/50 to-transparent' : ''
                        }`}
                      >
                        <div className="flex items-center gap-4">
                          {/* Rank */}
                          <div className={`w-10 h-10 rounded-full flex items-center justify-center font-bold text-lg ${
                            entry.rank === 1 ? 'bg-amber-100 text-amber-600' :
                            entry.rank === 2 ? 'bg-neutral-200 text-neutral-600' :
                            entry.rank === 3 ? 'bg-orange-100 text-orange-600' :
                            'bg-neutral-100 text-neutral-500'
                          }`}>
                            {entry.rank <= 3 ? (
                              <Star className={`w-5 h-5 ${
                                entry.rank === 1 ? 'text-amber-500' :
                                entry.rank === 2 ? 'text-neutral-400' :
                                'text-orange-400'
                              }`} />
                            ) : (
                              entry.rank
                            )}
                          </div>

                          {/* Info */}
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2">
                              <p className="font-semibold text-neutral-900 truncate">
                                {entry.displayName}
                              </p>
                              <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${config.bg} ${config.color} ${config.border} border`}>
                                <TierIcon className="w-3 h-3 inline mr-1" />
                                {entry.tier.charAt(0).toUpperCase() + entry.tier.slice(1)}
                              </span>
                            </div>
                            <p className="text-sm text-neutral-500">
                              {entry.contributionCount} contribution{entry.contributionCount !== 1 ? 's' : ''}
                              {entry.blocksSponsored > 0 && (
                                <span className="mx-1.5">·</span>
                              )}
                              {entry.blocksSponsored > 0 && (
                                <span>{entry.blocksSponsored} block{entry.blocksSponsored !== 1 ? 's' : ''} sponsored</span>
                              )}
                            </p>
                          </div>

                          {/* Amount */}
                          <div className="text-right">
                            <p className="font-bold text-lg text-brand-green-600">
                              {formatAmount(entry.totalAmount)}
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
            <div className="mt-6 bg-white rounded-xl p-4 shadow-sm">
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
                    <div key={tier} className={`flex items-center gap-2 p-2 rounded-lg ${config.bg} ${config.border} border`}>
                      <TierIcon className={`w-4 h-4 ${config.color}`} />
                      <div>
                        <p className={`text-sm font-medium ${config.color}`}>
                          {tier.charAt(0).toUpperCase() + tier.slice(1)}
                        </p>
                        <p className="text-xs text-neutral-500">{thresholds[tier]}</p>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* CTA */}
          <div className="mt-8 text-center">
            <Link
              href="/donate"
              className="inline-flex items-center gap-2 btn-primary px-8 py-3"
            >
              <Heart className="w-4 h-4" />
              Make a Contribution
              <ChevronRight className="w-4 h-4" />
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}
