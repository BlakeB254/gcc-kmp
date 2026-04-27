'use client'

import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import { Suspense, useState, useEffect } from 'react'
import { Logo } from '@/components/brand/Logo'
import {
  Clock,
  Mail,
  CheckCircle2,
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
  Upload,
} from 'lucide-react'
import { cn } from '@/lib/utils'

// FAQ items for pending workers
const faqs = [
  {
    question: 'How long does verification take?',
    answer: 'Most applications are reviewed within 24-48 hours. You\'ll receive an email notification as soon as your status changes. Complex cases requiring additional verification may take up to 72 hours.'
  },
  {
    question: 'What documents are being verified?',
    answer: 'We verify your government-issued ID (driver\'s license, state ID, or passport) and proof of address (utility bill, bank statement, or lease agreement from the past 90 days). Your address determines which paid blocks you can claim once your neighborhood goes active.'
  },
  {
    question: 'What if my documents are rejected?',
    answer: 'You\'ll receive a detailed email explaining what needs to be corrected. Common issues include blurry photos, expired documents, or mismatched addresses. You can resubmit updated documents from this page anytime.'
  },
  {
    question: 'Can I do anything before verification finishes?',
    answer: 'Yes — you can already submit spot reports anywhere in the world (helping us map dirty blocks). Paid block routes unlock once both your verification is complete and paid blocks are active in your neighborhood.'
  },
  {
    question: 'How much will I earn?',
    answer: 'Each paid block pays $100, deposited directly to your bank account within 30 minutes of job completion through Stripe. There are no fees deducted from your pay.'
  },
]

type StepStatus = 'completed' | 'current' | 'upcoming'

interface StepDef {
  id: number
  title: string
  description: string
  icon: typeof FileText
  status: StepStatus
}

interface WorkerDocsState {
  governmentIdUploaded: boolean
  proofOfAddressUploaded: boolean
}

interface WorkerStatus {
  verificationStatus: 'pending' | 'approved' | 'rejected' | 'needs_resubmission' | string
  documents: WorkerDocsState
  stripeOnboardingStatus: string
}

function buildSteps(status: WorkerStatus | null): StepDef[] {
  const docsUploaded =
    !!status && status.documents.governmentIdUploaded && status.documents.proofOfAddressUploaded
  const approved = status?.verificationStatus === 'approved'
  const stripeReady = status?.stripeOnboardingStatus === 'completed'

  // Step 1 — Upload documents. Only "completed" once BOTH are actually on file.
  const step1Status: StepStatus = docsUploaded ? 'completed' : 'current'

  // Step 2 — Document review. Current only after upload, completed once approved.
  const step2Status: StepStatus = !docsUploaded
    ? 'upcoming'
    : approved
      ? 'completed'
      : 'current'

  // Step 3 — Get approved. Same gating as step 2's completion.
  const step3Status: StepStatus = approved ? 'completed' : 'upcoming'

  // Step 4 — Stripe onboarding.
  const step4Status: StepStatus = stripeReady ? 'completed' : approved ? 'current' : 'upcoming'

  // Step 5 — Start earning.
  const step5Status: StepStatus = approved && stripeReady ? 'current' : 'upcoming'

  return [
    {
      id: 1,
      title: 'Upload Documents',
      description: 'Submit your government ID and proof of address',
      icon: FileText,
      status: step1Status,
    },
    {
      id: 2,
      title: 'Document Review',
      description: 'Our team verifies your identity',
      icon: Shield,
      status: step2Status,
    },
    {
      id: 3,
      title: 'Get Approved',
      description: 'Receive your worker certification',
      icon: CheckCircle2,
      status: step3Status,
    },
    {
      id: 4,
      title: 'Set Up Payouts',
      description: 'Connect your bank via Stripe',
      icon: CreditCard,
      status: step4Status,
    },
    {
      id: 5,
      title: 'Start Earning',
      description: 'Claim blocks and get paid $100 each',
      icon: DollarSign,
      status: step5Status,
    },
  ]
}

