import type { Metadata } from 'next'
import Image from 'next/image'
import Link from 'next/link'

const APP_STORE_URL = 'https://apps.apple.com/us/app/global-community-cleaners/id6760203806'
const PLAY_STORE_URL = 'https://play.google.com/store/apps/details?id=com.globalcommunitycleaners.worker'

export const metadata: Metadata = {
  title: 'Get the GCC App',
  description:
    'See every block. Pay your neighbor. Earn $100 per block. The Global Community Cleaners app shows the live heatmap of every cleaning, scheduled or done, near you.',
  openGraph: {
    title: 'Get the GCC App',
    description:
      'See every block. Pay your neighbor. Earn $100 per block. Live heatmap of cleanings near you.',
    type: 'website',
    siteName: 'Global Community Cleaners',
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Get the GCC App',
    description: 'See every block. Pay your neighbor. Earn $100 per block.',
  },
  other: {
    'apple-itunes-app': 'app-id=6760203806',
  },
}

export default function AppLandingPage() {
  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-700 via-brand-green-600 to-brand-green-800 text-white">
      <div className="section-container py-16 sm:py-24">
        <div className="max-w-2xl mx-auto text-center">
          <Link href="/" className="inline-block mb-8 hover:opacity-90 transition-opacity">
            <Image
              src="/images/gcc-logo.png"
              alt="Global Community Cleaners"
              width={120}
              height={120}
              className="mx-auto"
              priority
            />
          </Link>
          <h1 className="text-4xl sm:text-5xl font-extrabold leading-tight mb-4">
            Get the GCC app.
          </h1>
          <p className="text-lg sm:text-xl text-white/90 leading-relaxed mb-10">
            Live heatmap of every cleaning scheduled, in progress, and done near you.
            <br className="hidden sm:inline" /> Earn $100 per block. Pay your neighbor.
            Watch your block transform.
          </p>

          <div className="flex flex-col sm:flex-row items-center justify-center gap-4 mb-12">
            <Link
              href={APP_STORE_URL}
              className="inline-flex items-center justify-center gap-3 bg-black text-white px-8 py-4 rounded-2xl font-semibold shadow-xl hover:bg-neutral-800 transition-colors min-w-[240px]"
              aria-label="Download GCC on the App Store"
            >
              <svg viewBox="0 0 24 24" className="w-6 h-6" fill="currentColor">
                <path d="M17.05 20.28c-.98.95-2.05.8-3.08.35-1.09-.46-2.09-.48-3.24 0-1.44.62-2.2.44-3.06-.35C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z" />
              </svg>
              <div className="flex flex-col items-start leading-none">
                <span className="text-xs opacity-80">Download on the</span>
                <span className="text-lg font-semibold">App Store</span>
              </div>
            </Link>

            <Link
              href={PLAY_STORE_URL}
              className="inline-flex items-center justify-center gap-3 bg-black text-white px-8 py-4 rounded-2xl font-semibold shadow-xl hover:bg-neutral-800 transition-colors min-w-[240px]"
              aria-label="Get GCC on Google Play"
            >
              <svg viewBox="0 0 24 24" className="w-6 h-6" fill="currentColor">
                <path d="M3.6 1.2C3.4 1.4 3.2 1.7 3.2 2.1v19.8c0 .4.2.7.4.9l11.7-11L3.6 1.2zm12.6 11.4l3.4 3.4 1.5-.9c1-.6 1-2.1 0-2.7l-1.5-.9-3.4 1.1zm-.7-.7l3.5-3.5L5.1 1.5c-.4-.2-.8-.3-1.2-.1l11.6 10.5zm0 1.4L4 22.6c.4.1.8.1 1.1-.1l13.9-7.9-3.5-3.3z" />
              </svg>
              <div className="flex flex-col items-start leading-none">
                <span className="text-xs opacity-80">Get it on</span>
                <span className="text-lg font-semibold">Google Play</span>
              </div>
            </Link>
          </div>

          <div className="grid grid-cols-3 gap-4 sm:gap-8 max-w-md mx-auto pt-8 border-t border-white/20">
            <div>
              <div className="text-3xl sm:text-4xl font-extrabold">$100</div>
              <div className="text-xs sm:text-sm text-white/80 mt-1">per block</div>
            </div>
            <div>
              <div className="text-3xl sm:text-4xl font-extrabold">100%</div>
              <div className="text-xs sm:text-sm text-white/80 mt-1">to the worker</div>
            </div>
            <div>
              <div className="text-3xl sm:text-4xl font-extrabold">Same-day</div>
              <div className="text-xs sm:text-sm text-white/80 mt-1">payout</div>
            </div>
          </div>

          <p className="text-sm text-white/70 mt-12 leading-relaxed">
            Currently launched in North Lawndale, Chicago (60623, 60624).
            <br />
            Scans stay outdoors —{' '}
            <Link href="/app/indoor-gate" className="underline font-semibold hover:text-white">
              Indoor Gate v4
            </Link>
            {' '}blocks indoor capture.
            <br />
            Bringing GCC to every neighborhood next.{' '}
            <Link href="/sponsor/enterprise" className="underline font-semibold hover:text-white">
              Partner with us →
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
