import { HeroSection } from '@/components/landing/HeroSection'
import { DonateCTASection } from '@/components/landing/DonateCTASection'
import { ImpactCounterSection } from '@/components/landing/ImpactCounterSection'
import { FAQSection } from '@/components/landing/FAQSection'
import { FooterSection } from '@/components/landing/FooterSection'
import { Header } from '@/components/landing/Header'

export default function HomePage() {
  return (
    <main className="flex min-h-screen flex-col">
      <Header />
      <HeroSection />

      {/* === DONATE - Any amount helps! === */}
      <DonateCTASection />

      {/* === IMPACT & TRANSPARENCY === */}
      <ImpactCounterSection />

      {/* === FAQ (Top 4) === */}
      <FAQSection limit={4} />

      <FooterSection />
    </main>
  )
}
