import { Header } from '@/components/landing/Header'
import { FooterSection } from '@/components/landing/FooterSection'
import { NLHeroSection } from '@/components/north-lawndale/NLHeroSection'
import { NLLegacySection } from '@/components/north-lawndale/NLLegacySection'
import { NLStatsSection } from '@/components/north-lawndale/NLStatsSection'
import { NLHowItWorksSection } from '@/components/north-lawndale/NLHowItWorksSection'
import { NLImpactSection } from '@/components/north-lawndale/NLImpactSection'
import { NLSignupSection } from '@/components/north-lawndale/NLSignupSection'

export default function NorthLawndalePage() {
  return (
    <main className="flex min-h-screen flex-col">
      <Header />
      <NLHeroSection />
      <NLLegacySection />
      <NLStatsSection />
      <NLHowItWorksSection />
      <NLImpactSection />
      <NLSignupSection />
      <FooterSection />
    </main>
  )
}
