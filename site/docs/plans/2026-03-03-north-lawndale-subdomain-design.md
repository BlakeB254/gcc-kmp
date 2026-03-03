# North Lawndale Subdomain Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a community-focused landing page at north-lawndale.globalcommunitycleaners.org using middleware-based subdomain routing within the existing Next.js site.

**Architecture:** Next.js middleware detects the `north-lawndale` subdomain and rewrites requests to a `(north-lawndale)` route group. Shares all brand styles, components, and the existing waitlist API (tagged with `source: 'north-lawndale'`).

**Tech Stack:** Next.js 16, Tailwind CSS, Radix UI, existing GCC brand system, Drizzle ORM (existing waitlist table)

---

### Task 1: Create Middleware for Subdomain Routing

**Files:**
- Create: `site/src/middleware.ts`

Detect `north-lawndale` subdomain, rewrite to `/(north-lawndale)` route group. Pass through all other requests. Handle localhost dev with `?subdomain=north-lawndale` query param.

### Task 2: Create North Lawndale Layout

**Files:**
- Create: `site/src/app/(north-lawndale)/layout.tsx`

Uses the same Header/Footer from the main site but with NL-specific metadata (title, description, OG tags for the subdomain).

### Task 3: Create North Lawndale Landing Page + All Sections

**Files:**
- Create: `site/src/app/(north-lawndale)/page.tsx`
- Create: `site/src/components/north-lawndale/NLHeroSection.tsx`
- Create: `site/src/components/north-lawndale/NLLegacySection.tsx`
- Create: `site/src/components/north-lawndale/NLStatsSection.tsx`
- Create: `site/src/components/north-lawndale/NLHowItWorksSection.tsx`
- Create: `site/src/components/north-lawndale/NLImpactSection.tsx`
- Create: `site/src/components/north-lawndale/NLSignupSection.tsx`

Sections: Hero → Legacy (MLK) → Stats → How It Works → Impact → Signup → Footer

### Task 4: Create Signup API Endpoint

**Files:**
- Create: `site/src/app/api/north-lawndale/join/route.ts`

Thin wrapper that calls the existing waitlist join API logic with pre-filled North Lawndale location data and `source: 'north-lawndale'`.

### Task 5: Verify Build & Document Vercel Setup

Run `next build` to verify no errors. Document the Vercel domain + DNS setup steps.

---

## Vercel Setup (Manual Steps for User)

1. Go to Vercel Dashboard → gcc project → Settings → Domains
2. Add: `north-lawndale.globalcommunitycleaners.org`
3. In DNS provider: add CNAME record `north-lawndale` → `cname.vercel-dns.com`
