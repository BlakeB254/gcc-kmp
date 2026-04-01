'use client'

import * as React from 'react'
import Link from 'next/link'
import {
  Building2,
  MapPin,
  Users,
  DollarSign,
  TrendingUp,
  Download,
  Calendar,
  ArrowRight,
  Plus,
  FileText,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Progress } from '@/components/ui/progress'
import { AnimatedStatCard, StatGrid } from '@/components/flow/display/AnimatedStatCard'

// Mock data - in production this would come from authenticated API
const businessData = {
  companyName: 'Westside Hardware',
  totalSponsored: 24,
  totalContribution: 240000, // in cents
  employeeMatching: {
    enabled: true,
    limit: 50000, // $500 per employee
    used: 32500,
    participatingEmployees: 13,
  },
  sponsorships: [
    {
      id: '1',
      date: '2025-01-15',
      blocks: 4,
      amount: 40000,
      status: 'completed',
      blockIds: ['NL-N01', 'NL-N02', 'NL-N03', 'NL-N04'],
    },
    {
      id: '2',
      date: '2025-01-08',
      blocks: 10,
      amount: 100000,
      status: 'completed',
      blockIds: ['NL-S01', 'NL-S02', 'NL-S03', 'NL-S04', 'NL-S05', 'NL-S06', 'NL-S07', 'NL-S08', 'NL-S09', 'NL-S10'],
    },
    {
      id: '3',
      date: '2024-12-20',
      blocks: 10,
      amount: 100000,
      status: 'completed',
      blockIds: ['NL-E01', 'NL-E02', 'NL-E03', 'NL-E04', 'NL-E05', 'NL-E06', 'NL-E07', 'NL-E08', 'NL-E09', 'NL-E10'],
    },
  ],
  impactMetrics: {
    workersEmployed: 24,
    litterRemoved: 3.2, // tons
    streetsImproved: 1.8, // miles
  },
}

function formatCurrency(cents: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }).format(cents / 100)
}

function formatDate(dateString: string): string {
  return new Date(dateString).toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  })
}

