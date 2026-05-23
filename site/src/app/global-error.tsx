'use client'

import { useEffect } from 'react'

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string }
  reset: () => void
}) {
  useEffect(() => {
    console.error(error)
  }, [error])

  return (
    <html lang="en">
      <body className="min-h-screen bg-white antialiased">
        <main className="flex min-h-screen flex-col items-center justify-center gap-6 px-6 text-center">
          <h1 className="text-3xl font-semibold text-gray-900 sm:text-4xl">
            Application error
          </h1>
          <p className="max-w-md text-gray-600">
            A critical error occurred. Please refresh the page or return to the
            homepage.
          </p>
          {error.digest && (
            <p className="font-mono text-xs text-gray-400">
              Reference: {error.digest}
            </p>
          )}
          <button
            onClick={reset}
            className="rounded-lg bg-emerald-600 px-5 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-emerald-700"
          >
            Try again
          </button>
        </main>
      </body>
    </html>
  )
}
