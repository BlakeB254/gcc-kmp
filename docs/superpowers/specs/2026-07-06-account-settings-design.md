# Account Settings: edit profile, change email/phone, password change, 2FA

Date: 2026-07-06
Status: self-approved (autonomous background session — Blake asked for "a way for
users to edit their information that follows established industry best patterns
for changing phone number, updating email address, setting up 2FA etc.").
Assumptions are flagged inline; anything Blake disagrees with is cheap to change
before merge.

## Goal

Workers can manage their own account from the iOS app: edit their name, change
their email address (verified), change their phone number, change their
password, and enroll in / disable two-factor authentication — each guarded the
way mature consumer apps guard them (re-authentication for sensitive changes,
verification of new contact points, security notifications, recovery codes).

## What exists today (from codebase exploration)

- Auth is stateless JWT (`site/src/lib/mobile-auth.ts`, `requireMobileAuth`),
  PBKDF2-SHA512 passwords split across `workers.hash`/`workers.salt`.
- `POST /api/auth/verify-2fa` + client `verify2FA()` + LoginScreen's
  `TwoFactorSection` UI exist but are DEAD — login always returns
  `requiresTwoFactor: false`, `createTwoFactorToken` has no callers, and there
  are no 2FA columns. This design lights up that existing seam.
- Email sending works (Resend, `site/src/lib/email/index.ts`). SMS is an
  unreliable carrier-gateway hack; Twilio is not wired (env vars only).
- No email/phone/profile change endpoints exist. The repo pattern is
  field-specific routes (`PATCH /api/workers/me/handle`).
- `workers.emailVerified` exists; no `phoneVerified`, no 2FA columns.
- In-memory OTP maps (`phone-verification.ts`) are broken on Vercel
  serverless; all new verification state must be DB-backed.

## Decisions (with the industry pattern each follows)

### 1. Edit profile (name) — low sensitivity
`PATCH /api/workers/me/profile` `{ name }`. Authenticated, no re-auth.
Name only for now (address editing is out of scope; display handle already has
its own flow).

### 2. Change password — re-auth with current password
`POST /api/workers/me/change-password` `{ currentPassword, newPassword }`.
Verify current password, min 8 chars (matches reset-password rule), re-hash,
send a security-notification email to the account address. Pattern: current
password required even though the session is authenticated (stolen-device
protection).

### 3. Change email — verify NEW address before switching, notify OLD
Two-step, DB-backed:
- `POST /api/workers/me/email/change-request` `{ newEmail, password }` —
  re-auth with password; reject if email taken (case-insensitive); store
  `pendingEmail` + `pendingEmailToken` (`"token:code"`, 6-digit code, same
  encoding as the existing `emailVerificationToken`) + `pendingEmailExpires`
  (30 min) + `pendingEmailAttempts` (5 max); send the code to the NEW address
  (Resend) and a "your email is being changed" notice to the OLD address.
- `POST /api/workers/me/email/confirm` `{ code }` — on match: `email =
  pendingEmail`, `emailVerified = true`, `emailVerifiedAt = now`, clear
  pending columns. Unique-violation race → 409 (same as handle route).

The account email only ever changes after the new inbox proves receipt — the
standard pattern (Google/GitHub/Stripe all do this).

### 4. Change phone — re-auth now, SMS-OTP seam for later
`POST /api/workers/me/phone/change-request` `{ newPhone, password }` — re-auth
with password, update immediately, security notice to account email.

**Assumption / deviation flagged for Blake:** the industry-best pattern is an
SMS OTP to the new number, but the only SMS channel today is the
carrier-email-gateway hack, which is too unreliable to gate an account field
on (users would get stuck mid-change). Phone is contact info here, not an auth
factor, so password re-auth + email notification is proportionate. The
endpoint is shaped so an SMS OTP confirm step can be inserted when Twilio is
actually wired (tracked in the PR body).

### 5. Two-factor authentication — TOTP (authenticator app) + recovery codes
TOTP over SMS/email because it needs no delivery channel (our SMS is
unreliable), works offline, and is the strongest widely-supported second
factor. RFC 6238, 30 s step, 6 digits, ±1 window, implemented in
`site/src/lib/totp.ts` with node crypto (no new dependency; verified against
RFC test vectors).

Schema: `totpSecret` (base32, nullable), `twoFactorEnabled` (bool, default
false), `twoFactorRecoveryCodes` (jsonb: array of SHA-256 hashes),
`twoFactorEnabledAt` (timestamptz).

Endpoints:
- `POST /api/workers/me/2fa/setup` `{ password }` — re-auth; generate secret;
  store it with `twoFactorEnabled` still false (not armed until confirmed —
  standard enrollment pattern); return `{ secret, otpauthUri }`.
- `POST /api/workers/me/2fa/enable` `{ code }` — verify a live TOTP code
  against the stored secret (proves the authenticator actually has it);
  set enabled, generate 10 recovery codes (format `XXXX-XXXX`), return them
  in plaintext ONCE, store only hashes. Security email sent.
- `POST /api/workers/me/2fa/disable` `{ password, code }` — password AND a
  valid TOTP or recovery code; clear all 2FA columns; security email sent.

Login flow change (`/api/auth/login`): after password verifies, if
`twoFactorEnabled` → return `requiresTwoFactor: true` + `twoFactorToken`
(5-minute challenge JWT carrying only workerId + purpose claim; no code in it,
unlike the dead scaffold) and NO session token. `POST /api/auth/verify-2fa` is
rewritten: validate challenge JWT, then accept a TOTP code or an unused
recovery code (recovery code is consumed = hash removed); issue the real
mobile token. Client already has this flow wired (LoginViewModel.verify2FA +
TwoFactorSection); only the section copy changes ("Enter the code from your
authenticator app").

**Assumption:** Apple/Google sign-in bypasses app-level 2FA (the IdP enforces
its own) — the common consumer pattern. Password reset flow is unchanged.

### Security notifications
One reusable `security-alert` Resend template; sent on password change, email
change (to the old address), phone change, 2FA enable/disable. Send failures
never fail the mutation (email is best-effort, matching existing routes).

### Rate limiting / abuse
DB-backed expiries + attempt caps (email-change: 5 attempts, 30 min; 2FA
challenge: 5-min JWT expiry). The unenforced `loginAttempts`/`lockUntil`
columns stay out of scope. No new in-memory limiter (broken on serverless).

## Mobile (Compose Multiplatform)

One new nav entry `Screen.AccountSettings`, pushed from SettingsScreen's
Account card (card becomes tappable, keeps showing name/email/phone). The
screen hosts sub-flows driven by a sealed `AccountSettingsStep` in one
`AccountSettingsViewModel` (avoids 5 nav routes; matches the phase-driven
screen pattern used elsewhere):

- Overview: rows for Name, Email (+ "Verified" chip), Phone, Password
  (`••••••••`), Two-Factor Authentication (On/Off) — the iOS Settings idiom.
- Edit name: single field + save.
- Change email: step 1 new email + current password → step 2 six-digit code
  entry (reuses the login TwoFactorSection idiom) + resend.
- Change phone: new phone + current password.
- Change password: current + new + confirm, client-side match/length checks.
- 2FA setup wizard: password → secret shown as a copyable setup key + an
  "Open authenticator app" button (opens the `otpauth://` URI directly — iOS
  hands it to the installed authenticator; no QR library needed) → code
  confirm → recovery codes screen (copy-all button, "I saved these" gate).
- 2FA disable: password + code confirm dialog.

New `GccApiClient` methods + `@Serializable` DTOs in `shared/` (request/
response types listed above), `Worker` gains `twoFactorEnabled: Boolean =
false` (serialization default keeps old servers compatible).

Validation (email format, phone digits, password length/match, code length)
lives in a pure `AccountValidation` object so it unit-tests without Ktor;
tests in `composeApp` commonTest (shared/'s own commonTest is broken).

## Schema-ownership compliance (CLAUDE.md)

`schema.ts` gains: `pendingEmail`, `pendingEmailToken`, `pendingEmailExpires`,
`pendingEmailAttempts`, `totpSecret`, `twoFactorEnabled`,
`twoFactorRecoveryCodes`, `twoFactorEnabledAt`. Same PR updates the Exposed
mirror (`server/.../db/tables/IdentityTables.kt`) and shared `Worker` DTO.
Deploy via `npm run db:push` (NOT drizzle-kit migrate — journal is divergent);
this happens at merge time, not in the PR.

## Error handling

- All new routes: `requireMobileAuth` guard, `{ data, success, message }`
  envelope, 401 wrong password, 409 email taken, 400 bad/expired/exhausted
  code (with distinct messages so the app can show "code expired" vs "wrong
  code"), 429 attempt-cap.
- Client: inline `GccError` text per existing form pattern; success returns to
  Overview with refreshed worker profile.

## Testing

- `site/`: `npx tsc --noEmit`; TOTP verified against RFC 6238 test vectors via
  a node script during development (no jest infra exists in site/).
- Mobile: `AccountValidationTest` + a recovery-code/format test in composeApp
  commonTest (`desktopTest` task); iOS sim link + xcodebuild smoke.
- Live round-trips against prod are NOT run (writes to prod auth data);
  TestFlight pass requested in the PR body.

## Out of scope

Address editing, SMS OTP delivery (until Twilio is wired), login attempt
lockout enforcement, sponsor accounts, web-UI parity, session revocation on
password change (tokens are stateless 7-day JWTs — noted in PR as a known
limitation worth a future `tokenVersion` column).
