# GCC Feature Spec — Canon vs. Methods

_Last updated: 2026-05-07_

This is the canonical spec for what Global Community Cleaners is
building. It is split into two parts:

- **§A · Canon** — the end-state we are aiming for. These are the
  invariants. Treat changes here as serious — they redefine the product.
- **§B · Methods (current)** — how we currently deliver against the
  canon. These are tactical and **expected to change over time**: as
  device capabilities improve, as the data layer matures, as we learn
  what works on the streets, the methods evolve. A working method today
  may be replaced by a better one next quarter without invalidating the
  canon above.

When in doubt, default to the canon. The methods exist to serve it.

---

## §A · CANON · The end result we are going for

These are the load-bearing commitments. Implementations come and go;
this is the bedrock.

### A.1 · We are the field-evidence layer for community cleanliness

GCC is **not a cleaning service**. We are the data infrastructure that
makes neighborhood cleanliness measurable, fundable, and accountable
block-by-block. Cleanups are an output of the system, not the input.

### A.2 · The data must be high-quality real-world spatial data

The end-state quality bar is **Pokémon-Go-grade real-world capture**:
volumetric, georeferenced, time-stamped, attested, and resistant to
gaming. A spot is not a flat photo with a pin on a map; a spot is a
*scanned region of physical space* with measurable extent, depth, and
provenance.

**Hard invariants:**

- Every spot is georeferenced with high-confidence GPS or AR-anchored
  position.
- Every spot is timestamped at capture and re-timestamped at every
  reverification.
- Every capture carries an attestation (device, session, capture index,
  live-capture flag) that distinguishes it from gallery uploads.
- Where the device supports it, the spot footprint is derived from
  *measured 3D scan extent*, not a slider radius.

### A.3 · Anyone, anywhere can contribute — but evidence is verified

The app is **global**. Any user, anywhere, can spot dumping, blight, or
unsafe conditions in their own neighborhood. Other users earn points by
verifying that a spot is real, still there, or has been resolved.

The verification layer is what makes the data trustworthy. A single
photograph from a single user is a claim; a confirmed spot with at
least one peer verification is evidence.

### A.4 · The block, paid, is the unit of action

When data turns into action, the unit is **one block, one resident,
$100, photographed before-and-after**. One participant per block.
Intergenerational by design (elders + youth). 100% of the block fee
goes to the resident doing the work. The data discipline (peer-reviewed
greening trials at Penn) is the same discipline we apply.

### A.5 · The work must be hard to fake and easy to participate in

Two competing pressures, both load-bearing:

- **Hard to fake**: live-capture attestation, GPS geofencing, peer
  verification, duplicate detection, capability-aware capture mode that
  prefers depth scans over flat photos when available.
- **Easy to participate in**: low friction signup, one-tap report,
  responsive on every iPhone size, keyboard never traps, account-less
  reading of articles, sign-in via Apple or Google for the lazy path.

Anything that erodes either invariant is a regression.

### A.6 · Bounties make the loop exciting; secret bounties make it surprising

Cleanups should not feel like chores. **Public bounties** announce a
reward in advance; **secret bounties** are hidden coins that only
reveal when a cleanup completes. Surprise reward turns a mundane
sidewalk pickup into a windfall — and gives sponsors a way to fund
"cleanup events" without exposing budgets in advance.

### A.7 · The platform respects worker dignity

Workers are not gig-app cogs. The model is **pay first, paperwork
discreet, recognition public**. Verification documents are sensitive;
amounts earned are not.

### A.8 · The data compounds; the rewards compound; the impact compounds

Every contribution makes the system smarter:

- More spots = better hotspot maps, better infrastructure asks (e.g.
  "this fenceline catches wind-driven debris; place a windbreak").
- More verifications = better trust scores per worker.
- More cleanups = before-and-after evidence per block, per sponsor.
- More points-backed rewards from local businesses = more reasons to
  participate = more data.

The compounding is the moat.

---

## §B · METHODS (current) · How we deliver against the canon today

Everything in this section is **subject to change**. We update §B
freely as devices get better, as code matures, as we learn from the
street. The canon (§A) does not change; the implementation (§B) does.

### B.1 · Capture (current method, evolving)

**Today:**

