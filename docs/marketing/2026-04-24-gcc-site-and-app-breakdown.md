# Global Community Cleaners — Site & App Breakdown

> A long-form, CTA-ready narrative covering everything sponsors, workers, and community members can do with GCC today and what's shipping next. Written to lift-and-drop into a landing page, press kit, investor memo, or long-caption social post.

---

## The headline

**$100 cleans your block. 100% of it pays a neighbor to do the work.**

Global Community Cleaners is a paid, neighbor-run block-cleaning program built on transparency, dignity, and recurring community investment. We launched in North Lawndale, Chicago (60623, 60624). We're scaling to every neighborhood that asks.

---

## What GCC actually is

A three-sided marketplace:

- **Sponsors** — individuals, small businesses, enterprises, nonprofits, municipalities — fund block cleanings at $100 per block. They can schedule when, add service and branding upgrades, and see exactly what their dollars paid for. Every payment is line-itemized.
- **Workers** — vetted residents of the service area — claim cleaning jobs in an app, show up, do the work, document it, and get paid the same day.
- **Community** — residents watch it happen. Every cleaned block gets a yard sign. Every before/after gets posted. Every sponsor is named.

There is no middle layer siphoning off the sponsor's contribution. **The full $100 goes to the worker who cleans the block.** Upsells and add-ons are priced separately so the core promise is never diluted.

---

## The sponsor experience (web — globalcommunitycleaners.org)

### Why sponsors show up

- **Small businesses** want clean sidewalks in front of their storefront and to be visibly invested in their community.
- **Enterprises** want a CSR program that's photographable, accountable, and local — not a generic check to a large nonprofit.
- **Residents** want to pay the kid down the block to sweep the block, without figuring out the logistics themselves.
- **Neighborhood institutions** — churches, community orgs, Black Panther Party Cubs, Del Kar Pharmacy, Barney's New Life, Monday Coffee — want to co-brand the work and make it part of their visible community footprint.

### The calculator

Sponsors open the **Sponsor Calculator** at `/sponsor/calculator`. They pick:

1. **How many blocks?** 1, 3, 5, 10, or a custom count. Price is transparent — $100 × block count.
2. **Specific blocks or general fund?** Sponsors can click blocks on an interactive map of North Lawndale to pick exact routes, or let GCC assign blocks to the highest-need areas.
3. **Add-ons?** Ten categories of sponsor upgrades:
   - Co-branded worker vests with the sponsor's logo
   - Equipment branding (cart panels, rocker decals)
   - Street advertising (yard signs: "Cleaned by [Sponsor]")
   - Digital presence (sponsored social posts with GCC tag)
   - Enhanced cleaning (wall plaques, pressure-washes)
   - Seasonal services (snow clearing with branded decals)
   - Green services (tree-well planting with branded markers)
   - Business engagement (flyer distribution to neighbors)
   - Events (block parties, community days)
   - Media (before/after photo packages, short documentary films)
4. **When?** Optional. Sponsors can pick a specific date, a monthly/quarterly/annual recurrence, or let GCC schedule based on route priority. If they defer, their dashboard shows the assignments filling in as GCC books them.
5. **Discounts** — volume, recurring, and enterprise tiers compound. A quarterly campaign across 50 blocks with full branding is priced very differently from a one-off $100 block.

Every add-on shows a **live co-branded mockup** on the calculator card — before/after wipe animations that visualize exactly what "Monday Morning Street Cleaners" (Monday Coffee's campaign) or "Cubs Clean the Block" (Black Panther Party Cubs' campaign) looks like on the actual cleaning worker's vest, on the cart, or on the community yard sign. The mockup carousel demos four different example sponsors so a new sponsor can see their own brand slotted in.

### The checkout

Stripe-powered, one-time or recurring. Apple Pay, Google Pay, card. 100% of the base block fee flows to the worker; GCC's fee is separately itemized and applies only to add-ons and upgrades.

### The dashboard — the real product

After checkout, sponsors land on a dashboard that shows:

