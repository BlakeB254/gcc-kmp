'use client'

import { useState, useEffect } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { Logo } from '@/components/brand/Logo'
import { cn } from '@/lib/utils'
import {
  DollarSign,
  MapPin,
  Users,
  Heart,
  Settings,
  History,
  LayoutDashboard,
  LogOut,
  HelpCircle,
  ExternalLink,
  ChevronRight,
  CheckCircle2,
  Clock,
  Bell,
  BellOff,
  Eye,
  EyeOff,
  Plus,
  Sparkles,
  Trophy,
  Globe,
  Star,
  Crown,
  Zap,
  Gift,
  Target,
  Copy,
  Check,
  User,
  Home,
  TrendingUp,
  Building2,
  Mail,
  MessageSquare,
  Share2,
} from 'lucide-react'

interface SponsorProfile {
  id: string
  name: string
  email: string
  type: 'individual' | 'business' | 'organization' | 'foundation'
  business?: {
    name: string
    website: string
  }
  stats: {
    totalContributed: number
    blocksFunded: number
    lastContributionAt: string | null
  }
  preferences: {
    frequency: 'one_time' | 'monthly' | 'quarterly' | 'annual'
    isAnonymous: boolean
    wantsUpdates: boolean
  }
  stripe: {
    subscriptionId: string | null
    defaultPaymentMethod: string | null
  }
  activeSponsorship: boolean
  signupRankings?: {
    global: number | null
  }
  earlyAdopterTier?: 'founder' | 'pioneer' | 'early' | 'standard'
  referralCode?: string
  createdAt: string
}

interface Sponsorship {
  id: string
  blockCode: string
  blockName: string
  amount: number
  status: 'completed' | 'pending' | 'cleaned'
  createdAt: string
  cleanedAt?: string
  workerName?: string
}

// Tier configuration
const tierConfig = {
  founder: {
    label: 'Founding Sponsor',
    emoji: '👑',
    color: 'from-purple-500 to-violet-600',
    bgColor: 'bg-purple-100',
    textColor: 'text-purple-700',
    borderColor: 'border-purple-200',
    description: 'First 10 sponsors',
    icon: Crown,
  },
  pioneer: {
    label: 'Pioneer Sponsor',
    emoji: '⭐',
    color: 'from-brand-green-400 to-amber-500',
    bgColor: 'bg-brand-green-100',
    textColor: 'text-brand-green-700',
    borderColor: 'border-brand-green-200',
    description: 'First 100 sponsors',
    icon: Star,
  },
  early: {
    label: 'Early Supporter',
    emoji: '✨',
    color: 'from-brand-green-400 to-emerald-500',
    bgColor: 'bg-brand-green-100',
    textColor: 'text-brand-green-700',
    borderColor: 'border-brand-green-200',
    description: 'First 1,000 sponsors',
    icon: Zap,
  },
  standard: {
    label: 'Sponsor',
    emoji: '💚',
    color: 'from-neutral-400 to-neutral-500',
    bgColor: 'bg-neutral-100',
    textColor: 'text-neutral-700',
    borderColor: 'border-neutral-200',
    description: 'Part of the movement',
    icon: Heart,
  },
}

// Impact metrics explanation
const impactMetrics = [
  {
    icon: MapPin,
    title: 'Streets Cleaned',
    description: 'Each block covers approximately 800 feet of Chicago streets, making the neighborhood visibly cleaner and safer.',
    color: 'bg-brand-green-100 text-brand-green-600',
  },
  {
    icon: Users,
    title: 'Workers Paid',
    description: 'Every $100 goes directly to a verified North Lawndale resident, providing meaningful income to the community.',
    color: 'bg-brand-green-100 text-brand-green-600',
  },
  {
    icon: Heart,
    title: 'Community Impact',
    description: 'Your sponsorship funds local employment, improves public health, and builds community pride in North Lawndale.',
    color: 'bg-brand-blue-100 text-brand-blue-600',
  },
]

function formatCurrency(cents: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 0,
  }).format(cents / 100)
}

function formatDate(dateString: string): string {
  return new Date(dateString).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  })
}

