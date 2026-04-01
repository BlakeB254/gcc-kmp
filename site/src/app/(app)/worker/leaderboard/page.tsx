'use client'

import { useState, useRef, useCallback, useEffect } from 'react'
import Link from 'next/link'
import { toPng } from 'html-to-image'
import {
  Trophy,
  Medal,
  Crown,
  Star,
  Flame,
  TrendingUp,
  Share2,
  Download,
  RefreshCw,
  Globe,
  MapPin,
  Building2,
  Home,
} from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { cn, formatCurrency, formatRelativeTime } from '@/lib/utils'

type LeaderboardScope = 'global' | 'state' | 'city' | 'neighborhood'

interface LeaderboardEntry {
  rank: number
  id: number
  name: string
  neighborhood: string | null
  jobs: number
  earnings: number
  rating: number | null
  lastJobAt: string | null
  tier: 'founder' | 'pioneer' | 'early' | 'standard'
  isCurrentUser: boolean
}

interface LeaderboardData {
  scope: LeaderboardScope
  leaderboard: LeaderboardEntry[]
  currentUserPosition: LeaderboardEntry | null
  total: number
  lastUpdated: string | null
}

const tierBadgeConfig = {
  founder: { label: 'Founder', color: 'bg-purple-500', textColor: 'text-purple-600' },
  pioneer: { label: 'Pioneer', color: 'bg-brand-green-500', textColor: 'text-brand-green-600' },
  early: { label: 'Early', color: 'bg-brand-green-500', textColor: 'text-brand-green-600' },
  standard: { label: 'Standard', color: 'bg-neutral-500', textColor: 'text-neutral-600' },
}

const scopeConfig: Record<LeaderboardScope, { label: string; icon: typeof Globe; description: string }> = {
  global: { label: 'Global', icon: Globe, description: 'All GCC workers' },
  state: { label: 'Illinois', icon: Building2, description: 'Workers in IL' },
  city: { label: 'Chicago', icon: MapPin, description: 'Workers in Chicago' },
  neighborhood: { label: 'North Lawndale', icon: Home, description: 'Your neighborhood' },
}

const rankStyles = {
  1: 'bg-gradient-to-r from-brand-green-100 to-amber-100 border-brand-green-300',
  2: 'bg-gradient-to-r from-slate-100 to-gray-100 border-slate-300',
  3: 'bg-gradient-to-r from-amber-100 to-orange-100 border-amber-300',
} as const

function LeaderboardRow({ entry }: { entry: LeaderboardEntry }) {
  const isTop3 = entry.rank <= 3
  const tierInfo = tierBadgeConfig[entry.tier]

  return (
    <div
      className={cn(
        'flex items-center gap-4 rounded-xl border p-4 transition-all',
        isTop3 ? rankStyles[entry.rank as 1 | 2 | 3] : 'bg-card hover:bg-muted/50',
        entry.isCurrentUser && 'ring-2 ring-brand-green-500 ring-offset-2'
      )}
    >
      {/* Rank */}
      <div className="flex w-8 items-center justify-center">
        {entry.rank === 1 ? (
          <Crown className="h-7 w-7 text-brand-green-500" />
        ) : entry.rank === 2 ? (
          <Medal className="h-6 w-6 text-slate-400" />
        ) : entry.rank === 3 ? (
          <Medal className="h-6 w-6 text-amber-600" />
        ) : (
          <span className="text-lg font-bold text-muted-foreground">{entry.rank}</span>
        )}
      </div>

      {/* Avatar & Name */}
      <div className="flex flex-1 items-center gap-3">
        <Avatar className={cn(isTop3 && 'ring-2 ring-offset-2', entry.rank === 1 && 'ring-brand-green-500')}>
          <AvatarFallback className="bg-brand-green-100 text-brand-green-700">
            {entry.name
              .split(' ')
              .map((n) => n[0])
              .join('')}
          </AvatarFallback>
        </Avatar>
        <div>
          <p className="font-semibold">
            {entry.name}
            {entry.isCurrentUser && <span className="ml-2 text-xs text-brand-green-600">(You)</span>}
          </p>
          <div className="flex items-center gap-2">
            {entry.tier !== 'standard' && (
              <span className={cn('text-xs font-medium', tierInfo.textColor)}>
                {tierInfo.label}
              </span>
            )}
            {entry.neighborhood && (
              <span className="text-xs text-muted-foreground">{entry.neighborhood}</span>
            )}
          </div>
        </div>
      </div>

      {/* Stats */}
      <div className="flex items-center gap-6">
        {entry.rating && entry.rating > 4.5 && (
          <div className="flex items-center gap-1 text-sm">
            <Star className="h-4 w-4 text-brand-green-500 fill-brand-green-500" />
            <span className="font-medium">{entry.rating.toFixed(1)}</span>
          </div>
        )}
        <div className="text-right">
          <p className="text-sm text-muted-foreground">{entry.jobs} jobs</p>
          <p className="font-bold text-brand-green-700">{formatCurrency(entry.earnings)}</p>
        </div>
      </div>
    </div>
  )
}

