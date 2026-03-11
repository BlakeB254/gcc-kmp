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
  Handshake,
  Building2,
  Mail,
  DollarSign,
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
  platinum: { icon: Crown, color: 'text-purple-500', bg: 'bg-purple-100', border: 'border-purple-200', label: 'Platinum' },
  gold: { icon: Trophy, color: 'text-amber-500', bg: 'bg-amber-100', border: 'border-amber-200', label: 'Gold' },
  silver: { icon: Medal, color: 'text-neutral-400', bg: 'bg-neutral-100', border: 'border-neutral-200', label: 'Silver' },
  bronze: { icon: Award, color: 'text-orange-400', bg: 'bg-orange-100', border: 'border-orange-200', label: 'Bronze' },
}

export default function SupportersPage() {
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
      setError('Failed to load supporter data')
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
    <div className="min-h-screen bg-gradient-to-b from-brand-gold-50 via-white to-brand-green-50">
      <div className="section-container py-12 lg:py-16">
        <div className="max-w-4xl mx-auto">
          {/* Header */}
          <div className="text-center mb-10">
            <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-brand-gold-100 mb-4">
              <Heart className="w-8 h-8 text-brand-gold-600" />
            </div>
            <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-3">
              Our Supporters
            </h1>
            <p className="text-lg text-neutral-600 max-w-2xl mx-auto">
              Every dollar, every block, every act of generosity brings us closer to cleaner streets
              and stronger communities. Thank you for making it possible.
            </p>
          </div>

          {/* Impact Stats */}
          {stats && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-10">
              <div className="bg-white rounded-xl p-5 shadow-sm border border-neutral-100 text-center">
                <DollarSign className="w-6 h-6 text-brand-green-600 mx-auto mb-2" />
                <p className="text-2xl font-bold text-brand-green-600">{formatAmount(stats.totalRaised)}</p>
                <p className="text-sm text-neutral-600">Total Raised</p>
              </div>
              <div className="bg-white rounded-xl p-5 shadow-sm border border-neutral-100 text-center">
                <MapPin className="w-6 h-6 text-brand-gold-600 mx-auto mb-2" />
                <p className="text-2xl font-bold text-brand-gold-600">{stats.blocksFunded}</p>
                <p className="text-sm text-neutral-600">Blocks Funded</p>
              </div>
              <div className="bg-white rounded-xl p-5 shadow-sm border border-neutral-100 text-center">
                <Users className="w-6 h-6 text-brand-brown-600 mx-auto mb-2" />
                <p className="text-2xl font-bold text-brand-brown-600">{stats.totalContributors}</p>
                <p className="text-sm text-neutral-600">Total Supporters</p>
              </div>
              <div className="bg-white rounded-xl p-5 shadow-sm border border-neutral-100 text-center">
                <Heart className="w-6 h-6 text-brand-sky-600 mx-auto mb-2" />
                <p className="text-2xl font-bold text-brand-sky-600">{stats.recentContributors}</p>
                <p className="text-sm text-neutral-600">This Month</p>
              </div>
            </div>
          )}

          {/* View Toggle */}
          <div className="flex bg-white p-1 rounded-xl shadow-sm border border-neutral-100 mb-8 max-w-sm mx-auto">
            <button
              onClick={() => setView('recent')}
              className={`flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg font-medium transition-all text-sm ${
                view === 'recent'
                  ? 'bg-brand-gold-500 text-white shadow-sm'
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
                  ? 'bg-brand-gold-500 text-white shadow-sm'
                  : 'text-neutral-600 hover:text-neutral-900'
              }`}
            >
              <Trophy className="w-4 h-4" />
              Top Supporters
            </button>
          </div>

          {/* Content */}
          <div className="bg-white rounded-2xl shadow-lg border border-neutral-100 overflow-hidden">
            {isLoading ? (
              <div className="p-12 text-center">
                <RefreshCw className="w-8 h-8 animate-spin mx-auto text-brand-gold-500 mb-3" />
                <p className="text-neutral-500">Loading supporters...</p>
              </div>
            ) : error ? (
              <div className="p-12 text-center">
                <p className="text-red-500 mb-3">{error}</p>
                <button
                  onClick={() => fetchData(view)}
                  className="text-brand-gold-600 hover:text-brand-gold-700 font-medium"
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
                    <p className="text-neutral-500 mb-6">
                      No contributions yet. Your donation will kickstart the movement.
                    </p>
                    <Link
                      href="/donate"
                      className="btn-primary inline-flex items-center gap-2"
                    >
                      <Heart className="w-4 h-4" />
                      Make a Donation
                    </Link>
                  </div>
                ) : (
                  recent.map((contribution) => (
                    <div key={contribution.id} className="p-5 hover:bg-neutral-50 transition-colors">
                      <div className="flex items-start gap-4">
                        <div className={`w-11 h-11 rounded-full flex items-center justify-center flex-shrink-0 ${
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
                            <p className="font-semibold text-neutral-900 truncate">
                              {contribution.displayName}
                            </p>
                            <span className={`font-bold text-lg ${
                              contribution.type === 'sponsorship' ? 'text-brand-gold-600' : 'text-brand-green-600'
                            }`}>
                              ${contribution.amount}
                            </span>
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
                              <MessageSquare className="w-4 h-4 flex-shrink-0 mt-0.5 text-brand-gold-400" />
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
                    <p className="text-neutral-500 mb-6">
                      Be the first to make the supporters leaderboard!
                    </p>
                    <Link
                      href="/donate"
                      className="btn-primary inline-flex items-center gap-2"
                    >
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
                          {/* Rank */}
                          <div className={`w-11 h-11 rounded-full flex items-center justify-center font-bold text-lg ${
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
                                {config.label}
                              </span>
                            </div>
                            <p className="text-sm text-neutral-500">
                              {entry.contributionCount} contribution{entry.contributionCount !== 1 ? 's' : ''}
                              {entry.blocksSponsored > 0 && (
                                <>
                                  <span className="mx-1.5">&middot;</span>
                                  <span>{entry.blocksSponsored} block{entry.blocksSponsored !== 1 ? 's' : ''} sponsored</span>
                                </>
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
                        <p className={`text-sm font-medium ${config.color}`}>
                          {config.label}
                        </p>
                        <p className="text-xs text-neutral-500">{thresholds[tier]}</p>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* Donate CTA */}
          <div className="mt-10 bg-gradient-to-r from-brand-green-600 to-brand-green-700 rounded-2xl p-8 text-center text-white">
            <h2 className="text-2xl font-bold mb-3">Join Our Supporters</h2>
            <p className="text-brand-green-100 mb-6 max-w-lg mx-auto">
              Every dollar goes directly to paying workers who clean blocks in North Lawndale.
              Your name and message can appear right here on our supporters wall.
            </p>
            <div className="flex flex-col sm:flex-row gap-3 justify-center">
              <Link
                href="/donate"
                className="inline-flex items-center justify-center gap-2 bg-white text-brand-green-700 font-semibold px-8 py-3 rounded-xl hover:bg-brand-green-50 transition-colors"
              >
                <Heart className="w-5 h-5" />
                Make a Donation
              </Link>
              <Link
                href="/sponsor"
                className="inline-flex items-center justify-center gap-2 border-2 border-white/60 text-white font-semibold px-8 py-3 rounded-xl hover:bg-white/10 hover:border-white transition-colors"
              >
                <MapPin className="w-5 h-5" />
                Sponsor a Block
              </Link>
            </div>
          </div>

          {/* Partnerships Section */}
          <div className="mt-10 bg-white rounded-2xl shadow-lg border border-neutral-100 overflow-hidden">
            <div className="bg-gradient-to-r from-brand-brown-800 to-brand-brown-900 px-8 py-6">
              <div className="flex items-center gap-3">
                <Handshake className="w-7 h-7 text-brand-gold-400" />
                <div>
                  <h2 className="text-xl font-bold text-white">Partnerships</h2>
                  <p className="text-brand-brown-200 text-sm">Organizations and businesses supporting our mission</p>
                </div>
              </div>
            </div>

            <div className="p-8">
              <div className="text-center py-6">
                <Building2 className="w-14 h-14 mx-auto mb-4 text-neutral-200" />
                <h3 className="text-lg font-semibold text-neutral-900 mb-2">
                  Partner With GCC
                </h3>
                <p className="text-neutral-600 max-w-lg mx-auto mb-6">
                  We&apos;re building partnerships with local businesses, organizations, and foundations
                  who share our vision for cleaner, safer neighborhoods. Corporate sponsorships include
                  branding, impact reports, and community recognition.
                </p>
                <div className="flex flex-col sm:flex-row gap-3 justify-center">
                  <Link
                    href="/sponsor/enterprise"
                    className="inline-flex items-center justify-center gap-2 bg-brand-brown-700 text-white font-medium px-6 py-3 rounded-xl hover:bg-brand-brown-800 transition-colors"
                  >
                    <Building2 className="w-4 h-4" />
                    Enterprise Packages
                    <ChevronRight className="w-4 h-4" />
                  </Link>
                  <a
                    href="mailto:partnerships@globalcommunitycleaners.org?subject=Partnership%20Inquiry"
                    className="inline-flex items-center justify-center gap-2 border-2 border-brand-brown-300 text-brand-brown-700 font-medium px-6 py-3 rounded-xl hover:bg-brand-brown-50 transition-colors"
                  >
                    <Mail className="w-4 h-4" />
                    Contact Us
                  </a>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
