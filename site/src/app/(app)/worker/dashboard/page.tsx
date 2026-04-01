'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { cn } from '@/lib/utils'
import { Logo } from '@/components/brand/Logo'
import {
  Clock,
  Mail,
  CheckCircle2,
  AlertCircle,
  FileText,
  CreditCard,
  MapPin,
  HelpCircle,
  ChevronDown,
  ChevronUp,
  ExternalLink,
  Sparkles,
  Shield,
  DollarSign,
  Users,
  Trophy,
  Globe,
  Building2,
  Home,
  Star,
  Crown,
  Zap,
  Copy,
  Check,
  Bell,
  User,
  ClipboardList,
  Award,
  TrendingUp,
  ChevronRight,
  LogOut,
  MessageSquare,
  Share2,
} from 'lucide-react'

/** Format street name to include 1500 block number for Roosevelt Rd */
function formatStreetName(streetName: string | undefined | null): string {
  if (!streetName) return ''
  if (streetName.includes('1500') || !streetName.toLowerCase().includes('roosevelt')) {
    return streetName
  }
  return streetName.replace(/^W\s+Roosevelt/i, '1500 W Roosevelt')
}

// Types
interface WorkerProfile {
  id: number
  name: string
  email: string
  phone: string
  emailVerified: boolean
  emailVerifiedAt: string | null
  verificationStatus: 'pending' | 'approved' | 'rejected' | 'needs_info'
  verificationNotes?: string
  address: {
    street: string
    city: string
    state: string
    zip: string
    isNorthLawndale: boolean
  }
  pendingAddressChange: {
    street: string
    city: string
    state: string
    zip: string
    requestedAt: string
  } | null
  lastAddressChangeAt?: string
  stripeOnboardingStatus: 'not_started' | 'in_progress' | 'completed' | 'restricted'
  signupRankings: {
    global: number | null
    country: number | null
    state: number | null
    city: number | null
    neighborhood?: number | null
  }
  earlyAdopterTier: 'founder' | 'pioneer' | 'early' | 'standard'
  referralCode: string
  stats: {
    jobsCompleted: number
    totalEarnings: number
    averageRating: number | null
    lastJobAt: string | null
  }
  notifications: {
    pushEnabled: boolean
    smsEnabled: boolean
    emailEnabled: boolean
  }
  createdAt: string
}

// Tier configuration
const tierConfig = {
  founder: {
    label: 'Founder',
    emoji: '👑',
    color: 'from-purple-500 to-violet-600',
    bgColor: 'bg-purple-100',
    textColor: 'text-purple-700',
    borderColor: 'border-purple-200',
    description: 'First 10 members',
    icon: Crown,
  },
  pioneer: {
    label: 'Pioneer',
    emoji: '⭐',
    color: 'from-brand-green-400 to-amber-500',
    bgColor: 'bg-brand-green-100',
    textColor: 'text-brand-green-700',
    borderColor: 'border-brand-green-200',
    description: 'First 100 members',
    icon: Star,
  },
  early: {
    label: 'Early Adopter',
    emoji: '✨',
    color: 'from-brand-green-400 to-emerald-500',
    bgColor: 'bg-brand-green-100',
    textColor: 'text-brand-green-700',
    borderColor: 'border-brand-green-200',
    description: 'First 1,000 members',
    icon: Zap,
  },
  standard: {
    label: 'Member',
    emoji: '🧹',
    color: 'from-neutral-400 to-neutral-500',
    bgColor: 'bg-neutral-100',
    textColor: 'text-neutral-700',
    borderColor: 'border-neutral-200',
    description: 'Part of the movement',
    icon: Users,
  },
}

