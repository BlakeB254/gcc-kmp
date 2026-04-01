# Website Signup Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify the website worker signup from a 4-step wizard (info → address → documents → review) to a single-step form (name, email, phone, password, terms) that matches the mobile app's global-friendly approach.

**Architecture:** Rewrite the signup page to a single-step form, simplify the API route to only require essential fields and remove document upload / zip validation, update DB schema defaults from Chicago/IL to empty strings.

**Tech Stack:** Next.js (App Router), React, Drizzle ORM, Vercel Blob (removed from signup flow), TypeScript

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `site/src/app/(app)/worker/signup/page.tsx` | **Rewrite** | Single-step signup form (name/email/phone/password/terms) |
| `site/src/app/api/workers/apply/route.ts` | **Rewrite** | Accept minimal fields, create worker, send verification email |
| `site/src/db/schema.ts` | Modify | Add empty string defaults for address columns |

---

### Task 1: Update DB Schema Defaults

**Files:**
- Modify: `site/src/db/schema.ts:96-99`

- [ ] **Step 1: Update address column defaults**

In `site/src/db/schema.ts`, replace lines 96-99:

```typescript
// FROM:
addressStreet: varchar('address_street').notNull(),
addressCity: varchar('address_city').default('Chicago').notNull(),
addressState: varchar('address_state').default('IL').notNull(),
addressZip: varchar('address_zip').notNull(),

// TO:
addressStreet: varchar('address_street').default('').notNull(),
addressCity: varchar('address_city').default('').notNull(),
addressState: varchar('address_state').default('').notNull(),
addressZip: varchar('address_zip').default('').notNull(),
```

- [ ] **Step 2: Commit**

```bash
git add site/src/db/schema.ts
git commit -m "fix(site): remove Chicago/IL defaults from workers schema"
```

---

### Task 2: Simplify API Route

**Files:**
- Rewrite: `site/src/app/api/workers/apply/route.ts`

- [ ] **Step 1: Rewrite the API route**

Replace the entire contents of `site/src/app/api/workers/apply/route.ts` with:

