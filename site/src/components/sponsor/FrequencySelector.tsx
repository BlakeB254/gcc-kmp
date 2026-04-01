'use client'

import { StepHeader } from './StepHeader'

export type SponsorFrequency = 'single' | 'weekly' | 'biweekly' | 'monthly'

interface FrequencySelectorProps {
  blockCount: number
  sponsorType: SponsorFrequency
  onSponsorTypeChange: (type: SponsorFrequency) => void
}

const FREQUENCIES: { value: SponsorFrequency; label: string; sub: string; badge?: string }[] = [
  { value: 'single', label: 'One-time', sub: '' },
  { value: 'weekly', label: 'Weekly', sub: '/wk' },
  { value: 'biweekly', label: 'Bi-weekly', sub: '/2wk' },
  { value: 'monthly', label: 'Monthly', sub: '/mo', badge: 'Popular' },
]

export function FrequencySelector({ blockCount, sponsorType, onSponsorTypeChange }: FrequencySelectorProps) {
  const amount = blockCount * 100

  return (
    <section className="mb-16">
      <StepHeader step={3} title="Choose frequency" description="One-time or set a recurring schedule" />

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3" role="radiogroup" aria-label="Sponsorship frequency">
        {FREQUENCIES.map(({ value, label, sub, badge }) => (
          <button
            key={value}
            onClick={() => onSponsorTypeChange(value)}
            role="radio"
            aria-checked={sponsorType === value}
            aria-label={`${label}${sub ? ` — $${amount}${sub}` : ` — $${amount}`}`}
            className={`p-4 rounded-xl border-2 text-center transition-all duration-200 relative hover-lift ${
              sponsorType === value
                ? 'border-brand-green-500 bg-brand-green-50 shadow-lg'
                : 'border-neutral-200 bg-white hover:border-brand-green-300'
            }`}
          >
            {badge && (
              <span className="absolute -top-2 -right-2 bg-brand-green-500 text-white text-xs px-2 py-0.5 rounded-full">
                {badge}
              </span>
            )}
            <p className="text-xl font-bold text-neutral-900">${amount}{sub}</p>
            <p className="text-sm text-neutral-600">{label}</p>
          </button>
        ))}
      </div>
    </section>
  )
}
