'use client'

import { useState } from 'react'
import { Smartphone, Bell, CheckCircle2, MapPin, Wallet, Camera, Zap } from 'lucide-react'
import { AppStoreWaitlistModal } from './AppStoreWaitlistModal'

export function MobileAppCTASection() {
  const [isModalOpen, setIsModalOpen] = useState(false)

  const features = [
    {
      icon: MapPin,
      title: 'Find Jobs Near You',
      description: 'See available blocks in your neighborhood with GPS-guided routes',
    },
    {
      icon: Camera,
      title: 'Easy Photo Verification',
      description: 'Snap before & after photos directly in the app with auto-upload',
    },
    {
      icon: Wallet,
      title: 'Instant Payouts',
      description: 'Get paid within 30 minutes of job completion via Stripe',
    },
    {
      icon: Zap,
      title: 'Real-time Notifications',
      description: 'Never miss a job - get alerts when blocks are funded near you',
    },
  ]

  return (
    <>
      <section className="py-28 lg:py-32 bg-gradient-to-br from-brand-brown-900 via-brand-brown-800 to-neutral-900 text-white overflow-hidden relative">
        {/* Background decorations */}
        <div className="absolute inset-0 pointer-events-none">
          <div className="absolute top-1/4 -left-20 w-64 h-64 bg-brand-gold-500/10 rounded-full blur-3xl" />
          <div className="absolute bottom-1/4 -right-20 w-80 h-80 bg-brand-green-500/10 rounded-full blur-3xl" />
        </div>

        <div className="section-container relative">
          <div className="grid lg:grid-cols-2 gap-12 lg:gap-16 items-center">
            {/* Left - Content */}
            <div>
              {/* Badge */}
              <div className="inline-flex items-center gap-2 rounded-full bg-white/10 backdrop-blur-sm px-4 py-2 mb-6 border border-white/20">
                <span className="relative flex h-2 w-2">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-brand-green-400 opacity-75" />
                  <span className="relative inline-flex rounded-full h-2 w-2 bg-brand-green-500" />
                </span>
                <span className="text-sm font-medium text-brand-gold-300">
                  Coming February 2nd
                </span>
              </div>

              <h2 className="text-4xl sm:text-5xl font-bold mb-6">
                GCC Goes{' '}
                <span className="text-transparent bg-clip-text bg-gradient-to-r from-brand-gold-400 to-brand-gold-300">
                  Mobile
                </span>
              </h2>

              <p className="text-lg text-neutral-300 mb-8 max-w-lg">
                The easiest way to find cleaning jobs, submit proof of work, and get paid instantly.
                Available on iOS and Android.
              </p>

              {/* Feature Grid */}
              <div className="grid sm:grid-cols-2 gap-4 mb-8">
                {features.map((feature, index) => {
                  const Icon = feature.icon
                  return (
                    <div
                      key={index}
                      className="flex gap-3 p-4 rounded-xl bg-white/5 backdrop-blur-sm border border-white/10 hover:bg-white/10 transition-colors"
                    >
                      <div className="flex-shrink-0 w-10 h-10 rounded-lg bg-gradient-to-br from-brand-gold-500 to-brand-gold-600 flex items-center justify-center">
                        <Icon className="w-5 h-5 text-white" />
                      </div>
                      <div>
                        <h3 className="font-semibold text-white mb-1">{feature.title}</h3>
                        <p className="text-sm text-neutral-400">{feature.description}</p>
                      </div>
                    </div>
                  )
                })}
              </div>

              {/* CTA Button */}
              <button
                onClick={() => setIsModalOpen(true)}
                className="group w-full sm:w-auto flex items-center justify-center gap-3 px-8 py-4 bg-brand-gold-500 hover:bg-brand-gold-400 text-neutral-900 font-bold text-lg rounded-xl transition-all shadow-lg shadow-brand-gold-500/25 hover:shadow-brand-gold-500/40"
              >
                <Bell className="w-5 h-5" />
                Get Notified When It Launches
                <svg
                  className="w-5 h-5 transition-transform group-hover:translate-x-1"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                </svg>
              </button>

              {/* Trust indicators */}
              <div className="flex items-center gap-4 mt-6 text-sm text-neutral-400">
                <div className="flex items-center gap-1.5">
                  <CheckCircle2 className="w-4 h-4 text-brand-green-500" />
                  Free download
                </div>
                <div className="flex items-center gap-1.5">
                  <CheckCircle2 className="w-4 h-4 text-brand-green-500" />
                  iOS & Android
                </div>
                <div className="flex items-center gap-1.5">
                  <CheckCircle2 className="w-4 h-4 text-brand-green-500" />
                  No ads
                </div>
              </div>
            </div>

            {/* Right - Phone Mockup */}
            <div className="relative flex justify-center lg:justify-end">
              {/* Glow effect */}
              <div className="absolute inset-0 flex items-center justify-center">
                <div className="w-72 h-72 bg-brand-gold-500/20 rounded-full blur-3xl animate-pulse" />
              </div>

              {/* Phone mockup */}
              <div className="relative w-[280px] h-[580px] bg-neutral-950 rounded-[3rem] border-4 border-neutral-800 shadow-2xl overflow-hidden">
                {/* Notch */}
                <div className="absolute top-0 left-1/2 -translate-x-1/2 w-32 h-7 bg-neutral-950 rounded-b-2xl z-10" />

                {/* Screen content */}
                <div className="absolute inset-2 rounded-[2.5rem] bg-gradient-to-b from-brand-brown-800 to-brand-brown-900 overflow-hidden">
                  {/* Status bar */}
                  <div className="flex items-center justify-between px-6 pt-12 pb-2">
                    <span className="text-xs text-white/60">9:41</span>
                    <div className="flex items-center gap-1">
                      <div className="w-4 h-2 border border-white/60 rounded-sm">
                        <div className="w-2 h-full bg-brand-green-500 rounded-sm" />
                      </div>
                    </div>
                  </div>

                  {/* App header */}
                  <div className="px-5 py-4 border-b border-white/10">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-brand-gold-400 to-brand-gold-600 flex items-center justify-center">
                        <span className="text-lg">🧹</span>
                      </div>
                      <div>
                        <p className="font-bold text-white text-sm">GCC</p>
                        <p className="text-xs text-brand-gold-400">North Lawndale</p>
                      </div>
                    </div>
                  </div>

                  {/* Available jobs card */}
                  <div className="p-4">
                    <div className="bg-white/10 backdrop-blur rounded-xl p-4 border border-white/10">
                      <div className="flex items-center justify-between mb-3">
                        <p className="text-xs font-medium text-brand-gold-400">Available Near You</p>
                        <span className="px-2 py-0.5 bg-brand-green-500/20 text-brand-green-400 text-xs rounded-full">
                          3 jobs
                        </span>
                      </div>

                      {/* Mock job item */}
                      <div className="space-y-2">
                        {[
                          { block: 'S Pulaski Rd', distance: '0.3 mi', amount: 100 },
                          { block: 'W Roosevelt Rd', distance: '0.5 mi', amount: 100 },
                          { block: 'S Kostner Ave', distance: '0.8 mi', amount: 100 },
                        ].map((job, i) => (
                          <div
                            key={i}
                            className="flex items-center justify-between p-2 rounded-lg bg-white/5"
                          >
                            <div className="flex items-center gap-2">
                              <div className="w-8 h-8 rounded-lg bg-brand-gold-500/20 flex items-center justify-center">
                                <MapPin className="w-4 h-4 text-brand-gold-400" />
                              </div>
                              <div>
                                <p className="text-xs font-medium text-white">{job.block}</p>
                                <p className="text-[10px] text-neutral-400">{job.distance} away</p>
                              </div>
                            </div>
                            <p className="text-sm font-bold text-brand-gold-400">${job.amount}</p>
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>

                  {/* Bottom nav mockup */}
                  <div className="absolute bottom-4 left-4 right-4">
                    <div className="flex items-center justify-around p-3 bg-neutral-900/90 backdrop-blur rounded-2xl border border-white/10">
                      {['Jobs', 'Map', 'Profile'].map((label, i) => (
                        <div key={i} className="flex flex-col items-center gap-1">
                          <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${i === 0 ? 'bg-brand-gold-500' : 'bg-white/10'}`}>
                            {i === 0 && <Wallet className="w-4 h-4 text-neutral-900" />}
                            {i === 1 && <MapPin className="w-4 h-4 text-neutral-400" />}
                            {i === 2 && <div className="w-4 h-4 rounded-full bg-neutral-500" />}
                          </div>
                          <span className={`text-[10px] ${i === 0 ? 'text-brand-gold-400' : 'text-neutral-500'}`}>
                            {label}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              </div>

              {/* Floating badges */}
              <div className="absolute -left-4 top-1/4 bg-white rounded-xl shadow-xl p-3 hidden lg:flex items-center gap-2 animate-float">
                <div className="w-8 h-8 rounded-full bg-brand-green-100 flex items-center justify-center">
                  <CheckCircle2 className="w-4 h-4 text-brand-green-600" />
                </div>
                <div>
                  <p className="text-[10px] text-neutral-500">Job Completed</p>
                  <p className="text-sm font-bold text-brand-green-600">+$100</p>
                </div>
              </div>

              <div className="absolute -right-4 bottom-1/3 bg-white rounded-xl shadow-xl p-3 hidden lg:flex items-center gap-2 animate-float-delayed">
                <div className="w-8 h-8 rounded-full bg-brand-gold-100 flex items-center justify-center">
                  <Zap className="w-4 h-4 text-brand-gold-600" />
                </div>
                <div>
                  <p className="text-[10px] text-neutral-500">Instant Payout</p>
                  <p className="text-sm font-bold text-brand-gold-600">Sent to bank</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <AppStoreWaitlistModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
      />

      {/* Add floating animation keyframes */}
      <style jsx>{`
        @keyframes float {
          0%, 100% { transform: translateY(0px); }
          50% { transform: translateY(-10px); }
        }
        @keyframes float-delayed {
          0%, 100% { transform: translateY(0px); }
          50% { transform: translateY(-8px); }
        }
        :global(.animate-float) {
          animation: float 3s ease-in-out infinite;
        }
        :global(.animate-float-delayed) {
          animation: float-delayed 3.5s ease-in-out infinite;
          animation-delay: 0.5s;
        }
      `}</style>
    </>
  )
}
