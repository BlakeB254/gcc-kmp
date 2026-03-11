'use client'

import { useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { CheckCircle2, Heart, MapPin, Share2, Twitter, Linkedin, Facebook, Copy, Users, FileText, Mail } from 'lucide-react'
import { useState, Suspense } from 'react'

function SuccessContent() {
  const searchParams = useSearchParams()
  const type = searchParams.get('type') || 'general'
  const blocksParam = searchParams.get('blocks')
  const demo = searchParams.get('demo') === 'true'

  const blockCount = blocksParam ? blocksParam.split(',').length : 0
  const isBlockSponsorship = type === 'block' && blockCount > 0

  const [copied, setCopied] = useState(false)

  const shareUrl = typeof window !== 'undefined' ? window.location.origin : 'https://globalcommunitycleaners.org'
  const shareText = isBlockSponsorship
    ? `I just sponsored ${blockCount} block${blockCount > 1 ? 's' : ''} to be cleaned in North Lawndale through @GCCleaners! 100% goes to local workers.`
    : `I just donated to @GCCleaners to help clean streets in North Lawndale. 100% of block funding goes to local workers!`

  const handleCopyLink = () => {
    navigator.clipboard.writeText(shareUrl)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="max-w-lg w-full">
      {/* Success Card */}
      <div className="bg-white rounded-2xl shadow-lg p-8 text-center">
        {/* Success Icon */}
        <div className="w-20 h-20 mx-auto mb-6 rounded-full bg-brand-green-100 flex items-center justify-center">
          <CheckCircle2 className="w-10 h-10 text-brand-green-600" />
        </div>

        {/* Demo Notice */}
        {demo && (
          <div className="mb-6 bg-yellow-100 text-yellow-800 px-4 py-2 rounded-lg text-sm">
            Demo Mode - No actual payment was processed
          </div>
        )}

        {/* Heading */}
        <h1 className="text-2xl font-bold text-neutral-900 mb-2">
          Thank You for Your Support!
        </h1>
        <p className="text-neutral-600 mb-6">
          {isBlockSponsorship ? (
            <>
              You&apos;ve sponsored <span className="font-semibold">{blockCount} block{blockCount > 1 ? 's' : ''}</span> to be cleaned.
              A confirmation email is on its way.
            </>
          ) : (
            <>
              Your donation to the General Fund will help clean blocks that need it most.
              A confirmation email is on its way.
            </>
          )}
        </p>

        {/* Impact Card */}
        <div className={`rounded-xl p-5 mb-6 ${isBlockSponsorship ? 'bg-brand-gold-50' : 'bg-brand-green-50'}`}>
          <div className="flex items-center justify-center gap-2 mb-3">
            {isBlockSponsorship ? (
              <MapPin className="w-5 h-5 text-brand-gold-600" />
            ) : (
              <Heart className="w-5 h-5 text-brand-green-600" />
            )}
            <span className={`font-semibold ${isBlockSponsorship ? 'text-brand-gold-800' : 'text-brand-green-800'}`}>
              Your Impact
            </span>
          </div>
          <ul className={`text-sm space-y-2 ${isBlockSponsorship ? 'text-brand-gold-700' : 'text-brand-green-700'}`}>
            <li className="flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 flex-shrink-0" />
              100% of block funding goes to workers
            </li>
            <li className="flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 flex-shrink-0" />
              {isBlockSponsorship ? 'Before/after photos sent via email' : 'Funds allocated to priority blocks'}
            </li>
            <li className="flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 flex-shrink-0" />
              Tax-deductible contribution
            </li>
          </ul>
        </div>

        {/* Tax Receipt Info */}
        <div className="bg-neutral-50 rounded-xl p-4 mb-6 border border-neutral-200">
          <div className="flex items-start gap-3">
            <div className="w-10 h-10 rounded-full bg-brand-green-100 flex items-center justify-center flex-shrink-0">
              <FileText className="w-5 h-5 text-brand-green-600" />
            </div>
            <div className="text-left">
              <h4 className="font-semibold text-neutral-900 mb-1">Tax-Deductible Receipt</h4>
              <p className="text-sm text-neutral-600 mb-2">
                A PDF receipt for your tax-deductible donation will be emailed to you from Stripe within a few minutes.
              </p>
              <p className="text-xs text-neutral-500 flex items-center gap-1">
                <Mail className="w-3 h-3" />
                Check your email (and spam folder) for your receipt
              </p>
            </div>
          </div>
        </div>

        {/* Share Section */}
        <div className="mb-6">
          <p className="text-sm text-neutral-500 mb-3 flex items-center justify-center gap-1">
            <Share2 className="w-4 h-4" />
            Spread the word
          </p>
          <div className="flex justify-center gap-3">
            <a
              href={`https://twitter.com/intent/tweet?text=${encodeURIComponent(shareText)}&url=${encodeURIComponent(shareUrl)}`}
              target="_blank"
              rel="noopener noreferrer"
              className="w-10 h-10 rounded-full bg-neutral-100 hover:bg-blue-100 flex items-center justify-center text-neutral-600 hover:text-blue-500 transition-colors"
            >
              <Twitter className="w-4 h-4" />
            </a>
            <a
              href={`https://www.linkedin.com/shareArticle?mini=true&url=${encodeURIComponent(shareUrl)}&title=${encodeURIComponent('Supporting Clean Streets in North Lawndale')}`}
              target="_blank"
              rel="noopener noreferrer"
              className="w-10 h-10 rounded-full bg-neutral-100 hover:bg-blue-100 flex items-center justify-center text-neutral-600 hover:text-blue-700 transition-colors"
            >
              <Linkedin className="w-4 h-4" />
            </a>
            <a
              href={`https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(shareUrl)}`}
              target="_blank"
              rel="noopener noreferrer"
              className="w-10 h-10 rounded-full bg-neutral-100 hover:bg-blue-100 flex items-center justify-center text-neutral-600 hover:text-blue-600 transition-colors"
            >
              <Facebook className="w-4 h-4" />
            </a>
            <button
              onClick={handleCopyLink}
              className="w-10 h-10 rounded-full bg-neutral-100 hover:bg-neutral-200 flex items-center justify-center text-neutral-600 transition-colors"
            >
              {copied ? <CheckCircle2 className="w-4 h-4 text-green-500" /> : <Copy className="w-4 h-4" />}
            </button>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="space-y-3">
          <Link
            href="/supporters"
            className="btn-primary w-full flex items-center justify-center gap-2"
          >
            <Users className="w-4 h-4" />
            View Our Supporters
          </Link>
          <Link
            href="/"
            className="btn-secondary w-full"
          >
            Return Home
          </Link>
        </div>
      </div>

      {/* What's Next */}
      <div className="mt-6 bg-white/50 rounded-xl p-5">
        <h3 className="font-semibold text-neutral-900 mb-3 text-center">What Happens Next?</h3>
        <ol className="text-sm text-neutral-600 space-y-2">
          <li className="flex items-start gap-3">
            <span className="w-5 h-5 rounded-full bg-brand-green-100 text-brand-green-700 flex items-center justify-center text-xs font-bold flex-shrink-0">1</span>
            <span>You&apos;ll receive a confirmation email with your tax-deductible receipt (PDF)</span>
          </li>
          <li className="flex items-start gap-3">
            <span className="w-5 h-5 rounded-full bg-brand-green-100 text-brand-green-700 flex items-center justify-center text-xs font-bold flex-shrink-0">2</span>
            <span>A local worker will claim {isBlockSponsorship ? 'your sponsored block' : 'a block funded by contributions'}</span>
          </li>
          <li className="flex items-start gap-3">
            <span className="w-5 h-5 rounded-full bg-brand-green-100 text-brand-green-700 flex items-center justify-center text-xs font-bold flex-shrink-0">3</span>
            <span>{isBlockSponsorship ? 'You\'ll receive before/after photos when cleaning is complete' : 'Your contribution helps pay workers directly'}</span>
          </li>
        </ol>
      </div>

      {/* Access Your Account */}
      <div className="mt-4 text-center">
        <p className="text-sm text-neutral-500 mb-2">
          Need your receipt later? Access your donation history anytime.
        </p>
        <Link
          href="/sponsor/login"
          className="text-sm font-medium text-brand-green-600 hover:text-brand-green-700"
        >
          Log in to your sponsor account →
        </Link>
      </div>
    </div>
  )
}

function LoadingFallback() {
  return (
    <div className="max-w-lg w-full">
      <div className="bg-white rounded-2xl shadow-lg p-8 text-center">
        <div className="w-20 h-20 mx-auto mb-6 rounded-full bg-neutral-100 animate-pulse" />
        <div className="h-8 bg-neutral-100 rounded animate-pulse mb-4" />
        <div className="h-4 bg-neutral-100 rounded animate-pulse mb-2" />
        <div className="h-4 bg-neutral-100 rounded animate-pulse w-3/4 mx-auto" />
      </div>
    </div>
  )
}

export default function DonateSuccessPage() {
  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white flex items-center justify-center p-4">
      <Suspense fallback={<LoadingFallback />}>
        <SuccessContent />
      </Suspense>
    </div>
  )
}
