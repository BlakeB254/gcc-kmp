'use client'

import { useEffect, useState, Suspense } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import Image from 'next/image'
import { Logo } from '@/components/brand/Logo'
import { LoadingFallback } from '@/components/shared/LoadingFallback'
import {
  Upload,
  CheckCircle2,
  Clock,
  Heart,
  DollarSign,
  Briefcase,
  Shield,
  ChevronRight,
  HelpCircle,
  Sparkles,
  TrendingUp,
  MapPin,
  Calendar,
} from 'lucide-react'

interface SessionResponse {
  authenticated: boolean
  email?: string
  name?: string
  roles?: Array<'worker' | 'sponsor' | 'admin'>
  worker?: {
    id: number
    name: string
    verificationStatus: string
    documents: {
      governmentIdUploaded: boolean
      governmentIdStatus: string
      proofOfAddressUploaded: boolean
      proofOfAddressStatus: string
    }
    stats: { jobsCompleted: number; totalEarnings: number }
  }
  sponsor?: {
    id: number
    name: string
    type: string
    stats: {
      totalContributed: number
      blocksFunded: number
      lastContributionAt: string | null
    }
    recentContributions: Array<{
      id: number
      amount: number
      status: string
      createdAt: string
      blockName: string | null
    }>
  }
}

function MemberContent() {
  const router = useRouter()
  const [session, setSession] = useState<SessionResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    fetch('/api/auth/session', { credentials: 'include' })
      .then((r) => {
        if (r.status === 401) {
          router.replace('/login?next=/member')
          return null
        }
        return r.json()
      })
      .then((data: SessionResponse | null) => {
        if (data) setSession(data)
      })
      .catch(() => router.replace('/login?next=/member'))
      .finally(() => {
        setLoading(false)
        // Trigger entrance animation just after first paint.
        requestAnimationFrame(() => setVisible(true))
      })
  }, [router])

  async function signOut() {
    try {
      await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' })
    } finally {
      try {
        localStorage.removeItem('adminToken')
      } catch {}
      router.push('/login')
    }
  }

  if (loading || !session) return <LoadingFallback />

  const { worker, sponsor, roles = [], name } = session
  const isAdmin = roles.includes('admin')
  const firstName = name?.split(' ')[0] || ''

  return (
    <div className="min-h-screen bg-neutral-50">
      {/* ═══════════════════════════════════════════════════════════════════
          Top bar — sticky, glass-morphism on scroll
          ═══════════════════════════════════════════════════════════════════ */}
      <header className="bg-white/95 backdrop-blur-md border-b border-neutral-100 sticky top-0 z-20">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 py-4 flex items-center justify-between">
          <Link href="/" aria-label="Home" className="hover-scale">
            <Logo variant="full" size="sm" />
          </Link>
          <div className="flex items-center gap-4">
            <Link
              href="/faq"
              className="hidden sm:inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-brand-green-700 transition-colors"
            >
              <HelpCircle className="w-4 h-4" />
              Help
            </Link>
            <button
              onClick={signOut}
              className="text-sm text-neutral-500 hover:text-red-600 transition-colors font-medium"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      {/* ═══════════════════════════════════════════════════════════════════
          Hero — photo background, gradient overlay, "live" badge
          ═══════════════════════════════════════════════════════════════════ */}
      <section className="relative overflow-hidden">
        {/* Background photo + gradient */}
        <div className="absolute inset-0 bg-gradient-to-br from-brand-green-900 via-brand-green-800 to-brand-green-900">
          <picture>
            <source
              srcSet="/images/optimized/community-street-desktop.webp"
              type="image/webp"
            />
            <Image
              src="/images/optimized/community-street-desktop.jpg"
              alt=""
              fill
              className="object-cover opacity-25"
              sizes="100vw"
              priority
            />
          </picture>
          <div className="absolute inset-0 bg-gradient-to-t from-brand-green-900/95 via-brand-green-900/70 to-brand-green-900/40" />
        </div>

        <div className="relative max-w-6xl mx-auto px-4 sm:px-6 py-12 sm:py-16">
          {/* Member portal badge with pulsing dot */}
          <div
            className={`inline-flex items-center gap-2 rounded-full bg-white/15 backdrop-blur-sm border border-white/20 px-3 py-1.5 mb-5 transition-all duration-700 ${
              visible ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-2'
            }`}
          >
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-brand-green-300 opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-brand-green-300" />
            </span>
            <span className="text-xs font-semibold text-white uppercase tracking-wider">
              Member portal
            </span>
          </div>

          <h1
            className={`text-3xl sm:text-5xl font-bold text-white tracking-tight mb-3 drop-shadow-md transition-all duration-700 delay-75 ${
              visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
            }`}
          >
            Welcome back{firstName ? `, ${firstName}` : ''}.
          </h1>
          <p
            className={`text-base sm:text-lg text-brand-green-100 max-w-2xl transition-all duration-700 delay-150 ${
              visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
            }`}
          >
            {roles.length > 1
              ? `One sign-in, every role: ${roles
                  .filter((r) => r !== 'admin')
                  .join(' + ')}${isAdmin ? ' + admin' : ''}. Pick up where you left off.`
              : roles.includes('worker')
                ? 'Earn $100 per block. Verify, claim, get paid.'
                : roles.includes('sponsor')
                  ? 'Track your impact and fund another block in seconds.'
                  : 'Pick up where you left off.'}
          </p>

          {/* Inline role badges */}
          {roles.length > 0 && (
            <div
              className={`flex flex-wrap gap-2 mt-6 transition-all duration-700 delay-200 ${
                visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
              }`}
            >
              {roles.map((r) => (
                <RoleBadge key={r} role={r} />
              ))}
            </div>
          )}
        </div>

        {/* Soft fade into the page background */}
        <div className="absolute bottom-0 left-0 right-0 h-12 bg-gradient-to-b from-transparent to-neutral-50 pointer-events-none" />
      </section>

      <main className="max-w-6xl mx-auto px-4 sm:px-6 py-10 -mt-4 relative z-10">
        {isAdmin && (
          <div
            className={`mb-8 transition-all duration-700 delay-300 ${
              visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'
            }`}
          >
            <AdminCallout />
          </div>
        )}

        {/* Portal cards — stack on mobile, side-by-side beyond lg */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {worker && (
            <div
              className={`hover-lift transition-all duration-700 delay-300 ${
                visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-6'
              }`}
            >
              <WorkerPortal worker={worker} />
            </div>
          )}
          {sponsor && (
            <div
              className={`hover-lift transition-all duration-700 delay-[400ms] ${
                visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-6'
              }`}
            >
              <SponsorPortal sponsor={sponsor} />
            </div>
          )}
        </div>
      </main>
    </div>
  )
}

