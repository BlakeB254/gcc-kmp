import { Metadata } from 'next'
import Link from 'next/link'
import {
  Home,
  MapPin,
  Users,
  Heart,
  Star,
  Shield,
  ArrowRight,
  CheckCircle,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

export const metadata: Metadata = {
  title: 'For Residents',
  description:
    'How North Lawndale residents benefit from GCC and ways to get involved.',
}

const benefits = [
  {
    icon: Home,
    title: 'Cleaner Streets',
    description:
      'Regular cleaning of neighborhood blocks means less litter, debris, and eyesores near your home.',
  },
  {
    icon: Shield,
    title: 'Safer Community',
    description:
      'Clean streets reduce crime and increase pride in the neighborhood. Studies show well-maintained areas have lower crime rates.',
  },
  {
    icon: Users,
    title: 'Local Jobs',
    description:
      'Your neighbors earn money cleaning blocks. The money stays in the community and supports local families.',
  },
  {
    icon: Star,
    title: 'Property Values',
    description:
      'Clean neighborhoods attract investment and can help increase property values over time.',
  },
]

const waysToHelp = [
  {
    title: 'Join as a Worker',
    description: 'Earn $100 per block cleaned. Flexible hours, instant pay.',
    href: '/worker/benefits',
    cta: 'Start Earning',
  },
  {
    title: 'Nominate a Block',
    description: 'Know a block that needs attention? Tell us about it.',
    href: '/nominate',
    cta: 'Nominate Now',
  },
  {
    title: 'Sponsor a Block',
    description: 'Fund the cleaning of specific blocks in your area.',
    href: '/sponsor/benefits',
    cta: 'Become a Sponsor',
  },
  {
    title: 'Spread the Word',
    description: 'Tell your neighbors about GCC. Share on social media.',
    href: '/join',
    cta: 'Join Waitlist',
  },
]

export default function ResidentsPage() {
  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Hero */}
      <section className="section-container py-16 text-center">
        <div className="mx-auto max-w-3xl">
          <h1 className="mb-4 text-4xl font-bold tracking-tight sm:text-5xl">
            Your Neighborhood,{' '}
            <span className="text-brand-green-600">Transformed</span>
          </h1>
          <p className="mb-8 text-lg text-muted-foreground">
            GCC is building a cleaner, safer North Lawndale - one block at a time.
            Here's how it benefits you and how you can get involved.
          </p>
        </div>
      </section>

      {/* Benefits */}
      <section className="section-container pb-16">
        <h2 className="mb-8 text-center text-2xl font-bold">How You Benefit</h2>
        <div className="grid gap-6 md:grid-cols-2">
          {benefits.map((benefit) => {
            const Icon = benefit.icon
            return (
              <Card key={benefit.title}>
                <CardHeader className="pb-2">
                  <div className="mb-2 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-green-100">
                    <Icon className="h-6 w-6 text-brand-green-600" />
                  </div>
                  <CardTitle className="text-lg">{benefit.title}</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-muted-foreground">
                    {benefit.description}
                  </p>
                </CardContent>
              </Card>
            )
          })}
        </div>
      </section>

      {/* How it works for residents */}
      <section className="bg-brand-green-800 py-16 text-white">
        <div className="section-container">
          <h2 className="mb-8 text-center text-2xl font-bold">
            How GCC Works in Your Neighborhood
          </h2>
          <div className="mx-auto max-w-3xl space-y-6">
            <div className="flex gap-4">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/20 text-lg font-bold">
                1
              </div>
              <div>
                <h3 className="font-semibold">Sponsors Fund Blocks</h3>
                <p className="text-white/80">
                  Individuals and businesses contribute $100 per block to our cleaning fund.
                </p>
              </div>
            </div>
            <div className="flex gap-4">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/20 text-lg font-bold">
                2
              </div>
              <div>
                <h3 className="font-semibold">Workers Claim Jobs</h3>
                <p className="text-white/80">
                  Verified local residents claim blocks to clean through our app.
                </p>
              </div>
            </div>
            <div className="flex gap-4">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/20 text-lg font-bold">
                3
              </div>
              <div>
                <h3 className="font-semibold">Blocks Get Cleaned</h3>
                <p className="text-white/80">
                  Workers sweep, pick up litter, and document their work with GPS and photos.
                </p>
              </div>
            </div>
            <div className="flex gap-4">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-white/20 text-lg font-bold">
                4
              </div>
              <div>
                <h3 className="font-semibold">Workers Get Paid</h3>
                <p className="text-white/80">
                  Upon verification, workers receive instant payment to their bank account.
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Ways to Help */}
      <section className="section-container py-16">
        <h2 className="mb-8 text-center text-2xl font-bold">Ways to Get Involved</h2>
        <div className="grid gap-6 md:grid-cols-2">
          {waysToHelp.map((item) => (
            <Card key={item.title} className="flex flex-col">
              <CardHeader>
                <CardTitle>{item.title}</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-1 flex-col">
                <p className="mb-4 flex-1 text-sm text-muted-foreground">
                  {item.description}
                </p>
                <Button variant="secondary" asChild>
                  <Link href={item.href}>
                    {item.cta}
                    <ArrowRight className="ml-2 h-4 w-4" />
                  </Link>
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      </section>

      {/* FAQ Preview */}
      <section className="bg-muted/50 py-16">
        <div className="section-container">
          <h2 className="mb-8 text-center text-2xl font-bold">
            Frequently Asked Questions
          </h2>
          <div className="mx-auto max-w-2xl space-y-4">
            <Card>
              <CardContent className="pt-6">
                <h3 className="mb-2 font-semibold">Is GCC a city program?</h3>
                <p className="text-sm text-muted-foreground">
                  No, GCC is an independent community initiative. We work alongside
                  city services, not to replace them, but to supplement cleaning
                  efforts in areas that need extra attention.
                </p>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="pt-6">
                <h3 className="mb-2 font-semibold">
                  How are blocks prioritized?
                </h3>
                <p className="text-sm text-muted-foreground">
                  We prioritize based on community nominations, available funding,
                  and areas with the greatest need. High-traffic areas and blocks
                  with safety concerns get priority.
                </p>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="pt-6">
                <h3 className="mb-2 font-semibold">
                  Can I request my block be cleaned?
                </h3>
                <p className="text-sm text-muted-foreground">
                  Yes! Use our nomination form to request specific blocks. You can
                  also help fund the cleaning yourself or wait for a sponsor to
                  step up.
                </p>
              </CardContent>
            </Card>
          </div>
        </div>
      </section>

      {/* Final CTA */}
      <section className="section-container py-16 text-center">
        <div className="mx-auto max-w-2xl">
          <Heart className="mx-auto mb-4 h-12 w-12 text-brand-green-500" />
          <h2 className="mb-4 text-2xl font-bold">Join the Movement</h2>
          <p className="mb-8 text-muted-foreground">
            Together, we can transform North Lawndale into the clean, vibrant
            community it deserves to be.
          </p>
          <div className="flex flex-col gap-4 sm:flex-row sm:justify-center">
            <Button size="lg" asChild>
              <Link href="/join">
                Join the Waitlist
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
            <Button variant="secondary" size="lg" asChild>
              <Link href="/impact">View Our Impact</Link>
            </Button>
          </div>
        </div>
      </section>
    </div>
  )
}
