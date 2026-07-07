'use client'

import Link from 'next/link'
import { FAQAccordion } from '@/components/shared/FAQAccordion'
import { faqItems } from '@/data/faq'
import { SectionHeading } from './SectionHeading'

interface FAQSectionProps {
  /** Show only this many items (undefined = show all) */
  limit?: number
}

export function FAQSection({ limit }: FAQSectionProps) {
  return (
    <section id="faq" className="py-golden-2xl bg-white">
      <div className="section-container">
        {/* Section Header */}
        <div className="mb-golden-xl animate-fade-in-up">
          <SectionHeading
            eyebrow="Questions?"
            title="Frequently Asked Questions"
            description="Everything you need to know about becoming a worker or sponsoring a block"
          />
        </div>

        {/* FAQ Accordion */}
        <div className="max-w-golden-wide mx-auto">
          <FAQAccordion items={faqItems} limit={limit} />
        </div>

        {/* Show "See all FAQs" link when limited */}
        {limit && limit < faqItems.length && (
          <div className="text-center mt-golden-lg animate-fade-in-up">
            <Link
              href="/faq"
              className="inline-flex items-center gap-2 text-brand-green-600 hover:text-brand-green-700 font-medium link-underline"
            >
              See all FAQs
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
              </svg>
            </Link>
          </div>
        )}

        {/* Contact CTA - only on full FAQ page */}
        {(!limit || limit >= faqItems.length) && (
          <div className="text-center mt-golden-xl animate-fade-in-up">
            <p className="text-neutral-600 mb-golden-sm">
              Still have questions?
            </p>
            <a
              href="mailto:hello@globalcommunitycleaners.org"
              className="inline-flex items-center gap-2 text-brand-green-600 hover:text-brand-green-700 font-medium link-underline"
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
                />
              </svg>
              Contact Us
            </a>
          </div>
        )}
      </div>
    </section>
  )
}
