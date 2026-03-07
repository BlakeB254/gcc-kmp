'use client'

import dynamic from 'next/dynamic'
import Link from 'next/link'
import { MapPin, DollarSign, Trash2, ArrowRight } from 'lucide-react'
import type { Bounty } from '@/components/map/BountyMap'

// Dynamically import BountyMap to avoid SSR issues with MapLibre
const BountyMap = dynamic(
  () => import('@/components/map/BountyMap').then(mod => ({ default: mod.BountyMap })),
  {
    ssr: false,
    loading: () => (
      <div className="h-[500px] bg-neutral-100 rounded-xl animate-pulse flex items-center justify-center">
        <span className="text-neutral-400">Loading map...</span>
      </div>
    ),
  }
)

const bounties: Bounty[] = [
  {
    id: '1',
    title: 'Illegal dumping on Pulaski',
    description: 'Large pile of construction debris blocking the sidewalk near the bus stop.',
    amountCents: 250000,
    status: 'open',
    coordinates: [-87.7260, 41.8612],
    pledgeCount: 2,
  },
  {
    id: '2',
    title: 'Mattress dumped in alley',
    description: 'Two mattresses blocking alley entrance between Pulaski and Kostner.',
    amountCents: 150000,
    status: 'open',
    coordinates: [-87.7195, 41.8580],
    pledgeCount: 1,
  },
  {
    id: '3',
    title: 'Tire pile on vacant lot',
    description: 'Approximately 30 tires stacked on a vacant lot. Potential mosquito breeding ground.',
    amountCents: 500000,
    status: 'in_progress',
    coordinates: [-87.7310, 41.8635],
    pledgeCount: 3,
  },
]

export default function BountiesPage() {
  return (
    <div className="min-h-screen">
      {/* Hero */}
      <section className="bg-gradient-to-br from-red-600 via-red-500 to-orange-500 text-white py-16 lg:py-20">
        <div className="section-container text-center">
          <h1 className="text-4xl sm:text-5xl font-bold mb-4">
            Community Bounties
          </h1>
          <p className="text-lg text-white/90 max-w-2xl mx-auto mb-6">
            See something dirty? Post a bounty. Someone in the community will claim it and clean it up.
            A peer-to-peer system that puts cleanup power in your hands.
          </p>
          <div className="flex flex-wrap justify-center gap-3">
            <Link
              href="/bounties/post"
              className="inline-flex items-center gap-2 bg-white text-red-600 font-semibold px-6 py-3 rounded-xl hover:bg-white/90 transition-colors"
            >
              <DollarSign className="w-5 h-5" />
              Post a Bounty
            </Link>
            <Link
              href="/bounties/how-it-works"
              className="inline-flex items-center gap-2 bg-white/20 text-white font-semibold px-6 py-3 rounded-xl hover:bg-white/30 transition-colors border border-white/30"
            >
              How It Works
              <ArrowRight className="w-4 h-4" />
            </Link>
          </div>
        </div>
      </section>

      {/* Stats Bar */}
      <section className="bg-neutral-900 text-white py-6">
        <div className="section-container">
          <div className="grid grid-cols-3 gap-4 text-center">
            <div>
              <p className="text-2xl sm:text-3xl font-bold text-red-400">12</p>
              <p className="text-sm text-neutral-400">Active Bounties</p>
            </div>
            <div>
              <p className="text-2xl sm:text-3xl font-bold text-orange-400">$4,200</p>
              <p className="text-sm text-neutral-400">Total Posted</p>
            </div>
            <div>
              <p className="text-2xl sm:text-3xl font-bold text-green-400">8</p>
              <p className="text-sm text-neutral-400">Cleaned This Week</p>
            </div>
          </div>
        </div>
      </section>

      {/* Map */}
      <section className="py-8 lg:py-12">
        <div className="section-container">
          <div className="mb-6 flex items-center justify-between">
            <h2 className="text-xl font-bold text-neutral-900">
              <MapPin className="w-5 h-5 inline-block mr-2 text-red-500" />
              Bounty Map
            </h2>
            <span className="text-sm text-neutral-500">
              {bounties.length} bounties shown
            </span>
          </div>
          <BountyMap
            bounties={bounties}
            height="500px"
            showLegend={true}
          />

          {/* Bounty List */}
          <div className="mt-8 grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {bounties.map((bounty) => (
              <div
                key={bounty.id}
                className="bg-white rounded-xl border border-neutral-200 p-5 hover:shadow-md transition-shadow"
              >
                <div className="flex items-start justify-between mb-3">
                  <h3 className="font-semibold text-neutral-900">{bounty.title}</h3>
                  <span
                    className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                      bounty.status === 'open'
                        ? 'bg-red-100 text-red-700'
                        : bounty.status === 'in_progress'
                        ? 'bg-blue-100 text-blue-700'
                        : 'bg-green-100 text-green-700'
                    }`}
                  >
                    {bounty.status === 'open'
                      ? 'Open'
                      : bounty.status === 'in_progress'
                      ? 'In Progress'
                      : 'Completed'}
                  </span>
                </div>
                <p className="text-sm text-neutral-600 mb-4 line-clamp-2">
                  {bounty.description}
                </p>
                <div className="flex items-center justify-between pt-3 border-t border-neutral-100">
                  <span className="text-lg font-bold text-green-600">
                    ${(bounty.amountCents / 100).toFixed(0)}
                  </span>
                  <span className="text-xs text-neutral-500">
                    {bounty.pledgeCount} {bounty.pledgeCount === 1 ? 'pledge' : 'pledges'}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* CTA */}
      <section className="bg-gradient-to-r from-neutral-900 to-neutral-800 text-white py-16">
        <div className="section-container text-center">
          <Trash2 className="w-10 h-10 mx-auto mb-4 text-red-400" />
          <h2 className="text-2xl sm:text-3xl font-bold mb-3">
            Download the App to Post or Claim Bounties
          </h2>
          <p className="text-neutral-400 max-w-xl mx-auto mb-8">
            GPS-verified cleanups ensure bounties only pay out when the job is actually done.
            Available on iOS and Android.
          </p>
          <div className="flex flex-wrap justify-center gap-4">
            <a
              href="#"
              className="inline-flex items-center gap-3 bg-white text-neutral-900 font-semibold px-6 py-3.5 rounded-xl hover:bg-neutral-100 transition-colors"
            >
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="currentColor">
                <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.8-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z" />
              </svg>
              App Store
            </a>
            <a
              href="#"
              className="inline-flex items-center gap-3 bg-white text-neutral-900 font-semibold px-6 py-3.5 rounded-xl hover:bg-neutral-100 transition-colors"
            >
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="currentColor">
                <path d="M3.609 1.814L13.792 12 3.61 22.186a.996.996 0 01-.61-.92V2.734a1 1 0 01.609-.92zm10.89 10.893l2.302 2.302-10.937 6.333 8.635-8.635zm3.199-3.199l2.807 1.626a1 1 0 010 1.732l-2.807 1.626L15.206 12l2.492-2.492zM5.864 2.658L16.8 8.99l-2.302 2.302-8.634-8.634z" />
              </svg>
              Google Play
            </a>
          </div>
        </div>
      </section>
    </div>
  )
}
