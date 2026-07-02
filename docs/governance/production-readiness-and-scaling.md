# GCC — Production Readiness & Scaling Audit

**Audited:** 2026-07-02 · **Scope:** where compute actually happens today, what scales
automatically, what doesn't, and the concrete steps to provision Cloudflare R2 for the
scan-guidance/world-model work (`docs/superpowers/specs/2026-07-02-scan-guidance-world-model-and-rewards-design.md`).

---

## 1. Where compute actually happens today (the honest picture)

This is **not** an app where the DGX or any always-on server does the heavy lifting. Per
`docs/governance/00-INDEX.md`'s own audited truth: **production is 100% the Next.js app
on Vercel.** The Kotlin Ktor server (`server/`) is written but **not deployed anywhere** —
it's a parallel implementation nobody's running in prod. There is no "GCC backend server"
in the traditional sense that needs capacity planning the way a long-running service does.

| Workload | Where it runs today | Scales how |
|---|---|---|
| AR/LiDAR capture, on-device litter classification, fog-of-war/coverage tracking | **iPhone, on-device** (ARKit/CoreML via `ArScanner`/`CameraManager`/`OnDeviceAnalyzer`) | Scales with user count automatically — every phone brings its own compute, zero server cost |
| API requests (scans, jobs, points, map reads) | **Vercel serverless functions** (Next.js App Router, `iad1` region) | Auto-scales per-request; you pay per invocation/compute-time, not per idle server |
| Database (all reads/writes) | **Neon Postgres**, accessed via `@neondatabase/serverless` (`neon-http` driver) | See §2 — this is the first real bottleneck, but a manageable one |
| Payout processing | **Stripe Connect**, triggered by an admin clicking "approve" | See §4 — this is a **human**, not compute, bottleneck |
| 3D reconstruction / splat training (Phase 3, not yet built) | **Would run on this DGX Spark** — single NVIDIA GB10, no NVLink, no multi-GPU | See §3 — this is the one workload that does NOT auto-scale |
| Raw scan blob storage (Phase 2, not yet built) | **Would be Cloudflare R2** — see §5 for setup | Scales essentially infinitely; cost, not capacity, is the constraint |

**The one-sentence version:** today, nothing in GCC's architecture requires you to
provision more servers as usage grows — Vercel and Neon both auto-scale within Vercel's
serverless model. The scaling risk isn't "will the app fall over," it's three specific,
named things below.

---

## 2. Database: Neon Postgres — good default, one real limit

The app uses Neon's **HTTP driver** (`neon-http`, not a pooled TCP connection) — confirmed
in `site/src/db/index.ts`. This matters: the classic "serverless functions exhaust
Postgres's connection limit" failure mode (each of hundreds of concurrent Vercel function
invocations opening its own TCP connection) **doesn't apply here**, because every query is
a stateless HTTP call to Neon's data API, not a held connection. This was very likely the
right call already made, not something that needs fixing.

**What actually limits this at scale:**
- **Neon's plan tier** determines compute size (vCPU/RAM for the Postgres instance
  itself) and storage. A sudden 10x scan-volume spike would show up as slower query
  latency before it shows up as outright failure — Neon auto-scales compute within a
  plan's ceiling, but crossing that ceiling needs a plan upgrade, not a code change.
- **The `worldObservations`/`scanCoverageEvents`/`cellCoverageSummary` tables grow
  unboundedly** — they're append-only by design (§0.2 of the scan-guidance spec). At real
  scan volume, `worldObservations` (already 116 rows as of this session, from a handful of
  test scans) will be the fastest-growing table in the schema. **Action before a real
  volume uptick:** confirm the `(locationCellId, itemType, isCurrent)` and
  `(locationCellId, capturedAt)` indexes (already in place) are actually being used by
  Neon's query planner under real load — `EXPLAIN ANALYZE` the map's current-state query
  once there's real data volume, not synthetic.
