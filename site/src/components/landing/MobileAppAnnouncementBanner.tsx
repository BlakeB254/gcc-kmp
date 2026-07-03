'use client'

import { useState } from 'react'
import { Smartphone, Bell, Apple, X } from 'lucide-react'
import { AppStoreWaitlistModal } from './AppStoreWaitlistModal'

const APP_STORE_URL = 'https://apps.apple.com/us/app/global-community-cleaners/id6760203806'

interface MobileAppAnnouncementBannerProps {
  variant?: 'hero' | 'sticky'
  onDismiss?: () => void
}

export function MobileAppAnnouncementBanner({
  variant = 'hero',
  onDismiss,
}: MobileAppAnnouncementBannerProps) {
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [isDismissed, setIsDismissed] = useState(false)

  if (isDismissed) return null

  const handleDismiss = () => {
    setIsDismissed(true)
    onDismiss?.()
  }

  if (variant === 'sticky') {
    return (
      <>
        <div className="fixed bottom-0 left-0 right-0 z-50 bg-gradient-to-r from-brand-green-800 to-brand-green-900 text-white px-4 py-3 shadow-lg border-t border-brand-green-500/30 safe-area-bottom">
          <div className="section-container">
            <div className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-3 flex-1 min-w-0">
                <div className="flex-shrink-0 w-10 h-10 rounded-xl bg-gradient-to-br from-brand-green-400 to-brand-green-600 flex items-center justify-center">
                  <Smartphone className="w-5 h-5 text-white" />
                </div>
                <div className="min-w-0">
                  <p className="font-semibold text-sm truncate">GCC Mobile App</p>
                  <p className="text-xs text-neutral-300 truncate">
                    Live now on iOS · Android coming soon
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2 flex-shrink-0">
                <a
                  href={APP_STORE_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex items-center gap-2 px-4 py-2 bg-brand-green-500 hover:bg-brand-green-400 text-neutral-900 font-semibold text-sm rounded-lg transition-colors"
                >
                  <Apple className="w-4 h-4" />
                  <span className="hidden sm:inline">Get the App</span>
                </a>
                <button
                  onClick={() => setIsModalOpen(true)}
                  className="hidden sm:flex p-2 text-neutral-400 hover:text-white transition-colors"
                  aria-label="Notify me when Android launches"
                  title="Notify me when Android launches"
                >
                  <Bell className="w-4 h-4" />
                </button>
                <button
                  onClick={handleDismiss}
                  className="p-2 text-neutral-400 hover:text-white transition-colors"
                  aria-label="Dismiss"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
            </div>
          </div>
        </div>

        <AppStoreWaitlistModal
          isOpen={isModalOpen}
          onClose={() => setIsModalOpen(false)}
        />
      </>
    )
  }

  // Hero variant - inline banner
  return (
    <>
      <div className="inline-flex flex-wrap items-center justify-center gap-3 rounded-full bg-gradient-to-r from-brand-green-800 to-brand-green-900 pl-2 pr-3 py-2 border border-brand-green-500/30 shadow-lg backdrop-blur-sm">
        {/* App Icon */}
        <div className="flex-shrink-0 w-8 h-8 rounded-full bg-gradient-to-br from-brand-green-400 to-brand-green-600 flex items-center justify-center">
          <Smartphone className="w-4 h-4 text-white" />
        </div>

        {/* Text Content */}
        <div className="flex items-center gap-2 text-white">
          <span className="relative flex h-2 w-2">
            <span className="relative inline-flex rounded-full h-2 w-2 bg-brand-green-400" />
          </span>
          <span className="text-sm font-medium">
            <span className="text-brand-green-400 font-bold">Live on iOS</span>
            {' '}· Android coming soon
          </span>
        </div>

        {/* CTA Buttons */}
        <div className="flex items-center gap-2">
          <a
            href={APP_STORE_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-1.5 px-3 py-1.5 bg-brand-green-500 hover:bg-brand-green-400 text-neutral-900 font-semibold text-xs rounded-full transition-colors"
          >
            <Apple className="w-3 h-3" />
            Get the App
          </a>
          <button
            onClick={() => setIsModalOpen(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-white/10 hover:bg-white/20 text-white font-medium text-xs rounded-full transition-colors"
          >
            <Bell className="w-3 h-3" />
            Notify for Android
          </button>
        </div>
      </div>

      <AppStoreWaitlistModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
      />
    </>
  )
}
