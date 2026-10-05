import type { Metadata } from 'next'
import { ImpactSupportersContent } from './ImpactSupportersContent'

export const metadata: Metadata = {
  title: 'Our Impact & Supporters',
  description:
    'See the live impact of Global Community Cleaners in North Lawndale — blocks cleaned, workers paid, and the sponsors and donors making it possible.',
  openGraph: {
    title: 'Our Impact & Supporters | Global Community Cleaners',
    description:
      'Live progress on blocks cleaned and workers paid, plus the honor wall of sponsors and donors funding cleaner streets in North Lawndale.',
  },
}

export default function ImpactSupportersPage() {
  return <ImpactSupportersContent />
}