```typescript
import { NextRequest } from 'next/server'
import { db } from '@/db'
import { workers, waitlistEntries, sponsors } from '@/db/schema'
import { eq, sql } from 'drizzle-orm'
import { sendEmail } from '@/lib/email'
import { emailVerificationEmail } from '@/lib/email/templates/email-verification'
import { getEarlyAdopterTier, generateReferralCode } from '@/lib/movement-rankings'
import crypto from 'crypto'

export const runtime = 'nodejs'
export const maxDuration = 30

function generateVerificationCode(): string {
  return Math.floor(100000 + Math.random() * 900000).toString()
}

function generateVerificationToken(): string {
  return crypto.randomBytes(32).toString('hex')
}

interface RequestBody {
  name: string
  email: string
  phone: string
  password?: string
}

export async function POST(request: NextRequest) {
  console.log('[apply] Starting worker registration')
  try {
    let body: RequestBody
    try {
      body = await request.json()
      console.log('[apply] Parsed body:', { name: body.name, email: body.email, phone: body.phone })
    } catch {
      return Response.json(
        { message: 'We couldn\'t process your request. Please refresh the page and try again.' },
        { status: 400 },
      )
    }

    const { name, email, phone } = body

    if (!name || !email || !phone) {
      return Response.json(
        { message: 'Please fill in all required fields: name, email, and phone.' },
        { status: 400 },
      )
    }

    // Check if email already exists
    const existing = await db
      .select({ id: workers.id })
      .from(workers)
      .where(eq(workers.email, email.trim().toLowerCase()))
      .limit(1)

    if (existing.length > 0) {
      return Response.json(
        { message: 'An account with this email already exists. Please log in instead, or use a different email address.' },
        { status: 400 },
      )
    }

    // Generate verification token and code
    const verificationToken = generateVerificationToken()
    const verificationCode = generateVerificationCode()
    const verificationExpires = new Date(Date.now() + 24 * 60 * 60 * 1000)

    // Calculate movement rankings
    const signupTimestamp = new Date()

    const [{ waitlistCount }] = await db
      .select({ waitlistCount: sql<number>`count(*)` })
      .from(waitlistEntries)

    const [{ workersCount }] = await db
      .select({ workersCount: sql<number>`count(*)` })
      .from(workers)

    const [{ sponsorsCount }] = await db
      .select({ sponsorsCount: sql<number>`count(*)` })
      .from(sponsors)

    const globalRank = Number(waitlistCount) + Number(workersCount) + Number(sponsorsCount) + 1
    const earlyAdopterTier = getEarlyAdopterTier(globalRank)
    const referralCode = generateReferralCode()

    // Create worker record — address fields default to empty string via schema
    const [worker] = await db
      .insert(workers)
      .values({
        name: name.trim(),
        email: email.trim().toLowerCase(),
        phone,
        addressStreet: '',
        addressCity: '',
        addressState: '',
        addressZip: '',
        emailVerified: false,
        emailVerificationToken: `${verificationToken}:${verificationCode}`,
        emailVerificationExpires: verificationExpires.toISOString(),
        verificationStatus: 'pending',
        signupTimestamp: signupTimestamp.toISOString(),
        signupRankingsGlobal: String(globalRank),
        signupRankingsCountry: String(globalRank),
        signupRankingsState: '0',
        signupRankingsCity: '0',
        signupRankingsNeighborhood: '0',
        earlyAdopterTier,
        referralCode,
        notificationsSmsEnabled: true,
        notificationsEmailEnabled: true,
      })
      .returning({ id: workers.id })

    // Send verification email
    const appUrl = process.env.NEXT_PUBLIC_APP_URL || 'https://globalcommunitycleaners.org'
    const verificationUrl = `${appUrl}/worker/verify-email?token=${verificationToken}&id=${worker.id}`

    const emailContent = emailVerificationEmail({
      name: name.trim(),
      verificationUrl,
      verificationCode,
    })

    const emailResult = await sendEmail({
      to: email.trim().toLowerCase(),
      subject: 'Verify your email - GCC Worker Application',
      html: emailContent.html,
      text: emailContent.text,
    })

    if (!emailResult.success) {
      console.error('[apply] Email failed to send:', emailResult.error)
    } else {
      console.log('[apply] Verification email sent to:', email)
    }

    return Response.json({
      success: true,
      workerId: worker.id,
      message: emailResult.success
        ? 'Registration submitted. Please check your email to verify your account.'
        : 'Registration submitted. If you don\'t receive a verification email, please use the resend option on the verification page.',
      requiresEmailVerification: true,
      emailSent: emailResult.success,
      rankings: { global: globalRank },
      earlyAdopterTier,
      referralCode,
    })
  } catch (error) {
    console.error('Worker registration error:', error)
    const errorMessage = error instanceof Error ? error.message : 'Unknown error'

    let userMessage = 'Something went wrong during registration. Please try again, or contact support if the problem continues.'

    if (errorMessage.includes('duplicate key') || errorMessage.includes('unique constraint')) {
      userMessage = 'An account with this email already exists. Please try logging in instead.'
    } else if (errorMessage.includes('connection') || errorMessage.includes('ECONNREFUSED')) {
      userMessage = 'Unable to connect to our servers. Please check your internet connection and try again.'
    } else if (errorMessage.includes('timeout')) {
      userMessage = 'The request took too long. Please try again.'
    }

    return Response.json(
      { message: userMessage, error: errorMessage, success: false },
      { status: 500, headers: { 'Content-Type': 'application/json' } },
    )
  }
}
```

**What was removed:**
- `FileData` interface and `base64ToBuffer`/`uploadFile` functions (document upload)
- `isInLaunchArea`/`getChicagoCommunityArea`/`LAUNCH_AREA_ZIPS` imports (zip gating)
- `@vercel/blob` import (no file uploads)
- `processImage` import (no image processing)
- `media` table import (no document records)
- Street/city/state/zip from required validation
- Document upload logic
- Location-specific ranking queries (state, city, neighborhood — set to `'0'` since no address provided)
- Community area detection

**What was kept:**
- Duplicate email check
- Email verification token/code generation
- Global movement rankings
- Early adopter tier + referral code
- Verification email sending
- Error handling patterns

- [ ] **Step 2: Commit**

```bash
git add site/src/app/api/workers/apply/route.ts
git commit -m "feat(site): simplify apply API to only require name/email/phone"
```

---

### Task 3: Rewrite Signup Page

**Files:**
- Rewrite: `site/src/app/(app)/worker/signup/page.tsx`

- [ ] **Step 1: Rewrite the signup page**

Replace the entire contents of `site/src/app/(app)/worker/signup/page.tsx` with:

