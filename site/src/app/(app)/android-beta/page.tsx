import type { Metadata } from 'next'
import Link from 'next/link'
import { TESTERS_REQUIRED, TEST_DAYS, testerGroupUrl } from '@/lib/android-testers'
import { AndroidTesterForm } from './AndroidTesterForm'

// Recruiting page for the Android closed test. Google Play will not publish
// the app until enough testers have stayed in a closed test — the rules, and
// what to change once Android is public, are in lib/android-testers.ts.

// The self-join group link comes from the environment, so read it per request
// rather than freezing whatever was set at build time.
export const dynamic = 'force-dynamic'

const APP_STORE_URL = 'https://apps.apple.com/us/app/global-community-cleaners/id6760203806'

export const metadata: Metadata = {
  title: 'Test the GCC Android App | Global Community Cleaners',
  description:
    'Have an Android phone? Join the GCC test group and help us launch the Global Community Cleaners app on Google Play.',
  alternates: { canonical: 'https://globalcommunitycleaners.org/android-beta' },
  openGraph: {
    title: 'Help launch GCC on Android',
    description:
      'Have an Android phone? Join the GCC test group and help us put the app on Google Play for everyone.',
    type: 'website',
    siteName: 'Global Community Cleaners',
  },
}

export default function AndroidBetaPage() {
  const groupUrl = testerGroupUrl()

  const steps = [
    {
      title: 'Sign up with your Google account',
      body: 'Give us the email address your Android phone uses for Google Play. That is how Google knows to let you in.',
    },
    groupUrl
      ? {
          title: 'Join the group, then tap “Become a tester”',
          body: 'We show you both links as soon as you sign up, and email them to you too.',
        }
      : {
          title: 'Tap “Become a tester”',
          body: 'We add you to the test list and email you one link, usually within a day or two.',
        },
    {
      title: `Keep the app for ${TEST_DAYS} days`,
      body: 'Install it from Google Play, try it out, and tell us what breaks. Staying in the test is what counts.',
    },
  ]

  return (
    <div className="bg-white">
      <section className="bg-gradient-to-b from-brand-green-700 to-brand-green-800 text-white">
        <div className="section-container py-14 sm:py-20">
          <div className="mx-auto max-w-3xl text-center">
            <p className="text-xs font-bold uppercase tracking-[0.2em] text-brand-green-200">
              Android testers wanted
            </p>
            <h1 className="mt-4 text-4xl font-extrabold leading-tight sm:text-5xl">
              Have an Android phone? Help us launch.
            </h1>
            <p className="mx-auto mt-5 max-w-2xl text-lg leading-relaxed text-white/90">
              The GCC app is ready for Android. Before Google Play will offer it to everyone,
              Google needs at least {TESTERS_REQUIRED} people to try it for {TEST_DAYS} days.
              You can be one of them.
            </p>
          </div>
        </div>
      </section>

      <section className="section-container py-12 sm:py-16">
        <div className="mx-auto grid max-w-5xl gap-10 lg:grid-cols-2 lg:gap-14">
          <div>
            <h2 className="text-2xl font-bold text-neutral-900">How it works</h2>
            <ol className="mt-6 space-y-6">
              {steps.map((step, i) => (
                <li key={step.title} className="flex gap-4">
                  <span
                    aria-hidden="true"
                    className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-full bg-brand-green-500 text-sm font-bold text-white"
                  >
                    {i + 1}
                  </span>
                  <div>
                    <h3 className="font-semibold text-neutral-900">{step.title}</h3>
                    <p className="mt-1 leading-relaxed text-neutral-600">{step.body}</p>
                  </div>
                </li>
              ))}
            </ol>

            <div className="mt-10 rounded-xl border border-neutral-200 bg-neutral-50 p-5">
              <h3 className="font-semibold text-neutral-900">Good to know</h3>
              <ul className="mt-3 list-disc space-y-2 pl-5 text-sm leading-relaxed text-neutral-600">
                <li>It is free, and you can test from anywhere in the world.</li>
                <li>
                  This is a real early version. Some things will not work perfectly yet, and
                  hearing about them from you is the point.
                </li>
                <li>
                  We use your email only to add you to the test and to send you the link. See our{' '}
                  <Link href="/privacy" className="text-brand-green-600 underline hover:text-brand-green-700">
                    privacy policy
                  </Link>
                  .
                </li>
                <li>
                  On an iPhone? The app is already on the{' '}
                  <a
                    href={APP_STORE_URL}
                    className="text-brand-green-600 underline hover:text-brand-green-700"
                  >
                    App Store
                  </a>
                  .
                </li>
              </ul>
            </div>
          </div>

          <div>
            <AndroidTesterForm groupUrl={groupUrl} />
          </div>
        </div>
      </section>
    </div>
  )
}
