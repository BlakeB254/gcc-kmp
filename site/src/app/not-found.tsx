import Link from 'next/link'

export default function NotFound() {
  return (
    <main className="flex min-h-[60vh] flex-col items-center justify-center gap-6 px-6 text-center">
      <div>
        <p className="text-sm font-medium uppercase tracking-wide text-emerald-700">
          404
        </p>
        <h1 className="mt-2 text-3xl font-semibold text-gray-900 sm:text-4xl">
          Page not found
        </h1>
        <p className="mt-3 max-w-md text-gray-600">
          The page you&apos;re looking for doesn&apos;t exist or has moved.
        </p>
      </div>
      <Link
        href="/"
        className="rounded-lg bg-emerald-600 px-5 py-2.5 text-sm font-medium text-white shadow-sm transition hover:bg-emerald-700"
      >
        Back to home
      </Link>
    </main>
  )
}