```tsx
'use client'

import { useState, useCallback, Suspense } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { cn } from '@/lib/utils'
import { Logo } from '@/components/brand/Logo'
import { FormInput } from '@/components/forms/FormInput'
import { LoadingFallback } from '@/components/shared/LoadingFallback'

interface FormData {
  name: string
  email: string
  phone: string
  password: string
  agreeToTerms: boolean
}

function formatPhoneNumber(value: string): string {
  const cleaned = value.replace(/\D/g, '')
  if (cleaned.length === 0) return ''
  if (cleaned.length <= 3) return `(${cleaned}`
  if (cleaned.length <= 6) return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3)}`
  return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3, 6)}-${cleaned.slice(6, 10)}`
}

function WorkerSignupForm() {
  const router = useRouter()
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isCheckingDuplicate, setIsCheckingDuplicate] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const [formData, setFormData] = useState<FormData>({
    name: '',
    email: '',
    phone: '',
    password: '',
    agreeToTerms: false,
  })

  const updateForm = useCallback((updates: Partial<FormData>) => {
    setFormData((prev) => ({ ...prev, ...updates }))
    setError(null)
    Object.keys(updates).forEach((key) => {
      setFieldErrors((prev) => {
        const next = { ...prev }
        delete next[key]
        return next
      })
    })
  }, [])

  const validate = (): boolean => {
    const errors: Record<string, string> = {}

    if (!formData.name.trim()) errors.name = 'Name is required'
    else if (formData.name.trim().length < 2) errors.name = 'Name must be at least 2 characters'

    if (!formData.email.trim()) errors.email = 'Email is required'
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email)) errors.email = 'Please enter a valid email'

    const digits = formData.phone.replace(/\D/g, '')
    if (digits.length < 10) errors.phone = 'Please enter a valid phone number'

    if (!formData.password) errors.password = 'Password is required'
    else if (formData.password.length < 8) errors.password = 'Password must be at least 8 characters'

    if (!formData.agreeToTerms) {
      setError('Please agree to the terms and conditions')
      return false
    }

    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors)
      setError('Please fix the errors above')
      return false
    }

    return true
  }

  const checkForDuplicates = async (): Promise<boolean> => {
    setIsCheckingDuplicate(true)
    try {
      const response = await fetch('/api/workers/check-duplicate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: formData.email.trim().toLowerCase(),
          phone: formData.phone,
        }),
      })
      const data = await response.json()
      if (data.exists) {
        if (data.field === 'email') setFieldErrors((prev) => ({ ...prev, email: data.message }))
        else if (data.field === 'phone') setFieldErrors((prev) => ({ ...prev, phone: data.message }))
        setError('This account already exists. Please log in instead.')
        return false
      }
      return true
    } catch {
      return true // Allow to proceed if check fails
    } finally {
      setIsCheckingDuplicate(false)
    }
  }

  const handleSubmit = async () => {
    if (!validate()) return

    const canProceed = await checkForDuplicates()
    if (!canProceed) return

    setIsSubmitting(true)
    setError(null)

    try {
      const response = await fetch('/api/workers/apply', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: formData.name.trim(),
          email: formData.email.trim().toLowerCase(),
          phone: formData.phone,
          password: formData.password,
        }),
      })

      const data = await response.json()

      if (!response.ok) {
        throw new Error(data.message || 'Registration failed')
      }

      if (data.workerId) {
        localStorage.setItem('gcc_pending_worker_id', data.workerId)
      }

      router.push(`/worker/verify-email?id=${data.workerId}`)
    } catch (err) {
      console.error('Registration error:', err)
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-brown-50 via-white to-brand-gold-50 py-golden-lg sm:py-golden-xl px-4">
      <div className="max-w-lg mx-auto">
        {/* Header */}
        <div className="text-center mb-golden-md sm:mb-golden-lg">
          <div className="mb-golden-sm sm:mb-golden-md">
            <Logo size="md" className="mx-auto" />
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold text-neutral-900 mb-2">
            Join GCC
          </h1>
          <p className="text-neutral-600">
            Create your worker account
          </p>
        </div>

        {/* Form */}
        <div className="bg-white rounded-2xl shadow-lg border border-neutral-200 p-6 sm:p-8 space-y-5">
          <FormInput
            label="Full Name"
            id="name"
            value={formData.name}
            onChange={(value) => updateForm({ name: value })}
            placeholder="Your full name"
            error={fieldErrors.name}
            autoComplete="name"
          />

          <FormInput
            label="Email"
            id="email"
            type="email"
            value={formData.email}
            onChange={(value) => updateForm({ email: value })}
            placeholder="you@example.com"
            error={fieldErrors.email}
            autoComplete="email"
          />

          <FormInput
            label="Phone"
            id="phone"
            type="tel"
            value={formData.phone}
            onChange={(value) => updateForm({ phone: formatPhoneNumber(value) })}
            placeholder="(312) 555-0123"
            error={fieldErrors.phone}
            hint="We'll text you when opportunities are available"
            maxLength={14}
            autoComplete="tel"
          />

          <FormInput
            label="Password"
            id="password"
            type="password"
            value={formData.password}
            onChange={(value) => updateForm({ password: value })}
            placeholder="8+ characters"
            error={fieldErrors.password}
            autoComplete="new-password"
          />

          {/* Terms */}
          <label className="flex items-start gap-3 cursor-pointer group">
            <input
              type="checkbox"
              checked={formData.agreeToTerms}
              onChange={(e) => updateForm({ agreeToTerms: e.target.checked })}
              className="mt-1 w-4 h-4 rounded border-neutral-300 text-brand-green-600 focus:ring-brand-green-500"
            />
            <span className="text-sm text-neutral-600 group-hover:text-neutral-900 transition-colors">
              I agree to the{' '}
              <Link href="/terms" className="text-brand-green-700 underline hover:text-brand-green-800">
                Terms of Service
              </Link>{' '}
              and{' '}
              <Link href="/privacy" className="text-brand-green-700 underline hover:text-brand-green-800">
                Privacy Policy
              </Link>
            </span>
          </label>

          {/* Error */}
          {error && (
            <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
              {error}
            </div>
          )}

          {/* Submit */}
          <button
            onClick={handleSubmit}
            disabled={isSubmitting || isCheckingDuplicate}
            className={cn(
              'w-full py-3 px-4 rounded-xl font-semibold text-white transition-all',
              isSubmitting || isCheckingDuplicate
                ? 'bg-neutral-400 cursor-not-allowed'
                : 'bg-brand-green-600 hover:bg-brand-green-700 active:bg-brand-green-800 shadow-md hover:shadow-lg',
            )}
          >
            {isSubmitting ? 'Creating Account...' : isCheckingDuplicate ? 'Checking...' : 'Create Account'}
          </button>

          {/* Login link */}
          <p className="text-center text-sm text-neutral-500">
            Already have an account?{' '}
            <Link href="/worker/login" className="text-brand-green-700 font-medium hover:underline">
              Sign in
            </Link>
          </p>
        </div>

        {/* Profile completion hint */}
        <p className="text-center text-xs text-neutral-400 mt-4">
          Complete your profile after signup to unlock paid cleaning routes
        </p>
      </div>
    </div>
  )
}

export default function WorkerSignupPage() {
  return (
    <Suspense fallback={<LoadingFallback />}>
      <WorkerSignupForm />
    </Suspense>
  )
}
```