- Three-tier capability detection at capture entry:
  - **PRO_SCANNER** (LiDAR-equipped iPhone Pro / iPad Pro): default to
    depth-aware `SpotScanCapture`. Footprint derived from scan extent.
  - **SCANNER** (ARKit-capable, no LiDAR): `SpotScanCapture` with
    AR-aware photo capture, no depth.
  - **BASIC** (older devices, Android, web): flat `CameraCapture` with
    GPS-tagged JPEG and a manual radius slider.
- Capture attestation embedded in every photo / scan: device model,
  OS version, session ID, capture index, `isLiveCapture: true`.
- GPS EXIF embedded into JPEGs at submit time (`embedGpsExif`); must
  match spot location within geofence radius (default 50m).
- LiDAR depth analysis (volume, area, max depth, confidence) recorded
  on supporting devices.
- On-device AI analysis pre-populates trash-type tags and suggested
  description before the user types anything.

**Where it is going:**

- Replace flat photo + pin entirely on PRO_SCANNER and SCANNER tiers.
- Capture depth point clouds and store them for later re-rendering.
- Use AR anchors so a returning verifier sees the original capture
  pose overlaid in their camera view.
- Pokémon-Go-style gameplay layer: discoverable, tappable, animated
  spot pins in AR.
- Open the capture stack to AR mesh on devices that support it
  (iPhone Pro, recent iPad Pro), without breaking BASIC tier.

### B.2 · Spot lifecycle (current method)

**Today:**

- Each spot has a `nextVerificationDue` (default `+24h` after
  creation/last verification).
- Lifecycle states: `pending` → `verified` → `acknowledged` → `resolved`.
  `rejected` is a side-state for invalid claims.
- The map info card surfaces lifecycle visually:
  - **Lifecycle Chip** — Fresh / Due Soon / Overdue with countdown.
  - **Progression Strip** — Reported → Confirmed → Cleaned → Bounty,
    with the bounty stage rendered as a masked "?" coin (the secret
    bounty UX) until cleanup completes.
- Duplicate detection: server rejects new reports within ~15 m of an
  existing pending/verified spot, unless the user provides an explicit
  `duplicateOverrideReason`.

**Where it is going:**

- Lifecycle clock keyed to local conditions (weather, season, urban
  density) instead of a flat 24h default.
- Heat-decay model: a spot that has been verified N times in the last
  M hours decays slower than a brand-new claim.
- Geofence-aware duplicate detection that respects street/alley
  topology — two pickups across a curb should never collide.

### B.3 · Block routes with fog-of-war (current method)

**Today:**

- A block route is a curated multi-checkpoint cleaning path inside a
  worker’s authorized service area.
- Authorization gate: `worker.verification.status == APPROVED` AND
  worker’s neighborhood matches the route’s community area. Else the
  user is sent to `VerificationGateScreen` with a contextual action
  label ("claim blocks", "claim this route").
- Fog-of-war reveal: only the next checkpoint is visible. Each
  before/clean-in-progress/after photo trio reveals the next.
- LiDAR depth badge ("3D SCAN") rendered when supported.

**Where it is going:**

- Authorization expressed as an explicit `authorizedZones[]` list on
  the Worker record (server schema migration), not inferred from
  `address.neighborhood`.
- Multi-worker collaborative routes (e.g. youth + elder pairing).
- Live-streamed checkpoint reveals to sponsors.

### B.4 · Picture verification (current method)

**Today:**

- Each block-route checkpoint requires a before / clean-in-progress /
  after triplet.
- Each photo carries a `CaptureAttestation`.
- LiDAR depth is captured opportunistically and used to estimate
  pre/post volume difference.

**Where it is going:**

- Replace photo triplet with **scan triplet** on capable devices
  (before-scan / mid-scan / after-scan). Volume delta becomes the
  authoritative measure of cleanup completeness.
- Server-side ML cross-checks: "does the after-scan actually contain
  less debris than the before-scan?"

### B.5 · Points system (current method)

| Action                                  | Points |
|-----------------------------------------|--------|
| Submit a spot report (observer reward)  | +10    |
| Verify someone else’s spot              | +5     |
| Complete a volunteer cleanup            | varies |
| Complete a checkpoint on a block route  | varies |
| Achievement unlocks                     | varies |

- Server writes both a `pointsLedger` row and a denormalized
  `workers.statsCommunityPoints` total.
- The success card on report submission renders the awarded amount
  inline via `SpotResponse.pointsAwarded`.

