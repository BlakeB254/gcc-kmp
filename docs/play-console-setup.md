# Google Play Console — setup answers

Companion to `app-store-metadata.md`, which is Apple's. **Do not copy Apple's answers
across.** Three things are materially different on Android and each one is a rejection
risk if carried over:

1. **There is no push notification on Android.** No Firebase, no `google-services.json`;
   `PushNotificationManager.kt` is a documented stub that returns no token. Apple's review
   notes promise push. Play tests claimed functionality — do not claim it here.
2. **No push token is collected on Android**, so the Data safety answers differ from
   Apple's "Device ID → APNs" declaration.
3. **Bank details are entered on Stripe's hosted onboarding, not in the app.** The app
   never sees them, which changes what we declare collecting.

---

## 0. Fix this first: the app is registered as a GAME

The Console is showing *"Finish setting up your game"* and *"Provide game information"*.
That means the app type is set to **Game**. GCC is a workforce app. Leaving it wrong
pulls in the games content-rating questionnaire, the wrong store taxonomy, and a
listing that sits beside games.

**Fix:** Console → *Grow → Store presence → Store settings* → **App category → App type: App**
(not Game). Then set the category below. Do this before the content rating, because the
rating questionnaire branches on it.

---

## 1. App category and contact details

| Field | Answer |
|---|---|
| App type | **App** (not Game) |
| Category | **Business** — the app is a tool for a vetted workforce. *Productivity* is a defensible second choice; Apple used Productivity. Avoid *Maps & Navigation*: maps are a means here, not the product. |
| Tags | community, jobs, local services — keep to what the app does |
| Email | Blake's operational address, not a personal one |
| Website | `https://globalcommunitycleaners.org` |
| Phone | optional; omit unless a monitored line exists |

---

## 2. Privacy policy

`https://globalcommunitycleaners.org/privacy` — verified live, HTTP 200.

Also live and needed elsewhere in the Console:
- Terms: `https://globalcommunitycleaners.org/terms` (200)
- **Account deletion: `https://globalcommunitycleaners.org/delete-account` (200)** — Data
  safety asks for this separately and it is mandatory.

---

## 3. Sign in details (App access)

The app is gated: a new registration cannot reach job features until an admin approves it.
Say so explicitly, or the reviewer sees an empty shell and rejects for "broken
functionality."

Select **"All or some functionality is restricted"** and provide:

| Field | Value |
|---|---|
| Name for instructions | Approved worker account |
| Username | `playreview@gcc.com` |
| Password | **not recorded here** — see `~/.config/gcc/play-reviewer-credentials.txt` (mode 600, alongside the upload keystore) |
| Any other instructions | See the block below |

> GCC is a workforce app for Global Community Cleaners NFP, a registered nonprofit in
> Chicago. Workers are recruited and vetted by the nonprofit before they can access job
> features — this is intentional, the app serves a vetted workforce and not the general
> public. The account above is pre-approved so every feature is reachable.
>
> Location is used for GPS-verified route completion and scan coverage during an active
> job only. Background location is required because a cleaning route is walked over
> 20–60 minutes with the screen off; coverage would be lost otherwise.
>
> The app pays workers. There are no in-app purchases and nothing is ever sold to a user.

**Account status, verified against production 2026-09-22:**

- `playreview@gcc.com` **exists** — worker id **165**, created 2026-09-22, logs in fine.
- It is **not approved**: `verification.status = pending`, government ID and proof of
  address both pending.
- Consequently `GET /api/routes/available` returns **400** for it.

**This is the thing that fails a review.** The reviewer signs in successfully, then hits
the approval gate and sees an app with no reachable functionality — which reads as broken,
not as intentional. Approve all three fields in **one statement**; the verify endpoint
refuses if they are split:

```sql
UPDATE workers SET verification_status = 'approved',
  verification_government_id_status = 'approved',
  verification_proof_of_address_status = 'approved'
WHERE id = 165;
```

(`applereview@gcc.com`, worker id 14, is in the identical pending state — approve it too
if Apple review is upcoming.)

**Gate before submitting:** log in as the account and confirm
`GET /api/routes/available` returns **200**, not 400. That single check is the difference
between passing review and losing the cycle. Then walk it end to end: browse routes,
accept a job, run a route scan with photo evidence, open the wallet.

**Why the password is not in this file:** it has to be typed into Play's App access form
in plaintext, so it is semi-public by design — but a credential in a tracked file is a
mistake this fleet has already had to clean up across two repositories. It lives at
`~/.config/gcc/play-reviewer-credentials.txt`, mode 600, next to the upload keystore.

