import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: 'Build a Sponsorship Package',
  description:
    'Build a custom sponsorship: choose how many blocks to fund, then add branding, services, events and media. $100 of every block goes to the resident who cleans it.',
  alternates: { canonical: '/sponsor/calculator' },
}

export default function SponsorCalculatorLayout({ children }: { children: React.ReactNode }) {
  return children
}