// Animated Ranking Card Component (matching worker dashboard)
function RankingCard({
  icon: Icon,
  label,
  rank,
  colorClass,
  delay = 0
}: {
  icon: typeof Globe
  label: string
  rank: number | null
  colorClass: string
  delay?: number
}) {
  const [displayRank, setDisplayRank] = useState(0)

  useEffect(() => {
    if (!rank) return
    const duration = 1200
    const steps = 30
    let step = 0
    const timer = setTimeout(() => {
      const interval = setInterval(() => {
        step++
        const progress = step / steps
        const eased = 1 - Math.pow(1 - progress, 3)
        setDisplayRank(Math.max(1, Math.floor(rank * eased)))
        if (step >= steps) {
          setDisplayRank(rank)
          clearInterval(interval)
        }
      }, duration / steps)
    }, delay)
    return () => clearTimeout(timer)
  }, [rank, delay])

  return (
    <div className="bg-white rounded-xl border border-neutral-200 p-4 text-center hover:shadow-md hover:border-brand-green-200 transition-all">
      <div className={cn('w-10 h-10 mx-auto rounded-lg flex items-center justify-center mb-2', colorClass)}>
        <Icon className="w-5 h-5 text-white" />
      </div>
      <div className="text-2xl sm:text-3xl font-bold text-neutral-900">
        #{displayRank || '-'}
      </div>
      <p className="text-xs text-neutral-500 mt-1">{label}</p>
    </div>
  )
}

// Copy Button Component
function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    await navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <button
      onClick={handleCopy}
      className="inline-flex items-center gap-1 text-sm text-brand-green-600 hover:text-brand-green-700 font-medium"
    >
      {copied ? (
        <>
          <Check className="w-4 h-4" />
          Copied!
        </>
      ) : (
        <>
          <Copy className="w-4 h-4" />
          Copy
        </>
      )}
    </button>
  )
}

// Stats Card Component
function StatCard({
  icon: Icon,
  label,
  value,
  subtext,
  colorClass
}: {
  icon: typeof DollarSign
  label: string
  value: string | number
  subtext?: string
  colorClass: string
}) {
  return (
    <div className="bg-white rounded-xl border border-neutral-200 p-4 sm:p-5 hover:shadow-md hover:border-brand-green-200 transition-all">
      <div className="flex items-start justify-between mb-2 sm:mb-3">
        <div className={cn('w-9 h-9 sm:w-10 sm:h-10 rounded-lg flex items-center justify-center', colorClass)}>
          <Icon className="w-4 h-4 sm:w-5 sm:h-5" />
        </div>
      </div>
      <p className="text-xl sm:text-2xl md:text-3xl font-bold text-neutral-900">{value}</p>
      <p className="text-xs sm:text-sm text-neutral-500 mt-1">{label}</p>
      {subtext && <p className="text-xs text-neutral-400 mt-1">{subtext}</p>}
    </div>
  )
}

// Sponsorship Item Component
function SponsorshipItem({ sponsorship }: { sponsorship: Sponsorship }) {
  return (
    <div className="p-4 flex items-center justify-between hover:bg-neutral-50 transition-colors">
      <div className="flex items-center gap-3 sm:gap-4">
        <div className={cn(
          'w-10 h-10 sm:w-12 sm:h-12 rounded-xl flex items-center justify-center flex-shrink-0',
          sponsorship.status === 'cleaned' ? 'bg-brand-green-100' : 'bg-brand-green-100'
        )}>
          {sponsorship.status === 'cleaned' ? (
            <CheckCircle2 className="w-5 h-5 sm:w-6 sm:h-6 text-brand-green-600" />
          ) : (
            <Clock className="w-5 h-5 sm:w-6 sm:h-6 text-brand-green-600" />
          )}
        </div>
        <div className="min-w-0">
          <p className="font-semibold text-neutral-900 truncate">{sponsorship.blockCode}</p>
          <p className="text-sm text-neutral-500 truncate">{sponsorship.blockName}</p>
          <p className="text-xs text-neutral-400 mt-0.5">
            {formatDate(sponsorship.createdAt)}
            {sponsorship.cleanedAt && ` • Cleaned ${formatDate(sponsorship.cleanedAt)}`}
          </p>
        </div>
      </div>
      <div className="text-right flex-shrink-0 ml-2">
        <p className="font-semibold text-neutral-900">{formatCurrency(sponsorship.amount)}</p>
        <span className={cn(
          'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium mt-1',
          sponsorship.status === 'cleaned' ? 'bg-brand-green-100 text-brand-green-700' :
          sponsorship.status === 'pending' ? 'bg-amber-100 text-amber-700' :
          'bg-neutral-100 text-neutral-700'
        )}>
          {sponsorship.status === 'cleaned' ? '✓ Cleaned' :
           sponsorship.status === 'pending' ? 'Awaiting' : 'Completed'}
        </span>
      </div>
    </div>
  )
}

// Impact Card Component
function ImpactCard({ metric }: { metric: typeof impactMetrics[0] }) {
  const Icon = metric.icon
  return (
    <div className="bg-white rounded-xl border border-neutral-200 p-4 sm:p-5">
      <div className={cn('w-10 h-10 rounded-lg flex items-center justify-center mb-3', metric.color)}>
        <Icon className="w-5 h-5" />
      </div>
      <h3 className="font-semibold text-neutral-900 mb-1">{metric.title}</h3>
      <p className="text-sm text-neutral-600 leading-relaxed">{metric.description}</p>
    </div>
  )
}