function RoleBadge({ role }: { role: 'worker' | 'sponsor' | 'admin' }) {
  const map = {
    worker: { label: 'Worker', cls: 'bg-brand-green-500/30 border-brand-green-300/50 text-brand-green-50', Icon: Briefcase },
    sponsor: { label: 'Sponsor', cls: 'bg-purple-500/30 border-purple-300/50 text-purple-50', Icon: Heart },
    admin: { label: 'Admin', cls: 'bg-white/20 border-white/30 text-white', Icon: Shield },
  } as const
  const { label, cls, Icon } = map[role]
  return (
    <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full border text-xs font-semibold uppercase tracking-wider ${cls}`}>
      <Icon className="w-3 h-3" />
      {label}
    </span>
  )
}

function AdminCallout() {
  return (
    <Link
      href="/admin/routes"
      className="block bg-gradient-to-r from-neutral-900 to-neutral-800 rounded-2xl p-5 sm:p-6 text-white border border-neutral-700 group hover:border-neutral-500 transition-colors"
    >
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-4">
          <div className="w-12 h-12 rounded-xl bg-white/10 flex items-center justify-center flex-shrink-0">
            <Shield className="w-6 h-6" />
          </div>
          <div>
            <p className="font-semibold text-base">Admin command center</p>
            <p className="text-sm text-neutral-400">Routes, workers, jobs, and analytics in one place</p>
          </div>
        </div>
        <ChevronRight className="w-5 h-5 text-neutral-400 group-hover:text-white group-hover:translate-x-1 transition-all flex-shrink-0" />
      </div>
    </Link>
  )
}

interface WorkerPortalProps {
  worker: NonNullable<SessionResponse['worker']>
}

function WorkerPortal({ worker }: WorkerPortalProps) {
  const docsUploaded =
    worker.documents.governmentIdUploaded && worker.documents.proofOfAddressUploaded
  const approved = worker.verificationStatus === 'approved'
  const subtitle = approved
    ? 'You\'re verified. Claim blocks and earn.'
    : docsUploaded
      ? 'Documents under review.'
      : 'One step from earning.'

  return (
    <section className="bg-white rounded-2xl shadow-md border border-neutral-200 overflow-hidden h-full flex flex-col">
      {/* Branded header bar */}
      <div className="relative bg-gradient-to-br from-brand-green-600 via-brand-green-700 to-brand-green-800 px-6 py-5 text-white overflow-hidden">
        {/* Decorative pattern */}
        <div className="absolute -top-12 -right-12 w-40 h-40 rounded-full bg-white/5 blur-2xl" />
        <div className="absolute -bottom-8 -right-8 w-32 h-32 rounded-full bg-white/5 blur-xl" />

        <div className="relative flex items-center gap-3">
          <div className="w-12 h-12 rounded-xl bg-white/15 backdrop-blur-sm border border-white/20 flex items-center justify-center flex-shrink-0">
            <Briefcase className="w-6 h-6" />
          </div>
          <div className="flex-1 min-w-0">
            <h2 className="text-lg font-bold tracking-tight">Earn — Worker portal</h2>
            <p className="text-xs text-brand-green-100">{subtitle}</p>
          </div>
          {approved && (
            <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-white/20 text-xs font-semibold uppercase tracking-wider">
              <CheckCircle2 className="w-3 h-3" />
              Verified
            </span>
          )}
        </div>
      </div>

      <div className="p-6 flex-1 flex flex-col">
        {/* State 1 — needs documents */}
        {!docsUploaded && (
          <div className="bg-gradient-to-br from-amber-50 to-amber-100/50 border border-amber-200 rounded-xl p-5 mb-5">
            <div className="flex items-start gap-3">
              <div className="relative flex-shrink-0">
                <div className="w-12 h-12 rounded-xl bg-amber-500 text-white flex items-center justify-center">
                  <Upload className="w-5 h-5" />
                </div>
                <span className="absolute -top-1 -right-1 flex h-3 w-3">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-amber-400 opacity-75" />
                  <span className="relative inline-flex rounded-full h-3 w-3 bg-amber-500 border-2 border-white" />
                </span>
              </div>
              <div className="flex-1">
                <h3 className="font-bold text-neutral-900 mb-1">Verify your identity</h3>
                <p className="text-sm text-neutral-700 mb-3">
                  Upload your ID and proof of address to unlock paid blocks. Spot reports work
                  without verification.
                </p>
                <DocChecklist worker={worker} />
                <Link
                  href="/worker/documents"
                  className="inline-flex items-center gap-2 mt-4 px-5 py-2.5 rounded-xl bg-amber-600 text-white font-semibold hover:bg-amber-700 hover-lift transition-all"
                >
                  <Upload className="w-4 h-4" />
                  {worker.documents.governmentIdUploaded || worker.documents.proofOfAddressUploaded
                    ? 'Finish uploading'
                    : 'Upload documents'}
                </Link>
              </div>
            </div>
          </div>
        )}

        {/* State 2 — review pending */}
        {docsUploaded && !approved && (
          <div className="bg-gradient-to-br from-brand-green-50 to-brand-green-100/50 border border-brand-green-200 rounded-xl p-5 mb-5">
            <div className="flex items-start gap-3">
              <div className="relative flex-shrink-0">
                <div className="w-12 h-12 rounded-xl bg-brand-green-600 text-white flex items-center justify-center">
                  <Clock className="w-5 h-5" />
                </div>
                <span className="absolute inset-0 rounded-xl border-2 border-brand-green-300 animate-ping opacity-40" />
              </div>
              <div>
                <h3 className="font-bold text-neutral-900 mb-1">Application under review</h3>
                <p className="text-sm text-neutral-700">
                  Most applications are processed within{' '}
                  <span className="font-semibold text-brand-green-700">24–48 hours</span>. You'll
                  get an email when your status changes.
                </p>
              </div>
            </div>
          </div>
        )}

        {/* State 3 — approved (stats) */}
        {approved && (
          <div className="grid grid-cols-2 gap-3 mb-5">
            <BigStat
              label="Jobs completed"
              value={worker.stats.jobsCompleted.toString()}
              icon={<TrendingUp className="w-4 h-4" />}
              accent="green"
            />
            <BigStat
              label="Total earned"
              value={`$${(worker.stats.totalEarnings / 100).toFixed(0)}`}
              icon={<DollarSign className="w-4 h-4" />}
              accent="green"
            />
          </div>
        )}

        {/* Primary CTA */}
        {approved ? (
          <Link
            href="/worker/dashboard"
            className="group inline-flex items-center justify-between px-5 py-4 rounded-xl bg-brand-green-600 hover:bg-brand-green-700 text-white font-semibold shadow-md hover-lift transition-all mb-4"
          >
            <span className="flex items-center gap-2">
              <Sparkles className="w-5 h-5" />
              Open worker dashboard
            </span>
            <ChevronRight className="w-5 h-5 group-hover:translate-x-1 transition-transform" />
          </Link>
        ) : null}

        {/* Quick links footer */}
        <div className="flex flex-wrap gap-2 mt-auto pt-4 border-t border-neutral-100">
          <SmallLink href="/worker/available">Available blocks</SmallLink>
          <SmallLink href="/worker/leaderboard">Leaderboard</SmallLink>
          <SmallLink href="/worker/profile">Profile</SmallLink>
        </div>
      </div>
    </section>
  )
}

function DocChecklist({ worker }: { worker: NonNullable<SessionResponse['worker']> }) {
  return (
    <ul className="space-y-1.5 text-sm">
      <DocChecklistItem label="Government-issued ID" done={worker.documents.governmentIdUploaded} />
      <DocChecklistItem label="Proof of address" done={worker.documents.proofOfAddressUploaded} />
    </ul>
  )
}

function DocChecklistItem({ label, done }: { label: string; done: boolean }) {
  return (
    <li className="flex items-center gap-2">
      {done ? (
        <CheckCircle2 className="w-4 h-4 text-brand-green-600 flex-shrink-0" />
      ) : (
        <span className="w-4 h-4 rounded-full border-2 border-amber-400 flex-shrink-0" />
      )}
      <span className={done ? 'text-neutral-600 line-through' : 'text-neutral-900 font-medium'}>
        {label}
      </span>
    </li>
  )
}

interface SponsorPortalProps {
  sponsor: NonNullable<SessionResponse['sponsor']>
}

function SponsorPortal({ sponsor }: SponsorPortalProps) {
  const totalUSD = sponsor.stats.totalContributed / 100
  const lastDate = sponsor.stats.lastContributionAt
    ? new Date(sponsor.stats.lastContributionAt)
    : null

  return (
    <section className="bg-white rounded-2xl shadow-md border border-neutral-200 overflow-hidden h-full flex flex-col">
      {/* Branded header bar */}
      <div className="relative bg-gradient-to-br from-purple-600 via-purple-700 to-purple-800 px-6 py-5 text-white overflow-hidden">
        <div className="absolute -top-12 -right-12 w-40 h-40 rounded-full bg-white/5 blur-2xl" />
        <div className="absolute -bottom-8 -right-8 w-32 h-32 rounded-full bg-white/5 blur-xl" />

        <div className="relative flex items-center gap-3">
          <div className="w-12 h-12 rounded-xl bg-white/15 backdrop-blur-sm border border-white/20 flex items-center justify-center flex-shrink-0">
            <Heart className="w-6 h-6" />
          </div>
          <div className="flex-1 min-w-0">
            <h2 className="text-lg font-bold tracking-tight">Sponsor — Funding portal</h2>
            <p className="text-xs text-purple-100">Track your impact and fund another block.</p>
          </div>
          {sponsor.stats.blocksFunded > 0 && (
            <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-white/20 text-xs font-semibold uppercase tracking-wider">
              <Sparkles className="w-3 h-3" />
              Supporter
            </span>
          )}
        </div>
      </div>

      <div className="p-6 flex-1 flex flex-col">
        {/* Stats grid */}
        <div className="grid grid-cols-2 gap-3 mb-5">
          <BigStat
            label="Total contributed"
            value={`$${totalUSD.toFixed(0)}`}
            icon={<Heart className="w-4 h-4" />}
            accent="purple"
          />
          <BigStat
            label="Blocks funded"
            value={sponsor.stats.blocksFunded.toString()}
            icon={<MapPin className="w-4 h-4" />}
            accent="purple"
          />
        </div>

        {/* Primary CTA — fund again */}
        <Link
          href="/sponsor"
          className="group inline-flex items-center justify-between px-5 py-4 rounded-xl bg-gradient-to-r from-purple-600 to-purple-700 hover:from-purple-700 hover:to-purple-800 text-white font-semibold shadow-md hover-lift transition-all mb-5"
        >
          <span className="flex items-center gap-2">
            <DollarSign className="w-5 h-5" />
            Sponsor a block — $100
          </span>
          <ChevronRight className="w-5 h-5 group-hover:translate-x-1 transition-transform" />
        </Link>

        {/* Recent contributions */}
        <div className="flex-1">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-xs font-bold text-neutral-500 uppercase tracking-wider">
              Recent contributions
            </h3>
            {lastDate && (
              <span className="inline-flex items-center gap-1 text-xs text-neutral-400">
                <Calendar className="w-3 h-3" />
                Last: {lastDate.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
              </span>
            )}
          </div>
          {sponsor.recentContributions.length === 0 ? (
            <div className="text-center py-8 px-4 rounded-xl bg-gradient-to-br from-purple-50 to-white border border-purple-100">
              <Heart className="w-10 h-10 text-purple-300 mx-auto mb-2" />
              <p className="text-sm text-neutral-600 mb-1 font-medium">No contributions yet.</p>
              <p className="text-xs text-neutral-500 mb-4">
                Be the first to fund a block in your community.
              </p>
              <Link
                href="/sponsor"
                className="inline-flex items-center gap-1.5 text-sm font-semibold text-purple-700 hover:text-purple-800"
              >
                Fund your first block
                <ChevronRight className="w-4 h-4" />
              </Link>
            </div>
          ) : (
            <ul className="divide-y divide-neutral-100 rounded-xl border border-neutral-200 overflow-hidden">
              {sponsor.recentContributions.map((tx) => (
                <li
                  key={tx.id}
                  className="flex items-center justify-between py-3 px-4 hover:bg-purple-50/50 transition-colors"
                >
                  <div className="min-w-0 flex-1">
                    <p className="font-medium text-neutral-900 truncate flex items-center gap-2">
                      {tx.blockName || 'General donation'}
                      <StatusPill status={tx.status} />
                    </p>
                    <p className="text-xs text-neutral-500 mt-0.5">
                      {new Date(tx.createdAt).toLocaleDateString(undefined, {
                        year: 'numeric',
                        month: 'short',
                        day: 'numeric',
                      })}
                    </p>
                  </div>
                  <span className="font-bold text-purple-700 tabular-nums text-base">
                    ${(tx.amount / 100).toFixed(0)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="flex flex-wrap gap-2 mt-4 pt-4 border-t border-neutral-100">
          <SmallLink href="/sponsor/dashboard">Full dashboard</SmallLink>
          <SmallLink href="/sponsor/calculator">Custom package</SmallLink>
          <SmallLink href="/donate">One-time donation</SmallLink>
        </div>
      </div>
    </section>
  )
}

function StatusPill({ status }: { status: string }) {
  const map: Record<string, string> = {
    completed: 'bg-brand-green-100 text-brand-green-700',
    pending: 'bg-amber-100 text-amber-700',
    failed: 'bg-red-100 text-red-700',
    refunded: 'bg-neutral-100 text-neutral-600',
  }
  const cls = map[status.toLowerCase()] || 'bg-neutral-100 text-neutral-600'
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold uppercase tracking-wider ${cls}`}>
      {status}
    </span>
  )
}

