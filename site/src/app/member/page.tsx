'use client'

import { useEffect, useState, Suspense } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
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
      .finally(() => setLoading(false))
  }, [router])

  async function signOut() {
    try {
      await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' })
    } finally {
      try { localStorage.removeItem('adminToken') } catch {}
      router.push('/login')
    }
  }

  if (loading || !session) return <LoadingFallback />

  const { worker, sponsor, roles = [], name } = session
  const isAdmin = roles.includes('admin')

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 via-white to-white">
      {/* Top bar */}
      <header className="bg-white/80 backdrop-blur-sm border-b border-neutral-200 sticky top-0 z-10">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 py-4 flex items-center justify-between">
          <Link href="/" aria-label="Home">
            <Logo variant="full" size="sm" />
          </Link>
          <div className="flex items-center gap-3">
            <Link
              href="/faq"
              className="hidden sm:inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-700"
            >
              <HelpCircle className="w-4 h-4" />
              Help
            </Link>
            <button
              onClick={signOut}
              className="text-sm text-neutral-500 hover:text-red-600 transition-colors"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-5xl mx-auto px-4 sm:px-6 py-8 sm:py-10">
        {/* Welcome */}
        <div className="mb-10">
          <p className="text-sm font-medium text-brand-green-700 uppercase tracking-wider mb-2">
            Member portal
          </p>
          <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-2">
            Welcome back{name ? `, ${name.split(' ')[0]}` : ''}.
          </h1>
          <p className="text-neutral-600">
            {roles.length > 1
              ? `You have access to ${roles.filter((r) => r !== 'admin').join(' + ')}${
                  isAdmin ? ' + admin' : ''
                }. Pick up where you left off.`
              : 'Pick up where you left off.'}
          </p>
        </div>

        {isAdmin && <AdminCallout />}

        {/* Stack the two portals side-by-side on desktop, full-width on mobile.
            Order: worker first (verification gate), then sponsor (history + re-funding). */}
        <div className="space-y-8">
          {worker && <WorkerPortal worker={worker} />}
          {sponsor && <SponsorPortal sponsor={sponsor} />}
        </div>
      </main>
    </div>
  )
}

function AdminCallout() {
  return (
    <div className="mb-6 bg-neutral-900 text-white rounded-2xl p-5 flex items-center justify-between gap-4">
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-xl bg-white/10 flex items-center justify-center">
          <Shield className="w-5 h-5" />
        </div>
        <div>
          <p className="font-semibold">Admin access</p>
          <p className="text-xs text-neutral-300">Routes, workers, jobs, analytics</p>
        </div>
      </div>
      <Link
        href="/admin/routes"
        className="inline-flex items-center gap-1.5 text-sm font-medium px-4 py-2 rounded-lg bg-white text-neutral-900 hover:bg-neutral-100 transition-colors"
      >
        Open admin
        <ChevronRight className="w-4 h-4" />
      </Link>
    </div>
  )
}

interface WorkerPortalProps {
  worker: NonNullable<SessionResponse['worker']>
}

function WorkerPortal({ worker }: WorkerPortalProps) {
  const docsUploaded =
    worker.documents.governmentIdUploaded && worker.documents.proofOfAddressUploaded
  const approved = worker.verificationStatus === 'approved'

  return (
    <section className="bg-white rounded-2xl border border-neutral-200 shadow-sm overflow-hidden">
      <div className="bg-gradient-to-r from-brand-green-600 to-brand-green-700 px-6 py-5 text-white">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-white/15 flex items-center justify-center">
            <Briefcase className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-lg font-bold">Earn — Worker portal</h2>
            <p className="text-xs text-brand-green-100">
              {approved
                ? 'You\'re verified. Claim blocks and earn.'
                : docsUploaded
                  ? 'Your documents are under review.'
                  : 'Finish verification to unlock paid blocks.'}
            </p>
          </div>
        </div>
      </div>

      <div className="p-6">
        {/* State 1: Docs not uploaded — primary verification prompt */}
        {!docsUploaded && (
          <div className="bg-amber-50 border border-amber-200 rounded-xl p-5 mb-5">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-amber-100 text-amber-700 flex items-center justify-center flex-shrink-0">
                <Upload className="w-5 h-5" />
              </div>
              <div className="flex-1">
                <h3 className="font-semibold text-neutral-900 mb-1">
                  Verify your identity
                </h3>
                <p className="text-sm text-neutral-700 mb-3">
                  We need a government-issued ID and a proof of address before you can claim
                  paid blocks. Spot reports work without verification.
                </p>
                <DocChecklist worker={worker} />
                <Link
                  href="/worker/documents"
                  className="inline-flex items-center gap-2 mt-4 px-5 py-2.5 rounded-xl bg-brand-green-600 text-white font-semibold hover:bg-brand-green-700 transition-colors"
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

        {/* State 2: Docs uploaded, awaiting review */}
        {docsUploaded && !approved && (
          <div className="bg-brand-green-50 border border-brand-green-200 rounded-xl p-5 mb-5">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-brand-green-100 text-brand-green-700 flex items-center justify-center flex-shrink-0">
                <Clock className="w-5 h-5" />
              </div>
              <div>
                <h3 className="font-semibold text-neutral-900 mb-1">Application under review</h3>
                <p className="text-sm text-neutral-700">
                  Most applications are processed within 24–48 hours. You'll get an email when
                  your status changes.
                </p>
              </div>
            </div>
          </div>
        )}

        {/* State 3: Approved — primary actions */}
        {approved && (
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-5">
            <StatTile label="Jobs completed" value={worker.stats.jobsCompleted.toString()} />
            <StatTile
              label="Total earned"
              value={`$${(worker.stats.totalEarnings / 100).toFixed(0)}`}
            />
            <Link
              href="/worker/dashboard"
              className="rounded-xl bg-brand-green-600 text-white p-4 flex items-center justify-between font-semibold hover:bg-brand-green-700 transition-colors"
            >
              Open worker dashboard
              <ChevronRight className="w-5 h-5" />
            </Link>
          </div>
        )}

        {/* Always-on links */}
        <div className="flex flex-wrap gap-2">
          <SmallLink href="/worker/available">Available blocks</SmallLink>
          <SmallLink href="/worker/leaderboard">Leaderboard</SmallLink>
          <SmallLink href="/worker/profile">Profile & address</SmallLink>
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
        <span className="w-4 h-4 rounded-full border-2 border-neutral-300 flex-shrink-0" />
      )}
      <span className={done ? 'text-neutral-600 line-through' : 'text-neutral-900'}>{label}</span>
    </li>
  )
}

