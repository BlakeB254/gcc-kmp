import { Header } from '@/components/landing/Header'
import Link from 'next/link'

export const metadata = {
  title: 'Privacy Policy | GCC',
  description: 'Privacy Policy for Global Community Cleaners',
}

export default function PrivacyPage() {
  return (
    <main className="min-h-screen bg-white">
      <Header />
      <div className="pt-24 pb-16 px-4">
        <div className="max-w-3xl mx-auto">
          <h1 className="text-3xl sm:text-4xl font-bold text-neutral-900 mb-8">
            Privacy Policy
          </h1>

          <div className="prose prose-neutral max-w-none">
            <p className="text-neutral-600 mb-6">
              Last updated: January 2025
            </p>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">1. Introduction</h2>
              <p className="text-neutral-700 mb-4">
                Global Community Cleaners ("GCC", "we", "our", or "us") is committed to protecting your privacy. This Privacy Policy explains how we collect, use, disclose, and safeguard your information when you use our services.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">2. Information We Collect</h2>
              <p className="text-neutral-700 mb-4">
                We collect information you provide directly to us, including:
              </p>
              <ul className="list-disc list-inside text-neutral-700 mb-4 space-y-2">
                <li><strong>Personal Information:</strong> Name, email address, phone number, and mailing address</li>
                <li><strong>Identity Verification:</strong> Government-issued ID and proof of address (for Workers)</li>
                <li><strong>Payment Information:</strong> Bank account details for receiving payments (processed through Stripe)</li>
                <li><strong>Location Data:</strong> GPS data during job completion to verify block cleanings</li>
                <li><strong>Photos:</strong> Before and after photos of block cleanings</li>
              </ul>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">3. How We Use Your Information</h2>
              <p className="text-neutral-700 mb-4">
                We use the information we collect to:
              </p>
              <ul className="list-disc list-inside text-neutral-700 mb-4 space-y-2">
                <li>Provide, maintain, and improve our services</li>
                <li>Verify your identity and eligibility to participate</li>
                <li>Process payments and payouts</li>
                <li>Send you notifications about job opportunities and updates</li>
                <li>Verify job completion and quality</li>
                <li>Communicate with you about our services</li>
                <li>Detect and prevent fraud</li>
              </ul>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">4. Information Sharing</h2>
              <p className="text-neutral-700 mb-4">
                We may share your information with:
              </p>
              <ul className="list-disc list-inside text-neutral-700 mb-4 space-y-2">
                <li><strong>Payment Processors:</strong> Stripe for processing payments</li>
                <li><strong>Service Providers:</strong> Companies that assist in operating our services</li>
                <li><strong>Legal Requirements:</strong> When required by law or to protect our rights</li>
              </ul>
              <p className="text-neutral-700 mb-4">
                We do not sell your personal information to third parties.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">5. Data Security</h2>
              <p className="text-neutral-700 mb-4">
                We implement appropriate technical and organizational measures to protect your personal information against unauthorized access, alteration, disclosure, or destruction. However, no method of transmission over the Internet is 100% secure.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">6. Data Retention</h2>
              <p className="text-neutral-700 mb-4">
                We retain your personal information for as long as necessary to provide our services and comply with legal obligations. You may request deletion of your account and associated data at any time.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">7. Your Rights</h2>
              <p className="text-neutral-700 mb-4">
                You have the right to:
              </p>
              <ul className="list-disc list-inside text-neutral-700 mb-4 space-y-2">
                <li>Access your personal information</li>
                <li>Correct inaccurate information</li>
                <li>Request deletion of your information</li>
                <li>Opt out of marketing communications</li>
                <li>Request a copy of your data</li>
              </ul>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">8. Cookies and Tracking</h2>
              <p className="text-neutral-700 mb-4">
                We use cookies and similar technologies to improve your experience, analyze usage patterns, and personalize content. You can control cookie preferences through your browser settings.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">9. Children's Privacy</h2>
              <p className="text-neutral-700 mb-4">
                Our services are not intended for individuals under 18 years of age. We do not knowingly collect personal information from children.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">10. Changes to This Policy</h2>
              <p className="text-neutral-700 mb-4">
                We may update this Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page and updating the "Last updated" date.
              </p>
            </section>

            <section className="mb-8">
              <h2 className="text-xl font-semibold text-neutral-900 mb-4">11. Contact Us</h2>
              <p className="text-neutral-700 mb-4">
                If you have questions about this Privacy Policy, please contact us at{' '}
                <a href="mailto:privacy@globalcommunitycleaners.org" className="text-brand-gold-600 hover:underline">
                  privacy@globalcommunitycleaners.org
                </a>
              </p>
            </section>
          </div>

          <div className="mt-12 pt-8 border-t border-neutral-200">
            <Link
              href="/"
              className="text-brand-gold-600 hover:text-brand-gold-700 font-medium"
            >
              &larr; Back to Home
            </Link>
          </div>
        </div>
      </div>
    </main>
  )
}
