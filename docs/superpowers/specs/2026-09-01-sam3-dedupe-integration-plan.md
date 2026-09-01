# SAM3 track dedupe — integration plan (work order #10)

**Goal:** one piece of litter seen in N frames of a scan chunk becomes ONE
observation in the twin, not N. Today `POST /api/observations/ar-detected`
(`site/src/app/api/observations/ar-detected/route.ts`) inserts every valid
marker as a new immutable `world_observations` row
(`site/src/lib/world-observations.ts` — "every insert is a new immutable
row"); nothing collapses repeat sightings of the same object across frames of
one walk.

## Spike result

- Runner: `~/cdx-platform/scripts/sam3_track.py` (408 lines, fail-closed:
  refuses public-deploy env flags, never downloads `facebook/sam3` weights,
  forces `HF_HUB_OFFLINE` for real runs).
- `--dry-run` against a real scrubbed GCC chunk
  (`scrubbed/464635:-721811/env-1785368290677-binw7a/capture/chunk-0.mov`):
  **exit 0**, correct schema
  (`{prompt, fps: 60.0, frames: [], license: "sam-internal", dry_run: true}`),
  fps probed from the chunk via ffprobe.
- Real inference env is **not functional** on this box: no
  `SAM3_CHECKPOINT`/`~/models/sam3/sam3.pt`, no `~/opensource/sam3` clone,
  no torch importable from system python. Per the runner's own contract the
  weights must be placed locally by hand — that is a Blake action, not an
  agent one. No GPU run was attempted.

## Where the hook goes

The dedupe is a **server-side post-pass in `recon_worker.py`**, per cell,
after the scrub gate admits the cell (the tracker reads scrubbed renditions
only — same privacy lane as every other server-side frame consumer):

```
recon_worker claim → scrub gate → fuse_cell (geometry)
                                → observation_dedupe (NEW, this plan)
                                → complete_cell / twin writes
```

Concretely a new `scripts/reconstruction/observation_dedupe.py`:

1. **Inputs per cell:**
   - scrubbed chunk .mov keys for the cell's accepted sessions (same
     enumeration `unscrubbed_video_chunks()` already does, inverted);
   - the cell's `world_observations` rows with `sourceType='ar_auto_detect'`
     and `capturedAt` inside each session's time window (Neon,
     `DATABASE_URL` recon_worker already holds);
   - distinct `itemType` labels among those rows → the SAM3 concept prompts
     (one tracker pass per distinct label, e.g. "plastic bottle", "litter").
2. **Track:** `sam3_track.py --video <chunk> --prompt <label> --out tracks.json`
   per (chunk, label). GPU-serial: wrap the batch in
   `gpu_admission.GpuReservation(...).acquire("sam3-dedupe")` exactly like
   `train_gsplat_for_cell` does — one chunk at a time, never beside a splat
   train.
3. **Associate marker → track:** a marker joins a track when its
   `capturedAt` falls inside the track's `[first t, last t]` (chunk start
   epoch + `t`) and its label matches the prompt. Where the chunk has ARKit
   poses + depth (it does, iOS lane), project the track's box center to a
   world point and require the marker's lat/lng within a small gate (start
   at 2 m — GPS fixes converge to 3–8 m, ARKit-projected points are better).
4. **Collapse:** per track keep ONE canonical observation (highest score;
   tie → earliest). The rows are immutable by design, so do NOT mutate:
   insert one new consolidated row (`sourceType='ar_track_dedupe'`,
   `attributes: {track_id, merged_observation_ids, model: "sam3",
   license: "sam-internal"}`) and supersede the members through the existing
   isCurrent mechanics in `recordWorldObservation` — the same pattern cell
   supersede already uses. Twin/heatmap readers keep reading current rows
   and automatically stop double-counting.
5. **Artifact:** write `tracks.json` per chunk to
   `reconstructed/{cell}/{v}/tracks/` (R2) for audit and for the future
   litter-delta layer (work order #9 wants exactly these track positions).

## License boundary

SAM3 weights/outputs are **internal-only** (the runner enforces it via
`PUBLIC_ENV_KEYS` fail-closed). The consolidated `world_observations` rows
are *derived data* (positions + labels we already had, minus duplicates) —
they stay publishable; only the model artifacts and `tracks.json` must never
ship to a client bundle. Keeping `license: "sam-internal"` inside
`attributes` preserves the provenance trail.

## Effort

~2-3 days once weights exist: dedupe module + Neon queries ~1 day, marker
association + tests ~1 day, recon_worker wiring + GpuReservation + R2
artifact ~half a day. The tracker venv should follow the gsplat pattern
(`GSPLAT_PYTHON`-style pinned venv, `sam3_train/setup.sh` equivalent) rather
than polluting system python.

## Go/no-go

**GO on the design** — the runner is sound, the dry-run passes on real GCC
footage, the hook point and data contract are clean, and the immutable-row
supersede pattern already exists to receive it.

**Blocked on exactly one thing:** local SAM3 checkpoint provisioning
(`~/models/sam3/sam3.pt` or `SAM3_CHECKPOINT`), which the runner deliberately
refuses to automate. Decision + download is Blake's; everything else can be
built and tested behind `--dry-run` before the weights land.