// Verification steps configuration
const verificationSteps = [
  {
    id: 1,
    title: 'Email Verified',
    description: 'Your email address has been confirmed',
    icon: Mail,
    getStatus: (profile: WorkerProfile) => profile.emailVerified ? 'completed' : 'current',
  },
  {
    id: 2,
    title: 'Documents Under Review',
    description: 'ID and proof of address being verified',
    icon: Shield,
    getStatus: (profile: WorkerProfile) => {
      if (!profile.emailVerified) return 'upcoming'
      if (profile.verificationStatus === 'approved') return 'completed'
      if (profile.verificationStatus === 'pending') return 'current'
      return 'current'
    },
  },
  {
    id: 3,
    title: 'Account Approved',
    description: 'Receive your worker certification',
    icon: CheckCircle2,
    getStatus: (profile: WorkerProfile) => {
      if (profile.verificationStatus === 'approved') return 'completed'
      return 'upcoming'
    },
  },
  {
    id: 4,
    title: 'Set Up Payouts',
    description: 'Connect your bank via Stripe',
    icon: CreditCard,
    getStatus: (profile: WorkerProfile) => {
      if (profile.verificationStatus !== 'approved') return 'upcoming'
      if (profile.stripeOnboardingStatus === 'completed') return 'completed'
      return 'current'
    },
  },
  {
    id: 5,
    title: 'Start Earning',
    description: 'Claim blocks and earn $100 each',
    icon: DollarSign,
    getStatus: (profile: WorkerProfile) => {
      if (profile.stripeOnboardingStatus === 'completed') return 'completed'
      return 'upcoming'
    },
  },
]

// FAQ items
const faqs = [
  {
    question: 'How long does verification take?',
    answer: 'Most applications are reviewed within 24-48 hours. You\'ll receive an email notification as soon as your status changes.'
  },
  {
    question: 'What documents are being verified?',
    answer: 'We verify your government-issued ID and proof of North Lawndale residency (utility bill, bank statement, or lease from the past 90 days).'
  },
  {
    question: 'What if my documents are rejected?',
    answer: 'You\'ll receive a detailed email explaining what needs to be corrected. You can resubmit updated documents through the link in your email.'
  },
  {
    question: 'How much will I earn?',
    answer: 'Each block pays $100, deposited directly to your bank account within 30 minutes of job completion. There are no fees deducted from your pay.'
  },
]

// Components
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

function StepIndicator({
  step,
  status,
  isLast
}: {
  step: typeof verificationSteps[0]
  status: 'completed' | 'current' | 'upcoming'
  isLast: boolean
}) {
  const Icon = step.icon

  return (
    <div className="flex items-start gap-4">
      <div className="flex flex-col items-center">
        <div className={cn(
          'w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 transition-all',
          status === 'completed' && 'bg-brand-green-500 text-white',
          status === 'current' && 'bg-brand-green-500 text-white ring-4 ring-brand-green-100',
          status === 'upcoming' && 'bg-neutral-100 text-neutral-400'
        )}>
          {status === 'completed' ? (
            <CheckCircle2 className="w-5 h-5" />
          ) : (
            <Icon className="w-5 h-5" />
          )}
        </div>
        {!isLast && (
          <div className={cn(
            'w-0.5 h-10 mt-2',
            status === 'completed' ? 'bg-brand-green-300' : 'bg-neutral-200'
          )} />
        )}
      </div>

      <div className="flex-1 pb-4">
        <div className="flex items-center gap-2">
          <h3 className={cn(
            'font-semibold',
            status === 'current' ? 'text-brand-green-700' : 'text-neutral-900'
          )}>
            {step.title}
          </h3>
          {status === 'current' && (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-brand-green-100 text-brand-green-700 animate-pulse">
              In Progress
            </span>
          )}
        </div>
        <p className="text-sm text-neutral-500 mt-0.5">{step.description}</p>
      </div>
    </div>
  )
}

