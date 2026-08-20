# GCC — Architecture Maps (Mermaid)

**Compiled:** 2026-08-19 · **Scope:** the whole GCC ecosystem as built — app, site, data, scan→recon→world-twin pipeline, release machinery, and the CDX platform lanes that feed it.
**Companion to:** doc #1 (production topology truth). This doc is the **visual layer** — read #1 for the canonical Next.js-vs-Ktor ruling.

## 1. System context — what runs and talks to what

```mermaid
flowchart LR
    subgraph Clients
        IOS[iOS app<br/>Compose MP + Swift AR<br/>v1.0.8 build 181]
        AND[Android app<br/>Compose MP]
        WEB[Website<br/>globalcommunitycleaners.org]
    end

    subgraph Vercel["Vercel — site/ (Next.js 16, THE production backend)"]
        API["~115 /api/* routes<br/>mobile API + web"]
        CRON["/api/cron/*<br/>route releases"]
    end

    NEON[(Neon Postgres<br/>Drizzle schema = truth)]
    STRIPE[Stripe<br/>checkout + payouts]
    R2[(Cloudflare R2<br/>gcc-scan-storage)]
    KT[Ktor server/<br/>NOT deployed — branch only]

    subgraph DGX["DGX Spark (GB10) — compute lane"]
        RECON[recon_worker<br/>fuse_cell · VidMap · WorldMirror]
        TRAIN[litter_model<br/>train + CoreML export]
    end

    TWIN[chicago-twin<br/>world twin + tiles]
    GAME[GGC game<br/>ggc-v2 R3F]

    IOS & AND -->|https api.*| API
    WEB --> API
    API --> NEON
    API --> STRIPE
    Render[Render cron<br/>15min tick] --> CRON --> NEON
    KT -.->|parity optional| NEON
    IOS -->|scan chunks<br/>background upload| R2
    R2 --> RECON
    NEON -->|scan_sessions index| RECON
    RECON --> R2
    RECON --> TWIN
    TWIN --> GAME
    TRAIN -->|LitterCropClassifier.mlpackage| IOS
    API --> NEON
```

**Truths to hold onto:** the mobile API **is** the Next.js app (Ktor is not deployed); Drizzle migrations own the schema; R2 raw scans are never deleted; recon outputs are versioned per cell with provenance.

## 2. Scan → reconstruction → world twin lifecycle

```mermaid
flowchart LR
    subgraph Phone["iPhone capture"]
        AR[ARSessionRecorder<br/>HEVC chunks + ARKit poses<br/>LiDAR depth + confidence]
        GPS[1Hz GPS + labels<br/>tap/draw litter marks]
    end

    subgraph R2L["R2 gcc-scan-storage"]
        RAW["raw/{cell}/…<br/>158 sessions · 26 recon-viable"]
        REC["reconstructed/{cell}/{v}"]
    end

    subgraph Pipe["recon pipeline (DGX)"]
        IDX[scan index<br/>backend/deploy<br/>R2→Neon scan_sessions]
        JOB[reconstruction_jobs<br/>SKIP LOCKED lease]
        FUSE[fuse_cell.py<br/>ARKit poses + LiDAR fusion<br/>CANONICAL — metric 0.973 vs GPS]
        VID[VidMap runner<br/>external footage ONLY<br/>NO-GO on ARKit footage 2026-08-19]
        WM[WorldMirror A/B<br/>gate satisfied by ARKit poses]
    end

    OUT[chicago-twin export<br/>tiles · prior_camera · props]
    BAKES[GGC game bakes<br/>trash/hazard snapshots · trees.json]

    AR --> RAW
    GPS --> RAW
    RAW --> IDX --> JOB --> FUSE --> REC
    VID -.-> REC
    REC --> WM
    REC --> OUT --> BAKES
```

**2026-08-19 evidence:** ARKit sidecar poses fit GPS at scale 0.973 / 3.4 m median residual — ARKit + LiDAR fusion (`fuse_cell.py`) is the canonical geometry path; VidMap diverged ~30 m on the same frames and is scoped to non-ARKit footage (Ray-Ban, third-party video). `build_ios_bundle.py` (data/vidmap-p0) converts any R2 scan into a runner bundle in one command.

## 3. Litter identification flywheel

