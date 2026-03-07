# App Store Metadata — GCC

All text and selections needed for App Store Connect submission.

---

## App Store Listing

**App Name:** GCC

**Subtitle:** Clean Streets. Earn Money.

**Category:**
- Primary: Productivity
- Secondary: Navigation

**Description:**

GCC is the official app for Global Community Cleaners, a nonprofit dedicated to revitalizing neighborhoods in Chicago's North Lawndale community. Workers use the app to accept GPS-verified cleaning routes, complete block-by-block assignments, and earn money for every block they clean. Each job is backed by real accountability: workers photograph before-and-after evidence at checkpoints along their route, and GPS verification confirms they walked the assigned blocks. The result is cleaner streets, documented proof of impact, and fair pay for honest work.

The app guides workers through every step of a cleaning job. Real-time maps powered by MapLibre show assigned routes, checkpoint locations, and progress. Workers claim available routes, navigate to their starting point, and tap through checkpoints as they clean. At each checkpoint, the camera captures photographic evidence that is timestamped and geotagged. Once a route is complete, the job enters a verification and approval process before payout is issued to the worker's wallet. The entire flow — from route acceptance to payment — happens inside the app.

GCC also features leaderboards and badges that recognize top performers and consistent contributors. Workers can track their earnings, view completed job history, and monitor their standing in the community. Whether you are looking for flexible work that makes a visible difference in your neighborhood or you want to be part of a movement to restore pride in Chicago's streets, GCC puts the opportunity in your hands. Download the app, get approved, and start earning.

**Keywords:** community cleaning,gig work,Chicago,earn money,block cleaning,route,GPS,neighborhood,worker,green jobs

**Support URL:** https://globalcommunitycleaners.org/support

**Privacy Policy URL:** https://globalcommunitycleaners.org/privacy

---

## Age Rating Questionnaire Answers

Answer each question in App Store Connect as follows:

| Question | Answer |
|----------|--------|
| Cartoon or Fantasy Violence | None |
| Realistic Violence | None |
| Prolonged Graphic or Sadistic Realistic Violence | None |
| Profanity or Crude Humor | None |
| Mature/Suggestive Themes | None |
| Horror/Fear Themes | None |
| Medical/Treatment Information | None |
| Alcohol, Tobacco, or Drug Use or References | None |
| Simulated Gambling | None |
| Sexual Content or Nudity | None |
| Graphic Sexual Content and Nudity | None |
| Unrestricted Web Access | No |
| Gambling and Contests | No |

**Resulting rating:** 4+ (suitable for all ages)

---

## App Privacy Declarations

Select the following data types in App Store Connect under "App Privacy":

### Location

| Data Type | Purpose | Linked to User | Tracking |
|-----------|---------|----------------|----------|
| Precise Location | App Functionality — used for GPS-verified route completion and checkpoint validation during active cleaning jobs | Yes | No |

### Photos or Videos

| Data Type | Purpose | Linked to User | Tracking |
|-----------|---------|----------------|----------|
| Photos | App Functionality — workers capture before/after checkpoint evidence photos during jobs | Yes | No |

### Contact Info

| Data Type | Purpose | Linked to User | Tracking |
|-----------|---------|----------------|----------|
| Name | App Functionality — worker account registration and identification | Yes | No |
| Email Address | App Functionality — account login, notifications, and support | Yes | No |
| Phone Number | App Functionality — account verification and contact | Yes | No |

### Financial Info

| Data Type | Purpose | Linked to User | Tracking |
|-----------|---------|----------------|----------|
| Payment Info | App Functionality — payout processing via Stripe for completed cleaning jobs | Yes | No |

### Identifiers

| Data Type | Purpose | Linked to User | Tracking |
|-----------|---------|----------------|----------|
| Device ID | App Functionality — push notification delivery via APNs | Yes | No |

### Usage Data

| Data Type | Purpose | Linked to User | Tracking |
|-----------|---------|----------------|----------|
| Product Interaction | Analytics — understanding feature usage to improve the worker experience | Yes | No |

---

## Review Notes for Apple

Paste the following into the "Notes" field in App Store Connect:

---

GCC is a workforce app for Global Community Cleaners NFP, a registered nonprofit organization in Chicago, IL. The app is used by approved cleaning workers to accept, complete, and get paid for neighborhood cleaning routes in the North Lawndale community.

**How the app works:**
Workers are recruited and verified by the nonprofit before they can access job features. Once approved, workers browse available cleaning routes on a map, accept a route, walk the assigned blocks while cleaning, and photograph before/after evidence at GPS-verified checkpoints. Completed jobs go through an approval process, and workers are paid per block.

**Approval gate:**
New users who register cannot access job features until their account is approved by an administrator. This is intentional — the app serves a vetted workforce, not the general public. The demo account below is pre-approved so all features are accessible for review.

**Demo credentials:**
- Email: `applereview@gcc.com`
- Password: `Review2026!`

This demo account has been pre-approved with full verification status, so the reviewer can test all features including route browsing, job acceptance, checkpoint completion, photo capture, and wallet/earnings views.

**Required permissions:**
- **Camera:** Required for capturing before/after evidence photos at each checkpoint during a cleaning job. The app cannot complete checkpoints without camera access.
- **Location (including background):** Required for GPS-verified route completion. The app validates that the worker is physically present at each checkpoint. Location is accessed during active jobs only and is used solely for route verification, not for tracking outside of job activity.
- **Push Notifications:** Used to alert workers when new routes are available, when a job is approved, and when payouts are issued.

**No in-app purchases or subscriptions.** Workers earn money through the app; they do not pay anything to use it.

---

## Demo Account Setup Reminder

Before submitting to the App Store, the demo account must exist in the production database with pre-approved status. Register the account through the app first (using the credentials above), then run the following SQL to grant full approval:

```sql
-- Run against Neon PostgreSQL before App Store submission
-- The password hash must be generated by the Ktor server's AuthService
-- Register via the app first, then update status:
UPDATE workers SET verification_status = 'approved',
  verification_government_id_status = 'approved',
  verification_proof_of_address_status = 'approved'
WHERE email = 'applereview@gcc.com';
```

Verify the account works end-to-end (login, browse routes, accept a job, complete a checkpoint with photo, view wallet) before submitting for review.
