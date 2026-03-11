'use client'

interface InlineErrorProps {
  message: string | null
  className?: string
}

export function InlineError({ message, className = '' }: InlineErrorProps) {
  if (!message) return null

  return (
    <div className={`bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700 flex items-center gap-2 ${className}`}>
      <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938-4.99A9 9 0 1112 3a9 9 0 01-6.938 8.01z" />
      </svg>
      {message}
    </div>
  )
}
