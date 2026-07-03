import Link from 'next/link'
import Image from 'next/image'
import { Target, DollarSign, MapPin, ArrowRight } from 'lucide-react'

export function BountyCTASection() {
  return (
    <section className="py-20 lg:py-24 bg-gradient-to-br from-red-600 via-red-500 to-orange-500 text-white overflow-hidden relative">
      {/* Background decorations */}
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute top-1/3 -left-20 w-64 h-64 bg-white/5 rounded-full blur-3xl" />
        <div className="absolute bottom-1/4 -right-20 w-80 h-80 bg-orange-400/20 rounded-full blur-3xl" />
      </div>

      <div className="section-container relative">
        <div className="grid lg:grid-cols-2 gap-12 items-center">
          {/* Left content */}
          <div>
            <div className="inline-flex items-center gap-2 rounded-full bg-white/15 backdrop-blur-sm px-4 py-2 mb-6 border border-white/20">
              <Target className="w-4 h-4" />
              <span className="text-sm font-medium">New Feature</span>
            </div>

            <h2 className="text-3xl sm:text-4xl font-bold mb-4">
              Community Bounties
            </h2>
            <p className="text-lg text-white/90 mb-8 max-w-lg">
              See illegal dumping? Post a bounty and the community will clean it up.
              Starting at just $10, anyone can fund a cleaner neighborhood.
            </p>

            <div className="flex flex-wrap gap-3">
              <Link
                href="/bounties"
                className="inline-flex items-center gap-2 bg-white text-red-600 font-semibold px-6 py-3 rounded-xl hover:bg-white/90 transition-colors"
              >
                Explore Bounties
                <ArrowRight className="w-4 h-4" />
              </Link>
              <Link
                href="/bounties/how-it-works"
                className="inline-flex items-center gap-2 bg-white/20 text-white font-semibold px-6 py-3 rounded-xl hover:bg-white/30 transition-colors border border-white/30"
              >
                How It Works
              </Link>
            </div>
          </div>

          {/* Right - real photo of a spot report in action, with stat badges layered on top */}
          <div className="relative">
            <div className="relative aspect-[4/5] w-full max-w-sm mx-auto overflow-hidden rounded-2xl shadow-2xl ring-1 ring-white/20">
              <Image
                src="/images/optimized/photo-spot-reporting.webp"
                alt="A GCC community member photographing illegal dumping to file a spot report"
                fill
                className="object-cover"
                sizes="(min-width: 1024px) 384px, 90vw"
                priority
              />
              <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent" />
            </div>

            {/* Stat badges float over the photo corners */}
            <div className="absolute -bottom-4 -left-4 flex items-center gap-2 rounded-xl bg-white/95 backdrop-blur-sm px-4 py-3 shadow-lg">
              <DollarSign className="w-5 h-5 text-red-600" />
              <div>
                <p className="text-lg font-bold text-neutral-900 leading-none">$10</p>
                <p className="text-xs text-neutral-500">Minimum bounty</p>
              </div>
            </div>
            <div className="absolute -top-4 -right-4 flex items-center gap-2 rounded-xl bg-white/95 backdrop-blur-sm px-4 py-3 shadow-lg">
              <MapPin className="w-5 h-5 text-red-600" />
              <div>
                <p className="text-lg font-bold text-neutral-900 leading-none">GPS</p>
                <p className="text-xs text-neutral-500">Verified cleanups</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