export default function LeaderboardPage() {
  const [scope, setScope] = useState<LeaderboardScope>('neighborhood')
  const [data, setData] = useState<LeaderboardData | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isRefreshing, setIsRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [isSaving, setIsSaving] = useState(false)
  const [isSaved, setIsSaved] = useState(false)
  const rankCardRef = useRef<HTMLDivElement>(null)

  const fetchLeaderboard = useCallback(
    async (showRefresh = false) => {
      if (showRefresh) setIsRefreshing(true)
      else setIsLoading(true)
      setError(null)

      try {
        const response = await fetch(`/api/leaderboard?scope=${scope}&limit=50`)
        const result = await response.json()

        if (!result.success) {
          throw new Error(result.error || 'Failed to fetch leaderboard')
        }

        setData(result)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Something went wrong')
      } finally {
        setIsLoading(false)
        setIsRefreshing(false)
      }
    },
    [scope]
  )

  useEffect(() => {
    fetchLeaderboard()
  }, [fetchLeaderboard])

  const handleRefresh = () => {
    fetchLeaderboard(true)
  }

  const currentUser = data?.currentUserPosition || data?.leaderboard.find((e) => e.isCurrentUser)

  const saveRankCard = useCallback(async () => {
    if (!rankCardRef.current || !currentUser) return

    setIsSaving(true)
    try {
      const dataUrl = await toPng(rankCardRef.current, {
        cacheBust: true,
        pixelRatio: 2,
        backgroundColor: '#18181b',
      })

      const link = document.createElement('a')
      link.download = `gcc-rank-${currentUser.name.replace(/\s+/g, '-').toLowerCase()}.png`
      link.href = dataUrl
      link.click()

      setIsSaved(true)
      setTimeout(() => setIsSaved(false), 3000)
    } catch (err) {
      console.error('Failed to save rank card:', err)
    } finally {
      setIsSaving(false)
    }
  }, [currentUser])

  const shareRank = () => {
    if (!currentUser) return
    const scopeLabel = scopeConfig[scope].label
    const text = `I'm ranked #${currentUser.rank} in ${scopeLabel} with @GCCmovement!\n\n${currentUser.jobs} jobs completed\n${formatCurrency(currentUser.earnings)} earned\n\nJoin us in cleaning up our communities!`
    window.open(`https://twitter.com/intent/tweet?text=${encodeURIComponent(text)}`, '_blank')
  }

  return (
    <div className="bg-gradient-to-b from-brand-green-50 to-white">
      {/* Page Title and Refresh */}
      <div className="section-container pt-6 pb-2 flex items-center justify-between">
        <h1 className="text-2xl font-bold">Leaderboard</h1>
        <button
          onClick={handleRefresh}
          disabled={isRefreshing}
          className="p-2 text-neutral-600 hover:text-neutral-900 disabled:opacity-50"
        >
          <RefreshCw className={cn('w-5 h-5', isRefreshing && 'animate-spin')} />
        </button>
      </div>

      {/* Scope Toggle */}
      <div className="section-container py-4">
        <div className="flex gap-2 overflow-x-auto pb-2 scrollbar-hide">
          {(Object.keys(scopeConfig) as LeaderboardScope[]).map((s) => {
            const config = scopeConfig[s]
            const Icon = config.icon
            return (
              <button
                key={s}
                onClick={() => setScope(s)}
                className={cn(
                  'flex items-center gap-2 px-4 py-2 rounded-full text-sm font-medium transition-all whitespace-nowrap',
                  scope === s
                    ? 'bg-brand-green-500 text-neutral-900'
                    : 'bg-neutral-100 text-neutral-600 hover:bg-neutral-200'
                )}
              >
                <Icon className="w-4 h-4" />
                {config.label}
              </button>
            )
          })}
        </div>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div className="section-container py-16 text-center">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-brand-green-600 mx-auto mb-4" />
          <p className="text-neutral-600">Loading leaderboard...</p>
        </div>
      )}

      {/* Error State */}
      {error && !isLoading && (
        <div className="section-container py-8">
          <div className="bg-red-50 border border-red-200 rounded-xl p-6 text-center">
            <p className="text-red-700 mb-4">{error}</p>
            <button onClick={() => fetchLeaderboard()} className="btn-primary">
              Try Again
            </button>
          </div>
        </div>
      )}

      {/* Content */}
      {data && !isLoading && (
        <>
          {/* Your Rank Card - Screenshottable */}
          {currentUser && (
            <section className="section-container py-6">
              <div
                ref={rankCardRef}
                className="p-1 bg-gradient-to-r from-brand-green-500 via-brand-green-500 to-brand-green-500 rounded-2xl"
              >
                <div className="bg-neutral-900 rounded-xl p-5 text-white">
                  <div className="flex items-center justify-between mb-4">
                    <div className="flex items-center gap-3">
                      <div className="w-14 h-14 rounded-full bg-gradient-to-br from-brand-green-400 to-brand-green-600 flex items-center justify-center">
                        <span className="text-2xl font-bold text-neutral-900">
                          {currentUser.name
                            .split(' ')
                            .map((n) => n[0])
                            .join('')}
                        </span>
                      </div>
                      <div>
                        <p className="font-bold text-lg">{currentUser.name}</p>
                        <p className="text-sm text-neutral-400">
                          {currentUser.neighborhood || 'North Lawndale'}
                        </p>
                      </div>
                    </div>
                    {currentUser.tier !== 'standard' && (
                      <div
                        className={cn(
                          'px-3 py-1 rounded-full text-xs font-bold',
                          tierBadgeConfig[currentUser.tier].color,
                          'text-white'
                        )}
                      >
                        {tierBadgeConfig[currentUser.tier].label}
                      </div>
                    )}
                  </div>

                  <div className="grid grid-cols-3 gap-4 mb-4">
                    <div className="text-center p-3 rounded-lg bg-neutral-800">
                      <p className="text-2xl font-bold text-brand-green-400">#{currentUser.rank}</p>
                      <p className="text-xs text-neutral-400">{scopeConfig[scope].label}</p>
                    </div>
                    <div className="text-center p-3 rounded-lg bg-neutral-800">
                      <p className="text-2xl font-bold text-brand-green-400">{currentUser.jobs}</p>
                      <p className="text-xs text-neutral-400">Jobs</p>
                    </div>
                    <div className="text-center p-3 rounded-lg bg-neutral-800">
                      <p className="text-2xl font-bold text-white">
                        {currentUser.rating ? currentUser.rating.toFixed(1) : '—'}
                      </p>
                      <p className="text-xs text-neutral-400">Rating</p>
                    </div>
                  </div>

                  <div className="flex items-center justify-between pt-3 border-t border-neutral-700">
                    <p className="text-sm text-neutral-400">Total Earned</p>
                    <p className="text-xl font-bold text-brand-green-400">
                      {formatCurrency(currentUser.earnings)}
                    </p>
                  </div>

                  {/* Watermark */}
                  <div className="flex items-center justify-center gap-2 mt-4 pt-3 border-t border-neutral-800">
                    <span className="text-lg">🧹</span>
                    <span className="text-xs text-neutral-500">Global Community Cleaners</span>
                  </div>
                </div>
              </div>

              {/* Share buttons */}
              <div className="flex gap-3 mt-4">
                <button
                  onClick={saveRankCard}
                  disabled={isSaving}
                  className={cn(
                    'flex-1 flex items-center justify-center gap-2 py-3 px-4 rounded-xl font-medium transition-all',
                    isSaved
                      ? 'bg-brand-green-500 text-white'
                      : 'bg-brand-green-500 hover:bg-brand-green-400 text-neutral-900'
                  )}
                >
                  {isSaving ? (
                    <>
                      <div className="animate-spin rounded-full h-5 w-5 border-2 border-current border-t-transparent" />
                      Saving...
                    </>
                  ) : isSaved ? (
                    <>
                      <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M5 13l4 4L19 7"
                        />
                      </svg>
                      Saved!
                    </>
                  ) : (
                    <>
                      <Download className="w-5 h-5" />
                      Save Rank Card
                    </>
                  )}
                </button>

                <button
                  onClick={shareRank}
                  className="flex-1 flex items-center justify-center gap-2 bg-black hover:bg-neutral-800 text-white font-medium py-3 px-4 rounded-xl transition-colors"
                >
                  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                    <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
                  </svg>
                  Share
                </button>
              </div>
            </section>
          )}

          {/* Last Updated */}
          {data.lastUpdated && (
            <div className="section-container pb-4">
              <p className="text-xs text-neutral-500 text-center">
                Last updated {formatRelativeTime(new Date(data.lastUpdated))} • {data.total}{' '}
                {data.total === 1 ? 'worker' : 'workers'} ranked
              </p>
            </div>
          )}

          {/* Leaderboard */}
          <section className="section-container pb-16">
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle className="flex items-center gap-2">
                    <Trophy className="h-5 w-5 text-brand-green-500" />
                    {scopeConfig[scope].label} Rankings
                  </CardTitle>
                  <Badge variant="warning">Live</Badge>
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                {data.leaderboard.length === 0 ? (
                  <div className="text-center py-8">
                    <Trophy className="w-12 h-12 text-neutral-300 mx-auto mb-4" />
                    <p className="text-neutral-600 mb-2">No rankings yet</p>
                    <p className="text-sm text-neutral-500">
                      Complete jobs to appear on the leaderboard!
                    </p>
                  </div>
                ) : (
                  data.leaderboard.map((entry) => <LeaderboardRow key={entry.id} entry={entry} />)
                )}
              </CardContent>
            </Card>
          </section>

          {/* Achievements Section */}
          <section className="bg-brand-green-800 py-16 text-white">
            <div className="section-container text-center">
              <h2 className="mb-4 text-2xl font-bold">Climb the Ranks</h2>
              <p className="mb-8 text-white/70">
                Complete more jobs and maintain high ratings to rise in the rankings
              </p>
              <div className="grid gap-4 sm:grid-cols-2 md:grid-cols-4">
                {[
                  { icon: Crown, label: 'Top 3', description: 'Gold/Silver/Bronze status' },
                  { icon: Star, label: 'High Rating', description: 'Above 4.5 stars' },
                  { icon: TrendingUp, label: 'Rising Star', description: 'Fastest climber this week' },
                  { icon: Flame, label: 'On Fire', description: '5+ jobs in a week' },
                ].map((item) => {
                  const Icon = item.icon
                  return (
                    <div key={item.label} className="rounded-xl bg-white/10 p-4 backdrop-blur">
                      <Icon className="mx-auto h-8 w-8 text-brand-green-400" />
                      <p className="mt-2 text-sm font-medium">{item.label}</p>
                      <p className="text-xs text-white/60">{item.description}</p>
                    </div>
                  )
                })}
              </div>
            </div>
          </section>
        </>
      )}
    </div>
  )
}
