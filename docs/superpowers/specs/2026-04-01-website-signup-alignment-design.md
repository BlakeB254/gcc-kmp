# Website Signup Alignment

> Simplify the website worker signup to match the mobile app's global-friendly approach: collect only essential info at registration, defer address and document verification to a later flow.

## Context

The mobile app was just globalized (shipped 2026-04-01) — signup no longer prefills Chicago/IL, address is optional, and verification happens separately. The website signup still enforces a 4-step wizard requiring address (zip-restricted to 60623/60624) and document uploads (government ID + proof of address) before account creation. This creates a friction mismatch: mobile users sign up in seconds, website users face a multi-step application.

### Business Rules (from mobile spec)

- **Routes** ($100 payouts): restricted to verified service area. Verified via residency forms, not signup.
- **Reports & cleanups**: available anywhere globally.
- **Bounties**: available anywhere but require KYC identity verification.
- **Signup**: collect minimal info. Address and documents collected later during verification.
- Verified location overwrites stated location.

## Architecture Note

The mobile app and website use **different registration code paths** that write to the **same database**:

- **Mobile app** → `POST /api/auth/register` → Ktor server (`server/src/main/kotlin/.../AuthService.kt`) — already updated in mobile changes
- **Website** → `POST /api/workers/apply` → Next.js API route (`site/src/app/api/workers/apply/route.ts`) → Drizzle ORM → same `workers` table

Changes in this spec are entirely within the `site/` directory. They don't conflict with the Ktor server changes already shipped. Both paths must produce the same result: a worker record with empty address fields until verified.

## Design

### 1. Simplify Signup Page

**File:** `site/src/app/(app)/worker/signup/page.tsx` (~950 lines → ~300 lines)

Replace the 4-step wizard with a single-step form:

**Fields:**
- Name (required, same validation)
- Email (required, same validation)
- Phone (required, same validation + formatting)
- Password (required, min 8 characters — aligns with mobile `RegisterScreen`)
- Terms agreement checkbox (required)

**Removed entirely:**
- `address` step (street, city, state, zip fields)
- `documents` step (government ID, proof of address uploads)
- `review` step (unnecessary for a short form)
- `StepIndicator` component usage
- `OutsideAreaDiverter` component and the `outside-area` view
- `isLaunchAreaZip` import and all zip validation logic
- `FileUpload` component usage
- The `Step` type (no longer multi-step)
- The `View` type (`form` | `outside-area`)

**Retained:**
- Duplicate email/phone check (`/api/workers/check-duplicate`)
- Phone number formatting helper
- Terms agreement with same copy
- Success redirect to login/dashboard
- Same visual design (gradient background, logo, card layout)

**Form data type becomes:**
```typescript
interface FormData {
  name: string
  email: string
  phone: string
  password: string
  agreeToTerms: boolean
}
```

**Address hint:** After registration, show a toast or banner: "Complete your profile to unlock paid routes" — this nudges users toward verification without blocking signup.

### 2. Simplify API Route

**File:** `site/src/app/api/workers/apply/route.ts` (~303 lines → ~120 lines)

**Required request body:**
```typescript
{
  name: string
  email: string
  phone: string
  password: string
}
```

**Changes:**
- Remove `street`, `city`, `state`, `zip` from required validation
- Remove `governmentId` and `proofOfAddress` from request body
- Remove `isLaunchAreaZip` check
- Remove Vercel Blob upload logic for documents
- Worker record created with empty address fields (`addressCity: ''`, `addressState: ''`, `addressStreet: ''`, `addressZip: ''`)
- Keep: duplicate email/phone check, password hashing, token generation, email verification flow

### 3. Update DB Schema Defaults

**File:** `site/src/db/schema.ts`

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

All four address columns get empty string defaults so worker records can be created without address data. Columns stay `notNull()` to avoid null-handling issues in existing code — empty string signals "not yet provided." Populated during verification via the existing `/api/workers/address-change` endpoint.

### 4. Join/Waitlist Page

**File:** `site/src/app/(app)/join/page.tsx`

No changes. The join page remains accessible at `/join` for community outreach and interest collection. The signup page no longer redirects to it (since there's no zip gating), but it's still useful as a standalone page.

### 5. Unchanged Files

- `site/src/components/forms/FormInput.tsx` — still used for name/email/phone/password fields
- `site/src/components/forms/FileUpload.tsx` — no longer imported by signup, but may be used elsewhere
- `site/src/components/forms/StepIndicator.tsx` — no longer imported by signup, but may be used elsewhere
- `site/src/lib/waitlist/rankings.ts` — `isLaunchAreaZip` no longer imported by signup, kept for join page

## What Success Looks Like

1. User visits `/worker/signup`, sees a simple form (name, email, phone, password, terms)
2. Submits in under 30 seconds — account created, redirected to dashboard
3. No address or document requirements at signup
4. Works for users anywhere in the world
5. Existing verification flows (address change, document upload) still work from profile page
6. Join/waitlist page still works independently
