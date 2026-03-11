'use client'

import { MapPin, Users, Check } from 'lucide-react'
import { StepHeader } from './StepHeader'
import type { SponsorshipMode } from './types'

interface SponsorModeSelectorProps {
  mode: SponsorshipMode
  onModeChange: (mode: SponsorshipMode) => void
}

export function SponsorModeSelector({ mode, onModeChange }: SponsorModeSelectorProps) {
  return (
    <section className="mb-16">
      <StepHeader step={1} title="How would you like to sponsor?" description="Pick your preferred sponsorship method" />

      <div className="grid sm:grid-cols-2 gap-4" role="radiogroup" aria-label="Sponsorship mode">
        <ModeCard
          active={mode === 'specific'}
          icon={<MapPin className="w-5 h-5" />}
          title="Choose Specific Blocks"
          description="Pick the exact blocks you want cleaned on the map"
          onClick={() => onModeChange('specific')}
        />
        <ModeCard
          active={mode === 'general'}
          icon={<Users className="w-5 h-5" />}
          title="Let GCC Choose"
          description="We assign blocks based on community need"
          onClick={() => onModeChange('general')}
        />
      </div>
    </section>
  )
}

function ModeCard({
  active,
  icon,
  title,
  description,
  onClick,
}: {
  active: boolean
  icon: React.ReactNode
  title: string
  description: string
  onClick: () => void
}) {
  return (
    <button
      onClick={onClick}
      role="radio"
      aria-checked={active}
      className={`p-5 rounded-xl border-2 text-left transition-all duration-200 hover-lift ${
        active
          ? 'border-brand-gold-500 bg-brand-gold-50 shadow-lg ring-2 ring-brand-gold-200'
          : 'border-neutral-200 bg-white hover:border-brand-gold-300'
      }`}
    >
      <div className="flex items-start gap-3">
        <div className={`w-10 h-10 rounded-full flex items-center justify-center ${
          active ? 'bg-brand-gold-500 text-white' : 'bg-neutral-100 text-neutral-500'
        }`}>
          {icon}
        </div>
        <div className="flex-1">
          <p className="font-semibold text-neutral-900">{title}</p>
          <p className="text-sm text-neutral-600 mt-1">{description}</p>
        </div>
        {active && (
          <div className="w-6 h-6 rounded-full bg-brand-gold-500 flex items-center justify-center">
            <Check className="w-4 h-4 text-white" />
          </div>
        )}
      </div>
    </button>
  )
}