```mermaid
flowchart TD
    CAP[Scan sessions<br/>image boxes + label provenance<br/>user_chip / user_text = gold]
    TACO[TACO dataset<br/>cold-start crops · CC BY 4.0<br/>pretrain only — mixed image licenses]
    DS[build_litter_dataset.py<br/>field crops from R2]
    MODEL[train_litter_classifier.py<br/>ultralytics crop classifier<br/>YOLO26-n candidate · RF-DETR = Apache-2.0 fallback]
    COREML[export_coreml.py<br/>LitterCropClassifier.mlpackage]
    APP[iOS app<br/>LitterCropClassifier.swift<br/>+ confidence-ranked suggestion chips]
    SVR[server-side pass planned<br/>Grounding DINO 1.0 / LocateAnything-3B<br/>+ SAM3 cross-frame dedupe]

    CAP --> DS --> MODEL --> COREML --> APP
    TACO --> MODEL
    APP --> CAP
    RAW2[R2 frames] --> SVR --> DS
```

Every field confirmation is a training sample; the model shrinks manual labeling each cycle. On-device detector license ruling (2026-08-19): Ultralytics is AGPL — **RF-DETR (Apache-2.0) is the long-term pick** for the closed-source app; YOLO26 acceptable only if AGPL terms are embraced.

## 4. Build & release topology

```mermaid
flowchart LR
    subgraph DGX2["DGX Spark (this box)"]
        DEV[primary dev<br/>composeApp · site · recon]
        AG[agent lanes<br/>U1-U4 UX · classifier · spikes]
    end
    GH[GitHub<br/>BlakeB254/gcc<br/>main = authoritative]
    MB[MacBook Air cdx-macbook<br/>~/CDX-DEV-PROJECTS/gcc-kmp<br/>Xcode 26.6 + fastlane]
    TF[TestFlight<br/>external Beta review]
    STORE[App Store<br/>manual release]
    VERCEL[Vercel auto-deploy<br/>site/ from main]

    DEV --> AG --> DEV
    DEV -->|cdx-mobile sync| GH --> MB
    MB -->|fastlane distribute| TF -->|fastlane submit| STORE
    GH --> VERCEL
```

`cdx-mobile` (sync/build/fetch) is the remote orchestrator; the release runbook is `docs/release/appstore-distribution.md`. v1.0.9 contents: scan-flow fixes F1–F9, intro carousel, first-block tutorial, splash v2 (video shell + Imagine asset), litter classifier bundled.

## 5. CDX platform lanes feeding GCC

```mermaid
flowchart TD
    KB[KB: external-tech-research<br/>VidMap dossier etc. — service degraded 2026-08-19]
    HERMES[Hermes kanban]
    G[graphic lane<br/>grok-imagine image/video]
    RES[researcher lane<br/>X research]
    ART[~/cdx/dev-projects/gcc/assets/store-art/<br/>splash video + preview-card backgrounds]

    HERMES --> G --> ART
    HERMES --> RES --> KB
    G -->|t_98d25ee1 splash video| ART
    G -->|t_a6456763 card backgrounds| ART
    ART --> IOS2[iOS v1.0.9 splash + intro slides]
    ART --> CARDS[App Store preview cards<br/>6 slides · one CTA per offering]
    BOOK[X bookmarks scrape<br/>out/research/x-bookmarks/2026-08-19<br/>295 items → §6b of pipeline plan]
    BOOK --> PLANS[gcc/docs/plans/2026-08-19-*]
```

## 6. Where the plans live

| Doc | Covers |
|---|---|
| `docs/plans/2026-08-19-scan-to-world-pipeline-and-ios-release-plan.md` | End-to-end scan→twin pipeline, trash-ID stack, VidMap verdict, iOS v1.0.9 release plan, bookmark integrations (§6b), work order |
| `docs/plans/2026-08-19-ios-ux-onboarding-splash-preview-cards.md` | UX inspection findings, flow fixes F1–F9, intro carousel, first-block tutorial, splash v2, store preview cards |
| `chicago-twin/docs/vidmap-p0-findings-2026-08-19.md` (+ addendum) | All three VidMap spikes, metrics tables, ARKit tiebreak |
| `gcc/data/vidmap-p0/` | `build_ios_bundle.py`, `diagnose_bundle.py`, bundles, `.venv` |
