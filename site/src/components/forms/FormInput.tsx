'use client'

import { useState, useRef } from 'react'
import { cn } from '@/lib/utils'

export interface FormInputProps {
  label: string
  id: string
  type?: string
  value: string
  onChange: (value: string) => void
  placeholder?: string
  disabled?: boolean
  error?: string
  hint?: string
  maxLength?: number
  autoComplete?: string
}

export function FormInput({
  label,
  id,
  type = 'text',
  value,
  onChange,
  placeholder,
  disabled = false,
  error,
  hint,
  maxLength,
  autoComplete,
}: FormInputProps) {
  const [isFocused, setIsFocused] = useState(false)
  const [hasInteracted, setHasInteracted] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const showError = error && hasInteracted
  const isValid = hasInteracted && !error && value.length > 0

  return (
    <div className="space-y-1.5">
      <label
        htmlFor={id}
        className={cn(
          'block text-sm font-medium transition-colors duration-200',
          isFocused ? 'text-brand-gold-700' : 'text-neutral-700'
        )}
      >
        {label}
      </label>
      <div className="relative">
        <input
          ref={inputRef}
          type={type}
          id={id}
          value={value}
          onChange={(e) => {
            onChange(e.target.value)
            if (!hasInteracted) setHasInteracted(true)
          }}
          onFocus={() => setIsFocused(true)}
          onBlur={() => {
            setIsFocused(false)
            if (!hasInteracted && value) setHasInteracted(true)
          }}
          placeholder={placeholder}
          disabled={disabled}
          maxLength={maxLength}
          autoComplete={autoComplete}
          className={cn(
            'w-full rounded-xl border-2 bg-white text-neutral-900 px-4 py-3.5 text-base transition-all duration-200',
            'placeholder:text-neutral-400',
            'focus:outline-none',
            disabled && 'bg-neutral-50 cursor-not-allowed opacity-60',
            !showError && !isValid && 'border-neutral-200 focus:border-brand-gold-400 focus:ring-4 focus:ring-brand-gold-50',
            showError && 'border-red-400 focus:border-red-500 focus:ring-4 focus:ring-red-50 animate-shake',
            isValid && 'border-brand-green-400 focus:border-brand-green-500 focus:ring-4 focus:ring-brand-green-50',
            'pr-10'
          )}
        />
        {/* Validation icon */}
        <div className="absolute right-3 top-1/2 -translate-y-1/2">
          {showError && (
            <div className="w-5 h-5 rounded-full bg-red-100 flex items-center justify-center animate-bounce-in">
              <svg className="w-3 h-3 text-red-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
          )}
          {isValid && (
            <div className="w-5 h-5 rounded-full bg-brand-green-100 flex items-center justify-center animate-bounce-in">
              <svg className="w-3 h-3 text-brand-green-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
              </svg>
            </div>
          )}
        </div>
      </div>
      {/* Error or hint text */}
      <div className="min-h-[1.25rem]">
        {showError ? (
          <p className="text-xs text-red-600 animate-slide-down">{error}</p>
        ) : hint ? (
          <p className="text-xs text-neutral-500">{hint}</p>
        ) : null}
      </div>
    </div>
  )
}
