import { Metadata } from 'next'
import Link from 'next/link'
import {
  DollarSign,
  Clock,
  MapPin,
  Smartphone,
  Trophy,
  Heart,
  ArrowRight,
  CheckCircle,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

export const metadata: Metadata = {
  title: 'Earn Money Cleaning | GCC Worker Benefits',
  description:
    'Join GCC and earn $100 per block cleaned. Flexible hours, instant pay, and meaningful work in your community.',
}

const benefits = [
  {
    icon: DollarSign,
    title: '$100 Per Block',
    description:
      'Earn a guaranteed $100 for each block you clean. No complicated pay structures - just complete the job and get paid.',
  },
  {
    icon: Clock,
    title: 'Flexible Schedule',
    description:
      'Work when you want. Claim jobs that fit your schedule. Morning, afternoon, or weekend - you decide.',
  },
  {
    icon: Smartphone,
    title: 'Easy App Experience',
    description:
      'Our mobile-friendly app guides you through each job. GPS tracking, photo uploads, and instant verification.',
  },
  {
    icon: MapPin,
    title: 'Work in Your Neighborhood',
    description:
      'Clean blocks right in North Lawndale. No long commutes - make a difference where you live.',
  },
  {
    icon: Trophy,
    title: 'Achievements & Leaderboards',
    description:
      'Earn badges, climb the leaderboard, and get recognized for your contributions to the community.',
  },
  {
    icon: Heart,
    title: 'Meaningful Impact',
    description:
      "You're not just cleaning streets - you're transforming your community and creating a safer, cleaner environment for everyone.",
  },
]

const earningsExamples = [
  { blocks: 1, perWeek: '$100', perMonth: '$400' },
  { blocks: 2, perWeek: '$200', perMonth: '$800' },
  { blocks: 5, perWeek: '$500', perMonth: '$2,000' },
  { blocks: 10, perWeek: '$1,000', perMonth: '$4,000' },
]

const requirements = [
  'Must be 18 years or older',
  'Live in or near North Lawndale (60623, 60624)',
  'Valid government ID',
  'Smartphone with camera',
  'Ability to walk and perform light physical work',
  'Pass basic background check',
]

export default function WorkerBenefitsPage() {
  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Hero Section */}
      <section className="section-container py-16 text-center">
        <div className="mx-auto max-w-3xl">
          <h1 className="mb-4 text-4xl font-bold tracking-tight sm:text-5xl">
            Earn <span className="text-brand-green-600">$100</span> Per Block
          </h1>
          <p className="mb-8 text-lg text-muted-foreground">
            Turn your time into money while making North Lawndale cleaner.
            No experience needed - just your commitment to our community.
          </p>
          <div className="flex flex-col gap-4 sm:flex-row sm:justify-center">
            <Button size="lg" asChild>
              <Link href="/worker/signup">
                Start Earning Today
                <ArrowRight className="ml-2 h-5 w-5" />
              </Link>
            </Button>
            <Button variant="secondary" size="lg" asChild>
              <Link href="/join">Join Waitlist</Link>
            </Button>
          </div>
        </div>
      </section>

      {/* Benefits Grid */}
      <section className="section-container py-12">
        <h2 className="mb-8 text-center text-2xl font-bold">Why Clean With GCC?</h2>
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
          {benefits.map((benefit) => {
            const Icon = benefit.icon
            return (
              <Card key={benefit.title} className="border-0 bg-white/80 backdrop-blur">
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

      {/* Earnings Calculator */}
      <section className="bg-brand-green-800 py-16 text-white">
        <div className="section-container">
          <h2 className="mb-8 text-center text-2xl font-bold">
            How Much Can You Earn?
          </h2>
          <div className="mx-auto max-w-2xl overflow-hidden rounded-2xl bg-white/10 backdrop-blur">
            <table className="w-full">
              <thead>
                <tr className="border-b border-white/20">
                  <th className="px-6 py-4 text-left text-sm font-semibold">
                    Blocks/Week
                  </th>
                  <th className="px-6 py-4 text-right text-sm font-semibold">
                    Weekly
                  </th>
                  <th className="px-6 py-4 text-right text-sm font-semibold">
                    Monthly*
                  </th>
                </tr>
              </thead>
              <tbody>
                {earningsExamples.map((example) => (
                  <tr
                    key={example.blocks}
                    className="border-b border-white/10 last:border-0"
                  >
                    <td className="px-6 py-4 text-sm">
                      {example.blocks} {example.blocks === 1 ? 'block' : 'blocks'}
                    </td>
                    <td className="px-6 py-4 text-right text-lg font-bold text-brand-green-300">
                      {example.perWeek}
                    </td>
                    <td className="px-6 py-4 text-right text-lg font-bold text-brand-green-300">
                      {example.perMonth}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="border-t border-white/20 px-6 py-3 text-center text-xs text-white/60">
              *Based on 4 weeks. Actual earnings depend on block availability.
            </p>
          </div>
        </div>
      </section>

      {/* Requirements */}
      <section className="section-container py-16">
        <div className="mx-auto max-w-2xl">
          <h2 className="mb-8 text-center text-2xl font-bold">What You Need</h2>
          <Card>
            <CardContent className="p-6">
              <ul className="space-y-4">
                {requirements.map((req) => (
                  <li key={req} className="flex items-start gap-3">
                    <CheckCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-brand-green-500" />
                    <span>{req}</span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        </div>
      </section>

      {/* Final CTA */}
      <section className="bg-brand-green-50 py-16">
        <div className="section-container text-center">
          <h2 className="mb-4 text-2xl font-bold">
            Ready to Start Earning?
          </h2>
          <p className="mb-8 text-muted-foreground">
            Join the GCC workforce and make a real difference in North Lawndale.
          </p>
          <Button size="lg" asChild>
            <Link href="/worker/signup">
              Apply Now
              <ArrowRight className="ml-2 h-5 w-5" />
            </Link>
          </Button>
        </div>
      </section>
    </div>
  )
}