export default function SponsorDashboardPage() {
  const router = useRouter()
  const [profile, setProfile] = useState<SponsorProfile | null>(null)
  const [sponsorships, setSponsorships] = useState<Sponsorship[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [activeTab, setActiveTab] = useState<'overview' | 'history' | 'impact' | 'settings'>('overview')

  // Settings state
  const [settings, setSettings] = useState({
    wantsUpdates: true,
    isAnonymous: false,
    frequency: 'one_time' as const,
  })
  const [isSavingSettings, setIsSavingSettings] = useState(false)
  const [settingsSuccess, setSettingsSuccess] = useState(false)

  useEffect(() => {
    fetchDashboardData()
  }, [])

  const fetchDashboardData = async () => {
    try {
      const [profileRes, sponsorshipsRes] = await Promise.all([
        fetch('/api/sponsors/me'),
        fetch('/api/sponsors/sponsorships'),
      ])

      if (profileRes.status === 401) {
        router.push('/sponsor/login')
        return
      }

      if (profileRes.ok) {
        const profileData = await profileRes.json()
        if (profileData.success) {
          setProfile(profileData.sponsor)
          setSettings({
            wantsUpdates: profileData.sponsor.preferences?.wantsUpdates !== false,
            isAnonymous: profileData.sponsor.preferences?.isAnonymous || false,
            frequency: profileData.sponsor.preferences?.frequency || 'one_time',
          })
        }
      }

      if (sponsorshipsRes.ok) {
        const sponsorshipsData = await sponsorshipsRes.json()
        setSponsorships(sponsorshipsData.sponsorships || [])
      }
    } catch (error) {
      console.error('Failed to fetch dashboard data:', error)
    } finally {
      setIsLoading(false)
    }
  }

  const handleSaveSettings = async () => {
    setIsSavingSettings(true)
    setSettingsSuccess(false)

    try {
      const response = await fetch('/api/sponsors/settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings),
      })

      if (response.ok) {
        setSettingsSuccess(true)
        setTimeout(() => setSettingsSuccess(false), 3000)
      }
    } catch (error) {
      console.error('Failed to save settings:', error)
    } finally {
      setIsSavingSettings(false)
    }
  }

  const handleLogout = async () => {
    try {
      await fetch('/api/sponsors/logout', { method: 'POST' })
      router.push('/sponsor/login')
    } catch (error) {
      console.error('Logout failed:', error)
    }
  }

  if (isLoading) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-brand-green-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-neutral-500">Loading dashboard...</p>
        </div>
      </div>
    )
  }

  if (!profile) {
    return (
      <div className="min-h-screen bg-neutral-50 flex items-center justify-center">
        <div className="text-center">
          <p className="text-neutral-600 mb-4">Unable to load profile</p>
          <Link href="/sponsor/login" className="text-brand-green-600 hover:text-brand-green-700 font-medium">
            Return to Login
          </Link>
        </div>
      </div>
    )
  }

  const tier = tierConfig[profile.earlyAdopterTier || 'standard']
  const TierIcon = tier.icon
  const cleanedCount = sponsorships.filter(s => s.status === 'cleaned').length
  const pendingCount = sponsorships.filter(s => s.status === 'pending').length

  const tabs = [
    { id: 'overview', label: 'Overview', icon: LayoutDashboard },
    { id: 'history', label: 'History', icon: History },
    { id: 'impact', label: 'Impact', icon: Heart },
    { id: 'settings', label: 'Settings', icon: Settings },
  ]

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 pb-24 sm:pb-8">
      {/* Header */}
      <header className="bg-white/90 backdrop-blur-sm border-b border-neutral-100 sticky top-0 z-10">
        <div className="max-w-4xl mx-auto px-4 py-3 sm:py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Logo variant="short" size="sm" />
            <div className="hidden sm:block">
              <p className="text-sm font-medium text-neutral-900">{profile.name}</p>
              <p className="text-xs text-neutral-500">
                {profile.business?.name || tier.label}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Link
              href="/sponsor"
              className="inline-flex items-center gap-2 px-3 sm:px-4 py-2 rounded-xl bg-brand-green-500 text-white font-medium hover:bg-brand-green-600 transition-colors text-sm"
            >
              <Plus className="w-4 h-4" />
              <span className="hidden sm:inline">Sponsor a Block</span>
              <span className="sm:hidden">Sponsor</span>
            </Link>
            <button
              onClick={handleLogout}
              className="p-2 text-neutral-500 hover:text-neutral-700 hover:bg-neutral-100 rounded-lg transition-colors"
              title="Log out"
            >
              <LogOut className="w-5 h-5" />
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-4 py-4 sm:py-6">
        {/* Welcome Banner */}
        <div className="bg-gradient-to-r from-brand-green-500 to-brand-green-600 rounded-2xl p-5 sm:p-6 mb-4 sm:mb-6 text-white relative overflow-hidden">
          <div className="absolute top-0 right-0 w-32 h-32 bg-white/10 rounded-full -translate-y-1/2 translate-x-1/2" />
          <div className="absolute bottom-0 left-0 w-24 h-24 bg-white/5 rounded-full translate-y-1/2 -translate-x-1/2" />

          <div className="relative z-10">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
              <div className="flex items-center gap-3 sm:gap-4">
                <div className={cn('w-12 h-12 sm:w-14 sm:h-14 rounded-xl bg-gradient-to-br flex items-center justify-center', tier.color)}>
                  <TierIcon className="w-6 h-6 sm:w-7 sm:h-7 text-white" />
                </div>
                <div>
                  <h1 className="text-lg sm:text-xl font-bold">Welcome back, {profile.name.split(' ')[0]}!</h1>
                  <div className="flex flex-wrap items-center gap-2 mt-1">
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-white/20 text-sm font-medium">
                      {tier.emoji} {tier.label}
                    </span>
                    {profile.signupRankings?.global && (
                      <span className="text-brand-green-100 text-sm">#{profile.signupRankings.global} Global</span>
                    )}
                  </div>
                </div>
              </div>
              {profile.activeSponsorship && (
                <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-white/20 self-start sm:self-auto">
                  <span className="relative flex h-2 w-2">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-white opacity-75" />
                    <span className="relative inline-flex rounded-full h-2 w-2 bg-white" />
                  </span>
                  <span className="text-sm font-medium">Active Subscriber</span>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Movement Rankings (for early adopters) */}
        {profile.signupRankings?.global && (
          <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden mb-4 sm:mb-6">
            <div className="px-4 sm:px-6 py-3 sm:py-4 border-b border-neutral-100 bg-gradient-to-r from-neutral-50 to-white">
              <h2 className="font-semibold text-neutral-900 flex items-center gap-2">
                <Trophy className="w-5 h-5 text-brand-green-500" />
                Your Movement Ranking
              </h2>
              <p className="text-sm text-neutral-500 mt-1">
                You're part of history! This rank is locked forever.
              </p>
            </div>
            <div className="p-4 sm:p-6">
              <div className="flex justify-center">
                <RankingCard
                  icon={Globe}
                  label="Global Sponsor"
                  rank={profile.signupRankings?.global}
                  colorClass="bg-gradient-to-br from-brand-green-400 to-amber-500"
                  delay={0}
                />
              </div>

              {/* Referral Link */}
              {profile.referralCode && (
                <div className="mt-6 p-4 rounded-xl bg-brand-green-50 border border-brand-green-200">
                  <div className="flex items-center gap-2 mb-2">
                    <Share2 className="w-4 h-4 text-brand-green-700" />
                    <p className="text-sm font-medium text-brand-green-800">Your Referral Link</p>
                  </div>
                  <div className="flex items-center gap-2 bg-white rounded-lg border border-brand-green-200 p-2">
                    <p className="flex-1 font-mono text-sm text-brand-green-900 truncate">
                      globalcommunitycleaners.org/join?ref={profile.referralCode}
                    </p>
                    <CopyButton text={`https://globalcommunitycleaners.org/join?ref=${profile.referralCode}`} />
                  </div>
                  <div className="flex gap-2 mt-3">
                    <a
                      href={`sms:?body=${encodeURIComponent(`Help clean up Chicago! GCC pays $100 per block cleaned. Sponsor a block or become a worker: https://globalcommunitycleaners.org/join?ref=${profile.referralCode}`)}`}
                      className="flex-1 flex items-center justify-center gap-2 py-2 px-3 bg-brand-green-600 text-white rounded-lg text-sm font-medium hover:bg-brand-green-700 transition-colors"
                    >
                      <MessageSquare className="w-4 h-4" />
                      Text
                    </a>
                    <a
                      href={`mailto:?subject=${encodeURIComponent('Support Clean Streets in Chicago!')}&body=${encodeURIComponent(`Hi!\n\nI've been supporting Global Community Cleaners (GCC) - they pay North Lawndale residents $100 to clean blocks in their neighborhood.\n\nYou can sponsor a block or join the movement here: https://globalcommunitycleaners.org/join?ref=${profile.referralCode}\n\n100% of sponsorships go directly to the workers!`)}`}
                      className="flex-1 flex items-center justify-center gap-2 py-2 px-3 bg-brand-green-600 text-white rounded-lg text-sm font-medium hover:bg-brand-green-700 transition-colors"
                    >
                      <Mail className="w-4 h-4" />
                      Email
                    </a>
                  </div>
                  <p className="text-xs text-brand-green-700 mt-3 text-center">
                    Share with friends and help grow the movement!
                  </p>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Stats Grid */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 sm:gap-4 mb-4 sm:mb-6">
          <StatCard
            icon={DollarSign}
            label="Total Contributed"
            value={formatCurrency(profile.stats?.totalContributed || 0)}
            colorClass="bg-brand-green-100 text-brand-green-600"
          />
          <StatCard
            icon={MapPin}
            label="Blocks Funded"
            value={profile.stats?.blocksFunded || 0}
            colorClass="bg-brand-green-100 text-brand-green-600"
          />
          <StatCard
            icon={CheckCircle2}
            label="Blocks Cleaned"
            value={cleanedCount}
            colorClass="bg-brand-blue-100 text-brand-blue-600"
          />
          <StatCard
            icon={Clock}
            label="Awaiting Cleaning"
            value={pendingCount}
            colorClass="bg-amber-100 text-amber-600"
          />
        </div>

        {/* Tabs */}
        <div className="flex gap-1 mb-4 sm:mb-6 p-1 bg-neutral-100 rounded-xl overflow-x-auto">
          {tabs.map((tab) => {
            const Icon = tab.icon
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                className={cn(
                  'flex items-center gap-1.5 sm:gap-2 px-3 sm:px-4 py-2 sm:py-2.5 rounded-lg font-medium text-xs sm:text-sm whitespace-nowrap transition-all flex-1 sm:flex-none justify-center sm:justify-start',
                  activeTab === tab.id
                    ? 'bg-white text-neutral-900 shadow-sm'
                    : 'text-neutral-500 hover:text-neutral-700'
                )}
              >
                <Icon className="w-4 h-4" />
                <span className="hidden sm:inline">{tab.label}</span>
              </button>
            )
          })}
        </div>

        {/* Tab Content */}
        {activeTab === 'overview' && (
          <div className="space-y-4 sm:space-y-6">
            {/* Recent Sponsorships */}
            <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden">
              <div className="px-4 sm:px-6 py-3 sm:py-4 border-b border-neutral-100 flex items-center justify-between">
                <div>
                  <h2 className="font-semibold text-neutral-900">Recent Sponsorships</h2>
                  <p className="text-sm text-neutral-500 hidden sm:block">Your latest contributions</p>
                </div>
                <button
                  onClick={() => setActiveTab('history')}
                  className="text-brand-green-600 hover:text-brand-green-700 text-sm font-medium flex items-center gap-1"
                >
                  View All
                  <ChevronRight className="w-4 h-4" />
                </button>
              </div>

              {sponsorships.length === 0 ? (
                <div className="p-6 sm:p-8 text-center">
                  <div className="w-14 h-14 sm:w-16 sm:h-16 mx-auto mb-4 rounded-2xl bg-brand-green-100 flex items-center justify-center">
                    <Gift className="w-7 h-7 sm:w-8 sm:h-8 text-brand-green-600" />
                  </div>
                  <p className="text-neutral-700 font-medium mb-2">No sponsorships yet</p>
                  <p className="text-sm text-neutral-500 mb-4">
                    Sponsor your first block and start making a difference!
                  </p>
                  <Link
                    href="/sponsor"
                    className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-brand-green-500 text-white font-medium hover:bg-brand-green-600 transition-colors"
                  >
                    <Plus className="w-4 h-4" />
                    Sponsor a Block
                  </Link>
                </div>
              ) : (
                <div className="divide-y divide-neutral-100">
                  {sponsorships.slice(0, 3).map((sponsorship) => (
                    <SponsorshipItem key={sponsorship.id} sponsorship={sponsorship} />
                  ))}
                </div>
              )}
            </div>

            {/* Quick Actions */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 sm:gap-4">
              <Link
                href="/sponsor"
                className="bg-white rounded-xl border border-neutral-200 p-4 sm:p-5 flex items-center gap-3 sm:gap-4 hover:shadow-md hover:border-brand-green-200 transition-all group"
              >
                <div className="w-11 h-11 sm:w-12 sm:h-12 rounded-xl bg-brand-green-100 flex items-center justify-center group-hover:scale-110 transition-transform flex-shrink-0">
                  <Plus className="w-5 h-5 sm:w-6 sm:h-6 text-brand-green-600" />
                </div>
                <div className="min-w-0">
                  <p className="font-semibold text-neutral-900">Sponsor Another Block</p>
                  <p className="text-sm text-neutral-500">$100 goes directly to a worker</p>
                </div>
                <ChevronRight className="w-5 h-5 text-neutral-400 ml-auto flex-shrink-0" />
              </Link>

              <Link
                href="/map"
                className="bg-white rounded-xl border border-neutral-200 p-4 sm:p-5 flex items-center gap-3 sm:gap-4 hover:shadow-md hover:border-brand-green-200 transition-all group"
              >
                <div className="w-11 h-11 sm:w-12 sm:h-12 rounded-xl bg-brand-green-100 flex items-center justify-center group-hover:scale-110 transition-transform flex-shrink-0">
                  <MapPin className="w-5 h-5 sm:w-6 sm:h-6 text-brand-green-600" />
                </div>
                <div className="min-w-0">
                  <p className="font-semibold text-neutral-900">View Block Map</p>
                  <p className="text-sm text-neutral-500">See all funded and cleaned blocks</p>
                </div>
                <ChevronRight className="w-5 h-5 text-neutral-400 ml-auto flex-shrink-0" />
              </Link>

              <Link
                href="/community"
                className="bg-white rounded-xl border border-neutral-200 p-4 sm:p-5 flex items-center gap-3 sm:gap-4 hover:shadow-md hover:border-brand-blue-200 transition-all group"
              >
                <div className="w-11 h-11 sm:w-12 sm:h-12 rounded-xl bg-brand-blue-100 flex items-center justify-center group-hover:scale-110 transition-transform flex-shrink-0">
                  <Users className="w-5 h-5 sm:w-6 sm:h-6 text-brand-blue-600" />
                </div>
                <div className="min-w-0">
                  <p className="font-semibold text-neutral-900">Community Forum</p>
                  <p className="text-sm text-neutral-500">Join the discussion with workers</p>
                </div>
                <ChevronRight className="w-5 h-5 text-neutral-400 ml-auto flex-shrink-0" />
              </Link>

              <Link
                href="/support"
                className="bg-white rounded-xl border border-neutral-200 p-4 sm:p-5 flex items-center gap-3 sm:gap-4 hover:shadow-md hover:border-purple-200 transition-all group"
              >
                <div className="w-11 h-11 sm:w-12 sm:h-12 rounded-xl bg-purple-100 flex items-center justify-center group-hover:scale-110 transition-transform flex-shrink-0">
                  <HelpCircle className="w-5 h-5 sm:w-6 sm:h-6 text-purple-600" />
                </div>
                <div className="min-w-0">
                  <p className="font-semibold text-neutral-900">Support & Feedback</p>
                  <p className="text-sm text-neutral-500">Get help or share suggestions</p>
                </div>
                <ChevronRight className="w-5 h-5 text-neutral-400 ml-auto flex-shrink-0" />
              </Link>
            </div>
          </div>
        )}

        {activeTab === 'history' && (
          <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden">
            <div className="px-4 sm:px-6 py-3 sm:py-4 border-b border-neutral-100">
              <h2 className="font-semibold text-neutral-900">Sponsorship History</h2>
              <p className="text-sm text-neutral-500">All your past contributions</p>
            </div>
            {sponsorships.length === 0 ? (
              <div className="p-8 text-center text-neutral-500">
                No sponsorship history yet
              </div>
            ) : (
              <div className="divide-y divide-neutral-100">
                {sponsorships.map((sponsorship) => (
                  <SponsorshipItem key={sponsorship.id} sponsorship={sponsorship} />
                ))}
              </div>
            )}
          </div>
        )}

        {activeTab === 'impact' && (
          <div className="space-y-4 sm:space-y-6">
            {/* Impact Summary */}
            <div className="bg-gradient-to-br from-brand-green-500 to-brand-green-600 rounded-2xl p-5 sm:p-6 text-white">
              <h2 className="font-semibold text-lg mb-4 flex items-center gap-2">
                <Sparkles className="w-5 h-5" />
                Your Total Impact
              </h2>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-3 sm:gap-4">
                <div className="bg-white/10 rounded-xl p-3 sm:p-4 text-center backdrop-blur-sm">
                  <p className="text-2xl sm:text-3xl font-bold">{profile.stats?.blocksFunded || 0}</p>
                  <p className="text-xs sm:text-sm text-brand-green-100">Blocks Funded</p>
                </div>
                <div className="bg-white/10 rounded-xl p-3 sm:p-4 text-center backdrop-blur-sm">
                  <p className="text-2xl sm:text-3xl font-bold">{cleanedCount}</p>
                  <p className="text-xs sm:text-sm text-brand-green-100">Streets Cleaned</p>
                </div>
                <div className="bg-white/10 rounded-xl p-3 sm:p-4 text-center backdrop-blur-sm col-span-2 sm:col-span-1">
                  <p className="text-2xl sm:text-3xl font-bold">{cleanedCount}</p>
                  <p className="text-xs sm:text-sm text-brand-green-100">Workers Paid</p>
                </div>
              </div>
              <p className="text-brand-green-100 text-sm mt-4 text-center">
                Thank you for supporting cleaner streets in North Lawndale!
              </p>
            </div>

            {/* How Your Money Helps */}
            <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden">
              <div className="px-4 sm:px-6 py-3 sm:py-4 border-b border-neutral-100">
                <h2 className="font-semibold text-neutral-900">How Your Money Helps</h2>
                <p className="text-sm text-neutral-500">Understanding your impact</p>
              </div>
              <div className="p-4 sm:p-6">
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 sm:gap-4">
                  {impactMetrics.map((metric, index) => (
                    <ImpactCard key={index} metric={metric} />
                  ))}
                </div>
              </div>
            </div>

            {/* Transparency Note */}
            <div className="bg-brand-green-50 rounded-xl border border-brand-green-200 p-4 sm:p-5">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-lg bg-brand-green-100 flex items-center justify-center flex-shrink-0">
                  <Target className="w-5 h-5 text-brand-green-600" />
                </div>
                <div>
                  <h3 className="font-semibold text-brand-green-900 mb-1">100% Transparent</h3>
                  <p className="text-sm text-brand-green-700">
                    Every $100 you sponsor goes directly to a worker. GCC operates on separate funding for operations, so your entire contribution funds employment in North Lawndale.
                  </p>
                </div>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'settings' && (
          <div className="space-y-4 sm:space-y-6">
            {/* Notification Preferences */}
            <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden">
              <div className="px-4 sm:px-6 py-3 sm:py-4 border-b border-neutral-100">
                <h2 className="font-semibold text-neutral-900">Notification Preferences</h2>
                <p className="text-sm text-neutral-500">Control how we communicate with you</p>
              </div>
              <div className="p-4 sm:p-6 space-y-3 sm:space-y-4">
                <label className="flex items-center justify-between p-3 sm:p-4 rounded-xl bg-neutral-50 cursor-pointer hover:bg-neutral-100 transition-colors">
                  <div className="flex items-center gap-3">
                    {settings.wantsUpdates ? (
                      <Bell className="w-5 h-5 text-brand-green-600" />
                    ) : (
                      <BellOff className="w-5 h-5 text-neutral-400" />
                    )}
                    <div>
                      <p className="font-medium text-neutral-900 text-sm sm:text-base">Cleaning Updates</p>
                      <p className="text-xs sm:text-sm text-neutral-500">Get notified when your sponsored blocks are cleaned</p>
                    </div>
                  </div>
                  <button
                    onClick={() => setSettings({ ...settings, wantsUpdates: !settings.wantsUpdates })}
                    className={cn(
                      'w-11 sm:w-12 h-6 rounded-full transition-colors relative flex-shrink-0 ml-2',
                      settings.wantsUpdates ? 'bg-brand-green-500' : 'bg-neutral-300'
                    )}
                  >
                    <span className={cn(
                      'absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform',
                      settings.wantsUpdates ? 'translate-x-5 sm:translate-x-6' : 'translate-x-0.5'
                    )} />
                  </button>
                </label>

                <label className="flex items-center justify-between p-3 sm:p-4 rounded-xl bg-neutral-50 cursor-pointer hover:bg-neutral-100 transition-colors">
                  <div className="flex items-center gap-3">
                    {settings.isAnonymous ? (
                      <EyeOff className="w-5 h-5 text-neutral-400" />
                    ) : (
                      <Eye className="w-5 h-5 text-brand-green-600" />
                    )}
                    <div>
                      <p className="font-medium text-neutral-900 text-sm sm:text-base">Anonymous Donations</p>
                      <p className="text-xs sm:text-sm text-neutral-500">Hide your name from public acknowledgments</p>
                    </div>
                  </div>
                  <button
                    onClick={() => setSettings({ ...settings, isAnonymous: !settings.isAnonymous })}
                    className={cn(
                      'w-11 sm:w-12 h-6 rounded-full transition-colors relative flex-shrink-0 ml-2',
                      settings.isAnonymous ? 'bg-brand-green-500' : 'bg-neutral-300'
                    )}
                  >
                    <span className={cn(
                      'absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform',
                      settings.isAnonymous ? 'translate-x-5 sm:translate-x-6' : 'translate-x-0.5'
                    )} />
                  </button>
                </label>
              </div>
            </div>

            {/* Account Info */}
            <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden">
              <div className="px-4 sm:px-6 py-3 sm:py-4 border-b border-neutral-100">
                <h2 className="font-semibold text-neutral-900">Account Information</h2>
              </div>
              <div className="p-4 sm:p-6 space-y-3 sm:space-y-4">
                <div className="flex items-center justify-between py-2 sm:py-3 border-b border-neutral-100">
                  <span className="text-neutral-500 text-sm">Name</span>
                  <span className="font-medium text-neutral-900 text-sm sm:text-base">{profile.name}</span>
                </div>
                <div className="flex items-center justify-between py-2 sm:py-3 border-b border-neutral-100">
                  <span className="text-neutral-500 text-sm">Email</span>
                  <span className="font-medium text-neutral-900 text-sm sm:text-base truncate ml-4">{profile.email}</span>
                </div>
                <div className="flex items-center justify-between py-2 sm:py-3 border-b border-neutral-100">
                  <span className="text-neutral-500 text-sm">Account Type</span>
                  <span className="font-medium text-neutral-900 text-sm sm:text-base capitalize">{profile.type}</span>
                </div>
                {profile.business?.name && (
                  <div className="flex items-center justify-between py-2 sm:py-3">
                    <span className="text-neutral-500 text-sm">Organization</span>
                    <span className="font-medium text-neutral-900 text-sm sm:text-base">{profile.business.name}</span>
                  </div>
                )}
              </div>
            </div>

            {/* Save Button */}
            <div className="flex items-center gap-4">
              <button
                onClick={handleSaveSettings}
                disabled={isSavingSettings}
                className="px-5 sm:px-6 py-2.5 sm:py-3 rounded-xl font-semibold bg-brand-green-500 text-white hover:bg-brand-green-600 transition-all disabled:opacity-50 text-sm sm:text-base"
              >
                {isSavingSettings ? 'Saving...' : 'Save Settings'}
              </button>
              {settingsSuccess && (
                <span className="text-brand-green-600 font-medium flex items-center gap-1 text-sm">
                  <CheckCircle2 className="w-4 h-4 sm:w-5 sm:h-5" />
                  Saved!
                </span>
              )}
            </div>

            {/* Support */}
            <div className="bg-neutral-50 rounded-xl border border-neutral-200 p-5 sm:p-6 text-center">
              <p className="text-sm text-neutral-600 mb-3">
                Need help with your account?
              </p>
              <a
                href="mailto:support@globalcommunitycleaners.org"
                className="inline-flex items-center gap-2 text-brand-green-600 hover:text-brand-green-700 font-medium text-sm sm:text-base"
              >
                <HelpCircle className="w-4 h-4" />
                support@globalcommunitycleaners.org
                <ExternalLink className="w-3 h-3" />
              </a>
            </div>
          </div>
        )}
      </main>

      {/* Mobile Bottom Navigation */}
      <nav className="fixed bottom-0 left-0 right-0 bg-white border-t border-neutral-200 sm:hidden safe-bottom z-10">
        <div className="px-4 py-2">
          <div className="flex justify-around">
            <button
              onClick={() => setActiveTab('overview')}
              className={cn(
                'flex flex-col items-center gap-1 py-2 px-4',
                activeTab === 'overview' ? 'text-brand-green-600' : 'text-neutral-400'
              )}
            >
              <div className={cn(
                'w-10 h-10 rounded-xl flex items-center justify-center',
                activeTab === 'overview' ? 'bg-brand-green-100' : 'bg-neutral-50'
              )}>
                <Home className="w-5 h-5" />
              </div>
              <span className="text-xs font-medium">Home</span>
            </button>
            <button
              onClick={() => setActiveTab('history')}
              className={cn(
                'flex flex-col items-center gap-1 py-2 px-4',
                activeTab === 'history' ? 'text-brand-green-600' : 'text-neutral-400'
              )}
            >
              <div className={cn(
                'w-10 h-10 rounded-xl flex items-center justify-center',
                activeTab === 'history' ? 'bg-brand-green-100' : 'bg-neutral-50'
              )}>
                <History className="w-5 h-5" />
              </div>
              <span className="text-xs font-medium">History</span>
            </button>
            <button
              onClick={() => setActiveTab('impact')}
              className={cn(
                'flex flex-col items-center gap-1 py-2 px-4',
                activeTab === 'impact' ? 'text-brand-green-600' : 'text-neutral-400'
              )}
            >
              <div className={cn(
                'w-10 h-10 rounded-xl flex items-center justify-center',
                activeTab === 'impact' ? 'bg-brand-green-100' : 'bg-neutral-50'
              )}>
                <TrendingUp className="w-5 h-5" />
              </div>
              <span className="text-xs font-medium">Impact</span>
            </button>
            <button
              onClick={() => setActiveTab('settings')}
              className={cn(
                'flex flex-col items-center gap-1 py-2 px-4',
                activeTab === 'settings' ? 'text-brand-green-600' : 'text-neutral-400'
              )}
            >
              <div className={cn(
                'w-10 h-10 rounded-xl flex items-center justify-center',
                activeTab === 'settings' ? 'bg-brand-green-100' : 'bg-neutral-50'
              )}>
                <User className="w-5 h-5" />
              </div>
              <span className="text-xs font-medium">Profile</span>
            </button>
          </div>
        </div>
      </nav>
    </div>
  )
}