- **Campaign summary** — total invested, blocks funded, workers engaged, before/after photo count.
- **Itemized ledger** — every transaction as a line item with the assigned block, the scheduled cleaning date, the base $100, any add-ons, and the subtotal. **The ledger updates live as GCC assigns blocks and dates.** A sponsor who deferred scheduling sees the ledger fill in row by row as the queue is processed.
- **Per-block upcharge panel** — add more services to an already-booked block up until 12 hours before the cleaning. Drop the "extra cleaning pass" add-on, swap it for "sidewalk pressure-wash," add a "photo package." All with live pricing and Stripe confirmation.
- **Campaign-wide upgrades** — one click to apply an upgrade to every block in the campaign at a 15% campaign-scope discount.
- **Inside-12-hours requests** — if the cleaning is too close, the sponsor submits a **request** instead. GCC messages back within two hours to confirm whether the add-on is feasible. Approved requests charge on confirmation; denied requests don't.
- **Photo gallery** — every cleaning produces before/after photos auto-uploaded by the worker via the mobile app. Sponsors can download, repost, share.
- **Impact reports** — a quarterly PDF showing block coordinates, hours worked, tons of trash removed, community engagement metrics, ROI for CSR reporting.
- **Tax receipt** — 501(c)(3) pass-through when applicable.

### The community-facing pages

Independent of the sponsor dashboard:

- **`/impact`** — live neighborhood heatmap showing every cleaned block, every funded block, every spot-report citizens have flagged as needing cleaning.
- **`/supporters`** — public wall of everyone who sponsored, displayed by tier, with links to sponsor pages or social handles (opt-in).
- **`/bounties`** — urgent cleaning needs citizens have flagged. Anyone can put $100 on a specific bounty to fund it.
- **`/wall`** — a neighborhood message wall where residents post what they want cleaned, thanked, or celebrated.
- **`/faq`** — every question about how the program works, how workers are paid, how the service area expands.
- **`/community`** — threaded forum for resident + worker + sponsor conversations moderated by GCC.
- **`/support`** — direct feedback channel for any of the three user types.

---

## The worker experience

### Becoming a worker

North Lawndale residents 18+ with a valid ID, a smartphone, and willingness to sweep a block. The full onboarding takes ~10 minutes: basic profile, address verification (proof-of-address upload), government ID upload, phone verification, Stripe Connect setup for instant payouts.

### Earning