- **`GET /api/map/coverage`'s current `LIMIT 5000` full-table scan** (flagged during this
  session's Phase 0 work) is the nearest concrete cliff — it becomes silently incomplete,
  not slow, past ~0.5 km² of cumulative scanned area. This needs the already-tracked
  `lat`/`lng` materialization fix before a real partner-funded scan campaign runs.
- **`drizzle-kit push`'s current introspection bug** (also flagged this session — it
  misreads the live schema as empty) needs resolving before the team can confidently make
  further schema changes at all, regardless of scale. This is a tooling correctness issue,
  not a capacity issue, but it blocks safely making the schema changes that scaling will
  require.

**Bottom line:** the DB won't be the first thing to break under growth, but it's the thing
that degrades gracefully (slower, not down) — worth real load-testing once partner-funded
volume is a realistic near-term scenario, not before.

---

## 3. The DGX Spark: the one workload that genuinely does NOT auto-scale

This is the important one to understand clearly, since it's the most unusual part of the
architecture relative to a normal "just deploy to more cloud instances" story.

**Hardware reality** (confirmed via direct `nvidia-smi`/`nvcc` inspection this session):
this machine has **one NVIDIA GB10 GPU, no NVLink, no peer GPUs, unified 128GB CPU/GPU
memory.** It is a single physical box. There is no "add a GPU" story here the way there is
with a cloud GPU fleet — this device's compute ceiling is fixed by its hardware, full stop.

**What this means for the splat-reconstruction pipeline (Phase 3, not yet built):** per the
design spec §2.1, DGX is explicitly scoped as the *processing* tier, not the durable store
— raw scans live in R2, DGX pulls batches down, trains splats, writes results back. That
architecture is correct and doesn't need to change. But it means:
- **Splat training throughput is capped by one GPU's compute, always.** If scan volume
  spikes (e.g., a partner funds a big community drive and 500 people scan the same
  neighborhood in a week), the reconstruction queue backs up — it doesn't fail, it just
  gets slower to produce a "canonical" reconstruction for that area. This is actually fine
  *by design* if you frame it correctly to users/partners: reconstruction was never meant
  to be real-time (Niantic's own VPS isn't either — it activates a location only after
  enough scans accumulate, per the research folded into the spec this session).
- **The real scale-up options, when this genuinely becomes a bottleneck**, in rough order
  of effort: (a) batch/queue more aggressively and process overnight rather than
  near-real-time — free, just a scheduling change; (b) burst overflow splat-training jobs
  to a cloud GPU instance (e.g., a rented A100/H100 on Lambda/RunPod/Modal) when the local
  queue backs up past a threshold — real but bounded cost, no new hardware to manage; (c)
  a second physical DGX-class box for true horizontal scale-out — the most expensive,
  slowest-to-provision option, and shouldn't be considered until (a) and (b) are
  demonstrably insufficient.
- **This is not a today problem.** Phase 3 isn't built yet, and the design spec already
  flags the whole pipeline as a research spike, not a production-ready system. Flagging
  this now so the scaling plan is understood *before* it's needed, not so it's acted on
  immediately.

---

## 4. Stripe payouts: the actual human bottleneck, not a compute one

This is worth naming explicitly because it's easy to miss when thinking about "scaling" as
a purely technical question. Per the same-day-payouts spec: **every payout is admin-gated
— there are no auto-payouts.** A human has to open the admin approval queue and click
"approve" for every single job, every single time.

If partner funding drives a real uptick in completed jobs, **the bottleneck is the number
of humans available to click "approve," not Vercel, not Neon, not the DGX.** This was a
deliberate, correct choice for the pilot stage (money safety > speed), but it's the first
thing that needs a real decision once volume is genuinely high: either scale the admin
team, or design a bounded auto-approval path (e.g., auto-approve below a dollar threshold
with photo-verification confidence above some bar, escalate everything else) — that's a
product/trust decision, not something to solve unilaterally in code.

---

## 5. Cloudflare R2 setup — what you need to do

