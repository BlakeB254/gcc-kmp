'use client'

import { cn } from '@/lib/utils'

export interface StepDefinition {
  id: string
  label: string
}

export interface StepIndicatorProps {
  steps: StepDefinition[]
  currentStepIndex: number
}

export function StepIndicator({ steps, currentStepIndex }: StepIndicatorProps) {
  return (
    <div className="mb-6 sm:mb-8">
      {/* Desktop view */}
      <div className="hidden sm:flex justify-between mb-2">
        {steps.map((step, index) => (
          <div
            key={step.id}
            className={cn(
              'flex items-center',
              index < steps.length - 1 ? 'flex-1' : ''
            )}
          >
            <div
              className={cn(
                'w-10 h-10 rounded-full flex items-center justify-center text-sm font-semibold transition-all duration-300',
                index < currentStepIndex && 'bg-brand-green-500 text-white',
                index === currentStepIndex && 'bg-brand-green-500 text-white ring-4 ring-brand-green-100',
                index > currentStepIndex && 'bg-neutral-200 text-neutral-500'
              )}
            >
              {index < currentStepIndex ? (
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5} d="M5 13l4 4L19 7" />
                </svg>
              ) : (
                index + 1
              )}
            </div>
            {index < steps.length - 1 && (
              <div className="flex-1 mx-3 h-1 rounded-full bg-neutral-200 overflow-hidden">
                <div
                  className={cn(
                    'h-full bg-brand-green-500 transition-all duration-500 ease-out',
                    index < currentStepIndex ? 'w-full' : 'w-0'
                  )}
                />
              </div>
            )}
          </div>
        ))}
      </div>
      <div className="hidden sm:flex justify-between text-xs text-neutral-500 px-1">
        {steps.map((step, index) => (
          <span
            key={step.id}
            className={cn(
              'transition-colors duration-200',
              index <= currentStepIndex && 'text-neutral-700 font-medium'
            )}
          >
            {step.label}
          </span>
        ))}
      </div>

      {/* Mobile view - simplified */}
      <div className="sm:hidden">
        <div className="flex items-center justify-between mb-3">
          <span className="text-sm font-medium text-neutral-700">
            Step {currentStepIndex + 1} of {steps.length}
          </span>
          <span className="text-sm text-brand-green-600 font-medium">
            {steps[currentStepIndex].label}
          </span>
        </div>
        <div className="h-2 rounded-full bg-neutral-200 overflow-hidden">
          <div
            className="h-full bg-gradient-to-r from-brand-green-500 to-brand-green-600 transition-all duration-500 ease-out"
            style={{ width: `${((currentStepIndex + 1) / steps.length) * 100}%` }}
          />
        </div>
      </div>
    </div>
  )
}
