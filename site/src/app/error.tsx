'use client'

import { useEffect } from 'react'

export default function Error({
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
    <main className="flex min-h-[60vh] flex-col items-center justify-center gap-6 px-6 text-center">
      <div>
        <p className="text-sm font-medium uppercase tracking-wide text-emerald-700">
          Something went wrong
        </p>
        <h1 className="mt-2 text-3xl font-semibold text-gray-900 sm:text-4xl">
          We hit a snag loading this page
        </h1>
        <p className="mt-3 max-w-md text-gray-600">
          The error has been logged. You can try again, or head back to the homepage.
        </p>
        {error.digest && (
          <p className="mt-2 font-mono text-xs text-gray-400">
            Reference: {error.digest}
          </p>
        )}
      </div>
      <div className="flex gap-3">
        <button
          onClick={reset}
          className="rounded-lg bg-emerald-600 px-5 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-emerald-700"
        >
          Try again
        </button>
        <a
          href="/"
          className="rounded-lg border border-gray-300 px-5 py-2.5 text-sm font-medium text-gray-700 transition hover:bg-gray-50"
        >
          Back to home
        </a>
      </div>
    </main>
  )
}