export default function BusinessDashboardPage() {
  const matchingPercent = businessData.employeeMatching.enabled
    ? (businessData.employeeMatching.used / businessData.employeeMatching.limit) * 100
    : 0

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-green-50 to-white">
      {/* Header */}
      <section className="section-container py-8">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-4">
            <div className="flex h-14 w-14 items-center justify-center rounded-xl bg-brand-green-100">
              <Building2 className="h-7 w-7 text-brand-green-600" />
            </div>
            <div>
              <h1 className="text-2xl font-bold">{businessData.companyName}</h1>
              <p className="text-muted-foreground">Business Dashboard</p>
            </div>
          </div>
          <div className="flex gap-3">
            <Button variant="secondary" asChild>
              <Link href="#">
                <Download className="mr-2 h-4 w-4" />
                Tax Documents
              </Link>
            </Button>
            <Button asChild>
              <Link href="/sponsor">
                <Plus className="mr-2 h-4 w-4" />
                New Sponsorship
              </Link>
            </Button>
          </div>
        </div>
      </section>

      {/* Stats Overview */}
      <section className="section-container pb-8">
        <StatGrid columns={4}>
          <AnimatedStatCard
            value={businessData.totalSponsored}
            label="Blocks Sponsored"
            icon={<MapPin className="h-6 w-6" />}
          />
          <AnimatedStatCard
            value={businessData.totalContribution / 100}
            label="Total Contributed"
            prefix="$"
            icon={<DollarSign className="h-6 w-6" />}
          />
          <AnimatedStatCard
            value={businessData.impactMetrics.workersEmployed}
            label="Workers Employed"
            icon={<Users className="h-6 w-6" />}
          />
          <AnimatedStatCard
            value={businessData.impactMetrics.litterRemoved}
            label="Tons Removed"
            suffix=" tons"
            icon={<TrendingUp className="h-6 w-6" />}
          />
        </StatGrid>
      </section>

      {/* Employee Matching & Recent Sponsorships */}
      <section className="section-container pb-12">
        <div className="grid gap-6 lg:grid-cols-2">
          {/* Employee Matching Card */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Users className="h-5 w-5 text-brand-green-600" />
                Employee Matching Program
              </CardTitle>
              <CardDescription>
                Match employee donations up to {formatCurrency(businessData.employeeMatching.limit)} each
              </CardDescription>
            </CardHeader>
            <CardContent>
              {businessData.employeeMatching.enabled ? (
                <>
                  <div className="mb-4">
                    <div className="mb-2 flex justify-between text-sm">
                      <span>Budget Used</span>
                      <span className="font-medium">
                        {formatCurrency(businessData.employeeMatching.used)} /{' '}
                        {formatCurrency(businessData.employeeMatching.limit)}
                      </span>
                    </div>
                    <Progress value={matchingPercent} className="h-3" />
                  </div>

                  <div className="rounded-lg bg-muted p-4">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="text-2xl font-bold text-brand-green-600">
                          {businessData.employeeMatching.participatingEmployees}
                        </p>
                        <p className="text-sm text-muted-foreground">
                          Participating Employees
                        </p>
                      </div>
                      <Badge variant="secondary" className="text-brand-green-600">
                        Active
                      </Badge>
                    </div>
                  </div>

                  <Button variant="secondary" className="mt-4 w-full">
                    Manage Matching Program
                  </Button>
                </>
              ) : (
                <div className="text-center py-6">
                  <Users className="mx-auto mb-3 h-12 w-12 text-muted-foreground" />
                  <p className="mb-4 text-muted-foreground">
                    Enable employee matching to double your impact
                  </p>
                  <Button>Enable Matching</Button>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Recent Sponsorships */}
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Calendar className="h-5 w-5 text-brand-green-600" />
                Recent Sponsorships
              </CardTitle>
              <CardDescription>
                Your sponsorship history and status
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                {businessData.sponsorships.map((sponsorship) => (
                  <div
                    key={sponsorship.id}
                    className="flex items-center justify-between rounded-lg border p-3"
                  >
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="font-medium">
                          {sponsorship.blocks} blocks
                        </span>
                        <Badge
                          variant={
                            sponsorship.status === 'completed'
                              ? 'default'
                              : 'secondary'
                          }
                          className={
                            sponsorship.status === 'completed'
                              ? 'bg-brand-green-600'
                              : ''
                          }
                        >
                          {sponsorship.status}
                        </Badge>
                      </div>
                      <p className="text-sm text-muted-foreground">
                        {formatDate(sponsorship.date)}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="font-semibold">
                        {formatCurrency(sponsorship.amount)}
                      </p>
                      <button className="text-xs text-primary hover:underline">
                        View blocks
                      </button>
                    </div>
                  </div>
                ))}
              </div>
              <Button variant="ghost" className="mt-4 w-full" asChild>
                <Link href="#">
                  View All History
                  <ArrowRight className="ml-2 h-4 w-4" />
                </Link>
              </Button>
            </CardContent>
          </Card>
        </div>
      </section>

      {/* Impact Summary */}
      <section className="bg-brand-green-800 py-12 text-white">
        <div className="section-container">
          <h2 className="mb-6 text-center text-xl font-bold">Your Community Impact</h2>
          <div className="grid gap-6 md:grid-cols-3">
            <div className="rounded-xl bg-white/10 p-6 text-center backdrop-blur">
              <MapPin className="mx-auto mb-3 h-8 w-8 text-brand-green-300" />
              <p className="text-3xl font-bold">{businessData.impactMetrics.streetsImproved} mi</p>
              <p className="text-white/70">Streets Improved</p>
            </div>
            <div className="rounded-xl bg-white/10 p-6 text-center backdrop-blur">
              <Users className="mx-auto mb-3 h-8 w-8 text-brand-green-300" />
              <p className="text-3xl font-bold">{businessData.impactMetrics.workersEmployed}</p>
              <p className="text-white/70">Local Workers Paid</p>
            </div>
            <div className="rounded-xl bg-white/10 p-6 text-center backdrop-blur">
              <TrendingUp className="mx-auto mb-3 h-8 w-8 text-brand-green-300" />
              <p className="text-3xl font-bold">{businessData.impactMetrics.litterRemoved} tons</p>
              <p className="text-white/70">Litter Removed</p>
            </div>
          </div>
        </div>
      </section>

      {/* Documents & Reports */}
      <section className="section-container py-12">
        <h2 className="mb-6 text-xl font-bold">Documents & Reports</h2>
        <div className="grid gap-4 md:grid-cols-3">
          <Card className="hover:shadow-md transition-shadow cursor-pointer">
            <CardContent className="flex items-center gap-4 pt-6">
              <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-brand-green-100">
                <FileText className="h-6 w-6 text-brand-green-600" />
              </div>
              <div>
                <p className="font-semibold">2024 Tax Summary</p>
                <p className="text-sm text-muted-foreground">Annual donation receipt</p>
              </div>
            </CardContent>
          </Card>
          <Card className="hover:shadow-md transition-shadow cursor-pointer">
            <CardContent className="flex items-center gap-4 pt-6">
              <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-brand-green-100">
                <TrendingUp className="h-6 w-6 text-brand-green-600" />
              </div>
              <div>
                <p className="font-semibold">Impact Report Q4</p>
                <p className="text-sm text-muted-foreground">Quarterly metrics</p>
              </div>
            </CardContent>
          </Card>
          <Card className="hover:shadow-md transition-shadow cursor-pointer">
            <CardContent className="flex items-center gap-4 pt-6">
              <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-brand-blue-100">
                <Download className="h-6 w-6 text-brand-blue-600" />
              </div>
              <div>
                <p className="font-semibold">Block Photos</p>
                <p className="text-sm text-muted-foreground">Before/after gallery</p>
              </div>
            </CardContent>
          </Card>
        </div>
      </section>
    </div>
  )
}
