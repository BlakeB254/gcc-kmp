'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import {
  MapPin,
  Users,
  DollarSign,
  Heart,
  TrendingUp,
  ArrowRight,
  Sparkles,
  Building,
  Palette,
  Shield,
  Handshake,
  Store,
  Loader2,
  Target,
  Leaf,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn, formatCurrency } from '@/lib/utils'

interface ImpactStats {
  workers: {
    total: number
    approved: number
    jobsCompleted: number
    totalEarnings: number
  }
  sponsors: {
    total: number
    totalContributed: number
    blocksFunded: number
  }
  jobs: {
    total: number
    completed: number
    active: number
  }
  blocks: {
    total: number
    cleaned: number
    funded: number
    available: number
  }
  movement: {
    waitlistCount: number
  }
}

function StatCard({
  icon: Icon,
  value,
  label,
  color,
  isLoading,
}: {
  icon: React.ComponentType<{ className?: string }>
  value: string | number
  label: string
  color: string
  isLoading?: boolean
}) {
  const colorClasses: Record<string, { border: string; bg: string; text: string }> = {
    gold: { border: 'border-brand-green-500', bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
    green: { border: 'border-brand-green-500', bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
    sky: { border: 'border-brand-blue-500', bg: 'bg-brand-blue-100', text: 'text-brand-blue-600' },
    brown: { border: 'border-brand-green-500', bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
  }
  const classes = colorClasses[color] || colorClasses.green

  return (
    <Card className={cn('border-l-4', classes.border)}>
      <CardContent className="pt-6">
        <div className="flex items-center gap-4">
          <div className={cn('rounded-full p-3', classes.bg)}>
            <Icon className={cn('h-6 w-6', classes.text)} />
          </div>
          <div>
            {isLoading ? (
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            ) : (
              <p className="text-3xl font-bold">{value}</p>
            )}
            <p className="text-sm text-muted-foreground">{label}</p>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

function ImpactPillar({
  icon: Icon,
  title,
  description,
  color,
}: {
  icon: React.ComponentType<{ className?: string }>
  title: string
  description: string
  color: string
}) {
  const colorClasses: Record<string, { bg: string; text: string }> = {
    gold: { bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
    green: { bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
    sky: { bg: 'bg-brand-blue-100', text: 'text-brand-blue-600' },
    brown: { bg: 'bg-brand-green-100', text: 'text-brand-green-600' },
  }
  const classes = colorClasses[color] || colorClasses.green

  return (
    <div className="text-center">
      <div className={cn('mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full', classes.bg)}>
        <Icon className={cn('h-8 w-8', classes.text)} />
      </div>
      <h3 className="mb-2 text-xl font-bold">{title}</h3>
      <p className="text-muted-foreground leading-relaxed">{description}</p>
    </div>
  )
}

export default function ImpactPage() {
  const [stats, setStats] = useState<ImpactStats | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    async function fetchStats() {
      try {
        const response = await fetch('/api/public/impact-stats')
        const data = await response.json()
        setStats(data)
      } catch (error) {
        console.error('Failed to fetch impact stats:', error)
      } finally {
        setIsLoading(false)
      }
    }
    fetchStats()
  }, [])

  // Calculate derived stats
  const totalMovementMembers = (stats?.workers.total || 0) + (stats?.sponsors.total || 0) + (stats?.movement.waitlistCount || 0)
  const hasActivity = (stats?.jobs.completed || 0) > 0 || (stats?.sponsors.blocksFunded || 0) > 0

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Header */}
      <section className="section-container py-12 text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-brand-green-100">
          <TrendingUp className="h-8 w-8 text-brand-green-600" />
        </div>
        <h1 className="mb-2 text-3xl font-bold">Our Community Impact</h1>
        <p className="text-muted-foreground max-w-2xl mx-auto">
          Every block cleaned is a step toward transformation. Track our progress as we build cleaner,
          safer, and more prosperous neighborhoods together.
        </p>
      </section>

      {/* Live Stats */}
      <section className="section-container pb-12">
        <h2 className="mb-6 text-xl font-semibold text-center">Live Progress</h2>
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
          <StatCard
            icon={MapPin}
            value={stats?.blocks.cleaned || 0}
            label="Blocks Cleaned"
            color="green"
            isLoading={isLoading}
          />
          <StatCard
            icon={Users}
            value={stats?.workers.approved || 0}
            label="Active Workers"
            color="sky"
            isLoading={isLoading}
          />
          <StatCard
            icon={DollarSign}
            value={formatCurrency(stats?.sponsors.totalContributed || 0)}
            label="Total Raised"
            color="gold"
            isLoading={isLoading}
          />
          <StatCard
            icon={Heart}
            value={stats?.sponsors.total || 0}
            label="Sponsors"
            color="brown"
            isLoading={isLoading}
          />
        </div>
      </section>

      {/* Milestone Progress */}
      {!isLoading && (
        <section className="section-container pb-12">
          <Card className="bg-gradient-to-r from-brand-green-50 to-brand-green-50 border-brand-green-200">
            <CardContent className="pt-6">
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-2">
                  <Target className="h-5 w-5 text-brand-green-600" />
                  <span className="font-semibold">First 100 Blocks Goal</span>
                </div>
                <span className="text-sm text-muted-foreground">
                  {stats?.blocks.cleaned || 0} / 100
                </span>
              </div>
              <div className="w-full bg-brand-green-200 rounded-full h-3">
                <div
                  className="bg-brand-green-600 h-3 rounded-full transition-all duration-500"
                  style={{ width: `${Math.min(((stats?.blocks.cleaned || 0) / 100) * 100, 100)}%` }}
                />
              </div>
              <p className="mt-3 text-sm text-muted-foreground">
                {(stats?.blocks.cleaned || 0) >= 100
                  ? "We've reached our first milestone! Next goal: 500 blocks."
                  : `${100 - (stats?.blocks.cleaned || 0)} more blocks until our first major milestone.`}
              </p>
            </CardContent>
          </Card>
        </section>
      )}

      {/* The Broken Windows Theory */}
      <section className="bg-white py-16">
        <div className="section-container">
          <div className="max-w-3xl mx-auto text-center mb-12">
            <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-brand-green-100">
              <Shield className="h-8 w-8 text-brand-green-600" />
            </div>
            <h2 className="text-2xl font-bold mb-4">The Science of Clean Streets</h2>
            <p className="text-muted-foreground leading-relaxed">
              The &ldquo;Broken Windows Theory&rdquo; demonstrates that visible signs of neglect—litter,
              graffiti, abandoned debris—signal that an area is uncared for, inviting further disorder.
              But the opposite is equally true: <strong>well-maintained spaces signal pride, care,
              and community investment</strong>.
            </p>
          </div>

          <div className="grid gap-8 md:grid-cols-2 lg:grid-cols-3">
            <Card className="border-0 shadow-sm hover:shadow-md transition-shadow">
              <CardContent className="pt-6">
                <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-red-100">
                  <Shield className="h-6 w-6 text-red-600" />
                </div>
                <h3 className="font-semibold mb-2">Reduced Crime</h3>
                <p className="text-sm text-muted-foreground">
                  Studies show that clean, well-lit streets experience significantly lower crime rates.
                  When people see a cared-for environment, they&apos;re less likely to vandalize it.
                </p>
              </CardContent>
            </Card>

            <Card className="border-0 shadow-sm hover:shadow-md transition-shadow">
              <CardContent className="pt-6">
                <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-green-100">
                  <Leaf className="h-6 w-6 text-green-600" />
                </div>
                <h3 className="font-semibold mb-2">Environmental Health</h3>
                <p className="text-sm text-muted-foreground">
                  Litter doesn&apos;t just look bad—it contaminates water, harms wildlife, and creates
                  health hazards. Clean blocks mean healthier families and a healthier planet.
                </p>
              </CardContent>
            </Card>

            <Card className="border-0 shadow-sm hover:shadow-md transition-shadow">
              <CardContent className="pt-6">
                <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-blue-100">
                  <Building className="h-6 w-6 text-blue-600" />
                </div>
                <h3 className="font-semibold mb-2">Property Values</h3>
                <p className="text-sm text-muted-foreground">
                  Clean neighborhoods attract investment. When streets are maintained, property values
                  stabilize and grow—building generational wealth for homeowners.
                </p>
              </CardContent>
            </Card>
          </div>
        </div>
      </section>

      {/* Our Four Pillars of Impact */}
      <section className="section-container py-16">
        <h2 className="text-2xl font-bold text-center mb-12">Four Pillars of Transformation</h2>
        <div className="grid gap-12 md:grid-cols-2 lg:grid-cols-4">
          <ImpactPillar
            icon={DollarSign}
            title="Economic Empowerment"
            description="Every dollar stays in the community. Workers earn fair wages that support their families, pay local bills, and circulate back into neighborhood businesses."
            color="gold"
          />
          <ImpactPillar
            icon={Palette}
            title="Inspiring Our Youth"
            description="Children raised in clean, beautiful environments develop higher self-esteem and greater aspirations. Beauty nurtures creativity and shows kids they matter."
            color="sky"
          />
          <ImpactPillar
            icon={Handshake}
            title="Community Bonds"
            description="Shared pride in clean streets brings neighbors together. When we invest in our blocks, we invest in each other—building trust and collaboration."
            color="green"
          />
          <ImpactPillar
            icon={Store}
            title="Local Business Growth"
            description="Clean streets attract foot traffic, encourage shopping local, and signal to entrepreneurs that this is a neighborhood worth investing in."
            color="brown"
          />
        </div>
      </section>

      {/* The Local Jobs Mission */}
      <section className="bg-gradient-to-r from-brand-green-800 to-brand-green-900 py-16 text-white">
        <div className="section-container">
          <div className="max-w-3xl mx-auto">
            <div className="text-center mb-8">
              <Sparkles className="h-12 w-12 mx-auto mb-4 text-brand-green-300" />
              <h2 className="text-2xl font-bold mb-4">Creating Dignified Work</h2>
            </div>

            <div className="space-y-6 text-brand-green-100">
              <p className="text-lg leading-relaxed">
                GCC isn&apos;t charity—it&apos;s <strong className="text-white">opportunity</strong>.
                We believe that residents of North Lawndale are the best people to care for North
                Lawndale. They know these streets. They&apos;re invested in their future. And they
                deserve well-paying work that builds pride, not dependence.
              </p>

              <div className="bg-white/10 rounded-xl p-6 border border-white/20">
                <h3 className="text-white font-semibold mb-3 flex items-center gap-2">
                  <DollarSign className="h-5 w-5" />
                  Our Pay Promise
                </h3>
                <p className="text-brand-green-100">
                  <strong className="text-white">100% of every sponsorship goes directly to workers</strong>.
                  No administrative fees. No overhead deductions. A $100 block sponsorship means $100
                  in a worker&apos;s pocket. This is radical transparency in action.
                </p>
              </div>

              <p className="leading-relaxed">
                We&apos;re building more than clean streets—we&apos;re building a workforce of
                trained, verified, reliable community members who can leverage this experience
                into other opportunities. GCC work history becomes a credential, a reference,
                a stepping stone.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* Children & Future Generations */}
      <section className="bg-brand-blue-50 py-16">
        <div className="section-container">
          <div className="grid gap-8 lg:grid-cols-2 items-center">
            <div>
              <div className="flex h-16 w-16 items-center justify-center rounded-full bg-brand-blue-100 mb-6">
                <Palette className="h-8 w-8 text-brand-blue-600" />
              </div>
              <h2 className="text-2xl font-bold mb-4">A Canvas for Creativity</h2>
              <div className="space-y-4 text-muted-foreground">
                <p className="leading-relaxed">
                  Children absorb their environment. When kids walk through streets littered with
                  broken glass and abandoned furniture, they internalize a message: <em>this place
                  doesn&apos;t matter, and neither do I</em>.
                </p>
                <p className="leading-relaxed">
                  But when those same streets are clean, maintained, and visibly cared for? The
                  message transforms: <strong className="text-brand-blue-700">this is our neighborhood,
                  and it&apos;s worth protecting</strong>.
                </p>
                <p className="leading-relaxed">
                  Beautiful environments spark imagination. They tell children that beauty is possible,
                  that change is possible, that <em>they</em> can create something beautiful too.
                  That&apos;s the gift we give with every block cleaned.
                </p>
              </div>
            </div>

            <div className="bg-white rounded-2xl p-8 shadow-lg">
              <blockquote className="text-xl italic text-brand-blue-800 mb-4">
                &ldquo;The environment shapes the child, and the child shapes the future.&rdquo;
              </blockquote>
              <p className="text-sm text-muted-foreground">
                Research consistently shows that children in cleaner, safer neighborhoods perform
                better academically, have lower rates of anxiety, and report higher life satisfaction.
                Clean streets aren&apos;t just about aesthetics—they&apos;re about opportunity.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* How It Works - Simple */}
      <section className="section-container py-16">
        <h2 className="mb-8 text-center text-2xl font-bold">How GCC Creates Change</h2>
        <div className="grid gap-8 md:grid-cols-3">
          <Card className="text-center">
            <CardHeader>
              <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-brand-green-100 text-2xl font-bold text-brand-green-600">
                1
              </div>
              <CardTitle>You Sponsor a Block</CardTitle>
            </CardHeader>
            <CardContent className="text-muted-foreground">
              $100 funds one block cleaning. Choose to sponsor anonymously or get recognized
              on our Sponsor Wall. 100% goes to the worker.
            </CardContent>
          </Card>
          <Card className="text-center">
            <CardHeader>
              <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-brand-green-100 text-2xl font-bold text-brand-green-600">
                2
              </div>
              <CardTitle>A Worker Claims It</CardTitle>
            </CardHeader>
            <CardContent className="text-muted-foreground">
              Verified North Lawndale residents claim funded blocks, clean them thoroughly,
              and document their work with before/after photos.
            </CardContent>
          </Card>
          <Card className="text-center">
            <CardHeader>
              <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-brand-blue-100 text-2xl font-bold text-brand-blue-600">
                3
              </div>
              <CardTitle>The Block Transforms</CardTitle>
            </CardHeader>
            <CardContent className="text-muted-foreground">
              One clean block inspires the next. Neighbors take pride. Kids feel safe.
              Businesses see potential. The cycle of investment begins.
            </CardContent>
          </Card>
        </div>
      </section>

      {/* Movement Stats */}
      {!isLoading && totalMovementMembers > 0 && (
        <section className="bg-brand-green-800 py-12 text-white">
          <div className="section-container">
            <div className="text-center mb-8">
              <h2 className="text-2xl font-bold mb-2">The Movement is Growing</h2>
              <p className="text-brand-green-200">Join {totalMovementMembers.toLocaleString()} people building a cleaner future</p>
            </div>
            <div className="grid gap-6 md:grid-cols-3 text-center">
              <div>
                <p className="text-4xl font-bold text-brand-green-400">{stats?.workers.total || 0}</p>
                <p className="text-brand-green-200">Registered Workers</p>
              </div>
              <div>
                <p className="text-4xl font-bold text-brand-green-400">{stats?.sponsors.total || 0}</p>
                <p className="text-brand-green-200">Sponsors</p>
              </div>
              <div>
                <p className="text-4xl font-bold text-brand-green-400">{stats?.movement.waitlistCount || 0}</p>
                <p className="text-brand-green-200">On Waitlist</p>
              </div>
            </div>
          </div>
        </section>
      )}

      {/* Call to Action */}
      <section className="section-container py-16">
        <div className="rounded-2xl bg-gradient-to-r from-brand-green-100 to-brand-green-100 p-8 text-center">
          <h2 className="mb-4 text-2xl font-bold">Be Part of the Transformation</h2>
          <p className="mb-6 text-muted-foreground max-w-2xl mx-auto">
            Every clean block is a vote for the future we want. Whether you clean, sponsor,
            or spread the word—you&apos;re part of something bigger.
          </p>
          <div className="flex flex-col gap-4 sm:flex-row sm:justify-center">
            <Button size="lg" asChild>
              <Link href="/worker/signup">
                Become a Worker
                <ArrowRight className="ml-2 h-4 w-4" />
              </Link>
            </Button>
            <Button size="lg" variant="secondary" asChild>
              <Link href="/sponsor">
                Sponsor a Block
              </Link>
            </Button>
            <Button size="lg" variant="outline" asChild>
              <Link href="/sponsor/wall">
                View Sponsor Wall
              </Link>
            </Button>
          </div>
        </div>
      </section>
    </div>
  )
}
