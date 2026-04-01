'use client'

import { forwardRef } from 'react'
import { Shield, Calendar, ChevronDown } from 'lucide-react'
import { StepHeader } from './StepHeader'
import type { SponsorFormData } from './types'

interface SponsorInfoFormProps {
  formData: SponsorFormData
  onFormDataChange: (data: SponsorFormData) => void
  cleaningDate: string
  onCleaningDateChange: (date: string) => void
  cleaningTimeStart: string
  onCleaningTimeStartChange: (time: string) => void
  cleaningTimeEnd: string
  onCleaningTimeEndChange: (time: string) => void
  minDate: string
  maxDate: string
}

const TIME_OPTIONS_START = ['06:00','07:00','08:00','09:00','10:00','11:00','12:00','13:00','14:00','15:00','16:00']
const TIME_OPTIONS_END = ['10:00','11:00','12:00','13:00','14:00','15:00','16:00','17:00','18:00','19:00','20:00']

function formatTime(t: string): string {
  const hour = parseInt(t)
  if (hour > 12) return `${hour - 12}:00 PM`
  if (hour === 12) return '12:00 PM'
  return `${hour}:00 AM`
}

export const SponsorInfoForm = forwardRef<HTMLDivElement, SponsorInfoFormProps>(
  function SponsorInfoForm(
    { formData, onFormDataChange, cleaningDate, onCleaningDateChange, cleaningTimeStart, onCleaningTimeStartChange, cleaningTimeEnd, onCleaningTimeEndChange, minDate, maxDate },
    ref,
  ) {
    const update = (partial: Partial<SponsorFormData>) => {
      onFormDataChange({ ...formData, ...partial })
    }

    return (
      <section className="mb-16" ref={ref}>
        <StepHeader step={4} title="Your information" description="For your receipt and optional public recognition" />

        <div className="bg-white rounded-2xl border-2 border-neutral-200 shadow-lg overflow-hidden">
          {/* Header strip */}
          <div className="bg-gradient-to-r from-brand-green-800 to-brand-green-900 px-6 py-4">
            <p className="text-white font-semibold flex items-center gap-2">
              <Shield className="w-4 h-4 text-brand-green-400" />
              Secure Sponsor Information
            </p>
            <p className="text-white/70 text-sm">Your details are only used for receipts and optional public recognition</p>
          </div>

          <div className="p-6 space-y-0">
            {/* Required fields label */}
            <p className="text-xs text-neutral-400 mb-4 flex items-center gap-1">
              <span className="text-red-400">*</span> Required fields
            </p>

            <div className="grid sm:grid-cols-2 gap-4 pb-5 border-b border-neutral-200">
              <div className="group">
                <label className="block text-sm font-medium text-neutral-700 mb-1 group-focus-within:text-brand-green-600 transition-colors">
                  Your Name <span className="text-red-400">*</span>
                </label>
                <input
                  type="text"
                  value={formData.name}
                  onChange={(e) => update({ name: e.target.value })}
                  className="input-field focus-glow transition-all duration-200"
                  placeholder="John Smith"
                  required
                />
              </div>
              <div className="group">
                <label className="block text-sm font-medium text-neutral-700 mb-1 group-focus-within:text-brand-green-600 transition-colors">
                  Email <span className="text-red-400">*</span>
                </label>
                <input
                  type="email"
                  value={formData.email}
                  onChange={(e) => update({ email: e.target.value })}
                  className="input-field focus-glow transition-all duration-200"
                  placeholder="you@example.com"
                  required
                />
              </div>
            </div>

            <div className="group py-5 border-b border-neutral-200">
              <label className="block text-sm font-medium text-neutral-700 mb-1 group-focus-within:text-brand-green-600 transition-colors">
                Company/Organization <span className="text-neutral-400 text-xs font-normal">(optional)</span>
              </label>
              <input
                type="text"
                value={formData.company}
                onChange={(e) => update({ company: e.target.value })}
                className="input-field focus-glow transition-all duration-200"
                placeholder="Your business name"
              />
            </div>

            <div className="group py-5 border-b border-neutral-200">
              <label className="block text-sm font-medium text-neutral-700 mb-1 group-focus-within:text-brand-green-600 transition-colors">
                Message for the Worker <span className="text-neutral-400 text-xs font-normal">(optional)</span>
              </label>
              <textarea
                value={formData.message}
                onChange={(e) => update({ message: e.target.value })}
                className="input-field min-h-[80px] focus-glow transition-all duration-200 resize-none"
                placeholder="Thank you for keeping our community clean!"
              />
            </div>

            <label className="flex items-center gap-2 cursor-pointer py-5 border-b border-neutral-200">
              <input
                type="checkbox"
                checked={formData.isAnonymous}
                onChange={(e) => update({ isAnonymous: e.target.checked })}
                className="rounded border-neutral-300 text-brand-green-600 focus:ring-brand-green-500"
              />
              <span className="text-sm text-neutral-600">Sponsor anonymously</span>
            </label>

            {/* Optional cleaning schedule */}
            <details className="pt-5">
              <summary className="text-sm font-medium text-neutral-700 cursor-pointer flex items-center gap-2 hover:text-brand-green-600 transition-colors">
                <Calendar className="w-4 h-4" />
                Schedule a cleaning date
                <span className="text-xs text-neutral-400 ml-1">(optional)</span>
                <ChevronDown className="w-4 h-4 ml-auto" />
              </summary>
              <div className="mt-4 space-y-4">
                <div>
                  <label className="block text-sm text-neutral-600 mb-1">Preferred Date</label>
                  <input
                    type="date"
                    value={cleaningDate}
                    onChange={(e) => onCleaningDateChange(e.target.value)}
                    min={minDate}
                    max={maxDate}
                    className="input-field w-full"
                  />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs text-neutral-500 mb-1">From</label>
                    <select
                      value={cleaningTimeStart}
                      onChange={(e) => onCleaningTimeStartChange(e.target.value)}
                      className="input-field w-full text-sm"
                    >
                      <option value="">Any time</option>
                      {TIME_OPTIONS_START.map(t => (
                        <option key={t} value={t}>{formatTime(t)}</option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="block text-xs text-neutral-500 mb-1">To</label>
                    <select
                      value={cleaningTimeEnd}
                      onChange={(e) => onCleaningTimeEndChange(e.target.value)}
                      className="input-field w-full text-sm"
                    >
                      <option value="">Any time</option>
                      {TIME_OPTIONS_END.map(t => (
                        <option key={t} value={t}>{formatTime(t)}</option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>
            </details>
          </div>
        </div>
      </section>
    )
  }
)
