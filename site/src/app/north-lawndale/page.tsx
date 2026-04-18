import { NLHeader } from '@/components/north-lawndale/NLHeader'
import { NLFooterSection } from '@/components/north-lawndale/NLFooterSection'
import { NLHeroSection } from '@/components/north-lawndale/NLHeroSection'
import { NLLegacySection } from '@/components/north-lawndale/NLLegacySection'
import { NLStatsSection } from '@/components/north-lawndale/NLStatsSection'
import { NLHowItWorksSection } from '@/components/north-lawndale/NLHowItWorksSection'
import { NLImpactSection } from '@/components/north-lawndale/NLImpactSection'
import { NLSignupSection } from '@/components/north-lawndale/NLSignupSection'

export default function NorthLawndalePage() {
  return (
    <main className="flex min-h-screen flex-col">
      <NLHeader />
      <NLHeroSection />
      <NLLegacySection />
      <NLStatsSection />
      <NLHowItWorksSection />
      <NLImpactSection />
      <NLSignupSection />
      <NLFooterSection />
    </main>
  )
}
