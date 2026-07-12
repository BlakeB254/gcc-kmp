# PRD — Scan Session Flight Recorder & DevOps Review

**Status:** PLANNED — direction approved by Blake 2026-07-12 ("we should be
sending the recordings of the scan sessions on completion to the backend …
a devops system of reviewing past scans for efficacy … vs me having to
manually screen record"). This doc is the plan of record; phases below are
sequenced for independent delivery.

**Goal:** every scan session self-reports enough on completion that the
experience can be reviewed remotely — what the worker saw, what the system
did, and how well it worked — replacing manual screen recordings as the
field-feedback channel.

---

## 0. What already exists (build ≥150) — the recorder is half-built

| Already uploaded on completion | Where |
|---|---|
| Camera video/depth/pose capture chunks (cellular by default since 150) | R2 via presigned PUT |
| Session manifest: GPS path, gate events (incl. purges/overrides), geo-ref clock pairings, observed items w/ provenance, scanned cells, diagnostics-log tail | R2 manifest |
| Session index (counts, durations, R2 keys) | Neon `scan_sessions` / `capture_chunks` via `backend/` indexer |
| Crashes | `crash_reports`; MetricKit crash/hang JSONs (on-device only today) |

The 2026-07-11 debugging session proved the model: the on-screen diagnostics
in Blake's screen recording contained the exact root causes (gate flapping,
R2 401). The flight recorder makes that data arrive without the video.

## Phase 1 — Session event stream (telemetry replay, no video) [FIRST]

The highest-value gap: today's manifest carries a *tail* of diagnostics and
coarse events. Phase 1 upgrades this to a complete, structured event stream:

- **`SessionEventLog`** (client): append-only JSONL, one entry per UX/system
  event with the session-relative timestamp: caption/instruction shown, tap
  → raycast result → pill phase transitions, confirm/reject, auto-detection
  (label+confidence+distance), gate state changes + causes, coverage
  milestones (25/50/75/95%), lap transitions, upload attempts/results,
  errors. Reuses `EnvScanSessionRecorder`'s journal plumbing (it already
  does JSONL + async appends); target <200KB/session gzipped.
- Uploaded ALWAYS on completion (and salvaged by orphan recovery on crash),
  as its own R2 artifact `…/events.jsonl.gz`, referenced from the manifest.
- **MetricKit diagnostics upload**: the crash/hang JSONs currently stranded
  in the Files app upload on next launch (small, invaluable).
- Backend: indexer registers the artifact; `scan_sessions` gains
  `event_log_r2_key` + headline metrics extracted at index time (see §3).

## Phase 2 — Review surface (admin web "Sessions")

A new admin-panel section on the site (admin auth, reuses the existing
map/table components):

- **Session list**: filterable by date/build/device/worker/route; headline
  metrics + red flags (crashed, gate-flapped, zero uploads, 401s).
- **Session replay page**: map pane (GPS trail + coverage blob growing over
  a scrubber timeline, litter events dropping pins) synchronized with the
  event stream rendered as a timeline/console. This is "watch the session"
  without video — enough for 90% of triage.
- **Video pane (2b)**: the capture chunks are the camera view; the geo-ref
  events already pair the AR clock to wall time, so chunk video can be
  scrubbed in sync with the event timeline. Presigned GET playback from R2.
- Explicitly NOT on-device screen recording (ReplayKit): battery/size/
  privacy cost is high and the event stream + camera chunks reconstruct the
  experience. Revisit only if event replay proves insufficient.

## Phase 3 — Efficacy metrics, aggregates, alerts (the devops loop)

- Per-session score computed at index time: coverage fill + quality tiers,
  items auto-detected vs tap-confirmed vs rejected (detector precision
  proxy), taps that raycast-missed, gate flip/override counts, upload
  success rate, session completion vs abandonment, crash/hang linkage.
- **Per-build aggregates**: the devops question is "how is build N faring
  in the field" — daily rollup per `CFBundleVersion` with deltas vs the
  prior build; regression flags (e.g., gate flaps/session ↑, confirm rate ↓).
- Surfaced on the admin dashboard + a scheduled digest (existing cron +
  notifyAdmins seam). Alert thresholds start manual, tuned from real data.

## Decisions taken (defaults — veto anytime)

- Telemetry-replay-first; no ReplayKit screen capture in v1.
- Event log always uploads (small); video chunks keep the policy toggle
  (now cellular-default).
- Retention: raw chunks per existing recon lifecycle; event logs + metrics
  indefinitely (tiny).
- Review UI lives in the existing site admin panel, not a new service.

## Decisions from Blake (2026-07-12) — binding

1. **Admin-only.** Session replay and the review surface are admin-only;
   workers never see raw session recordings.
2. **Privacy scrubbing is a requirement, not an option**: bystanders AND
   vehicles (cars/license plates, roadway scans) should be scrubbed/blurred
   from scan imagery where possible. Implementation home: the DGX ingest
   side (detection + blur pass over capture chunks before they feed
   reconstruction or the review UI); the review UI must prefer scrubbed
   renditions when they exist. Until the scrub pass exists, admin-only
   access is the mitigation.
3. Everything else: maintainer's best judgment (digest default: admin
   dashboard card + existing notifyAdmins push, weekly).
