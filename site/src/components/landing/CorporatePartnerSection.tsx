'use client'

import Link from 'next/link'

export function CorporatePartnerSection() {
  const comparisonItems = [
    {
      traditional: '$100 on digital ads',
      gcc: '$100 to a neighbor',
      icon: '💵',
    },
    {
      traditional: 'Impression that scrolls by',
      gcc: 'Real street-level impact',
      icon: '👀',
    },
    {
      traditional: 'No tax benefit',
      gcc: 'Fully tax deductible',
      icon: '📋',
    },
    {
      traditional: 'Forgotten instantly',
      gcc: 'Community remembers',
      icon: '❤️',
    },
  ]

  const corporateBenefits = [
    {
      icon: '🎯',
      title: 'Targeted Community Presence',
      description: 'Sponsor blocks around your storefronts, offices, or strategic locations. Be known as the business that cleans the neighborhood.',
    },
    {
      icon: '📊',
      title: 'Measurable Impact Reports',
      description: 'Quarterly impact reports with photos, worker hours, streets cleaned—real metrics for your ESG reporting.',
    },
    {
      icon: '💼',
      title: '100% Tax Deductible',
      description: 'Every dollar is a charitable contribution. Transform your advertising line item into a tax-advantaged social investment.',
    },
    {
      icon: '🏆',
      title: 'Authentic Brand Story',
      description: 'Not greenwashing—real jobs for real people. Your customers will see the difference on their streets.',
    },
  ]

  return (
    <section className="py-28 lg:py-32 bg-gradient-to-br from-neutral-900 via-neutral-800 to-brand-green-900 text-white overflow-hidden">
      <div className="section-container">
        {/* Header */}
        <div className="text-center mb-16 lg:mb-20">
          <div className="inline-flex items-center gap-2 rounded-full bg-brand-green-500/20 border border-brand-green-500/30 px-4 py-2 mb-6">
            <span className="text-xl">🏢</span>
            <span className="text-sm font-medium text-brand-green-300">For Corporations & Businesses</span>
          </div>

          <h2 className="text-3xl sm:text-4xl md:text-5xl font-bold mb-6">
            Turn Your Ad Budget Into{' '}
            <span className="text-brand-green-400">Community Wages</span>
          </h2>

          <p className="text-lg text-neutral-300 max-w-3xl mx-auto">
            What if your marketing spend paid for living wages instead of algorithm fees?
            Every $100 you redirect becomes $100 in a neighbor's pocket—not a tech company's revenue.
          </p>
        </div>

        {/* Comparison Table */}
        <div className="max-w-4xl mx-auto mb-16 lg:mb-20">
          <div className="bg-white/5 rounded-2xl border border-white/10 overflow-hidden">
            {/* Header Row */}
            <div className="grid grid-cols-3 gap-4 p-4 bg-white/5 border-b border-white/10">
              <div className="text-center text-neutral-400 text-sm font-medium"></div>
              <div className="text-center">
                <span className="inline-flex items-center gap-2 text-red-400 font-medium">
                  <span className="text-lg">📱</span> Traditional Ads
                </span>
              </div>
              <div className="text-center">
                <span className="inline-flex items-center gap-2 text-brand-green-400 font-medium">
                  <span className="text-lg">💚</span> GCC Sponsorship
                </span>
              </div>
            </div>

            {/* Comparison Rows */}
            {comparisonItems.map((item, index) => (
              <div
                key={index}
                className="grid grid-cols-3 gap-4 p-4 border-b border-white/5 last:border-b-0 items-center"
              >
                <div className="text-center text-2xl">{item.icon}</div>
                <div className="text-center text-neutral-400 text-sm line-through decoration-red-500/50">
                  {item.traditional}
                </div>
                <div className="text-center text-brand-green-400 font-medium text-sm">
                  {item.gcc}
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* The Math */}
        <div className="max-w-4xl mx-auto mb-16 lg:mb-20">
          <div className="bg-gradient-to-r from-brand-green-500/20 to-brand-green-600/20 rounded-2xl p-6 sm:p-8 border border-brand-green-500/30">
            <h3 className="text-2xl font-bold text-center mb-6">The Simple Math</h3>

            <div className="grid sm:grid-cols-3 gap-6 text-center">
              <div className="bg-white/10 rounded-xl p-4 sm:p-6">
                <p className="text-3xl sm:text-4xl font-bold text-brand-green-400 mb-2">$10,000</p>
                <p className="text-sm text-neutral-300">Monthly ad spend → GCC</p>
              </div>
              <div className="bg-white/10 rounded-xl p-4 sm:p-6">
                <p className="text-3xl sm:text-4xl font-bold text-brand-green-400 mb-2">100</p>
                <p className="text-sm text-neutral-300">Blocks cleaned per month</p>
              </div>
              <div className="bg-white/10 rounded-xl p-4 sm:p-6">
                <p className="text-3xl sm:text-4xl font-bold text-white mb-2">$10,000</p>
                <p className="text-sm text-neutral-300">Directly to workers + Tax writeoff</p>
              </div>
            </div>

            <p className="text-center text-neutral-300 mt-6 text-sm">
              <strong className="text-white">Zero</strong> goes to platform fees.
              <strong className="text-white"> Zero</strong> goes to algorithms.
              <strong className="text-brand-green-400"> 100%</strong> becomes wages in North Lawndale.
            </p>
          </div>
        </div>

        {/* Corporate Benefits Grid */}
        <div className="max-w-5xl mx-auto mb-16 lg:mb-20">
          <h3 className="text-2xl font-bold text-center mb-10">Why Smart Companies Are Switching</h3>

          <div className="grid sm:grid-cols-2 gap-6">
            {corporateBenefits.map((benefit, index) => (
              <div
                key={index}
                className="bg-white/5 rounded-xl p-6 border border-white/10 hover:bg-white/10 hover:border-brand-green-500/30 transition-all"
              >
                <div className="flex items-start gap-4">
                  <span className="text-3xl">{benefit.icon}</span>
                  <div>
                    <h4 className="font-semibold text-lg mb-2">{benefit.title}</h4>
                    <p className="text-neutral-400 text-sm leading-relaxed">{benefit.description}</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Add-ons Highlight */}
        <div className="max-w-4xl mx-auto mb-16 lg:mb-20">
          <div className="bg-white/5 rounded-2xl p-6 sm:p-8 border border-white/10">
            <div className="flex flex-col sm:flex-row items-center gap-6">
              <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-brand-green-400 to-brand-green-600 flex items-center justify-center flex-shrink-0">
                <span className="text-4xl">✨</span>
              </div>
              <div className="text-center sm:text-left">
                <h3 className="text-xl font-bold mb-2">Premium Add-Ons for Visibility</h3>
                <p className="text-neutral-300 text-sm mb-4">
                  Want branded recognition? Add premium services on top of your sponsorship.
                  The base $100/block <strong className="text-brand-green-400">always goes 100% to workers</strong>—
                  add-ons fund branded vests, signage, graffiti removal, and more.
                </p>
                <div className="flex flex-wrap gap-2 justify-center sm:justify-start">
                  <span className="px-3 py-1 rounded-full bg-brand-green-500/20 text-brand-green-300 text-xs">Logo vests</span>
                  <span className="px-3 py-1 rounded-full bg-brand-green-500/20 text-brand-green-300 text-xs">Block signage</span>
                  <span className="px-3 py-1 rounded-full bg-brand-green-500/20 text-brand-green-300 text-xs">Social recognition</span>
                  <span className="px-3 py-1 rounded-full bg-brand-green-500/20 text-brand-green-300 text-xs">Impact reports</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* CTA */}
        <div className="text-center">
          <p className="text-neutral-400 mb-6 max-w-2xl mx-auto">
            Ready to redirect your advertising budget to real community impact?
          </p>

          <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
            <Link
              href="/sponsor/enterprise"
              className="px-8 py-4 rounded-xl bg-brand-green-500 text-white font-semibold hover:bg-brand-green-600 transition-all text-lg"
            >
              Start Corporate Partnership
            </Link>
            <Link
              href="/sponsor/calculator"
              className="px-8 py-4 rounded-xl border-2 border-white/30 text-white font-semibold hover:bg-white/10 transition-all text-lg"
            >
              Build Custom Package
            </Link>
          </div>

          <p className="mt-6 text-sm text-neutral-500">
            Contact us at{' '}
            <a href="mailto:partnerships@globalcommunitycleaners.org" className="text-brand-green-400 hover:underline">
              partnerships@globalcommunitycleaners.org
            </a>
            {' '}for bulk sponsorships
          </p>
        </div>
      </div>
    </section>
  )
}
