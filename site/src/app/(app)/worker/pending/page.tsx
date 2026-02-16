'use client'

import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { Suspense, useState, useEffect } from 'react'
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
  Users
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
    answer: 'We verify your government-issued ID (driver\'s license, state ID, or passport) and proof of North Lawndale residency (utility bill, bank statement, or lease agreement from the past 90 days).'
  },
  {
    question: 'What if my documents are rejected?',
    answer: 'You\'ll receive a detailed email explaining what needs to be corrected. Common issues include blurry photos, expired documents, or mismatched addresses. You can resubmit updated documents through the link in your email.'
  },
  {
    question: 'Can I start working before verification?',
    answer: 'No, all workers must complete identity verification before claiming blocks. This protects both our community and ensures fair payment processing through Stripe.'
  },
  {
    question: 'How much will I earn?',
    answer: 'Each block pays $100, deposited directly to your bank account within 30 minutes of job completion through Stripe. There are no fees deducted from your pay.'
  },
]

// Step configuration
const steps = [
  {
    id: 1,
    title: 'Submit Application',
    description: 'Upload your ID and proof of address',
    icon: FileText,
    status: 'completed' as const,
  },
  {
    id: 2,
    title: 'Document Review',
    description: 'Our team verifies your identity',
    icon: Shield,
    status: 'current' as const,
  },
  {
    id: 3,
    title: 'Get Approved',
    description: 'Receive your worker certification',
    icon: CheckCircle2,
    status: 'upcoming' as const,
  },
  {
    id: 4,
    title: 'Set Up Payouts',
    description: 'Connect your bank via Stripe',
    icon: CreditCard,
    status: 'upcoming' as const,
  },
  {
    id: 5,
    title: 'Start Earning',
    description: 'Claim blocks and get paid $100 each',
    icon: DollarSign,
    status: 'upcoming' as const,
  },
]

function FAQItem({ question, answer }: { question: string; answer: string }) {
  const [isOpen, setIsOpen] = useState(false)

  return (
    <div className="border-b border-neutral-100 last:border-0">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center justify-between py-4 text-left hover:text-brand-gold-700 transition-colors"
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
  step: typeof steps[0];
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
          step.status === 'current' && 'bg-brand-gold-500 text-white ring-4 ring-brand-gold-100 animate-pulse',
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
            step.status === 'current' ? 'text-brand-gold-700' : 'text-neutral-900'
          )}>
            {step.title}
          </h3>
          {step.status === 'current' && (
            <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-brand-gold-100 text-brand-gold-700">
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
  const searchParams = useSearchParams()
  const workerId = searchParams.get('id')
  const [timeWaiting, setTimeWaiting] = useState('')

  // Calculate estimated time remaining (mock - would be from API)
  useEffect(() => {
    const submittedAt = new Date()
    submittedAt.setHours(submittedAt.getHours() - 2) // Mock: submitted 2 hours ago

    const updateTime = () => {
      const now = new Date()
      const diff = now.getTime() - submittedAt.getTime()
      const hours = Math.floor(diff / (1000 * 60 * 60))
      const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))

      if (hours > 0) {
        setTimeWaiting(`${hours}h ${minutes}m`)
      } else {
        setTimeWaiting(`${minutes} minutes`)
      }
    }

    updateTime()
    const interval = setInterval(updateTime, 60000)
    return () => clearInterval(interval)
  }, [])

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-brown-50 via-white to-brand-gold-50">
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
        {/* Status Hero */}
        <div className="text-center mb-8">
          {/* Animated Status Icon */}
          <div className="relative inline-block mb-6">
            <div className="w-24 h-24 rounded-full bg-brand-gold-100 flex items-center justify-center">
              <Clock className="w-12 h-12 text-brand-gold-600" />
            </div>
            {/* Pulsing ring */}
            <div className="absolute inset-0 rounded-full border-4 border-brand-gold-300 animate-ping opacity-30" />
          </div>

          <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-3">
            Application Under Review
          </h1>
          <p className="text-neutral-600 max-w-md mx-auto">
            We're verifying your documents. Most applications are processed within <span className="font-semibold text-brand-gold-700">24-48 hours</span>.
          </p>
        </div>

        {/* Status Card */}
        <div className="bg-white rounded-2xl border border-neutral-200 shadow-sm overflow-hidden mb-8">
          {/* Status Bar */}
          <div className="bg-gradient-to-r from-brand-gold-500 to-brand-gold-600 px-6 py-4 text-white">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-full bg-white/20 flex items-center justify-center">
                  <Shield className="w-5 h-5" />
                </div>
                <div>
                  <p className="font-semibold">Verification in Progress</p>
                  <p className="text-brand-gold-100 text-sm">Documents being reviewed</p>
                </div>
              </div>
              {timeWaiting && (
                <div className="text-right">
                  <p className="text-xs text-brand-gold-200">Submitted</p>
                  <p className="font-mono font-semibold">{timeWaiting} ago</p>
                </div>
              )}
            </div>
          </div>

          {/* Progress Steps */}
          <div className="p-6">
            <h2 className="font-semibold text-neutral-900 mb-6 flex items-center gap-2">
              <Sparkles className="w-5 h-5 text-brand-gold-500" />
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

        {/* Info Cards Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-8">
          {/* Email Notification Card */}
          <div className="bg-white rounded-xl border border-neutral-200 p-5">
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-brand-sky-100 flex items-center justify-center flex-shrink-0">
                <Mail className="w-5 h-5 text-brand-sky-600" />
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
              <div className="w-10 h-10 rounded-lg bg-brand-gold-100 flex items-center justify-center flex-shrink-0">
                <MapPin className="w-5 h-5 text-brand-gold-600" />
              </div>
              <div>
                <h3 className="font-semibold text-neutral-900 mb-1">North Lawndale</h3>
                <p className="text-sm text-neutral-600">
                  Blocks are available throughout North Lawndale. Work as close to home as you like.
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
                  Be part of a community transforming our neighborhood, one block at a time.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* FAQ Section */}
        <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden mb-8">
          <div className="px-6 py-4 border-b border-neutral-100 bg-neutral-50">
            <h2 className="font-semibold text-neutral-900 flex items-center gap-2">
              <HelpCircle className="w-5 h-5 text-brand-gold-500" />
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
            className="inline-flex items-center gap-2 text-brand-gold-600 hover:text-brand-gold-700 font-medium text-sm"
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
      <div className="min-h-screen bg-gradient-to-br from-brand-brown-50 via-white to-brand-gold-50 flex items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-brand-gold-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-neutral-500">Loading...</p>
        </div>
      </div>
    }>
      <PendingContent />
    </Suspense>
  )
}
