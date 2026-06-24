# GCC — Website Sitemap (Next.js App Router, `site/src/app`)

Audited 2026-06-24. Route groups `(app)` don't affect URLs. Dynamic segments in `[brackets]`.

## Public / marketing
`/` (home) · `/app` (app-store landing) · `/north-lawndale` (pilot landing) · `/faq` · `/impact` · `/privacy` · `/terms` · `/blog` · `/blog/[slug]` · `/supporters` · `/residents` · `/business` · `/nominate` · `/unsubscribe`

## Community & engagement
`/community` (forum) · `/community/post/[id]` · `/bounties` · `/bounties/how-it-works` · `/bounties/post`

## Donate / sponsor (public funnels)
`/donate` · `/donate/success` · `/donate/wall`

## Auth & waitlist
`/login` (unified worker/sponsor/admin; aliases `/worker/login`, `/sponsor/login`) · `/auth/verify` (magic link) · `/join` · `/join/success` · `/join/update` · `/waitlist` · `/member` (post-login router)

## Worker area
Onboarding: `/worker/signup` · `/worker/verify-email` · `/worker/documents` · `/worker/forgot-password` · `/worker/reset-password`
Work: `/worker/dashboard` · `/worker/available` (claimable) · `/worker/pending` · `/worker/approved` · `/worker/job/[id]` (execute: checkpoints, before/after photos, GPS) · `/worker/job/[id]/completed`
Info: `/worker/profile` · `/worker/leaderboard` · `/worker/benefits`

## Sponsor area
Onboarding: `/sponsor/signup` · `/sponsor/verify-email` · `/sponsor/forgot-password` · `/sponsor/reset-password`
Manage: `/sponsor` · `/sponsor/dashboard` · `/sponsor/calculator` · `/sponsor/enterprise` · `/sponsor/benefits` · `/sponsor/success`
Recognition & docs: `/sponsor/wall` · `/sponsor/profile/[id]` · `/sponsor/certificates/[id]` · `/sponsor/receipts/[id]` · `/sponsor/receipts/tx/[txId]`

## Support
`/support` · `/support/ticket/[id]`

## Admin console (token-gated; `admin/layout.tsx` redirects to `/login`)
- `/admin/routes` — **route/block management**: list, draw/generate routes, set payout, **assign workers**. (This is the web tool for ordered direct-assignment onboarding today.)
- `/admin/sponsors` — sponsor deliverables/packages fulfillment.
- `/admin/newsletter` — audience + campaigns.
- `/business/dashboard` — business-facing (alt).

**Note:** there is **no dedicated web admin page for queue/batch "next-in-line" onboarding** (matches the API gap) and **no dedicated admin "scan review" page** — cleaning evidence is reviewed via the worker job flow. See `gap-analysis-queue-onboarding.md`.