**What was removed (compared to original 952-line file):**
- `Step` and `View` types
- `OutsideAreaDiverter` component (~80 lines)
- Multi-step state management (`currentStep`, `currentView`, `StepIndicator`)
- Address fields (street, city, state, zip)
- Document upload fields (governmentId, proofOfAddress)
- `isLaunchAreaZip` import and zip validation
- `FileUpload` and `StepIndicator` component imports
- Image compression helpers (`compressImage`, `fileToBase64`)
- Review step UI
- `useSearchParams` (was used for prefilling from waitlist redirect)

**What was kept:**
- Same visual design language (gradients, `brand-*` classes, rounded cards)
- `FormInput` component usage (same pattern)
- Phone number formatting
- Duplicate email/phone check
- Error handling UX
- Redirect to `/worker/verify-email` on success
- Terms checkbox with links
- Login link
- `Suspense` wrapper with `LoadingFallback`

- [ ] **Step 2: Commit**

```bash
git add site/src/app/(app)/worker/signup/page.tsx
git commit -m "feat(site): simplify signup to single-step form (name/email/phone/password)"
```

---

### Task 4: Verify and Clean Up

- [ ] **Step 1: Search for remaining Chicago defaults in the site directory**

```bash
grep -rn '"Chicago"' site/src/ --include="*.ts" --include="*.tsx" | grep -v "node_modules\|.next\|community-areas\|waitlist\|join\|test"
grep -rn "'Chicago'" site/src/ --include="*.ts" --include="*.tsx" | grep -v "node_modules\|.next\|community-areas\|waitlist\|join\|test"
```

Expected: The only remaining "Chicago" references should be in:
- `site/src/lib/chicago-community-areas.ts` (geographic data — keep)
- `site/src/lib/waitlist/` (waitlist ranking logic — keep)
- `site/src/app/(app)/join/` (waitlist page — keep)

The signup page and apply route should have zero Chicago references.

- [ ] **Step 2: Verify the signup page loads**

```bash
cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc/site && npx next build 2>&1 | tail -20
```

Or if a dev server is available:
```bash
cd /Users/codexmetatron/CDX-DEV-PROJECTS/gcc/site && npm run dev
```

Then verify `/worker/signup` renders the simplified form.

- [ ] **Step 3: Commit if any fixes were needed**

```bash
git add -A && git commit -m "chore(site): clean up remaining signup references"
```

Only run if Step 1 or 2 revealed issues.
