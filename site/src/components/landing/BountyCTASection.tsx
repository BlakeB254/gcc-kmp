import Link from 'next/link'
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

          {/* Right - stats cards */}
          <div className="grid grid-cols-2 gap-4">
            <div className="bg-white/10 backdrop-blur-sm rounded-xl p-5 border border-white/20">
              <DollarSign className="w-8 h-8 text-white/80 mb-3" />
              <p className="text-2xl font-bold">$10</p>
              <p className="text-sm text-white/70">Minimum bounty</p>
            </div>
            <div className="bg-white/10 backdrop-blur-sm rounded-xl p-5 border border-white/20">
              <MapPin className="w-8 h-8 text-white/80 mb-3" />
              <p className="text-2xl font-bold">GPS</p>
              <p className="text-sm text-white/70">Verified cleanups</p>
            </div>
            <div className="bg-white/10 backdrop-blur-sm rounded-xl p-5 border border-white/20 col-span-2">
              <div className="flex items-center gap-4">
                <div className="flex -space-x-2">
                  {[1, 2, 3].map((i) => (
                    <div
                      key={i}
                      className="w-10 h-10 rounded-full bg-white/20 border-2 border-white/40 flex items-center justify-center text-sm font-bold"
                    >
                      {['S', 'C', 'F'][i - 1]}
                    </div>
                  ))}
                </div>
                <div>
                  <p className="font-semibold">Peer-to-Peer System</p>
                  <p className="text-sm text-white/70">
                    Spotters report, funders pledge, cleaners earn
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