**Where it is going:**

- Point values weighted by capture quality (LiDAR scan > flat photo).
- Streak bonuses, neighborhood multipliers, sponsor-funded boosts.
- Real-world reward redemption (tickets, gift cards, restaurant
  credits) backed by local-business partners.

### B.6 · Bounties (current method)

- **Public** — bounty visible on the map with full amount.
- **Secret** — hidden as a "?" coin badge until cleanup completes.
- On spot submission, the server returns `matchingBounties` so the
  success card can surface them as accept-able offers.

**Where it is going:**

- Geofenced flash bounties tied to real-world events (post-festival
  cleanup, post-storm debris).
- Sponsor-issued bounty packs with branded wrappers.

### B.7 · Auth (current method)

- **Native Sign in with Apple** via `ASAuthorizationController` in
  `iosMain/SocialAuth.kt`. Delegate is pinned via a top-level holder
  to survive Apple’s weak-delegate API + Kotlin/Native GC.
- **Sign in with Google** via web OAuth: tap → Safari →
  `globalcommunitycleaners.org/auth/google?mobile=true` → universal
  link callback to `/auth/mobile-callback?token=…` →
  `LoginViewModel.handleGoogleAuthToken`.
- Email + password as fallback. Face ID re-login after first sign-in.

**Where it is going:**

- Passkeys.
- One-tap social-graph onboarding with neighborhood inference.

### B.8 · Articles (current method)

- API: `GET /api/articles` (list summaries), `GET /api/articles/[slug]`
  (full body with markdown).
- Backed by a hardcoded array in
  `site/src/app/api/articles/_articles.ts`.
- iOS reader renders a markdown subset client-side
  (`#`/`##`/`###`, `**bold**`, `_italic_`, `> quote`, `- bullet`,
  `[link](url)`). "Open on web" fallback for any unhandled formatting.
- Entry point: a 📰 *Articles* card on the FeedScreen.

**Where it is going:**

- Migrate to a real CMS or MDX collection on the marketing site, with
  the API route reading from there.
- Author bios, hero images, related-articles stitching.
- Comments / reactions tied to the worker identity.

### B.9 · Responsive layout (current method)

- Compose hosts the entire screen via SwiftUI’s
  `ignoresSafeArea(.all)`; Compose owns inset handling.
- Every overlay uses `statusBarsPadding()`; forms add `imePadding()`.
- `GccTextField` defaults to `imeAction = Done`, dismissing the
  keyboard via `LocalFocusManager.clearFocus()`.
- Tap-outside-an-input dismisses the keyboard everywhere.
- Spot description form scrolls vertically with `imePadding()` so the
  Submit button is always reachable on iPhone SE through Pro Max.

**Where it is going:**

- Adaptive layouts for iPad and landscape orientation.
- Dynamic Type respect end-to-end.

### B.10 · Map UX (current method)

- Heatmap (affected-blocks density polygons) at far zoom; individual
  spot pins at close zoom; crossfade in the 13–15 zoom band.
- Spot info card shows photo strip, lifecycle chip, progression strip,
  and verify / clean-up actions.
- Bottom-right "Spot" floating action button on the Map tab.

**Where it is going:**

- AR overlay mode where the camera shows nearby spots floating in
  physical space (Pokémon-Go style discovery).
- Heatmap that animates over time (yesterday → today → projected).

### B.11 · Glossary

| Term | Meaning |
|------|---------|
| **Spot** | A `CommunityReport` of type `sighting` |
| **Volunteer cleanup** | A `CommunityReport` of type `volunteer_cleanup` |
| **Block route** | A curated multi-checkpoint cleaning path |
| **Affected block** | A funded city block on the heatmap |
| **Observer reward** | Points granted for spotting/reporting |
| **Secret bounty** | Hidden bounty revealed at cleanup completion |
| **Service area** | The geographic region a worker is eligible for |
| **Capture attestation** | Live-capture proof bundle on every photo/scan |
| **Geoscan** | Capability-aware capture, defaulting to LiDAR/AR scan when supported |

---

## §C · How to evolve this document

When you ship something:

- If your work changes the **end state** we are aiming for — talk
  about it first. Then update §A.
- If your work changes **how we deliver** an existing canon item —
  update the relevant §B subsection. Keep the "where it is going"
  forward-looking notes honest.
- Date every meaningful update at the top of this file.
