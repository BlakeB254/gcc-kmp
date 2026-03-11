import type { Metadata } from 'next'
import { HeroSection } from '@/components/landing/HeroSection'
import { DonateCTASection } from '@/components/landing/DonateCTASection'
import { NeighborhoodMapSection } from '@/components/landing/NeighborhoodMapSection'
import { BountyCTASection } from '@/components/landing/BountyCTASection'
import { ImpactCounterSection } from '@/components/landing/ImpactCounterSection'
import { FAQSection } from '@/components/landing/FAQSection'
import { FooterSection } from '@/components/landing/FooterSection'
import { Header } from '@/components/landing/Header'

export const metadata: Metadata = {
  title: 'Clean Streets. Real Money. Your Neighborhood.',
  description:
    'Global Community Cleaners connects North Lawndale residents with paid block-cleaning opportunities. Sponsor a block for $100 or earn money keeping your neighborhood clean.',
  openGraph: {
    title: 'Global Community Cleaners | Clean Streets. Real Money. Your Neighborhood.',
    description:
      'Sponsor a block for $100 or earn money keeping your neighborhood clean. Join the movement transforming North Lawndale, Chicago.',
  },
}

export default function HomePage() {
  return (
    <main className="flex min-h-screen flex-col">
      <Header />
      <HeroSection />

      {/* === DONATE - Any amount helps! === */}
      <DonateCTASection />

      {/* === NORTH LAWNDALE BLOCK MAP === */}
      <NeighborhoodMapSection />

      {/* === COMMUNITY BOUNTIES === */}
      <BountyCTASection />

      {/* === IMPACT & TRANSPARENCY === */}
      <ImpactCounterSection />

      {/* === FAQ (Top 4) === */}
      <FAQSection limit={4} />

      <FooterSection />
    </main>
  )
}