---

## 4. Ads

**No, my app does not contain ads.** No ad SDK is present in the build.

(The in-game business placements discussed elsewhere are a *separate* product and are not
in this app.)

---

## 5. Content rating

Run the IARC questionnaire as an **App** (see §0), category **Utility, Productivity,
Communication or Other**.

| Question | Answer |
|---|---|
| Violence, sexual content, profanity, drugs, horror | None |
| Gambling / simulated gambling | No |
| User-generated content shared with others | **Yes** — workers upload cleanup photos visible to admins and in community feeds |
| Users can interact / share location with others | **Yes** — location is attached to jobs and reports |
| Digital purchases | No |

Expect **Everyone** or **Everyone 10+**. Answer the UGC and location questions honestly:
they do not raise the rating much, and understating them is a policy violation.

---

## 6. Target audience

**18 and over only.**

Do not tick any age band under 18. This is paid work with identity verification and bank
payouts — selecting an under-18 band pulls the app into the **Families policy**, which
brings a much heavier compliance surface and would almost certainly fail.

Appeal to children: **No.**

---

## 7. Data safety

Declare collection, linked to the user, **not** used for tracking, for:

| Data type | Collected | Purpose | Note |
|---|---|---|---|
| Name, Email, Phone | Yes | Account management, App functionality | Registration and contact |
| **Precise location** | Yes | App functionality | GPS-verified route completion and scan coverage **during an active job only** |
| Photos | Yes | App functionality | Route scans and before/after cleanup evidence |
| **Government ID documents** | Yes | App functionality, Fraud prevention | Uploaded files for worker verification |
| App interactions | Yes | Analytics | Feature usage |
| Crash logs / diagnostics | Yes | App functionality | Carries an **app-generated install ID**, not an advertising or device ID |

**Do NOT declare:**
- *Device or other IDs* for push — there is no push token on Android (no Firebase)
- *Payment info / bank account* — collected by **Stripe on their hosted onboarding page**,
  never by the app. Declare it only if Play's definition pulls in processor-collected
  data; if unsure, say the app does not collect it and note Stripe in the privacy policy,
  which already covers it.

Encryption in transit: **Yes.** Users can request deletion: **Yes** →
`https://globalcommunitycleaners.org/delete-account`.

---

## 8. Government apps

**No.** Global Community Cleaners NFP is a 501(c)(3) nonprofit (EIN 39-3553882), not a
government entity and not acting on behalf of one.

---

## 9. Financial features

**Most likely: "My app doesn't provide any financial features."**

Play's financial-features declaration targets lending, banking, crypto, investments,
insurance and money transmission. GCC does none of those: it pays a worker for completed
work through Stripe Connect, the way any employer or platform pays a contractor.

**Read this one carefully rather than taking my word.** Stripe Connect onboarding collects
bank details, and if Play's wording reaches processor-collected payout data, the honest
answer changes. If in doubt, declare and explain — an over-declaration is a question, an
under-declaration is a policy violation.

---

## 10. Health

**No.** No health, medical, fitness or wellness features.

---

## 11. Store listing

| Field | Value |
|---|---|
| App name (30 chars) | `GCC — Clean Blocks, Get Paid` (28) |
| Short description (80) | `Accept a cleaning route, document the work, and get paid per block.` (67) |
| Full description | Adapt the Apple copy in `app-store-metadata.md` — **but delete the push-notification claim**, which is not true on Android |

### Graphics required

| Asset | Spec | Status |
|---|---|---|
| App icon | **512 × 512** PNG | ⚠️ the repo's largest launcher icon is **192 × 192** — a 512 must be produced from the vector/high-res source, not upscaled |
| Feature graphic | **1024 × 500** PNG/JPG | ⚠️ does not exist yet |
| Phone screenshots | 2–8, min 320px, 16:9 or 9:16 | ⚠️ capture after the Null Island map bug is fixed — the dashboard currently renders at 0°,0° (West Africa) |

---

## Open issues that affect submission

- **The dashboard map renders at latitude 0, longitude 0.** The route card reads
  "North Lawndale / 60624" correctly, so text arrives and coordinates do not. It is the
  first screen a reviewer sees. Fix before screenshots, and ideally before submitting.
- **`POST_NOTIFICATIONS` is declared in the manifest but there is no push implementation.**
  Either wire Firebase or drop the permission; an unused sensitive permission invites
  questions during review.