- **$100 per block** — flat, guaranteed. No tips, no hidden cuts, no platform fees.
- **Same-day payout** — via Stripe Connect. Workers choose instant (Stripe's standard fee) or standard 1–2 day.
- **Flexible schedule** — claim the jobs that fit. Morning, afternoon, weekend. No shifts. No minimum hours. No manager.
- **Earnings calculator on the benefits page**: 1 block/week = $400/mo. 5 blocks/week = $2,000/mo. 10 blocks/week = $4,000/mo.

### Doing the work

Workers use the GCC iOS app (also on Android via Compose Multiplatform). The app:

1. Shows a **heatmap of available blocks** near their address, colored by cleaning urgency and funding status.
2. Lets them **claim a block** with one tap. The job is now theirs — locked from other workers for 48 hours.
3. Provides a **checkpoint route** — the block is broken into checkpoints (start, midpoint, end) with GPS-verified photo submissions.
4. Requires **before, during, and after photos** at each checkpoint. These feed directly into the sponsor dashboard and community gallery.
5. Triggers **instant payout** on job verification — typically within 30 minutes of the worker finishing.
6. Tracks **achievements, leaderboards, and community points** — a monthly "Top Cleaners" leaderboard for each neighborhood.

### Growth path

Workers earn community points for every job. Points unlock roles: **Cleaner** → **Verified Cleaner** → **Block Lead** → **Route Captain** → **Neighborhood Organizer**. Higher roles earn per-job bonuses, can mentor new workers, and get priority on premium-tier jobs (enterprise sponsor blocks, event crews, seasonal surge work).

---

## The iOS app — where the experience comes alive

The app is Compose Multiplatform, hosted on iOS via a thin Swift wrapper. One codebase, native feel on both platforms.

### What's live today

- Worker signup + verification flow
- Job claim + checkpoint photo flow
- Instant payout settings
- Profile + leaderboards
- Heatmap of available blocks

### What's shipping next (in-progress implementation)

- **Dynamic, richer heatmap** — every block in any non-trivial state (available, scheduled, cleaning-in-progress, completed, flagged-issue) rendered as a GeoJSON polygon with intensity-weighted fill. At wide zoom you see a smooth density heatmap; at street level you see individual block polygons color-coded by status.
- **Tap-to-inspect** — tap any affected area, get a bottom sheet with the block code, status, sponsor count, scheduled cleaning datetime, last-cleaned-at timestamp, and a "Sponsor this block" CTA that deep-links back to the web calculator.
- **Live updates** — the heatmap polls every 30 seconds. If GCC just scheduled a cleaning on your block, you see it in half a minute.
- **Universal links** — text the URL, tap it on iPhone, the app opens directly to the right block if installed. If not installed, the link lands on a polished /app page with the App Store badge.
- **Assignment notifications** — sponsors who let GCC assign their blocks get push notifications + email + dashboard inbox entries every time a block is scheduled, along with an itemized line item added to their ledger.
- **Brand kit unified** — the same kelly-green, blue, type system, and corner radii you see on the web now consistently applied to every screen in the iOS and Android app.

### What's next after that

- **Sponsor mode** inside the app — so sponsors can view their dashboard + approve upcharge requests from mobile.
- **Offline-first** — worker app keeps working on sidewalks with spotty LTE. Jobs queue, photos buffer, sync when back online.
- **In-app co-branded mockups** — the same live sponsor carousel that's on the web calculator, accessible on a phone so a sponsor can pitch the program at a business-district meeting.

---

## The new flex-upcharge flow (shipping next)

A direct response to sponsor feedback: **"We know what we want at the time of booking, but sometimes the block conditions change and we want to add more service right before the cleaning."**

### The rules

1. Every sponsor booking becomes one **transaction** per block.
2. Transactions may or may not have a **scheduled cleaning date**. If the sponsor didn't pick, GCC assigns it and notifies them.
3. **Up to 12 hours before** the scheduled cleaning, the sponsor can add or remove upcharges on that block directly — charged immediately via their saved Stripe method.
4. **Inside 12 hours**, the sponsor submits a request. GCC's operations team sees it in an admin queue, confirms within 2 hours whether the crew can actually fulfill the add-on, then approves-and-charges or denies.
5. **Campaign-scope** add-ons (applied to every block in a campaign at once) get a **15% campaign discount**. Per-block add-ons are full list price.
6. **Refunds** — remove an upcharge before T-12h, Stripe refunds immediately. Can't remove inside 12h (would leave the crew short on supplies / logistics).

### Why it matters

Sponsors get **agency** without operational chaos. They don't have to predict everything at booking. They can react to what their block needs the day before the cleaning — rain tomorrow? add pressure-wash and leaf-haul. Community event on Saturday? upgrade to event-crew pricing. Miss the 12-hour window? GCC still listens, just manually.

### Itemized transparency

Every add-on that hits the sponsor's card shows up on the itemized ledger within seconds. Every denial shows up with a note explaining why. Every refund shows up as a line item. **Sponsors never get a surprise charge. They never see a lump-sum bill.** They see exactly what they bought, exactly when, and exactly what it cost.

---

## Why this is different from "traditional" community programs

| Traditional charity model | GCC |
|---|---|
| Donate $100, hope it's spent well | $100 pays a neighbor directly, line-itemized |
| Annual impact report as a PDF | Photo-documented every cleaning, same day |
| "Volunteer opportunities" | Paid work, same-day payout |
| Opaque overhead | 100% of base flows to worker; add-ons are separately priced |
| One-way sponsorship | Co-branded campaigns — your brand is visibly linked to the work |
| Hard to schedule around seasonality | Add upcharges up to T-12h without a new contract |
| National charity, local invisibility | Hyperlocal by design — launching neighborhood by neighborhood |

---

## Expansion — bringing GCC to your neighborhood

We launched in North Lawndale. We are expanding. If you are:

- A **community leader** with a block you want visibly invested in
- A **nonprofit** looking for a labor partner with skin in the game
- A **municipality** with a sanitation budget hole you'd rather pay residents to plug
- A **business** anywhere in the world that wants clean streets and a visible local partnership

Tell us. The program is designed to scale: any neighborhood with a resident workforce willing to do the work, a funding pipeline (sponsor base, municipal grant, CSR program, or bounty fund), and coordination capacity can run GCC locally.

**Enterprise tiers** (Community Champion → Impact Partner → Documentary Sponsor → Movement Partner → Legacy Sponsor → Founding Partner) offer long-term custom partnerships — full neighborhoods, multi-month campaigns, embedded film documentation, co-designed research studies. See `/sponsor/enterprise`.

---

## CTAs — copy-paste for landing, social, email

**Short (Twitter/SMS):**
> $100 cleans your block. 100% of it pays a neighbor. Sponsor a block today → globalcommunitycleaners.org/sponsor

**Medium (Instagram/LinkedIn):**
> In North Lawndale, residents clean their own blocks. Sponsors pay them directly — $100 a block, 100% to the worker. You pick the block, they post before-and-after photos. It's paid work for neighbors, clean streets for the block, and real transparency for you. Start a campaign today at globalcommunitycleaners.org/sponsor.

**Long (email / press release):**
> Global Community Cleaners is a transparent, paid, resident-run street-cleaning program. Every $100 a sponsor contributes pays one North Lawndale resident to clean one specific block on a specific date — with before-and-after photos delivered the same day. Sponsors can co-brand the work (custom vests, yard signs, social-media co-posts), schedule recurring cleanings, and add enhanced services (snow removal, tree-well planting, pressure-washing) up to 12 hours before the cleaning happens. Workers earn same-day payouts via Stripe. Sponsors see an itemized ledger of every dollar. Communities get visibly cleaner streets and dignified employment for their neighbors. Launch partners: Monday Coffee, Del Kar Pharmacy, Barney's New Life Health Foods, Black Panther Party Cubs. Expansion partnerships open globally — we're looking for community leaders, nonprofits, municipalities, and businesses anywhere in the world ready to bring this model to their neighborhood. globalcommunitycleaners.org.

**App-install CTA (iMessage share / QR poster):**
> Get the GCC app. See the heatmap of every cleaned, scheduled, and funding-ready block in your neighborhood. If you're a resident, earn $100 per block. If you're a sponsor, pick the exact block your business will fund. globalcommunitycleaners.org/app

**In-store signage / lawn sign:**
> This block was cleaned by [Sponsor] in partnership with GCC.
> Your neighbor earned $100 keeping your street clean.
> Sponsor the next one → globalcommunitycleaners.org/sponsor

---

## What we're building right now (transparency)

- ✅ Upgraded co-branded sponsor calculator with live wipe-and-swap mockups across 4 example sponsors
- ✅ Sponsor sign-in on the top nav, returning-sponsor banner on /sponsor, sponsor dashboard with block tracking
- ✅ Forward-looking empty-state copy everywhere — the program is always accepting new sponsorships, there's never a "full" or "closed" state
- ✅ Map shows route polygons even before DB has live blocks, so sponsors can always visualize coverage
- 🚧 Flex-upcharge flow: add services up to 12 hours before cleaning, request-path for sub-12h
- 🚧 Itemized sponsor ledger: every transaction, every add-on, every refund line-itemized in real time
- 🚧 Admin-side assignment queue: GCC operations dashboard for manually assigning blocks + dates to deferred-scheduling sponsors
- 🚧 iMessage-grade OG image for `/app` share links
- 🚧 Unified brand kit flowing from a single tokens.json into web (Tailwind), iOS (asset catalog), and Android (Compose MaterialTheme)
- 🚧 Dynamic iOS app heatmap with tap-to-inspect affected areas + live updates
- 🚧 Universal links: tap a GCC block URL on iPhone, open directly in the app

---

## Closing

The point of GCC isn't philanthropy. It's **infrastructure** — a transparent, recurring, neighborhood-scale system where somebody on your block earns a fair wage to make your block look like somebody cares. Because somebody does. You do. Your neighbors do. Your local coffee shop does. And increasingly, so does the small pharmacy, the nonprofit, the church, the local youth program, and the business across the street that decided to stop waiting for the city to show up.

**If you live in North Lawndale:** we want you cleaning or sponsoring. Both work.

**If you don't:** we want you bringing GCC to your neighborhood. Partner with us.

**If you're a brand looking for a CSR program that actually shows up in the community every week:** we want to co-brand real work with real impact, not a one-time photo op.

globalcommunitycleaners.org

---

_Last updated: 2026-04-24. Live preview: https://gcc-omega.vercel.app. Auto-deploy via GitHub → Vercel._
