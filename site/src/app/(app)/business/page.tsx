import { Metadata } from 'next'
import Link from 'next/link'
import {
  Building2,
  Users,
  TrendingUp,
  Heart,
  MapPin,
  Award,
  ArrowRight,
  BadgeCheck,
  Calculator,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'

export const metadata: Metadata = {
  title: 'For Businesses',
  description:
    'Corporate sponsorship programs for businesses. Tax-deductible, employee engagement, and real community impact.',
}

const programs = [
  {
    icon: MapPin,
    title: 'Block Sponsorship',
    description:
      'Sponsor specific blocks near your business location. Perfect visibility for your brand while making real impact.',
    features: ['Choose your blocks', 'Logo on cleaned blocks', 'Before/after photos'],
    popular: false,
  },
  {
    icon: Users,
    title: 'Employee Matching',
    description:
      "Double your employees' charitable contributions. We track and match donations up to your set limit.",
    features: ['2x impact', 'Employee engagement', 'Tax benefits for company'],
    popular: false,
  },
  {
    icon: Heart,
    title: 'Community Partner',
    description:
      'Long-term partnership for sustained neighborhood improvement. Quarterly reports and recognition.',
    features: ['Recurring sponsorship', 'Quarterly impact reports', 'Annual celebration invite'],
    popular: false,
  },
]

const benefits = [
  {
    icon: TrendingUp,
    title: 'Increase Foot Traffic',
    description:
      'A swept, cared-for sidewalk is the first thing a customer sees. It invites people to slow down, stop and shop.',
  },
  {
    icon: BadgeCheck,
    title: 'Build Brand Trust',
    description:
      'Community investment builds authentic connections with local customers who value corporate responsibility.',
  },
  {
    icon: Award,
    title: 'Tax Deductions',
    description:
      '100% of your sponsorship is tax-deductible. We provide detailed receipts and annual summaries.',
  },
  {
    icon: Users,
    title: 'Employee Engagement',
    description:
      'Give your team a cause to rally around. Volunteer days and matching programs boost morale.',
  },
]

export default function BusinessPage() {
  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Hero Section */}
      <section className="section-container py-16 text-center">
        <div className="mx-auto max-w-3xl">
          <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-2xl bg-brand-green-100">
            <Building2 className="h-10 w-10 text-brand-green-600" />
          </div>
          <h1 className="mb-4 text-4xl font-bold tracking-tight sm:text-5xl">
            Partner with <span className="text-brand-green-600">Your Community</span>
          </h1>
          <p className="mb-8 text-lg text-muted-foreground">
            Corporate sponsorship programs that deliver real impact. Tax-deductible
            contributions, employee engagement, and a cleaner neighborhood for your
            customers.
          </p>
          <div className="flex flex-col gap-4 sm:flex-row sm:justify-center">
            <Button size="lg" asChild>
              <Link href="/sponsor">
                Start Sponsoring
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
            <Button variant="secondary" size="lg" asChild>
              <Link href="#programs">View Programs</Link>
            </Button>
          </div>
        </div>
      </section>

      {/* Programs Section */}
      <section id="programs" className="section-container py-16">
        <h2 className="mb-8 text-center text-2xl font-bold">Sponsorship Programs</h2>
        <div className="grid gap-6 lg:grid-cols-3">
          {programs.map((program) => {
            const Icon = program.icon
            return (
              <Card
                key={program.title}
                className={`relative ${program.popular ? 'ring-2 ring-primary' : ''}`}
              >
                {program.popular && (
                  <div className="absolute -top-3 left-1/2 -translate-x-1/2">
                    <Badge className="bg-primary text-primary-foreground">
                      Most Popular
                    </Badge>
                  </div>
                )}
                <CardHeader>
                  <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-green-100">
                    <Icon className="h-6 w-6 text-brand-green-600" />
                  </div>
                  <CardTitle>{program.title}</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="mb-4 text-sm text-muted-foreground">
                    {program.description}
                  </p>
                  <ul className="space-y-2">
                    {program.features.map((feature) => (
                      <li
                        key={feature}
                        className="flex items-center gap-2 text-sm"
                      >
                        <BadgeCheck className="h-4 w-4 text-brand-green-500" />
                        {feature}
                      </li>
                    ))}
                  </ul>
                  <Button className="mt-6 w-full" variant="secondary" asChild>
                    <Link href="/sponsor">Learn More</Link>
                  </Button>
                </CardContent>
              </Card>
            )
          })}
        </div>
      </section>

      {/* Benefits Grid */}
      <section className="bg-brand-green-800 py-16 text-white">
        <div className="section-container">
          <h2 className="mb-8 text-center text-2xl font-bold">
            Why Businesses Partner with GCC
          </h2>
          <div className="grid gap-6 md:grid-cols-2">
            {benefits.map((benefit) => {
              const Icon = benefit.icon
              return (
                <div
                  key={benefit.title}
                  className="rounded-xl bg-white/10 p-6 backdrop-blur"
                >
                  <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-white/20">
                    <Icon className="h-6 w-6 text-brand-green-300" />
                  </div>
                  <h3 className="mb-2 text-lg font-semibold">{benefit.title}</h3>
                  <p className="text-white/80">{benefit.description}</p>
                </div>
              )
            })}
          </div>
        </div>
      </section>

      {/* ROI Calculator Preview */}
      <section className="section-container py-16">
        <div className="mx-auto max-w-3xl">
          <div className="mb-8 text-center">
            <Calculator className="mx-auto mb-4 h-12 w-12 text-brand-green-500" />
            <h2 className="mb-2 text-2xl font-bold">Calculate Your Impact</h2>
            <p className="text-muted-foreground">
              See how your sponsorship translates to real community benefits
            </p>
          </div>

          <Card className="overflow-hidden">
            <CardHeader className="bg-gradient-to-r from-brand-green-50 to-brand-green-50">
              <CardTitle>Example: 10 Block Sponsorship</CardTitle>
            </CardHeader>
            <CardContent className="p-6">
              <div className="grid gap-6 md:grid-cols-3">
                <div className="text-center">
                  <p className="text-3xl font-bold text-brand-green-600">$1,000</p>
                  <p className="text-sm text-muted-foreground">Total Investment</p>
                </div>
                <div className="text-center">
                  <p className="text-3xl font-bold text-brand-green-600">10</p>
                  <p className="text-sm text-muted-foreground">
                    Local Workers Employed
                  </p>
                </div>
                <div className="text-center">
                  <p className="text-3xl font-bold text-brand-green-600">~$250</p>
                  <p className="text-sm text-muted-foreground">
                    Tax Savings (25% bracket)
                  </p>
                </div>
              </div>
              <div className="mt-6 rounded-lg bg-muted p-4 text-center">
                <p className="text-sm">
                  <span className="font-semibold">Net cost after tax savings:</span>{' '}
                  <span className="text-lg font-bold text-brand-green-600">$750</span>
                  {' '}for 10 blocks cleaned by local workers
                </p>
              </div>
            </CardContent>
          </Card>
        </div>
      </section>

      {/* CTA */}
      <section className="section-container py-16 text-center">
        <div className="mx-auto max-w-2xl">
          <h2 className="mb-4 text-2xl font-bold">Ready to Make a Difference?</h2>
          <p className="mb-8 text-muted-foreground">
            Sponsor the block outside your door today, or write to{' '}
            <a
              href="mailto:info@globalcommunitycleaners.org?subject=Sponsoring%20the%20block%20outside%20my%20business"
              className="font-medium text-brand-green-600 underline hover:text-brand-green-700"
            >
              info@globalcommunitycleaners.org
            </a>{' '}
            to talk through a package for your business.
          </p>
          <div className="flex flex-col gap-4 sm:flex-row sm:justify-center">
            <Button size="lg" asChild>
              <Link href="/sponsor">
                Start Sponsoring
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
            <Button variant="secondary" size="lg" asChild>
              <Link href="/sponsor/wall">View Current Sponsors</Link>
            </Button>
          </div>
        </div>
      </section>
    </div>
  )
}
