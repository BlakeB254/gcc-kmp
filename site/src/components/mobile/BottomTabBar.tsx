'use client'

import * as React from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import {
  Home,
  MapPin,
  Briefcase,
  User,
  Heart,
  DollarSign,
  type LucideIcon,
} from 'lucide-react'
import { cn } from '@/lib/utils'

export interface TabItem {
  href: string
  label: string
  icon: LucideIcon
  activeMatch?: string | RegExp
}

// Worker navigation tabs
export const workerTabs: TabItem[] = [
  { href: '/worker/dashboard', label: 'Home', icon: Home, activeMatch: '/worker/dashboard' },
  { href: '/worker/map', label: 'Map', icon: MapPin, activeMatch: '/worker/map' },
  { href: '/worker/jobs', label: 'Jobs', icon: Briefcase, activeMatch: '/worker/job' },
  { href: '/worker/earnings', label: 'Earnings', icon: DollarSign, activeMatch: '/worker/earnings' },
  { href: '/worker/profile', label: 'Profile', icon: User, activeMatch: '/worker/profile' },
]

// Sponsor navigation tabs
export const sponsorTabs: TabItem[] = [
  { href: '/sponsor/dashboard', label: 'Home', icon: Home, activeMatch: '/sponsor/dashboard' },
  { href: '/sponsor', label: 'Sponsor', icon: Heart, activeMatch: /^\/sponsor$/ },
  { href: '/sponsor/wall', label: 'Wall', icon: MapPin, activeMatch: '/sponsor/wall' },
  { href: '/impact', label: 'Impact', icon: DollarSign, activeMatch: '/impact' },
]

// Community/general navigation tabs
export const communityTabs: TabItem[] = [
  { href: '/', label: 'Home', icon: Home, activeMatch: /^\/$/ },
  { href: '/impact', label: 'Impact', icon: Heart, activeMatch: '/impact' },
  { href: '/nominate', label: 'Nominate', icon: MapPin, activeMatch: '/nominate' },
  { href: '/residents', label: 'Residents', icon: User, activeMatch: '/residents' },
]

interface BottomTabBarProps {
  tabs: TabItem[]
  className?: string
}

export function BottomTabBar({ tabs, className }: BottomTabBarProps) {
  const pathname = usePathname()

  const isActive = (tab: TabItem) => {
    if (tab.activeMatch instanceof RegExp) {
      return tab.activeMatch.test(pathname)
    }
    return pathname.startsWith(tab.activeMatch || tab.href)
  }

  return (
    <nav
      className={cn(
        'fixed bottom-0 left-0 right-0 z-50 border-t border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/80',
        className
      )}
    >
      <div className="mx-auto flex h-16 max-w-lg items-center justify-around safe-bottom">
        {tabs.map((tab) => {
          const active = isActive(tab)
          const Icon = tab.icon

          return (
            <Link
              key={tab.href}
              href={tab.href}
              className={cn(
                'flex min-w-[64px] flex-col items-center justify-center gap-1 px-3 py-2 transition-colors',
                active
                  ? 'text-primary'
                  : 'text-muted-foreground hover:text-foreground'
              )}
            >
              <Icon
                className={cn(
                  'h-6 w-6 transition-transform',
                  active && 'scale-110'
                )}
                strokeWidth={active ? 2.5 : 2}
              />
              <span
                className={cn(
                  'text-xs',
                  active ? 'font-semibold' : 'font-medium'
                )}
              >
                {tab.label}
              </span>
            </Link>
          )
        })}
      </div>
    </nav>
  )
}

// Convenience wrappers for specific user types
export function WorkerTabBar({ className }: { className?: string }) {
  return <BottomTabBar tabs={workerTabs} className={className} />
}

export function SponsorTabBar({ className }: { className?: string }) {
  return <BottomTabBar tabs={sponsorTabs} className={className} />
}

export function CommunityTabBar({ className }: { className?: string }) {
  return <BottomTabBar tabs={communityTabs} className={className} />
}