function BigStat({
  label,
  value,
  icon,
  accent,
}: {
  label: string
  value: string
  icon: React.ReactNode
  accent: 'green' | 'purple'
}) {
  const tone =
    accent === 'purple'
      ? {
          gradient: 'from-purple-50 to-white',
          text: 'text-purple-700',
          icon: 'bg-purple-100 text-purple-600',
          border: 'border-purple-100',
        }
      : {
          gradient: 'from-brand-green-50 to-white',
          text: 'text-brand-green-700',
          icon: 'bg-brand-green-100 text-brand-green-600',
          border: 'border-brand-green-100',
        }

  return (
    <div className={`rounded-xl bg-gradient-to-br ${tone.gradient} border ${tone.border} p-4`}>
      <div className="flex items-center gap-2 mb-1">
        <span className={`w-7 h-7 rounded-lg ${tone.icon} flex items-center justify-center`}>
          {icon}
        </span>
        <span className="text-[11px] font-semibold text-neutral-500 uppercase tracking-wider">
          {label}
        </span>
      </div>
      <p className={`text-3xl font-bold ${tone.text} tabular-nums leading-none mt-2`}>{value}</p>
    </div>
  )
}

function SmallLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className="text-sm font-medium px-3 py-1.5 rounded-lg bg-neutral-100 text-neutral-700 hover:bg-brand-green-50 hover:text-brand-green-700 transition-colors"
    >
      {children}
    </Link>
  )
}

export default function MemberPage() {
  return (
    <Suspense fallback={<LoadingFallback />}>
      <MemberContent />
    </Suspense>
  )
}
