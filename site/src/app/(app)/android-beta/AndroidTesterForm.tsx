'use client'

import { useEffect, useRef, useState } from 'react'
import { Check, Loader2 } from 'lucide-react'
import { ANDROID_PLAY_STORE_URL, ANDROID_TESTING_OPT_IN_URL, TEST_DAYS } from '@/lib/android-testers'

interface AndroidTesterFormProps {
  /** Self-join Google Group link, when one is attached to the closed-test track. */
  groupUrl: string | null
}

const linkButton =
  'mt-3 inline-flex items-center justify-center rounded-lg bg-brand-green-500 px-5 py-3 text-sm font-semibold text-white transition-colors hover:bg-brand-green-600'

export function AndroidTesterForm({ groupUrl }: AndroidTesterFormProps) {
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [website, setWebsite] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isDone, setIsDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const doneRef = useRef<HTMLDivElement>(null)

  // The confirmation replaces the form; bring its top into view below the fixed header.
  useEffect(() => {
    if (isDone) doneRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [isDone])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setIsSubmitting(true)
    try {
      const response = await fetch('/api/android-testers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, name: name || undefined, website, source: 'android-beta' }),
      })
      const data = await response.json().catch(() => ({}))
      if (!response.ok || !data.success) {
        throw new Error(data.error || 'Could not sign you up. Please try again.')
      }
      setIsDone(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong. Please try again.')
    } finally {
      setIsSubmitting(false)
    }
  }

  if (isDone) {
    return (
      <div
        ref={doneRef}
        className="scroll-mt-28 rounded-2xl border border-brand-green-200 bg-brand-green-50 p-6 sm:p-8"
        role="status"
        aria-live="polite"
      >
        <div className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-full bg-brand-green-500">
            <Check className="h-5 w-5 text-white" aria-hidden="true" />
          </span>
          <h2 className="text-xl font-bold text-neutral-900">You are signed up. Thank you.</h2>
        </div>

        {groupUrl ? (
          <ol className="mt-6 space-y-6 text-neutral-700">
            <li>
              <p className="font-semibold text-neutral-900">1. Join the tester group</p>
              <p className="mt-1 text-sm leading-relaxed">
                Use the Google account on your Android phone ({email}). Do this first — the next
                link only works once you are in the group.
              </p>
              <a href={groupUrl} target="_blank" rel="noopener noreferrer" className={linkButton}>
                Join the tester group
              </a>
            </li>
            <li>
              <p className="font-semibold text-neutral-900">2. Tap “Become a tester”</p>
              <p className="mt-1 text-sm leading-relaxed">
                Open this on your Android phone. If it says the app is not available, wait a few
                minutes after joining the group and try again.
              </p>
              <a
                href={ANDROID_TESTING_OPT_IN_URL}
                target="_blank"
                rel="noopener noreferrer"
                className={linkButton}
              >
                Become a tester
              </a>
            </li>
            <li>
              <p className="font-semibold text-neutral-900">3. Install and keep it {TEST_DAYS} days</p>
              <p className="mt-1 text-sm leading-relaxed">
                Install GCC from{' '}
                <a
                  href={ANDROID_PLAY_STORE_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-brand-green-600 underline hover:text-brand-green-700"
                >
                  Google Play
                </a>{' '}
                and keep it on your phone for {TEST_DAYS} days.
              </p>
            </li>
          </ol>
        ) : (
          <div className="mt-5 space-y-3 leading-relaxed text-neutral-700">
            <p>
              We are adding <span className="font-semibold">{email}</span> to the test list. This
              is done by hand and usually takes a day or two.
            </p>
            <p>
              Watch your inbox for an email from us with one link. Open it on your Android phone,
              tap <span className="font-semibold">Become a tester</span>, and install the app.
            </p>
            <p className="text-sm text-neutral-600">
              Know someone else with an Android phone? Send them this page — every tester helps.
            </p>
          </div>
        )}
      </div>
    )
  }

  return (
    <form
      onSubmit={handleSubmit}
      className="rounded-2xl border border-neutral-200 bg-white p-6 shadow-sm sm:p-8"
    >
      <h2 className="text-2xl font-bold text-neutral-900">Become a tester</h2>
      <p className="mt-2 text-neutral-600">It takes under a minute.</p>

      <div className="mt-6 space-y-5">
        <div>
          <label htmlFor="tester-email" className="mb-1 block text-sm font-medium text-neutral-700">
            Google account email <span className="text-red-600">*</span>
          </label>
          <input
            id="tester-email"
            type="email"
            autoComplete="email"
            inputMode="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@gmail.com"
            aria-describedby="tester-email-help"
            className="w-full rounded-lg border border-neutral-300 px-4 py-3 outline-none transition-all focus:border-brand-green-500 focus:ring-2 focus:ring-brand-green-500/20"
          />
          <p id="tester-email-help" className="mt-2 text-sm leading-relaxed text-neutral-600">
            Use the address your phone is signed in to Google Play with. On your phone: open the
            Play Store and tap your picture in the top corner to see it. A different address will
            not work.
          </p>
        </div>

        <div>
          <label htmlFor="tester-name" className="mb-1 block text-sm font-medium text-neutral-700">
            First name <span className="font-normal text-neutral-500">(optional)</span>
          </label>
          <input
            id="tester-name"
            type="text"
            autoComplete="given-name"
            maxLength={120}
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full rounded-lg border border-neutral-300 px-4 py-3 outline-none transition-all focus:border-brand-green-500 focus:ring-2 focus:ring-brand-green-500/20"
          />
        </div>

        {/* Honeypot: off-screen and skipped by keyboard and screen readers. */}
        <div className="absolute -left-[9999px]" aria-hidden="true">
          <label htmlFor="tester-website">Website</label>
          <input
            id="tester-website"
            type="text"
            tabIndex={-1}
            autoComplete="off"
            value={website}
            onChange={(e) => setWebsite(e.target.value)}
          />
        </div>

        {error && (
          <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
            {error}
          </div>
        )}

        <button
          type="submit"
          disabled={isSubmitting || !email}
          className="flex w-full items-center justify-center gap-2 rounded-lg bg-brand-green-500 py-4 font-bold text-white transition-colors hover:bg-brand-green-600 disabled:bg-neutral-200 disabled:text-neutral-500"
        >
          {isSubmitting ? (
            <>
              <Loader2 className="h-5 w-5 animate-spin" aria-hidden="true" />
              Signing you up…
            </>
          ) : (
            'Sign me up to test'
          )}
        </button>
      </div>
    </form>
  )
}
