import Link from 'next/link'
import Image from 'next/image'
import { Target, DollarSign, MapPin, ArrowRight } from 'lucide-react'

export function BountyCTASection() {
  return (
    <section className="py-20 lg:py-24 bg-neutral-950 text-white overflow-hidden relative">
      {/* Background decorations — warm alert glows on a dark ground instead of a full red wash */}
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute top-1/3 -left-20 w-72 h-72 bg-red-600/25 rounded-full blur-3xl" />
        <div className="absolute bottom-1/4 -right-20 w-96 h-96 bg-orange-500/20 rounded-full blur-3xl" />
        <div className="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-black/40" />
      </div>

      <div className="section-container relative">
        <div className="grid lg:grid-cols-2 gap-12 items-center">
          {/* Left content */}
          <div>
            <div className="section-eyebrow bg-red-500/15 text-red-300 ring-1 ring-inset ring-red-500/30 mb-6">
              <Target className="w-4 h-4" aria-hidden="true" />
              New feature
            </div>

            <h2 className="section-title mb-4">
              Community Bounties
            </h2>
            <p className="text-lg text-neutral-300 mb-8 max-w-lg">
              See illegal dumping? Post a bounty and the community will clean it up.
              Starting at just $10, anyone can fund a cleaner neighborhood.
            </p>

            <div className="flex flex-wrap gap-3">
              <Link
                href="/bounties"
                className="inline-flex items-center gap-2 bg-red-500 text-white font-semibold px-6 py-3 rounded-full hover:bg-red-400 shadow-lg shadow-red-500/25 transition-all hover:-translate-y-0.5"
              >
                Explore Bounties
                <ArrowRight className="w-4 h-4" />
              </Link>
              <Link
                href="/bounties/how-it-works"
                className="inline-flex items-center gap-2 bg-white/10 text-white font-semibold px-6 py-3 rounded-full hover:bg-white/20 transition-colors border border-white/20"
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
            <div className="absolute -bottom-4 -left-4 flex items-center gap-2 rounded-xl bg-white/95 backdrop-blur-sm px-4 py-3 shadow-lg shadow-black/30">
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
