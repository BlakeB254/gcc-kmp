# GCC backend — scan control-plane (Kotlin/Ktor)

The first brick of GCC's unified Kotlin backend. It is a small, always-on Ktor
service that does the **stateful, long-running work Next.js/Vercel can't** — and
it grows over time to absorb more of the API. It reuses `:shared` for DTOs, so
mobile, web, and this backend all speak the same types.

**This is a new, clean module — not a revival of the diverged `server/`.**
Drizzle (`site/src/db/schema.ts`) remains the authoritative schema owner; this
service only reads/writes those tables (plain JDBC, no Exposed mirror).

## What it does today

**Scan indexing.** Capture blobs (video/depth/pose/USDZ/frames/manifest) upload
to R2 (`gcc-scan-storage`) but had no database index — they were discoverable
only by prefix scan. This service walks R2 and mirrors every session into Neon:

| R2 | Neon |
|----|------|
| `raw/{cell}/…/*manifest.json` | one `scan_sessions` row (parsed from the `EnvScanManifest` itself) |
| every artifact the manifest names | a `capture_chunks` row (idempotent on `r2_key`) |
| `reconstructed/{cell}/{v}/…` | a `reconstruction_assets` row |

It runs on a schedule (`INDEX_INTERVAL_MINUTES`, default 30) and on demand via
`POST /admin/index-scans`. Fully idempotent.

## Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/healthz` | none | liveness (no DB/R2 touch) |
| GET | `/` | none | banner |
| POST | `/admin/index-scans` | `Authorization: Bearer $INDEX_SECRET` | run one sweep now, returns counts |

## Configuration (env vars)

See `deploy/.env.example`. Required: `DATABASE_URL`. R2 vars enable indexing;
without them the service runs but the indexer stays idle. `INDEX_SECRET` guards
the manual trigger (fail-closed if unset). `PORT` defaults to 8080.

## Run on the DGX (recommended host, ~$0)

```bash
cd backend/deploy
cp .env.example .env      # fill in DATABASE_URL, R2_*, INDEX_SECRET
docker compose up -d --build
curl localhost:8080/healthz            # -> ok
curl -X POST localhost:8080/admin/index-scans -H "Authorization: Bearer $INDEX_SECRET"
```

### Expose it publicly with a Cloudflare Tunnel (no open ports)

The DGX sits behind NAT, so front the service with a Cloudflare Tunnel instead
of port-forwarding:

1. Cloudflare dashboard → Zero Trust → Networks → Tunnels → create a tunnel.
2. Add a public hostname (e.g. `backend.globalcommunitycleaners.org`) → service
   `http://localhost:8080` (or `http://gcc-backend:8080` if you run the
   `cloudflared` sidecar in `docker-compose.yml`).
3. Put the tunnel token in `.env` as `CLOUDFLARE_TUNNEL_TOKEN` and uncomment the
   `cloudflared` service, or run `cloudflared` directly on the DGX.

## Local dev

```bash
DATABASE_URL=... R2_ACCOUNT_ID=... R2_ACCESS_KEY_ID=... R2_SECRET_ACCESS_KEY=... \
  R2_BUCKET_NAME=gcc-scan-storage INDEX_SECRET=dev \
  ./gradlew :backend:run
```

## R2 lifecycle (storage hygiene)

`ops/apply-r2-lifecycle.mjs` applies safe, non-destructive rules (abort stale
multipart uploads after 7d; transition `raw/` to Infrequent Access after 30d).
It deliberately does **not** delete raw scans — reconstruction is still manual,
so a time-based delete would drop un-processed data. The real "delete raw N days
after a reconstruction exists" rule is Phase 3, driven by the index.

## Roadmap

- **Phase 3 (queue SHIPPED 2026-07-10):** every index sweep now enqueues a
  `reconstruction_jobs` row per completed, unreconstructed session with a
  full capture set (migration 0034). The DGX worker
  (`scripts/reconstruction/recon_worker.py`) leases with `SELECT … FOR
  UPDATE SKIP LOCKED`, fuses the job's cell, writes `reconstructed/…`,
  records `reconstruction_assets` + `scan_sessions.reconstruction_version`.
  See `scripts/reconstruction/README.md` §2026-07-10 for the real-data
  prototype results and the outstanding R2 write-token blocker.
- **Later:** migrate Next.js API endpoints here incrementally until Ktor is the
  primary backend and Next.js is the website frontend.