interface SponsorPortalProps {
  sponsor: NonNullable<SessionResponse['sponsor']>
}

function SponsorPortal({ sponsor }: SponsorPortalProps) {
  const totalUSD = sponsor.stats.totalContributed / 100
  return (
    <section className="bg-white rounded-2xl border border-neutral-200 shadow-sm overflow-hidden">
      <div className="bg-gradient-to-r from-purple-600 to-purple-700 px-6 py-5 text-white">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-white/15 flex items-center justify-center">
            <Heart className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-lg font-bold">Sponsor — Funding portal</h2>
            <p className="text-xs text-purple-100">Track your contributions and fund again.</p>
          </div>
        </div>
      </div>

      <div className="p-6">
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3 mb-6">
          <StatTile
            label="Total contributed"
            value={`$${totalUSD.toFixed(0)}`}
            accent="purple"
          />
          <StatTile
            label="Blocks funded"
            value={sponsor.stats.blocksFunded.toString()}
            accent="purple"
          />
          <Link
            href="/sponsor"
            className="rounded-xl bg-purple-600 text-white p-4 flex items-center justify-between font-semibold hover:bg-purple-700 transition-colors"
          >
            <span className="flex items-center gap-2">
              <DollarSign className="w-5 h-5" />
              Sponsor a block
            </span>
            <ChevronRight className="w-5 h-5" />
          </Link>
        </div>

        {/* Recent contributions */}
        <h3 className="text-sm font-semibold text-neutral-700 uppercase tracking-wider mb-3">
          Recent contributions
        </h3>
        {sponsor.recentContributions.length === 0 ? (
          <div className="text-center py-8 px-4 rounded-xl bg-neutral-50 border border-neutral-200">
            <p className="text-neutral-500 text-sm mb-3">No contributions yet.</p>
            <Link
              href="/sponsor"
              className="inline-flex items-center gap-2 text-sm font-semibold text-purple-700 hover:text-purple-800"
            >
              Fund your first block
              <ChevronRight className="w-4 h-4" />
            </Link>
          </div>
        ) : (
          <ul className="divide-y divide-neutral-100 rounded-xl border border-neutral-200 overflow-hidden">
            {sponsor.recentContributions.map((tx) => (
              <li key={tx.id} className="flex items-center justify-between py-3 px-4 hover:bg-neutral-50">
                <div className="min-w-0 flex-1">
                  <p className="font-medium text-neutral-900 truncate">
                    {tx.blockName || 'General donation'}
                  </p>
                  <p className="text-xs text-neutral-500">
                    {new Date(tx.createdAt).toLocaleDateString(undefined, {
                      year: 'numeric',
                      month: 'short',
                      day: 'numeric',
                    })}
                    {' · '}
                    <span className="capitalize">{tx.status}</span>
                  </p>
                </div>
                <span className="font-semibold text-neutral-900 tabular-nums">
                  ${(tx.amount / 100).toFixed(0)}
                </span>
              </li>
            ))}
          </ul>
        )}

        {/* Always-on links */}
        <div className="flex flex-wrap gap-2 mt-5">
          <SmallLink href="/sponsor/dashboard">Full dashboard</SmallLink>
          <SmallLink href="/sponsor/calculator">Custom package</SmallLink>
          <SmallLink href="/donate">One-time donation</SmallLink>
        </div>
      </div>
    </section>
  )
}

function StatTile({
  label,
  value,
  accent = 'green',
}: {
  label: string
  value: string
  accent?: 'green' | 'purple'
}) {
  const accentClass =
    accent === 'purple' ? 'text-purple-700' : 'text-brand-green-700'
  return (
    <div className="rounded-xl bg-neutral-50 border border-neutral-200 p-4">
      <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1">{label}</p>
      <p className={`text-2xl font-bold ${accentClass}`}>{value}</p>
    </div>
  )
}

function SmallLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className="text-sm font-medium px-3 py-1.5 rounded-lg bg-neutral-100 text-neutral-700 hover:bg-neutral-200 transition-colors"
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