function FAQItem({ question, answer }: { question: string; answer: string }) {
  const [isOpen, setIsOpen] = useState(false)

  return (
    <div className="border-b border-neutral-100 last:border-0">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between py-4 text-left hover:text-brand-green-700 transition-colors"
      >
        <span className="font-medium text-neutral-900 pr-4 text-sm">{question}</span>
        {isOpen ? (
          <ChevronUp className="w-5 h-5 text-neutral-400 flex-shrink-0" />
        ) : (
          <ChevronDown className="w-5 h-5 text-neutral-400 flex-shrink-0" />
        )}
      </button>
      <div className={cn(
        'overflow-hidden transition-all duration-300',
        isOpen ? 'max-h-48 pb-4' : 'max-h-0'
      )}>
        <p className="text-sm text-neutral-600 leading-relaxed">{answer}</p>
      </div>
    </div>
  )
}

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
      className="inline-flex items-center gap-1 text-sm text-brand-green-600 hover:text-brand-green-700"
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

// Pending Dashboard Component
function PendingDashboard({ profile }: { profile: WorkerProfile }) {
  const tier = tierConfig[profile.earlyAdopterTier || 'standard']
  const TierIcon = tier.icon

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 pb-24">
      {/* Header */}
      <header className="bg-white/90 backdrop-blur-sm border-b border-neutral-100 sticky top-0 z-10">
        <div className="max-w-2xl mx-auto px-4 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Logo variant="short" size="sm" />
            <div className="hidden sm:block">
              <p className="text-sm font-medium text-neutral-900">{profile.name}</p>
              <p className="text-xs text-neutral-500">Application Pending</p>
            </div>
          </div>
          <Link
            href="/worker/profile"
            className="w-10 h-10 rounded-full bg-neutral-100 flex items-center justify-center hover:bg-neutral-200 transition-colors"
          >
            <User className="w-5 h-5 text-neutral-600" />
          </Link>
        </div>
      </header>

      <main className="max-w-2xl mx-auto px-4 py-6">
        {/* Welcome Banner */}
        <div className="bg-gradient-to-r from-brand-green-500 to-brand-green-600 rounded-2xl p-6 mb-6 text-white relative overflow-hidden">
          <div className="absolute top-0 right-0 w-32 h-32 bg-white/10 rounded-full -translate-y-1/2 translate-x-1/2" />
          <div className="absolute bottom-0 left-0 w-24 h-24 bg-white/5 rounded-full translate-y-1/2 -translate-x-1/2" />

          <div className="relative z-10">
            <div className="flex items-center gap-3 mb-4">
              <div className={cn('w-14 h-14 rounded-xl bg-gradient-to-br flex items-center justify-center', tier.color)}>
                <TierIcon className="w-7 h-7 text-white" />
              </div>
              <div>
                <h1 className="text-xl font-bold">Welcome, {profile.name.split(' ')[0]}!</h1>
                <div className="flex items-center gap-2 mt-1">
                  <span className="text-brand-green-100">You're a</span>
                  <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-white/20 text-sm font-semibold">
                    {tier.emoji} {tier.label}
                  </span>
                </div>
              </div>
            </div>
            <p className="text-brand-green-100 text-sm">
              Your application is being reviewed. We'll notify you by email within 24-48 hours.
            </p>
          </div>
        </div>

        {/* Your Rankings */}
        <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden mb-6">
          <div className="px-6 py-4 border-b border-neutral-100 bg-gradient-to-r from-neutral-50 to-white">
            <h2 className="font-semibold text-neutral-900 flex items-center gap-2">
              <Trophy className="w-5 h-5 text-brand-green-500" />
              Your Movement Rankings
            </h2>
            <p className="text-sm text-neutral-500 mt-1">
              You're part of history! These ranks are locked forever.
            </p>
          </div>
          <div className="p-6">
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <RankingCard
                icon={Globe}
                label="Global"
                rank={profile.signupRankings?.global}
                colorClass="bg-gradient-to-br from-brand-green-400 to-amber-500"
                delay={0}
              />
              <RankingCard
                icon={Building2}
                label="Illinois"
                rank={profile.signupRankings?.state}
                colorClass="bg-gradient-to-br from-brand-green-400 to-emerald-500"
                delay={100}
              />
              <RankingCard
                icon={MapPin}
                label="Chicago"
                rank={profile.signupRankings?.city}
                colorClass="bg-gradient-to-br from-brand-blue-400 to-blue-500"
                delay={200}
              />
              <RankingCard
                icon={Home}
                label="N. Lawndale"
                rank={profile.signupRankings?.neighborhood || profile.signupRankings?.city}
                colorClass="bg-gradient-to-br from-purple-400 to-violet-500"
                delay={300}
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
                    href={`sms:?body=${encodeURIComponent(`Join me in the GCC movement! Earn $100 cleaning blocks in your neighborhood: https://globalcommunitycleaners.org/join?ref=${profile.referralCode}`)}`}
                    className="flex-1 flex items-center justify-center gap-2 py-2 px-3 bg-brand-green-600 text-white rounded-lg text-sm font-medium hover:bg-brand-green-700 transition-colors"
                  >
                    <MessageSquare className="w-4 h-4" />
                    Text
                  </a>
                  <a
                    href={`mailto:?subject=${encodeURIComponent('Join Global Community Cleaners!')}&body=${encodeURIComponent(`Hey!\n\nI joined GCC - they pay $100 to clean blocks in your neighborhood.\n\nSign up here: https://globalcommunitycleaners.org/join?ref=${profile.referralCode}\n\nLet's do this together!`)}`}
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

        {/* Verification Status */}
        <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden mb-6">
          <div className="px-6 py-4 border-b border-neutral-100">
            <div className="flex items-center justify-between">
              <h2 className="font-semibold text-neutral-900 flex items-center gap-2">
                <Shield className="w-5 h-5 text-brand-green-500" />
                Verification Progress
              </h2>
              <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-amber-100 text-amber-700 text-xs font-medium">
                <Clock className="w-3.5 h-3.5" />
                In Review
              </span>
            </div>
          </div>
          <div className="p-6">
            {verificationSteps.map((step, index) => (
              <StepIndicator
                key={step.id}
                step={step}
                status={step.getStatus(profile)}
                isLast={index === verificationSteps.length - 1}
              />
            ))}
          </div>
        </div>

        {/* What to Expect */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-6">
          <div className="bg-white rounded-xl border border-neutral-200 p-5">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-brand-green-100 flex items-center justify-center flex-shrink-0">
                <DollarSign className="w-5 h-5 text-brand-green-600" />
              </div>
              <div>
                <h3 className="font-semibold text-neutral-900">$100 Per Block</h3>
                <p className="text-sm text-neutral-600 mt-1">
                  Paid directly to your bank within 30 minutes of completing each job.
                </p>
              </div>
            </div>
          </div>

          <div className="bg-white rounded-xl border border-neutral-200 p-5">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-brand-blue-100 flex items-center justify-center flex-shrink-0">
                <Mail className="w-5 h-5 text-brand-blue-600" />
              </div>
              <div>
                <h3 className="font-semibold text-neutral-900">Check Your Email</h3>
                <p className="text-sm text-neutral-600 mt-1">
                  We'll notify you immediately when your status changes.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* FAQ */}
        <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden mb-6">
          <div className="px-6 py-4 border-b border-neutral-100 bg-neutral-50">
            <h2 className="font-semibold text-neutral-900 flex items-center gap-2">
              <HelpCircle className="w-5 h-5 text-brand-green-500" />
              Common Questions
            </h2>
          </div>
          <div className="px-6">
            {faqs.map((faq, index) => (
              <FAQItem key={index} question={faq.question} answer={faq.answer} />
            ))}
          </div>
        </div>

        {/* Support */}
        <div className="bg-neutral-50 rounded-xl border border-neutral-200 p-6 text-center">
          <p className="text-sm text-neutral-600 mb-3">
            Need help with your application?
          </p>
          <a
            href="mailto:support@globalcommunitycleaners.org"
            className="inline-flex items-center gap-2 text-brand-green-600 hover:text-brand-green-700 font-medium"
          >
            <Mail className="w-4 h-4" />
            support@globalcommunitycleaners.org
            <ExternalLink className="w-3 h-3" />
          </a>
        </div>
      </main>

      {/* Bottom Navigation */}
      <nav className="fixed bottom-0 left-0 right-0 bg-white border-t border-neutral-200 safe-bottom">
        <div className="max-w-2xl mx-auto px-4 py-2">
          <div className="flex justify-around">
            <Link
              href="/worker/dashboard"
              className="flex flex-col items-center gap-1 py-2 px-4 text-brand-green-600"
            >
              <div className="w-10 h-10 rounded-xl bg-brand-green-100 flex items-center justify-center">
                <Home className="w-5 h-5" />
              </div>
              <span className="text-xs font-medium">Home</span>
            </Link>
            <div className="flex flex-col items-center gap-1 py-2 px-4 text-neutral-300 cursor-not-allowed">
              <div className="w-10 h-10 rounded-xl bg-neutral-50 flex items-center justify-center">
                <ClipboardList className="w-5 h-5" />
              </div>
              <span className="text-xs">Jobs</span>
            </div>
            <div className="flex flex-col items-center gap-1 py-2 px-4 text-neutral-300 cursor-not-allowed">
              <div className="w-10 h-10 rounded-xl bg-neutral-50 flex items-center justify-center">
                <Trophy className="w-5 h-5" />
              </div>
              <span className="text-xs">Rankings</span>
            </div>
            <Link
              href="/worker/profile"
              className="flex flex-col items-center gap-1 py-2 px-4 text-neutral-400 hover:text-neutral-600 transition-colors"
            >
              <div className="w-10 h-10 rounded-xl bg-neutral-50 flex items-center justify-center">
                <User className="w-5 h-5" />
              </div>
              <span className="text-xs">Profile</span>
            </Link>
          </div>
        </div>
      </nav>
    </div>
  )
}

// Approved Dashboard Component (existing functionality, reorganized)
function ApprovedDashboard({ profile }: { profile: WorkerProfile }) {
  const router = useRouter()
  const [availableBlocks, setAvailableBlocks] = useState<any[]>([])
  const [activeJob, setActiveJob] = useState<any>(null)
  const [claimingBlockId, setClaimingBlockId] = useState<string | null>(null)
  const tier = tierConfig[profile.earlyAdopterTier || 'standard']
  const TierIcon = tier.icon

  useEffect(() => {
    async function fetchBlocks() {
      try {
        const res = await fetch('/api/blocks?status=available')
        const data = await res.json()
        setAvailableBlocks(data.blocks || [])
      } catch (error) {
        console.error('Failed to fetch blocks:', error)
      }
    }
    fetchBlocks()
  }, [])

  const handleClaimBlock = async (blockId: string) => {
    setClaimingBlockId(blockId)
    try {
      const res = await fetch('/api/jobs/claim', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ blockId, workerId: profile.id }),
      })
      if (res.ok) {
        const data = await res.json()
        setActiveJob(data.job)
        setAvailableBlocks((prev) => prev.filter((b) => b.id !== blockId))
      }
    } catch (error) {
      console.error('Failed to claim block:', error)
    } finally {
      setClaimingBlockId(null)
    }
  }

  const handleLogout = async () => {
    try {
      await fetch('/api/workers/logout', { method: 'POST' })
      router.push('/worker/login')
    } catch (error) {
      console.error('Logout failed:', error)
    }
  }

  return (
    <div className="min-h-screen bg-neutral-50 pb-24">
      {/* Header */}
      <header className="bg-white border-b border-neutral-200 sticky top-0 z-10">
        <div className="max-w-4xl mx-auto px-4 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Logo variant="short" size="sm" />
            <div>
              <h1 className="font-semibold text-neutral-900">Worker Dashboard</h1>
              <p className="text-sm text-neutral-500">North Lawndale</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Link
              href="/worker/profile"
              className="w-10 h-10 rounded-full bg-neutral-100 flex items-center justify-center hover:bg-neutral-200 transition-colors"
            >
              <User className="w-5 h-5 text-neutral-600" />
            </Link>
            <button
              onClick={handleLogout}
              className="w-10 h-10 rounded-full bg-neutral-100 flex items-center justify-center hover:bg-neutral-200 transition-colors"
            >
              <LogOut className="w-5 h-5 text-neutral-600" />
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-4 py-6">
        {/* Welcome Card with Rankings */}
        <div className="bg-gradient-to-r from-brand-green-500 to-brand-green-600 rounded-2xl p-6 mb-6 text-white relative overflow-hidden">
          <div className="absolute top-0 right-0 w-32 h-32 bg-white/10 rounded-full -translate-y-1/2 translate-x-1/2" />

          <div className="relative z-10 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div className="flex items-center gap-4">
              <div className="w-16 h-16 rounded-xl bg-white/20 flex items-center justify-center">
                <span className="text-3xl font-bold">{profile.name.split(' ').map(n => n[0]).join('')}</span>
              </div>
              <div>
                <h2 className="text-xl font-bold">{profile.name}</h2>
                <div className="flex items-center gap-2 mt-1">
                  <span className={cn('inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-white/20 text-sm font-medium')}>
                    {tier.emoji} {tier.label}
                  </span>
                  <span className="text-brand-green-100 text-sm">#{profile.signupRankings?.global || '-'} Global</span>
                </div>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <CheckCircle2 className="w-5 h-5 text-brand-green-300" />
              <span className="text-sm font-medium">Verified Worker</span>
            </div>
          </div>
        </div>

        {/* Stats Grid */}
        <div className="grid grid-cols-3 gap-4 mb-6">
          <div className="bg-white rounded-xl border border-neutral-200 p-4 text-center">
            <div className="w-10 h-10 mx-auto rounded-lg bg-brand-green-100 flex items-center justify-center mb-2">
              <CheckCircle2 className="w-5 h-5 text-brand-green-600" />
            </div>
            <p className="text-2xl font-bold text-neutral-900">{profile.stats?.jobsCompleted || 0}</p>
            <p className="text-xs text-neutral-500">Jobs Done</p>
          </div>
          <div className="bg-white rounded-xl border border-neutral-200 p-4 text-center">
            <div className="w-10 h-10 mx-auto rounded-lg bg-brand-green-100 flex items-center justify-center mb-2">
              <DollarSign className="w-5 h-5 text-brand-green-600" />
            </div>
            <p className="text-2xl font-bold text-neutral-900">
              ${Math.floor((profile.stats?.totalEarnings || 0) / 100)}
            </p>
            <p className="text-xs text-neutral-500">Total Earned</p>
          </div>
          <div className="bg-white rounded-xl border border-neutral-200 p-4 text-center">
            <div className="w-10 h-10 mx-auto rounded-lg bg-brand-blue-100 flex items-center justify-center mb-2">
              <Star className="w-5 h-5 text-brand-blue-600" />
            </div>
            <p className="text-2xl font-bold text-neutral-900">
              {profile.stats?.averageRating?.toFixed(1) || '-'}
            </p>
            <p className="text-xs text-neutral-500">Rating</p>
          </div>
        </div>

        {/* Active Job */}
        {activeJob && (
          <Link
            href={`/worker/job/${activeJob.id}`}
            className="block bg-gradient-to-r from-brand-green-500 to-brand-green-600 rounded-2xl p-6 mb-6 text-white shadow-lg hover:shadow-xl transition-all"
          >
            <div className="flex items-center justify-between">
              <div>
                <div className="flex items-center gap-2 mb-2">
                  <span className="w-2 h-2 rounded-full bg-white animate-pulse" />
                  <span className="text-sm font-medium">Active Job</span>
                </div>
                <p className="text-xl font-bold">{activeJob.block?.displayName}</p>
              </div>
              <ChevronRight className="w-6 h-6" />
            </div>
          </Link>
        )}

        {/* Available Blocks */}
        <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden mb-6">
          <div className="px-6 py-4 border-b border-neutral-100 flex items-center justify-between">
            <div>
              <h2 className="font-semibold text-neutral-900">Available Blocks</h2>
              <p className="text-sm text-neutral-500">{availableBlocks.length} blocks ready to claim</p>
            </div>
            <Link href="/worker/available" className="text-brand-green-600 hover:text-brand-green-700 text-sm font-medium flex items-center gap-1">
              <MapPin className="w-4 h-4" />
              Map View
            </Link>
          </div>

          {availableBlocks.length === 0 ? (
            <div className="p-8 text-center">
              <div className="w-16 h-16 mx-auto mb-4 rounded-2xl bg-neutral-100 flex items-center justify-center">
                <MapPin className="w-8 h-8 text-neutral-400" />
              </div>
              <p className="text-neutral-700 font-medium">No blocks available</p>
              <p className="text-sm text-neutral-500 mt-1">Check back soon for new opportunities</p>
            </div>
          ) : (
            <div className="divide-y divide-neutral-100">
              {availableBlocks.slice(0, 5).map((block) => (
                <div key={block.id} className="p-4 flex items-center justify-between hover:bg-neutral-50">
                  <div className="flex items-center gap-4">
                    <div className="w-12 h-12 rounded-xl bg-brand-green-100 flex items-center justify-center">
                      <MapPin className="w-6 h-6 text-brand-green-600" />
                    </div>
                    <div>
                      <p className="font-semibold text-neutral-900">{block.code || block.name}</p>
                      <p className="text-sm text-neutral-500">{formatStreetName(block.streetName) || 'North Lawndale'}</p>
                    </div>
                  </div>
                  <button
                    onClick={() => handleClaimBlock(block.id)}
                    disabled={!!activeJob || claimingBlockId === block.id}
                    className={cn(
                      'px-4 py-2.5 rounded-xl font-semibold text-sm transition-all',
                      activeJob
                        ? 'bg-neutral-100 text-neutral-400 cursor-not-allowed'
                        : 'bg-brand-green-600 text-white hover:bg-brand-green-700'
                    )}
                  >
                    {claimingBlockId === block.id ? 'Claiming...' : 'Claim $100'}
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Quick Actions */}
        <div className="grid grid-cols-2 gap-4">
          <Link
            href="/community"
            className="bg-white rounded-xl border border-neutral-200 p-4 flex items-center gap-3 hover:shadow-md hover:border-brand-green-200 transition-all"
          >
            <div className="w-10 h-10 rounded-lg bg-brand-green-100 flex items-center justify-center">
              <Users className="w-5 h-5 text-brand-green-600" />
            </div>
            <div>
              <p className="font-medium text-neutral-900 text-sm">Community</p>
              <p className="text-xs text-neutral-500">Forum & discussions</p>
            </div>
          </Link>
          <Link
            href="/support"
            className="bg-white rounded-xl border border-neutral-200 p-4 flex items-center gap-3 hover:shadow-md hover:border-brand-green-200 transition-all"
          >
            <div className="w-10 h-10 rounded-lg bg-brand-green-100 flex items-center justify-center">
              <HelpCircle className="w-5 h-5 text-brand-green-600" />
            </div>
            <div>
              <p className="font-medium text-neutral-900 text-sm">Support</p>
              <p className="text-xs text-neutral-500">Get help & feedback</p>
            </div>
          </Link>
          <Link
            href="/worker/profile"
            className="bg-white rounded-xl border border-neutral-200 p-4 flex items-center gap-3 hover:shadow-md hover:border-brand-blue-200 transition-all"
          >
            <div className="w-10 h-10 rounded-lg bg-brand-blue-100 flex items-center justify-center">
              <TrendingUp className="w-5 h-5 text-brand-blue-600" />
            </div>
            <div>
              <p className="font-medium text-neutral-900 text-sm">View Earnings</p>
              <p className="text-xs text-neutral-500">Payout history</p>
            </div>
          </Link>
          <Link
            href="/faq"
            className="bg-white rounded-xl border border-neutral-200 p-4 flex items-center gap-3 hover:shadow-md hover:border-purple-200 transition-all"
          >
            <div className="w-10 h-10 rounded-lg bg-purple-100 flex items-center justify-center">
              <HelpCircle className="w-5 h-5 text-purple-600" />
            </div>
            <div>
              <p className="font-medium text-neutral-900 text-sm">FAQ</p>
              <p className="text-xs text-neutral-500">Common questions</p>
            </div>
          </Link>
        </div>
      </main>

      {/* Bottom Navigation */}
      <nav className="fixed bottom-0 left-0 right-0 bg-white border-t border-neutral-200 safe-bottom">
        <div className="max-w-4xl mx-auto px-4 py-2">
          <div className="flex justify-around">
            <Link href="/worker/dashboard" className="flex flex-col items-center gap-1 py-2 px-4 text-brand-green-600">
              <div className="w-10 h-10 rounded-xl bg-brand-green-100 flex items-center justify-center">
                <Home className="w-5 h-5" />
              </div>
              <span className="text-xs font-medium">Home</span>
            </Link>
            <Link href="/worker/available" className="flex flex-col items-center gap-1 py-2 px-4 text-neutral-400 hover:text-neutral-600">
              <div className="w-10 h-10 rounded-xl bg-neutral-50 flex items-center justify-center">
                <ClipboardList className="w-5 h-5" />
              </div>
              <span className="text-xs">Jobs</span>
            </Link>
            <Link href="/worker/leaderboard" className="flex flex-col items-center gap-1 py-2 px-4 text-neutral-400 hover:text-neutral-600">
              <div className="w-10 h-10 rounded-xl bg-neutral-50 flex items-center justify-center">
                <Trophy className="w-5 h-5" />
              </div>
              <span className="text-xs">Rankings</span>
            </Link>
            <Link href="/worker/profile" className="flex flex-col items-center gap-1 py-2 px-4 text-neutral-400 hover:text-neutral-600">
              <div className="w-10 h-10 rounded-xl bg-neutral-50 flex items-center justify-center">
                <User className="w-5 h-5" />
              </div>
              <span className="text-xs">Profile</span>
            </Link>
          </div>
        </div>
      </nav>
    </div>
  )
}

// Main Dashboard Page
export default function WorkerDashboardPage() {
  const router = useRouter()
  const [profile, setProfile] = useState<WorkerProfile | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    async function fetchProfile() {
      try {
        const res = await fetch('/api/workers/me')
        if (!res.ok) {
          if (res.status === 401) {
            router.push('/worker/login')
            return
          }
          throw new Error('Failed to fetch profile')
        }
        const data = await res.json()
        if (data.success && data.user) {
          setProfile(data.user)
        }
      } catch (error) {
        console.error('Failed to fetch profile:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchProfile()
  }, [router])

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
          <Link href="/worker/login" className="text-brand-green-600 hover:text-brand-green-700 font-medium">
            Return to Login
          </Link>
        </div>
      </div>
    )
  }

  // Show pending dashboard if not approved
  if (profile.verificationStatus !== 'approved') {
    return <PendingDashboard profile={profile} />
  }

  // Show approved dashboard
  return <ApprovedDashboard profile={profile} />
}
