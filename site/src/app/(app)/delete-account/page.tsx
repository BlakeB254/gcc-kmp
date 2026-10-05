import { Header } from '@/components/landing/Header'
import Link from 'next/link'

// Public account-deletion page. Google Play's User Data policy requires a web
// URL where anyone can request deletion without installing or opening the app;
// it is the link given in the Play Console Data safety form. Keep what it says
// about deletion in step with lib/delete-worker.ts.
export const metadata = {
  title: 'Delete Your Account',
  description: 'How to delete your Global Community Cleaners (GCC) account and associated data',
}

export default function DeleteAccountPage() {
  return (
    <main className="min-h-screen bg-white">
      <Header />
      <div className="pt-24 pb-16 px-4">
        <div className="max-w-3xl mx-auto">
          <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-8">
            Delete Your GCC Account
          </h1>

          <div className="prose prose-neutral max-w-none">
            <p className="text-neutral-700 mb-6">
              This page explains how to delete your account in the GCC app, published by Global
              Community Cleaners, and what happens to your data when you do.
            </p>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">Option 1: In the app</h2>
              <ol className="list-decimal list-inside text-neutral-700 mb-4 space-y-2">
                <li>Open the GCC app and sign in.</li>
                <li>Go to <strong>Settings</strong>.</li>
                <li>Tap <strong>Delete Account</strong> and confirm.</li>
              </ol>
              <p className="text-neutral-700 mb-4">
                Your account is deleted immediately.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">
                Option 2: By email (no app needed)
              </h2>
              <p className="text-neutral-700 mb-4">
                Email{' '}
                <a
                  href="mailto:support@globalcommunitycleaners.org?subject=Delete%20my%20GCC%20account"
                  className="text-brand-green-600 hover:underline"
                >
                  support@globalcommunitycleaners.org
                </a>{' '}
                from the email address on your account, with the subject{' '}
                <strong>&ldquo;Delete my GCC account&rdquo;</strong>. We may ask you to confirm the
                request before deleting, so that no one else can delete your account. We will
                process your request within 30 days and confirm by email.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">What is deleted</h2>
              <ul className="list-disc list-inside text-neutral-700 mb-4 space-y-2">
                <li>Your account and sign-in</li>
                <li>Your profile: name, email address, phone number, and mailing address</li>
                <li>
                  Your identity verification documents (government-issued ID and proof of address),
                  including the stored files
                </li>
              </ul>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">What may be retained</h2>
              <ul className="list-disc list-inside text-neutral-700 mb-4 space-y-2">
                <li>
                  Records of completed cleaning work, including route scans and cleanup photos
                  submitted as job evidence, which form part of our program and impact records
                </li>
                <li>
                  Payment and payout records we are required to keep for tax, accounting, and legal
                  purposes, and records held by our payment processor, Stripe, under its own
                  retention policies
                </li>
              </ul>
            </section>

            <p className="text-neutral-700 mb-4">
              For more on how we handle your information, see our{' '}
              <Link href="/privacy" className="text-brand-green-600 hover:underline">
                Privacy Policy
              </Link>
              .
            </p>
          </div>
        </div>
      </div>
    </main>
  )
}
