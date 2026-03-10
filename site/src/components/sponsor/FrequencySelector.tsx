'use client'

import { StepHeader } from './StepHeader'

interface FrequencySelectorProps {
  blockCount: number
  sponsorType: 'single' | 'monthly'
  onSponsorTypeChange: (type: 'single' | 'monthly') => void
}

export function FrequencySelector({ blockCount, sponsorType, onSponsorTypeChange }: FrequencySelectorProps) {
  const amount = blockCount * 100

  return (
    <section className="mb-16">
      <StepHeader step={3} title="Choose frequency" description="One-time or monthly recurring sponsorship" />

      <div className="grid grid-cols-2 gap-4">
        <button
          onClick={() => onSponsorTypeChange('single')}
          className={`p-5 rounded-xl border-2 text-center transition-all duration-200 hover-lift ${
            sponsorType === 'single'
              ? 'border-brand-gold-500 bg-brand-gold-50 shadow-lg'
              : 'border-neutral-200 bg-white hover:border-brand-gold-300'
          }`}
        >
          <p className="text-2xl font-bold text-neutral-900">${amount}</p>
          <p className="text-sm text-neutral-600">One-time</p>
        </button>
        <button
          onClick={() => onSponsorTypeChange('monthly')}
          className={`p-5 rounded-xl border-2 text-center transition-all duration-200 relative hover-lift ${
            sponsorType === 'monthly'
              ? 'border-brand-gold-500 bg-brand-gold-50 shadow-lg'
              : 'border-neutral-200 bg-white hover:border-brand-gold-300'
          }`}
        >
          <span className="absolute -top-2 -right-2 bg-brand-green-500 text-white text-xs px-2 py-0.5 rounded-full">
            Best value
          </span>
          <p className="text-2xl font-bold text-neutral-900">${amount}</p>
          <p className="text-sm text-neutral-600">Monthly</p>
        </button>
      </div>
    </section>
  )
}
