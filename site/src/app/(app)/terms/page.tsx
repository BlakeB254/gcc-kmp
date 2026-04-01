import { Header } from '@/components/landing/Header'
import Link from 'next/link'

export const metadata = {
  title: 'Terms of Service | GCC',
  description: 'Terms of Service for Global Community Cleaners',
}

export default function TermsPage() {
  return (
    <main className="min-h-screen bg-white">
      <Header />
      <div className="pt-24 pb-16 px-4">
        <div className="max-w-3xl mx-auto">
          <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-8">
            Terms of Service
          </h1>

          <div className="prose prose-neutral max-w-none">
            <p className="text-neutral-600 mb-6">
              Last updated: January 2025
            </p>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">1. Acceptance of Terms</h2>
              <p className="text-neutral-700 mb-4">
                By accessing and using Global Community Cleaners ("GCC") services, you agree to be bound by these Terms of Service. If you do not agree to these terms, please do not use our services.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">2. Description of Service</h2>
              <p className="text-neutral-700 mb-4">
                GCC provides a platform connecting community members who want to clean their neighborhoods ("Workers") with individuals and organizations who want to sponsor block cleanings ("Sponsors"). Workers receive compensation for completing approved cleaning jobs.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">3. Worker Requirements</h2>
              <p className="text-neutral-700 mb-4">
                To become a Worker, you must:
              </p>
              <ul className="list-disc list-inside text-neutral-700 mb-4 space-y-2">
                <li>Be at least 18 years of age</li>
                <li>Reside in an active GCC service area</li>
                <li>Provide valid government-issued identification</li>
                <li>Provide proof of address</li>
                <li>Be legally authorized to work in the United States</li>
                <li>Complete the verification process</li>
              </ul>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">4. Payment Terms</h2>
              <p className="text-neutral-700 mb-4">
                Workers are compensated $100 per completed and approved block cleaning. Payments are processed through Stripe and typically arrive within 1-3 business days after job approval.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">5. Job Completion Requirements</h2>
              <p className="text-neutral-700 mb-4">
                To receive payment, Workers must:
              </p>
              <ul className="list-disc list-inside text-neutral-700 mb-4 space-y-2">
                <li>Take before and after photos at each checkpoint</li>
                <li>Complete the cleaning within the designated timeframe</li>
                <li>Follow all safety guidelines</li>
                <li>Properly dispose of collected trash</li>
              </ul>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">6. Sponsor Terms</h2>
              <p className="text-neutral-700 mb-4">
                Sponsors agree that their contributions will be used to fund block cleanings in the designated service areas. Sponsorships are non-refundable once a block cleaning has been assigned.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">7. Prohibited Conduct</h2>
              <p className="text-neutral-700 mb-4">
                Users may not:
              </p>
              <ul className="list-disc list-inside text-neutral-700 mb-4 space-y-2">
                <li>Provide false or misleading information</li>
                <li>Submit fraudulent job completions</li>
                <li>Harass or threaten other users</li>
                <li>Use the platform for any illegal purpose</li>
                <li>Attempt to circumvent the payment system</li>
              </ul>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">8. Termination</h2>
              <p className="text-neutral-700 mb-4">
                GCC reserves the right to terminate or suspend any account at any time for violation of these terms or any other reason at our sole discretion.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">9. Limitation of Liability</h2>
              <p className="text-neutral-700 mb-4">
                GCC is not liable for any injuries, damages, or losses incurred while participating in our program. Workers participate at their own risk and are responsible for their own safety.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">10. Contact</h2>
              <p className="text-neutral-700 mb-4">
                For questions about these Terms of Service, please contact us at{' '}
                <a href="mailto:support@globalcommunitycleaners.org" className="text-brand-green-600 hover:underline">
                  support@globalcommunitycleaners.org
                </a>
              </p>
            </section>
          </div>

          <div className="mt-12 pt-8 border-t border-neutral-200">
            <Link
              href="/"
              className="text-brand-green-600 hover:text-brand-green-700 font-medium"
            >
              &larr; Back to Home
            </Link>
          </div>
        </div>
      </div>
    </main>
  )
}
