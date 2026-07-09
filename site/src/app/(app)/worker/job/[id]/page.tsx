import Link from 'next/link'

type JobPageProps = {
  params: Promise<{ id: string }>
}

export default async function JobNativeHandoffPage({ params }: JobPageProps) {
  const { id } = await params
  const appUrl = `gcc://jobs/${encodeURIComponent(id)}`

  return (
    <main className="min-h-screen bg-neutral-950 text-white">
      <div className="mx-auto flex min-h-screen w-full max-w-xl flex-col justify-center px-6 py-10">
        <div className="mb-8">
          <p className="text-sm font-medium uppercase tracking-[0.2em] text-brand-green-300">
            Active Job
          </p>
          <h1 className="mt-3 text-3xl font-bold leading-tight">
            Continue this cleanup in the GCC app
          </h1>
          <p className="mt-4 text-base leading-7 text-neutral-300">
            Job #{id} uses the native route scan, camera, GPS, and evidence flow. The old
            browser photo workflow has been retired.
          </p>
        </div>

        <div className="space-y-3">
          <a
            href={appUrl}
            className="block rounded-lg bg-brand-green-500 px-5 py-3 text-center font-semibold text-white transition hover:bg-brand-green-600"
          >
            Open in GCC App
          </a>
          <Link
            href="/worker/dashboard"
            className="block rounded-lg border border-neutral-700 px-5 py-3 text-center font-semibold text-neutral-100 transition hover:bg-neutral-900"
          >
            Back to Dashboard
          </Link>
          <Link
            href="/worker/available"
            className="block rounded-lg px-5 py-3 text-center font-semibold text-neutral-300 transition hover:text-white"
          >
            View Available Work
          </Link>
        </div>
      </div>
    </main>
  )
}
