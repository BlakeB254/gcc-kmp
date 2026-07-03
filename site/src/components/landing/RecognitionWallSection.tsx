import Link from 'next/link'
import { ArrowRight } from 'lucide-react'
import { SupporterRecognitionWall } from '@/components/shared/SupporterRecognitionWall'

export function RecognitionWallSection() {
  return (
    <section className="py-20 lg:py-24 bg-white">
      <div className="section-container">
        <SupporterRecognitionWall
          limit={8}
          minAmount={2000}
          title="Our Honor Wall"
          description="Major sponsors ($2,000+) putting real money behind cleaner streets in North Lawndale."
        />

        <div className="text-center mt-10">
          <Link
            href="/impact-supporters"
            className="inline-flex items-center gap-2 text-brand-green-700 font-semibold hover:text-brand-green-800 transition-colors"
          >
            View All Supporters
            <ArrowRight className="w-4 h-4" />
          </Link>
        </div>
      </div>
    </section>
  )
}
