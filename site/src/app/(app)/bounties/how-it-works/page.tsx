import Link from 'next/link'
import {
  Eye,
  DollarSign,
  MapPin,
  CheckCircle2,
  Lock,
  Globe,
  Heart,
  Gift,
  ArrowRight,
  Star,
} from 'lucide-react'
import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: 'How Bounties Work',
  description:
    'Learn how GCC community bounties work: spot illegal dumping, post a bounty, and someone in your neighborhood will clean it up.',
}

const steps = [
  {
    icon: Eye,
    title: 'Spot It',
    description:
      'See illegal dumping, a tire pile, or any mess in your neighborhood? Snap a photo and report it in the GCC app.',
    color: 'bg-red-500',
    ringColor: 'ring-red-200',
  },
  {
    icon: DollarSign,
    title: 'Post It ($10 min)',
    description:
      'Post a bounty starting at $10. Others in the community can pledge additional funds to increase the reward.',
    color: 'bg-orange-500',
    ringColor: 'ring-orange-200',
  },
  {
    icon: MapPin,
    title: 'Someone Claims',
    description:
      'A verified GCC cleaner claims the bounty. They must be within GPS range of the site to start the cleanup.',
    color: 'bg-blue-500',
    ringColor: 'ring-blue-200',
  },
  {
    icon: CheckCircle2,
    title: 'Verified Clean',
    description:
      'The cleaner submits before-and-after photos. Once verified, the bounty is paid out instantly.',
    color: 'bg-green-500',
    ringColor: 'ring-green-200',
  },
]

