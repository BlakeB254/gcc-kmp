import { Metadata } from 'next'
import { FAQSection } from '@/components/landing/FAQSection'

export const metadata: Metadata = {
  title: 'FAQ',
  description: 'Frequently asked questions about becoming a GCC worker or sponsoring block cleanings in North Lawndale, Chicago.',
}

export default function FAQPage() {
  return (
    <div className="bg-white">
      <FAQSection />
    </div>
  )
}
