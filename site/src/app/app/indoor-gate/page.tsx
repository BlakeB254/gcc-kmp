import type { Metadata } from 'next'
import Link from 'next/link'
import { Header } from '@/components/landing/Header'
import { IndoorGateAppPreview } from '@/components/indoor-gate/IndoorGateAppPreview'

export const metadata: Metadata = {
  title: 'Indoor Gate v4 | GCC App',
  description:
    'GCC scans stay outdoors. Indoor Gate v4 denies capture until the worker is proven outside, then recovers in a few seconds.',
}

export default function IndoorGateAppPage() {
  return (
    <main className="min-h-screen bg-neutral-50">
      <Header />
      <div className="pt-24 pb-16 px-4">
        <div className="mx-auto max-w-5xl">
          <p className="mb-2 text-xs font-semibold uppercase tracking-[0.2em] text-brand-green-700">
            App interface · Indoor Gate v4
          </p>
          <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900">
            Scans stay outside.
          </h1>
          <p className="mt-3 max-w-2xl text-neutral-600">
            The worker camera preview can open indoors, but nothing is saved,
            credited, or uploaded until OutdoorGate proves OUTDOOR. A ceiling
            plane flips INDOOR instantly. Walking back outside recovers in about
            three seconds.
          </p>

          <div className="mt-8 rounded-2xl border border-neutral-200 bg-white p-5 sm:p-8">
            <IndoorGateAppPreview />
          </div>

          <ul className="mt-8 grid gap-3 text-sm text-neutral-700 sm:grid-cols-2">
            <li className="rounded-lg border border-neutral-200 bg-white p-4">
              <strong>Deny by default.</strong> UNCERTAIN shows a small
              “Confirming you&apos;re outside…” chip. Complete Scan stays off.
            </li>
            <li className="rounded-lg border border-neutral-200 bg-white p-4">
              <strong>Hard indoor block.</strong> Full “Go outside” overlay plus
              an audited “I&apos;m outside — continue” override.
            </li>
            <li className="rounded-lg border border-neutral-200 bg-white p-4">
              <strong>10-second privacy purge.</strong> Frames from the walk
              inside are dropped before any upload.
            </li>
            <li className="rounded-lg border border-neutral-200 bg-white p-4">
              <strong>Override yields to a ceiling.</strong> The pin survives
              weak GPS, but a classified ceiling plane clears it.
            </li>
          </ul>

          <p className="mt-8 text-sm text-neutral-500">
            Policy details live in the{' '}
            <Link href="/privacy" className="text-brand-green-700 underline">
              privacy policy
            </Link>
            . This page is the review surface for the same copy the iOS scan
            screens already ship.
          </p>
        </div>
      </div>
    </main>
  )
}