export default function HowItWorksPage() {
  return (
    <div className="min-h-screen">
      {/* Hero */}
      <section className="bg-gradient-to-br from-red-600 via-red-500 to-orange-500 text-white py-16 lg:py-20">
        <div className="section-container text-center">
          <h1 className="text-4xl sm:text-5xl font-bold mb-4">
            How Bounties Work
          </h1>
          <p className="text-lg text-white/90 max-w-2xl mx-auto">
            A simple, transparent system that turns community frustration into community action.
            Four steps from mess to clean.
          </p>
        </div>
      </section>

      {/* 4-Step Flow */}
      <section className="py-16 lg:py-20">
        <div className="section-container">
          <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-8">
            {steps.map((step, index) => {
              const Icon = step.icon
              return (
                <div key={index} className="text-center relative">
                  {/* Connector line (hidden on last item and mobile) */}
                  {index < steps.length - 1 && (
                    <div className="hidden lg:block absolute top-10 left-[60%] w-[80%] h-0.5 bg-neutral-200" />
                  )}

                  <div
                    className={`w-20 h-20 ${step.color} rounded-full flex items-center justify-center mx-auto mb-4 ring-4 ${step.ringColor} relative z-10`}
                  >
                    <Icon className="w-9 h-9 text-white" />
                  </div>
                  <div className="text-xs font-bold text-neutral-400 uppercase tracking-wider mb-2">
                    Step {index + 1}
                  </div>
                  <h3 className="text-lg font-bold text-neutral-900 mb-2">
                    {step.title}
                  </h3>
                  <p className="text-sm text-neutral-600 max-w-[260px] mx-auto">
                    {step.description}
                  </p>
                </div>
              )
            })}
          </div>
        </div>
      </section>

      {/* Public vs Secret Bounties */}
      <section className="py-16 lg:py-20 bg-neutral-50">
        <div className="section-container">
          <h2 className="text-2xl sm:text-3xl font-bold text-neutral-900 text-center mb-10">
            Two Types of Bounties
          </h2>
          <div className="grid md:grid-cols-2 gap-6 max-w-4xl mx-auto">
            {/* Public */}
            <div className="bg-white rounded-2xl border-2 border-neutral-200 p-6 lg:p-8">
              <div className="w-14 h-14 rounded-full bg-green-100 flex items-center justify-center mb-4">
                <Globe className="w-7 h-7 text-green-600" />
              </div>
              <h3 className="text-xl font-bold text-neutral-900 mb-2">
                Public Bounties
              </h3>
              <p className="text-neutral-600 mb-4">
                Visible to everyone on the bounty map. Anyone in the community can pledge additional
                funds to increase the reward. Great for well-known problem spots.
              </p>
              <ul className="space-y-2 text-sm text-neutral-600">
                <li className="flex items-center gap-2">
                  <CheckCircle2 className="w-4 h-4 text-green-500 flex-shrink-0" />
                  Visible on the map to all users
                </li>
                <li className="flex items-center gap-2">
                  <CheckCircle2 className="w-4 h-4 text-green-500 flex-shrink-0" />
                  Community can add pledges
                </li>
                <li className="flex items-center gap-2">
                  <CheckCircle2 className="w-4 h-4 text-green-500 flex-shrink-0" />
                  Larger bounties attract faster claims
                </li>
              </ul>
            </div>

            {/* Secret */}
            <div className="bg-white rounded-2xl border-2 border-neutral-200 p-6 lg:p-8">
              <div className="w-14 h-14 rounded-full bg-purple-100 flex items-center justify-center mb-4">
                <Lock className="w-7 h-7 text-purple-600" />
              </div>
              <h3 className="text-xl font-bold text-neutral-900 mb-2">
                Secret Bounties
              </h3>
              <p className="text-neutral-600 mb-4">
                Only visible to verified GCC cleaners within a certain radius. Perfect for reporting
                sensitive locations or when you want to avoid drawing attention to a site.
              </p>
              <ul className="space-y-2 text-sm text-neutral-600">
                <li className="flex items-center gap-2">
                  <CheckCircle2 className="w-4 h-4 text-purple-500 flex-shrink-0" />
                  Hidden from the public map
                </li>
                <li className="flex items-center gap-2">
                  <CheckCircle2 className="w-4 h-4 text-purple-500 flex-shrink-0" />
                  Only nearby verified cleaners see it
                </li>
                <li className="flex items-center gap-2">
                  <CheckCircle2 className="w-4 h-4 text-purple-500 flex-shrink-0" />
                  Discreet reporting for sensitive spots
                </li>
              </ul>
            </div>
          </div>
        </div>
      </section>

      {/* Gamification - Altruistic cleanups */}
      <section className="py-16 lg:py-20">
        <div className="section-container">
          <div className="max-w-3xl mx-auto text-center">
            <div className="w-16 h-16 rounded-full bg-yellow-100 flex items-center justify-center mx-auto mb-6">
              <Star className="w-8 h-8 text-yellow-500" />
            </div>
            <h2 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-4">
              No Bounty? Still Worth It.
            </h2>
            <p className="text-lg text-neutral-600 mb-8">
              Cleanups without a bounty earn <strong>more points</strong> on the GCC leaderboard.
              Altruistic cleaners get bonus XP, exclusive badges, and priority access to
              high-value bounties. The community remembers who shows up when there&apos;s no
              money on the table.
            </p>
            <div className="grid sm:grid-cols-3 gap-4">
              <div className="bg-yellow-50 rounded-xl p-5 border border-yellow-200">
                <p className="text-2xl font-bold text-yellow-600 mb-1">2x</p>
                <p className="text-sm text-neutral-600">Points for altruistic cleans</p>
              </div>
              <div className="bg-yellow-50 rounded-xl p-5 border border-yellow-200">
                <p className="text-2xl font-bold text-yellow-600 mb-1">Priority</p>
                <p className="text-sm text-neutral-600">Access to premium bounties</p>
              </div>
              <div className="bg-yellow-50 rounded-xl p-5 border border-yellow-200">
                <p className="text-2xl font-bold text-yellow-600 mb-1">Badges</p>
                <p className="text-sm text-neutral-600">Exclusive community recognition</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Reward the Spotter */}
      <section className="py-16 lg:py-20 bg-gradient-to-r from-orange-50 to-red-50">
        <div className="section-container">
          <div className="max-w-3xl mx-auto">
            <div className="flex flex-col md:flex-row items-center gap-8">
              <div className="w-20 h-20 rounded-full bg-orange-100 flex items-center justify-center flex-shrink-0">
                <Gift className="w-10 h-10 text-orange-500" />
              </div>
              <div>
                <h2 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-3">
                  Reward the Spotter
                </h2>
                <p className="text-neutral-600 mb-4">
                  The person who first reports a mess deserves credit. When you post or pledge
                  on a bounty, you can add an optional tip for the original spotter. This
                  incentivizes people to keep their eyes open and report problems quickly.
                </p>
                <div className="flex items-center gap-4 text-sm">
                  <div className="flex items-center gap-2 bg-white rounded-full px-4 py-2 border border-orange-200">
                    <Heart className="w-4 h-4 text-orange-500" />
                    <span className="text-neutral-700">Tips start at $2</span>
                  </div>
                  <div className="flex items-center gap-2 bg-white rounded-full px-4 py-2 border border-orange-200">
                    <DollarSign className="w-4 h-4 text-orange-500" />
                    <span className="text-neutral-700">100% goes to the spotter</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* CTA */}
      <section className="py-16 lg:py-20 bg-neutral-900 text-white">
        <div className="section-container text-center">
          <h2 className="text-2xl sm:text-3xl font-bold mb-4">
            Ready to Clean Up Your Block?
          </h2>
          <p className="text-neutral-400 max-w-xl mx-auto mb-8">
            Post your first bounty and watch the community rally to clean it up.
          </p>
          <Link
            href="/bounties/post"
            className="inline-flex items-center gap-2 bg-red-500 hover:bg-red-600 text-white font-semibold px-8 py-4 rounded-xl transition-colors text-lg"
          >
            Post a Bounty
            <ArrowRight className="w-5 h-5" />
          </Link>
        </div>
      </section>
    </div>
  )
}
