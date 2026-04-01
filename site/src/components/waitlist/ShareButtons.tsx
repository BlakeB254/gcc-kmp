'use client'

import { useState } from 'react'
import {
  getTwitterShareUrl,
  getFacebookShareUrl,
  getLinkedInShareUrl,
  getSmsShareUrl,
  getEmailShareUrl,
  copyReferralLink,
  canUseWebShare,
  useWebShare,
  getReferralUrl,
  type ShareData,
} from '@/lib/waitlist/sharing'
import { SOCIAL_COLORS } from '@/lib/design-tokens'

interface ShareButtonsProps {
  shareData: ShareData
  className?: string
}

export function ShareButtons({ shareData, className = '' }: ShareButtonsProps) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    const success = await copyReferralLink(shareData.referralCode)
    if (success) {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    }
  }

  const handleNativeShare = async () => {
    await useWebShare(shareData)
  }

  const buttons = [
    {
      name: 'Twitter',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
          <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
        </svg>
      ),
      href: getTwitterShareUrl(shareData),
      color: `bg-[${SOCIAL_COLORS.twitter}] hover:bg-neutral-800`,
    },
    {
      name: 'Facebook',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
          <path d="M24 12.073c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.99 4.388 10.954 10.125 11.854v-8.385H7.078v-3.47h3.047V9.43c0-3.007 1.792-4.669 4.533-4.669 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.925-1.956 1.874v2.25h3.328l-.532 3.47h-2.796v8.385C19.612 23.027 24 18.062 24 12.073z" />
        </svg>
      ),
      href: getFacebookShareUrl(shareData),
      color: `bg-[${SOCIAL_COLORS.facebook}] hover:bg-[#166FE5]`,
    },
    {
      name: 'LinkedIn',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
          <path d="M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433c-1.144 0-2.063-.926-2.063-2.065 0-1.138.92-2.063 2.063-2.063 1.14 0 2.064.925 2.064 2.063 0 1.139-.925 2.065-2.064 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.222 0h.003z" />
        </svg>
      ),
      href: getLinkedInShareUrl(shareData),
      color: `bg-[${SOCIAL_COLORS.linkedin}] hover:bg-[#004182]`,
    },
    {
      name: 'SMS',
      icon: (
        <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
          />
        </svg>
      ),
      href: getSmsShareUrl(shareData),
      color: 'bg-green-500 hover:bg-green-600',
    },
    {
      name: 'Email',
      icon: (
        <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
          />
        </svg>
      ),
      href: getEmailShareUrl(shareData),
      color: 'bg-neutral-600 hover:bg-neutral-700',
    },
  ]

  return (
    <div className={`space-y-4 ${className}`}>
      {/* Share buttons */}
      <div className="flex flex-wrap justify-center gap-2">
        {buttons.map((button) => (
          <a
            key={button.name}
            href={button.href}
            target="_blank"
            rel="noopener noreferrer"
            className={`
              flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg
              text-white font-medium transition-colors
              ${button.color}
            `}
            title={`Share on ${button.name}`}
          >
            {button.icon}
            <span className="hidden sm:inline">{button.name}</span>
          </a>
        ))}
      </div>

      {/* Copy link button */}
      <div className="flex flex-col items-center gap-2">
        <div className="flex w-full max-w-md">
          <input
            type="text"
            readOnly
            value={getReferralUrl(shareData.referralCode)}
            className="flex-1 px-4 py-2 text-sm bg-neutral-100 rounded-l-lg border border-neutral-200 truncate"
          />
          <button
            onClick={handleCopy}
            className={`
              px-4 py-2 font-medium text-sm rounded-r-lg transition-all
              ${
                copied
                  ? 'bg-green-500 text-white'
                  : 'bg-brand-green-500 text-white hover:bg-brand-green-600'
              }
            `}
          >
            {copied ? 'Copied!' : 'Copy'}
          </button>
        </div>
      </div>

      {/* Native share button (mobile) */}
      {typeof window !== 'undefined' && canUseWebShare() && (
        <button
          onClick={handleNativeShare}
          className="w-full max-w-md mx-auto flex items-center justify-center gap-2 px-6 py-3 bg-brand-green-500 text-white font-semibold rounded-lg hover:bg-brand-green-600 transition-colors"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"
            />
          </svg>
          Share with Friends
        </button>
      )}
    </div>
  )
}