function FAQItem({ question, answer }: { question: string; answer: string }) {
  const [isOpen, setIsOpen] = useState(false)

  return (
    <div className="border-b border-neutral-100 last:border-0">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between py-4 text-left hover:text-brand-green-700 transition-colors"
      >
        <span className="font-medium text-neutral-900 pr-4">{question}</span>
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

function StepIndicator({
  step,
  isLast
}: {
  step: StepDef;
  isLast: boolean
}) {
  const Icon = step.icon

  return (
    <div className="flex items-start gap-4">
      {/* Vertical line connector */}
      <div className="flex flex-col items-center">
        <div className={cn(
          'w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 transition-all',
          step.status === 'completed' && 'bg-brand-green-500 text-white',
          step.status === 'current' && 'bg-brand-green-500 text-white ring-4 ring-brand-green-100 animate-pulse',
          step.status === 'upcoming' && 'bg-neutral-100 text-neutral-400'
        )}>
          {step.status === 'completed' ? (
            <CheckCircle2 className="w-5 h-5" />
          ) : (
            <Icon className="w-5 h-5" />
          )}
        </div>
        {!isLast && (
          <div className={cn(
            'w-0.5 h-12 mt-2',
            step.status === 'completed' ? 'bg-brand-green-300' : 'bg-neutral-200'
          )} />
        )}
      </div>

      {/* Content */}
      <div className="flex-1 pb-6">
        <div className="flex items-center gap-2">
          <h3 className={cn(
            'font-semibold',
            step.status === 'current' ? 'text-brand-green-700' : 'text-neutral-900'
          )}>
            {step.title}
          </h3>
          {step.status === 'current' && (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-brand-green-100 text-brand-green-700">
              In Progress
            </span>
          )}
        </div>
        <p className="text-sm text-neutral-500 mt-1">{step.description}</p>
      </div>
    </div>
  )
}

function PendingContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const workerId = searchParams.get('id')

  const [status, setStatus] = useState<WorkerStatus | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const load = async () => {
      try {
        const res = await fetch('/api/workers/me', { credentials: 'include' })
        if (res.status === 401) {
          router.replace('/worker/login')
          return
        }
        const data = await res.json()
        if (data?.user) {
          setStatus({
            verificationStatus: data.user.verificationStatus || 'pending',
            documents: {
              governmentIdUploaded: !!data.user.documents?.governmentIdUploaded,
              proofOfAddressUploaded: !!data.user.documents?.proofOfAddressUploaded,
            },
            stripeOnboardingStatus: data.user.stripeOnboardingStatus || 'not_started',
          })
        }
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [router])

  const docsUploaded =
    !!status &&
    status.documents.governmentIdUploaded &&
    status.documents.proofOfAddressUploaded
  const steps = buildSteps(status)

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50">
      {/* Header */}
      <header className="bg-white/80 backdrop-blur-sm border-b border-neutral-100 sticky top-0 z-10">
        <div className="max-w-2xl mx-auto px-4 py-4 flex items-center justify-between">
          <Logo variant="short" size="md" />
          <Link
            href="/faq"
            className="inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-700"
          >
            <HelpCircle className="w-4 h-4" />
            Help
          </Link>
        </div>
      </header>

      <main className="max-w-2xl mx-auto px-4 py-8">
        {/* Status Hero — copy adapts based on whether docs are actually on file */}
        <div className="text-center mb-8">
          <div className="relative inline-block mb-6">
            <div className="w-24 h-24 rounded-full bg-brand-green-100 flex items-center justify-center">
              {docsUploaded ? (
                <Clock className="w-12 h-12 text-brand-green-600" />
              ) : (
                <Upload className="w-12 h-12 text-brand-green-600" />
              )}
            </div>
            <div className="absolute inset-0 rounded-full border-4 border-brand-green-300 animate-ping opacity-30" />
          </div>

          {loading ? (
            <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-3">
              Loading your status…
            </h1>
          ) : docsUploaded ? (
            <>
              <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-3">
                Application Under Review
              </h1>
              <p className="text-neutral-600 max-w-md mx-auto">
                We're verifying your documents. Most applications are processed within{' '}
                <span className="font-semibold text-brand-green-700">24–48 hours</span>.
              </p>
            </>
          ) : (
            <>
              <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-3">
                Finish Your Application
              </h1>
              <p className="text-neutral-600 max-w-md mx-auto">
                We still need your documents before we can verify you and unlock paid blocks.
              </p>
            </>
          )}
        </div>

        {/* If docs missing, surface the resume CTA prominently */}
        {!loading && !docsUploaded && (
          <div className="mb-8 bg-white rounded-2xl border-2 border-brand-green-300 shadow-sm p-6">
            <div className="flex items-start gap-4">
              <div className="w-12 h-12 rounded-xl bg-brand-green-100 text-brand-green-700 flex items-center justify-center flex-shrink-0">
                <Upload className="w-6 h-6" />
              </div>
              <div className="flex-1 min-w-0">
                <h2 className="font-semibold text-neutral-900 mb-1">Upload your documents</h2>
                <p className="text-sm text-neutral-600 mb-4">
                  {status?.documents.governmentIdUploaded
                    ? 'Just one more — we still need your proof of address.'
                    : status?.documents.proofOfAddressUploaded
                      ? 'Just one more — we still need your government ID.'
                      : 'Government-issued ID and proof of address. Takes 2 minutes.'}
                </p>
                <button
                  onClick={() => router.push('/worker/documents')}
                  className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-brand-green-600 text-white font-semibold hover:bg-brand-green-700 transition-colors"
                >
                  <Upload className="w-4 h-4" />
                  Resume Upload
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Status Card */}
        <div className="bg-white rounded-2xl border border-neutral-200 shadow-sm overflow-hidden mb-8">
          <div className="bg-gradient-to-r from-brand-green-500 to-brand-green-600 px-6 py-4 text-white">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-full bg-white/20 flex items-center justify-center">
                <Shield className="w-5 h-5" />
              </div>
              <div>
                <p className="font-semibold">
                  {docsUploaded ? 'Verification in Progress' : 'Documents Needed'}
                </p>
                <p className="text-brand-green-100 text-sm">
                  {docsUploaded
                    ? 'Your team is reviewing your documents'
                    : 'Upload your ID and proof of address to begin review'}
                </p>
              </div>
            </div>
          </div>

          {/* Progress Steps */}
          <div className="p-6">
            <h2 className="font-semibold text-neutral-900 mb-6 flex items-center gap-2">
              <Sparkles className="w-5 h-5 text-brand-green-500" />
              Your Journey to Earning
            </h2>
            <div className="space-y-0">
              {steps.map((step, index) => (
                <StepIndicator
                  key={step.id}
                  step={step}
                  isLast={index === steps.length - 1}
                />
              ))}
            </div>
          </div>
        </div>

        {/* Spot reports — encouragement that anyone can start contributing immediately */}
        <div className="bg-gradient-to-r from-brand-blue-50 to-brand-green-50 rounded-2xl border border-brand-blue-200 p-5 sm:p-6 mb-8">
          <div className="flex items-start gap-4">
            <div className="w-12 h-12 rounded-xl bg-white text-brand-blue-600 flex items-center justify-center flex-shrink-0 shadow-sm">
              <MapPin className="w-6 h-6" />
            </div>
            <div className="flex-1">
              <h3 className="font-semibold text-neutral-900 mb-1">
                You can start helping right now
              </h3>
              <p className="text-sm text-neutral-600">
                Submit spot reports anywhere — log dirty blocks in your neighborhood and help us
                map where work is needed. Paid blocks unlock once your verification is complete
                and your area goes active.
              </p>
            </div>
          </div>
        </div>

        {/* Info Cards Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-8">
          {/* Email Notification Card */}
          <div className="bg-white rounded-xl border border-neutral-200 p-5">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-brand-blue-100 flex items-center justify-center flex-shrink-0">
                <Mail className="w-5 h-5 text-brand-blue-600" />
              </div>
              <div>
                <h3 className="font-semibold text-neutral-900 mb-1">Check Your Email</h3>
                <p className="text-sm text-neutral-600">
                  We'll notify you immediately when your status changes. Check spam if you don't see it.
                </p>
              </div>
            </div>
          </div>

          {/* What You'll Earn Card */}
          <div className="bg-white rounded-xl border border-neutral-200 p-5">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-brand-green-100 flex items-center justify-center flex-shrink-0">
                <DollarSign className="w-5 h-5 text-brand-green-600" />
              </div>
              <div>
                <h3 className="font-semibold text-neutral-900 mb-1">$100 Per Block</h3>
                <p className="text-sm text-neutral-600">
                  Every block you clean earns $100, paid directly to your bank within 30 minutes.
                </p>
              </div>
            </div>
          </div>

          {/* Coverage Area Card */}
          <div className="bg-white rounded-xl border border-neutral-200 p-5">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-brand-green-100 flex items-center justify-center flex-shrink-0">
                <MapPin className="w-5 h-5 text-brand-green-600" />
              </div>
              <div>
                <h3 className="font-semibold text-neutral-900 mb-1">Your Neighborhood</h3>
                <p className="text-sm text-neutral-600">
                  Paid blocks are tied to the address on your proof-of-address. We'll unlock routes near you as we expand into your area.
                </p>
              </div>
            </div>
          </div>

          {/* Community Card */}
          <div className="bg-white rounded-xl border border-neutral-200 p-5">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-purple-100 flex items-center justify-center flex-shrink-0">
                <Users className="w-5 h-5 text-purple-600" />
              </div>
              <div>
                <h3 className="font-semibold text-neutral-900 mb-1">Join the Movement</h3>
                <p className="text-sm text-neutral-600">
                  Be part of a community transforming neighborhoods worldwide, one block at a time.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* FAQ Section */}
        <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden mb-8">
          <div className="px-6 py-4 border-b border-neutral-100 bg-neutral-50">
            <h2 className="font-semibold text-neutral-900 flex items-center gap-2">
              <HelpCircle className="w-5 h-5 text-brand-green-500" />
              Frequently Asked Questions
            </h2>
          </div>
          <div className="px-6">
            {faqs.map((faq, index) => (
              <FAQItem key={index} question={faq.question} answer={faq.answer} />
            ))}
          </div>
        </div>

        {/* Application ID & Support */}
        <div className="bg-neutral-50 rounded-xl border border-neutral-200 p-6 text-center">
          {workerId && (
            <div className="mb-4">
              <p className="text-xs text-neutral-500 uppercase tracking-wider mb-1">Application ID</p>
              <p className="font-mono text-lg font-semibold text-neutral-700">{workerId}</p>
            </div>
          )}
          <p className="text-sm text-neutral-600 mb-4">
            Questions about your application? We're here to help.
          </p>
          <a
            href="mailto:support@globalcommunitycleaners.org"
            className="inline-flex items-center gap-2 text-brand-green-600 hover:text-brand-green-700 font-medium text-sm"
          >
            <Mail className="w-4 h-4" />
            support@globalcommunitycleaners.org
            <ExternalLink className="w-3 h-3" />
          </a>
        </div>

        {/* Return Home Button */}
        <div className="mt-8 text-center">
          <Link
            href="/"
            className="inline-flex items-center gap-2 px-6 py-3 rounded-xl bg-neutral-900 text-white font-medium hover:bg-neutral-800 transition-colors"
          >
            Return to Homepage
          </Link>
        </div>
      </main>
    </div>
  )
}

export default function PendingPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen bg-gradient-to-br from-brand-green-50 via-white to-brand-green-50 flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-brand-green-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-neutral-500">Loading...</p>
        </div>
      </div>
    }>
      <PendingContent />
    </Suspense>
  )
}