This powers Phase 2 of the scan-guidance spec (canonical raw scan storage). R2 was chosen
over S3/Backblaze specifically for **zero egress fees** — material here because the DGX
will repeatedly re-pull raw scan blobs from storage for splat processing (§3), and
egress-metered storage would make that expensive at real scale. Steps require your
Cloudflare dashboard access — I can't provision this myself.

1. **Create the bucket** — Cloudflare dashboard → R2 → Create bucket. Suggest
   `gcc-scan-storage` (or `gcc-scan-storage-prod`/`-dev` if you want separate buckets per
   environment — recommended once this handles real user data, given this project
   currently has no separate dev database either, per its own documented gap).
2. **Create an API token scoped to R2 only** — R2 → Manage R2 API Tokens → Create API
   Token. Grant **Object Read & Write**, scoped to just the bucket you created (not
   account-wide). This gives you an Access Key ID + Secret Access Key (S3-compatible
   credentials — R2 speaks the S3 API).
3. **Note your account ID** — visible in the Cloudflare dashboard URL or under R2 →
   Overview. The R2 S3-compatible endpoint is
   `https://<account_id>.r2.cloudflarestorage.com`.
4. **CORS configuration** — since the mobile app will upload directly to R2 via presigned
   URLs (per spec §2.2, not proxied through the Vercel API), the bucket needs a CORS
   policy allowing `PUT` from the app's origin. R2 bucket → Settings → CORS Policy:
   ```json
   [
     {
       "AllowedOrigins": ["*"],
       "AllowedMethods": ["PUT", "GET"],
       "AllowedHeaders": ["*"],
       "MaxAgeSeconds": 3600
     }
   ]
   ```
   (`"*"` origin is fine here since uploads are authenticated via presigned URL, not
   cookie/CORS-based auth — tighten later if you want, not required for correctness.)
5. **Environment variables to add** (to `site/.env.local` for local dev, and Vercel's
   project environment variables for prod) — once you have the four values from steps
   2-3:
   ```
   R2_ACCOUNT_ID=<your account id>
   R2_ACCESS_KEY_ID=<from the API token>
   R2_SECRET_ACCESS_KEY=<from the API token>
   R2_BUCKET_NAME=gcc-scan-storage
   ```
6. **Once you've done the above and shared which env var names you actually used** (or
   just confirmed the ones above), I can implement Phase 2's ingestion path: the
   `POST /api/scans/[id]/upload-url` endpoint (issues presigned upload URLs using the AWS
   S3 SDK against R2's S3-compatible API — R2 needs no new SDK, `@aws-sdk/client-s3`
   works against it directly with the R2 endpoint), the DGX-side batch-pull worker, and
   the metadata linkage back to `scanCoverageEvents`. This is real, concrete implementation
   work I can do on this device once credentials exist — I just can't create the bucket or
   token myself.

**What NOT to do:** don't put R2 credentials in `site/.env.local` and commit them —
that file should already be gitignored (confirmed elsewhere in this session), but double
check before pasting real credentials into it.

---

## 6. Summary: what to actually watch, in priority order

1. **Stripe approval throughput** (§4) — the first real bottleneck if partner funding
   drives volume up. A people/process question, not an infra one.
2. **`GET /api/map/coverage`'s `LIMIT 5000` cliff** (§2) — already tracked, cheap fix,
   should land before real scan volume makes it a silent-correctness problem.
3. **`drizzle-kit push`'s introspection bug** (§2) — blocks safely evolving the schema at
   all; worth a dedicated fix session independent of any scaling trigger.
4. **DGX splat-processing capacity** (§3) — genuinely fixed-ceiling compute, but not a
   near-term concern since Phase 3 isn't built yet; the scale-up path (cloud GPU burst)
   is understood and cheap to execute when actually needed.
5. **Neon plan tier** (§2) — auto-scales gracefully within its ceiling; a plan upgrade is
   a dashboard click, not an architecture change, when/if query latency degrades under
   real load.

None of these are "the app will fall over" risks today — they're specific, named,
already-understood things to watch and act on in order, not a vague "scalability" worry.
