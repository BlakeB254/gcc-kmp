# Environment Scan Reliability, Reconstruction Ingest, and Living Map

**Date:** 2026-07-09

**Status:** Product behavior approved; implementation awaits specification review

**Scope:** iOS environment scan, direct-to-R2 capture delivery, Next.js control plane, DGX Spark reconstruction, mobile/web map presentation, and the scanner's marketing storyboard

## Executive decision

The environment scanner is one product with four independently observable outcomes:

1. the user can see where the phone is facing and what ground has been captured;
2. tapping visible litter always creates a visible, durable candidate instead of silently failing;
3. every privacy-approved color, pose, calibration, LiDAR, location, and detection artifact reaches a backend-owned session inventory before local deletion; and
4. a reconstruction worker turns those artifacts into versioned geometry and litter layers that the map renders differently at close, neighborhood, and city zooms.

The current Next.js/Vercel application remains the control plane. Large capture bytes continue to travel directly from the phone to Cloudflare R2. The dormant Ktor server is not deployed for this work: it is not part of the Gradle build, its authentication and DTOs have diverged, and it has no capture, R2, or reconstruction-job implementation. If an additional paid host is needed, the useful purchase is an x86 reconstruction/registration worker or durable job runner, not a second application API.

The app must never use “Scan complete” to imply that reconstruction data reached the backend. Report creation, local scan preservation, artifact upload, backend acceptance, reconstruction, and map publication are separate states.

## Goals

- Make the minimap readable in bright outdoor conditions and restore a persistent representation of phone heading.
- Make scan coverage visually distinguish “in view,” “captured,” and “captured closely at good quality.”
- Make a user's litter tap authoritative as an observation request, even when the classifier cannot name the object.
- Prevent attached vehicle tires and other contextually normal objects from becoming litter automatically.
- Preserve all useful photogrammetry and LiDAR inputs across offline use, process termination, expired upload tickets, and transient storage failures.
- Give the backend a canonical, queryable inventory of every session artifact and its verification state.
- Give DGX Spark one documented decoder boundary for the format the iPhone actually records.
- Publish versioned reconstruction outputs and semantic litter overlays to the map with zoom-appropriate levels of detail.
- Keep indoor/private capture out of R2, including the gate's trailing uncertainty window.
- Produce a visual language that can also drive onboarding and marketing artwork.

## Non-goals

- Reconstructing a full environment on the phone.
- Proxying video or depth files through Vercel Functions or the Ktor server.
- Claiming DGX reconstruction is production-ready before a real captured session passes decode, registration, fusion, publication, and map-render tests.
- Treating whole-frame image classification as a bounding-box litter detector.
- Baking red litter coloring permanently into the base mesh; semantic truth must remain independently correctable and versioned.
- Replacing MapLibre or the existing coverage ledger.
- Android raw reconstruction capture in this increment. Shared models and map behavior should remain portable, but real-device release verification is iOS-first.

## Confirmed field evidence and code findings

| Finding | Evidence | Product effect | Required response |
|---|---|---|---|
| The heading cone was intentionally retired in favor of a scene beam | `CameraCapture.kt` passes null location/heading and commit `9467021d` says the beam supersedes the cone | The beam disappears when fewer than three ray hits exist, leaving no facing direction | Restore a persistent 55° cone and render the irregular beam inside it |
| The minimap applies a full-canvas slate veil and projects its overlay separately from MapLibre | `ScanMinimapOverlay.kt` draws the veil and uses a manual center-relative Mercator projection | Outdoor detail is dim and overlay/basemap state can diverge | Use a daylight style and one authoritative camera/projection snapshot |
| The neon green bar is camera-attached geometry | `GccArNative.swift` creates an elongated emissive box in front of the camera | It reads as a broken arrow and intersects the controls | Delete it; directional guidance belongs in the cone, minimap, and text prompt |
| The ground grid is emissive and is not properly depth-occluded | RealityKit and SceneKit paths use bright emission; the SceneKit material does not write depth | Grid lines cover vegetation, fences, and objects | Use subtle ground-only lines with occlusion and no emission |
| A camera tap silently performs a focus raycast and captures a generic frame | `CameraCapture.kt` calls `focusAt` and frame capture; `ArEnvScan.kt` only biases saliency | Users receive no positive or negative tap result | Add a tap state machine, ROI analysis, a durable candidate, and explicit feedback |
| The analyzer can take its first image after 2.5 seconds without a user tap | `SpotScanCapture.kt` auto-captures the first eligible frame | The single reported image may depict a car or foliage | Label automatic evidence separately and do not file a litter report without confirmation |
| Whole-frame classifier labels such as `tire` pass at low confidence | `TrashDetectionRules.kt` accepts several broad labels at 0.35 | A tire attached to a car was reported as trash | Require localized object evidence and negative parent context before auto-confirmation |
| Session summary item count includes non-litter world features | `SpotScreen.kt` uses all `observedItems` | “Items tagged” can mean plants or construction features | Show separate “Litter identified” and “Features mapped” counts |
| Report-photo upload can reuse a Vercel Blob pathname | `/api/spots/upload` uses millisecond timestamp plus form key | Production returned HTTP 500 “This blob already exists” | Use a collision-proof object name and idempotent retry behavior |
| R2 capture upload failed on-device with `NSURLErrorDomain -1200` | Field diagnostics show a virtual-hosted R2 presigned URL | Sidecar remained pending and the user saw a storage failure | Standardize/test the presign host shape on a real iPhone and keep retryable bytes |
| The native recorder emits MOV + JSONL + LZFSE depth | `ArSessionRecorder.swift` | Rich data already exists locally | Preserve and formally version this format |
| LiDAR configuration requests scene depth but not ARKit scene reconstruction | `GccArNative.swift`, `ArEnvScan.kt`, and `ArScanner.kt` set depth frame semantics without `sceneReconstruction` | Depth frames are captured, but ARKit mesh anchors/classification are not preserved | Feature-gate `.meshWithClassification` on supported devices and record mesh deltas when the performance gate passes |
| The current DGX reader expects discrete JPEG + 16-bit PNG frames | `scripts/reconstruction/frame_io.py` | DGX cannot consume the background recorder's primary format | Add a canonical chunk decoder and normalize to an internal frame model |
| The manifest lists only chunks uploaded before it was created | `SpotViewModel.uploadEnvScanSession` snapshots `r2Key` values | Late chunks can exist in R2 without being discoverable from the manifest | Make the backend artifact table authoritative and finalize only after reconciliation |
| Orphan recovery skips a capture directory when any row for the session already exists | `bookOrphanCaptureSessions` checks `existing.isNotEmpty()` at session level | A partially booked/crashed session can leave additional files forever unregistered | Reconcile each `(sessionId, chunkIndex, kind)` file independently and idempotently |
| DGX discovery lists only `*-manifest.json` objects | `scripts/reconstruction/r2_store.py` | Unreferenced late artifacts remain stranded | Claim work from backend session/job records, not R2 filename discovery alone |
| Core Open3D 0.18 operations segfault on DGX Spark | `scripts/reconstruction/README.md` documents repros for ICP, KD-tree, and RGBD point clouds | The current pipeline cannot run end-to-end on DGX Spark | Use a verified x86 registration stage initially or replace/build the failing dependency |
| Reconstruction asset storage exists without an ingestion writer or map reader | `reconstructionAssets` schema exists; no publication API or rendering query exists | Successful output cannot become an active map version | Add authenticated publication and map-read contracts |
| Live map zoom is collected but not consumed by health-layer aggregation | `MapFullScreen.kt` writes `currentMapZoom` only from `onZoomChanged` | Pinch zoom can leave the wrong cell aggregation/LOD | Drive health aggregation and layer visibility from the live camera zoom |

## Target user experience

### Scanner launch and safe-area layout

The scanner opens with one short, persistent instruction below the safe area:

> Tap visible litter to identify it. Walk closer for a better scan.

The instruction collapses after the first successful tap or after eight seconds, then remains available through a small help control. Status pills live in one safe-area-aware horizontal rail below the Dynamic Island/status bar. The rail may wrap to two rows on compact devices; no pill may be clipped by the top inset.

The top rail contains only actionable state:

- recording state: `REC`, `Saved locally`, `Uploading`, or `Upload paused`;
- scan quality: `Good`, `Move slowly`, `Move closer`, or `Tracking limited`;
- device capability: `LiDAR 3D` when applicable.

Diagnostics are hidden in production unless a deliberate debug gesture or internal build enables them. Presigned query strings and credentials are always redacted from logs.

### Camera view

The AR view contains four visual systems:

1. a subtle, stable, ground-occluded grid;
2. litter candidates and confirmed-item reticles;
3. short contextual prompts;
4. the completion control and readable minimap.

The unexplained green camera-attached bar is removed. The grid uses a one-meter minor interval and a three-meter major interval aligned with the same geospatial phase used by minimap coverage cells. It is non-emissive, fades with distance, writes/obeys depth, and does not draw over plants, fences, cars, or other foreground geometry. Grid placement stays visually stable during a session even if GPS refinement adjusts the geospatial origin.

### Tap-to-identify contract

A tap is never silent and never discarded because classification confidence is low.

The interaction states are:

1. **Tap received:** an orange pulse appears at the tap within 100 ms and haptic feedback confirms input.
2. **Locating:** the app raycasts the point and shows a dashed orange target ring. If no stable raycast exists, it asks the user to hold steady and try the same target again.
3. **Checking:** the app analyzes a crop centered on the tap, preserving surrounding context. The reticle displays `Checking…`.
4. **Candidate created:** a local observation is persisted with tap coordinates, raycast/world position, crop, timestamp, pose, and provisional label. This happens even if the model returns `unknown`.
5. **Guidance:** the app says `Move closer to identify` for uncertain/distant candidates or `Litter identified` for localized, context-safe detections.
6. **Confirmation:** an uncertain candidate asks `Is this litter?` with yes/no controls. “Yes” is a user-confirmed litter observation; “No” is a negative training example and removes litter styling.

Automatic detections use the same candidate pipeline but cannot silently submit a report. High-confidence automatic promotion requires a localized mask or bounding box, sufficient size, temporal persistence, and a context check. A `tire` prediction is suppressed when its region overlaps or is structurally attached to a car, truck, wheel well, bicycle in use, or other non-discarded parent object. The user can still explicitly mark a detached tire as litter.

Reticle language is concise:

- dashed orange: `Checking…`;
- orange: `Move closer to identify`;
- red outline with a check: `Litter identified`;
- gray outline: `Feature mapped`;
- crossed/soft gray: `Not litter`.

The app records whether an observation was automatic, user-tapped, user-confirmed, model-confirmed, or rejected. Those labels travel with reconstruction data and form a valuable supervised dataset for later litter-model training.

### Minimap

The minimap is a legible scanner instrument, not a dimmed miniature of the main map.

- The basemap uses a daylight treatment: reduced saturation, modest contrast, and no opaque fog veil. Streets, building edges, and labels remain readable through the overlay.
- The user's position is a high-contrast white center puck with a dark border and a small heading tick.
- A persistent 55° heading cone rotates continuously with the phone. Its outer radius is the maximum `SEEN` distance, currently 55 meters, and remains visible even when AR raycasts return no polygon.
- A brighter, irregular live visibility beam sits inside the cone. Its edge follows the current raycast fan's measured reach and may shorten around nearby ground/walls. The beam is live sensing, not credited coverage.
- Accepted coverage remains behind the user as merged organic paint: light for `SEEN`, medium for `GRAZED`, and deep for `CLOSE` with good scan quality.
- A close tap can immediately credit the target cell at the appropriate provisional tier, but confirmed quality controls the final deep shade.
- The tapped target uses an orange ring on both camera and minimap, tied to the same world position.
- The minimap automatically chooses a camera radius that contains the user, cone, target, and recent coverage; it is not locked to a zoom that crops the 55-meter scan range.
- Map camera and Compose overlay use one authoritative projection snapshot per frame so the puck, cone, beam, cells, and basemap cannot drift independently.

The cone answers “where am I facing?” The live beam answers “what can the sensors see right now?” Coverage paint answers “what has already been accepted?” These must remain visually distinct.

### Completion and upload status

Pressing `Complete Scan` closes active capture after local artifact finalization; it does not wait indefinitely for network delivery. The result screen shows separate rows:

- **Report:** not created, created, or failed;
- **Scan data:** saved on device, uploading `x of y`, uploaded and verified, or needs connection;
- **3D processing:** queued, processing, published, degraded, or unavailable for this session.

The app can truthfully say `Scan saved on this iPhone` while offline. It says `Uploaded` only after the backend verifies every required R2 object and acknowledges session finalization. It says `Processing` only after a reconstruction job exists.

The summary separates:

- area covered;
- duration;
- litter identified;
- environmental features mapped;
- evidence photos;
- reconstruction data status.

Zero area and a missing duration are treated as degraded session diagnostics, not dressed up as a successful full scan.

## Canonical capture contract

### Session identity and ownership

The phone creates a random UUIDv4 local session ID before recording so a scan can begin offline. On first sync, `POST /api/capture-sessions` idempotently binds that local ID to the authenticated worker/device and returns a canonical backend session ID, capture schema version, and limits. Every later ticket is authorized against that database row. Clients do not choose arbitrary R2 keys or overwrite a session by guessing its name.

One session is anchored to an initial 10-meter location cell but may reference neighboring cells traversed during the scan. The initial cell is an index, not a claim that all geometry belongs inside it.

### Artifact streams

The primary iOS capture remains rotating chunk files. The current 60-second/64-MB limits are acceptable as initial bounds, but upload must stream from disk instead of loading an entire chunk into a Kotlin `ByteArray`.

| Artifact | Required content | Delivery behavior |
|---|---|---|
| client session descriptor | device/app/capture schema, start/end times, coordinate conventions, clock anchors, privacy/gate intervals, GPS/heading track, coverage, observations, expected artifact inventory | Small durable queue; revisioned until finalization |
| `chunk-NNN.mov` | accepted color samples with original timing, codec and output dimensions | Disk-backed, retryable direct PUT |
| `chunk-NNN.jsonl` | header plus pose/calibration/sample events | Disk-backed, retryable direct PUT |
| `chunk-NNN.depth` | timestamped scene-depth planes and confidence planes when supplied by ARKit | Disk-backed, retryable direct PUT |
| `chunk-NNN.mesh` | timestamped ARMeshAnchor additions/updates/removals: anchor ID/transform, vertices, normals, faces, and classification on supported LiDAR devices | Capability- and performance-gated, disk-backed, retryable direct PUT |
| evidence JPEG/HEIC | user taps, automatic candidate crops, best frames, report images | Durable when tied to a candidate; report copies may use their existing media store |
| masks/labels JSON | tap ROI, bbox/mask, classifier candidates, confidence, context suppression, confirmation source | Small durable queue |
| USDZ | guided large-object capture when used | Disk-backed, retryable direct PUT |

On a LiDAR-capable device, absent depth is an explicit degraded reason, not silently equivalent to a non-LiDAR session. A non-LiDAR session remains valid for photogrammetry.

Completeness is not defined solely by whatever rows the client chooses to register. The backend owns schema rules keyed by capture schema, device capability, session duration, and declared thermal/privacy intervals. Those rules require a contiguous sequence of chunk-closure records, recorder-health events, expected artifact kinds for each closed chunk, and an explicit server-recognized absence reason for every omission. A client can report `failed`, `thermal_suppressed`, `privacy_discarded`, or `not_supported`; it cannot relabel a required missing stream as optional and receive `backend_verified`.

### Crash-atomic local finalization

Native chunk writers use `.partial` names, flush/close their file handles, finish the video writer, and atomically rename complete files before they are eligible for inventory. `local_finalized` is set only after the recorder is quiescent and one SQLDelight transaction reconciles the entire session directory into chunk-closure and artifact rows.

If the bounded native writer wait expires, the session records `finalizer_timeout`, remains `local_finalizing`, and launch recovery re-enumerates every file by `(sessionId, chunkIndex, kind)`; it does not treat the presence of one database row as proof that the directory was booked. Recovery may seal the session as explicitly degraded only after no writer/partial file can still appear. This barrier prevents a crash after one asynchronous row insert from stranding the rest of a chunk.

### Per-frame and per-sample metadata

Capture schema v2 records enough information to recover geometry and correlate user events:

- AR monotonic timestamp and a periodic `{arTimestampSeconds, unixTimeMs}` clock anchor;
- frame/sample identifier and whether a color sample was actually appended to the video writer;
- exact video presentation timestamp for appended samples;
- camera-to-world transform with an explicit column/row-major declaration and ARKit coordinate-system declaration;
- 3×3 camera intrinsics, source image dimensions, encoded video dimensions, and the scale/crop/orientation transform between them;
- camera tracking state and reason when limited;
- interface orientation/display transform needed to map tap and model coordinates to the native image;
- scene depth timestamp, dimensions, row stride, pixel format, and unit (`Float32` meters for current ARKit depth);
- declared depth source (`sceneDepth` or `smoothedSceneDepth`) so the worker does not assume filtering semantics;
- scene-depth confidence map when available, with its dimensions and value semantics;
- ARMeshAnchor revision events with anchor UUID, local transform, vertex/normal/face buffers, classification values, and event timestamp when scene reconstruction is enabled;
- thermal state, dropped-frame counters, and pause interval reasons;
- nearest GPS fix with its own timestamp, latitude, longitude, horizontal accuracy, course/heading accuracy, and true/magnetic heading declaration;
- gate state and privacy segment identifier;
- camera exposure/ISO/lens/white-balance metadata when the active capture API exposes reliable values; absence is explicit rather than guessed.

The current schema writes a sidecar record before knowing whether the encoder accepted the video frame. Schema v2 adds an `appendedVideoSample` event or sample table so the decoder never pairs a pose with a video frame that was dropped under backpressure or thermal pressure.

### Coordinate and calibration conventions

The schema declares all conventions instead of relying on comments:

- transforms are serialized in native ARKit column-major order for capture, then converted once at ingest;
- `cameraToWorld` and `worldToCamera` are never inferred from field names alone;
- image coordinates are expressed in the native captured-image orientation;
- intrinsics are scaled to the encoded image dimensions during ingest, with aspect/crop assertions;
- depth is registered to its calibration camera, not assumed to share color resolution;
- GPS anchors map the session-local AR world into geographic coordinates; registration may refine the transform but preserves source provenance.

A fixture recorded from a known checkerboard/room validates pixel projection, transform handedness, image orientation, depth scale, and geo heading before schema v2 ships.

### Privacy segmentation

Only privacy-approved outdoor segments may leave the device.

- Gate transitions split capture segments immediately.
- An `INDOOR` transition discards the in-progress segment and every not-yet-uploaded segment overlapping the configured trailing retraction window.
- Completed chunks remain in a `privacy_pending` state for at least that holdback window; the uploader cannot race a late indoor decision.
- Manifest gate events specify accepted and discarded intervals, but discarded media bytes are deleted locally and never uploaded.
- A manual override is explicitly recorded and visible in internal audit data.
- The backend rejects artifacts whose segment is not listed as accepted by the finalized session.

The first production constant is `PRIVACY_UPLOAD_HOLDBACK_MS = 30_000`, twice the current 15-second gate evidence window and longer than the current 10-second purge window. The same constant gates raw chunks, candidate crops, and community-report photos. A photo or file created in the most recent holdback interval remains local until its segment is accepted. A defensive deletion/retraction job removes any public report blob that was uploaded before a later privacy invalidation. Changes to gate evidence/purge windows must fail a test unless this holdback remains at least as long as both.

Mobile descriptor revisions are inputs to the control plane, not the sealed discovery manifest. After artifact reconciliation, the backend writes an immutable final `session.json` containing the verified inventory and its own manifest checksum. DGX receives that sealed revision through the job record.

## Durable delivery and backend control plane

### State machine

Each capture session moves monotonically through observable states:

```text
recording
  -> local_finalizing
  -> local_finalized
  -> upload_pending / uploading
  -> uploaded_unverified
  -> backend_verified
  -> reconstruction_queued
  -> reconstructing
  -> map_published
```

Side states are `privacy_discarded`, `upload_paused`, `degraded`, `quarantined`, and `failed`. A report can succeed or fail independently of this state machine.

Each artifact has its own state:

```text
local -> ticketed -> uploading -> put_complete -> verified -> retained_by_backend
```

The phone deletes an artifact only after `verified` acknowledgment. A successful PUT without completion acknowledgment is not sufficient.

### Backend tables

The Next.js/Drizzle schema gains:

**`capture_sessions`**

- canonical session ID, unique local session ID, worker ID, device ID, schema version;
- initial cell, start/end timestamps, privacy status;
- schema-required, client-declared, and verified artifact counts and byte totals;
- session state, degraded reasons, final manifest R2 key;
- created/updated/finalized timestamps.

**`capture_artifacts`**

- opaque artifact ID and owning session ID;
- unique client artifact ID, used as the idempotency key for registration;
- chunk index, artifact kind, content type, and server-derived requirement class;
- expected size, SHA-256 checksum, R2 key;
- privacy segment, upload/verification state, retry count;
- verified size/checksum/ETag and timestamps;
- structured last error code with a redacted message.

**`reconstruction_jobs`**

- job ID, session or cell target, input schema/version;
- priority, status, attempt, lease owner/expiry, and monotonically increasing lease-generation fencing token;
- input validation report, failure code, output version;
- queued/started/finished timestamps.

**`reconstruction_versions`** becomes the atomic parent of a cell's output version. It stores the unique `(locationCellId, version)`, input inventory digests, job/fencing token, state, quality report, and activation timestamp. A partial unique constraint permits only one active version per cell.

The existing `reconstruction_assets` table belongs to a reconstruction-version parent and remains the file ledger. It gains source session IDs, source artifact checksums, output byte length/SHA-256, bounds, LOD metadata, semantic-layer version, processing software/model versions, and verification state. Existing source coverage-event IDs remain for provenance.

### API contracts

- `POST /api/capture-sessions` creates or returns the owned session for an idempotent local session ID.
- `POST /api/capture-sessions/{id}/artifacts` idempotently registers an expected artifact by client artifact ID and returns an upload ticket. A uniqueness constraint on `(sessionId, clientArtifactId)` makes a lost response safe to retry.
- `POST /api/capture-sessions/{id}/artifacts/{artifactId}/complete` verifies object existence, size, content type, and R2-enforced checksum using HEAD.
- `POST /api/capture-sessions/{id}/seal` stores a locally finalized inventory revision containing the terminal chunk index, every `(chunk, kind)` outcome (`present`, `discarded_privacy`, `not_supported`, or `failed`), expected byte totals, and a digest of the ordered inventory.
- `POST /api/capture-sessions/{id}/finalize` locks the session row, reconciles the sealed inventory against registered/verified artifacts, and queues reconstruction only when every required outcome is accounted for; it returns explicit degraded/missing data otherwise.
- `GET /api/capture-sessions/{id}` gives the app report/upload/reconstruction/map status.
- Internal service-authenticated job endpoints lease reconstruction work, heartbeat leases, record validation/failure, and publish asset candidates. Every mutation includes the current lease-generation fencing token; an expired worker receives a conflict even if it finishes later.
- `POST /api/internal/reconstruction/jobs/{id}/publish` accepts only the current fencing token, verifies every output object's length and SHA-256, and atomically activates one reconstruction-version parent per cell.

The legacy `/api/captures/upload-url` session-artifact mode is retired as a control-plane bypass. It must reject any `sessionId`/`artifactPath` request that is not backed by an owned `capture_artifacts` row, including old-client traffic; compatibility clients may upload non-reconstruction report media through their separate authorized route but cannot create verified raw-session objects.

Bulk bytes never pass through these routes.

### R2 key and presign behavior

R2 keys are issued from registered session/artifact rows, not client-composed path strings:

```text
raw/{cell}/{sessionId}/{artifactId}/{filename}
reconstructed/{cell}/{version}/{assetType}/{lod}.{format}
```

The presign client uses one host addressing mode validated on a physical iPhone. The first implementation should enable path-style addressing (`forcePathStyle`) for the TypeScript S3 client and the equivalent `addressing_style=path` botocore configuration for DGX, then verify a real-device PUT against `https://{account}.r2.cloudflarestorage.com/{bucket}/...`; this avoids the virtual-hosted bucket label present in the observed TLS failure. If Cloudflare or the SDK rejects that mode in the target environment, the release is blocked until an equivalent tested hostname is selected. A desktop `curl` success alone does not clear the iOS gate.

Tickets expire quickly and are refreshable. The app stores no presigned URL; it stores artifact identity and requests a fresh ticket for every retry.

The registered artifact includes its byte length and SHA-256 before ticket issuance. The presigned request binds an R2-enforced checksum header such as `x-amz-checksum-sha256`, and the physical-device integration test must prove that a mismatched body is rejected and that HEAD returns the stored checksum. Client-supplied object metadata by itself is not proof of content. If R2 cannot enforce/return the checksum in the deployed configuration, the object remains `uploaded_unverified` and local deletion waits for a worker to stream, hash, and acknowledge it. An ETag alone is not treated as a universal content checksum.

Sealing and finalization are idempotent transactions keyed by the ordered inventory digest. Post-seal artifact registration cannot mutate that revision. A newly recovered artifact creates a new inventory revision. If processing has not started, the old job is invalidated and re-queued from the new revision; if processing or publication already occurred, the artifact becomes input to a clearly versioned supplementary reconstruction. Active asset provenance never changes underneath an existing version.

### Retry and retention policy

- Use native background file uploads or an equivalent streaming file body; do not read 64 MB chunks into memory.
- Retry timeout, connection, TLS, 408, 425, 429, and 5xx failures with exponential backoff plus jitter.
- Treat 401/403 as a reason to refresh authentication/ticket before classifying the artifact.
- Treat 409 as an idempotency reconciliation request, not data loss.
- Treat 413 as a chunk-splitting/configuration failure and preserve the file.
- Quarantine deterministic schema/content errors for support review; never immediately delete bytes because an error string happens to contain a 4xx code.
- Trigger retry on app launch, scan completion, foreground transition, charging, and network transition to an allowed transport.
- Expose cellular-upload consent and pending byte count in settings.
- Keep a bounded local storage budget with visible pressure handling. Eviction may remove only backend-verified artifacts; unverified artifacts require explicit user action after a warning.

The existing manifest queue cap of 12 is insufficient as the sole durability guarantee. Session metadata and artifact inventory belong in SQLDelight, while settings may retain only small preferences.

### Report-photo upload

The report media route uses Vercel Blob's `addRandomSuffix: true` and a database idempotency key tied to report/session plus client photo ID. A retry returns the previously recorded blob URL when the photo is already accepted. Report media failure remains separate from raw reconstruction artifact delivery, and both statuses are shown accurately.

## DGX Spark ingestion and reconstruction

### Worker input discovery

DGX does not scan R2 for filenames as its source of truth. It leases a backend reconstruction job containing the finalized session ID, schema version, verified artifact list, checksums, and expected byte totals. R2 prefix listing is a repair/audit tool only.

Before decoding, the worker:

1. downloads or streams each verified object;
2. rechecks size and SHA-256;
3. validates that required video/sidecar pairs exist per chunk;
4. validates privacy-approved intervals;
5. emits a machine-readable input validation report;
6. marks the job `degraded` rather than guessing when required calibration or timing is absent.

### Canonical chunk decoder

A new decoder module is the compatibility boundary between mobile capture and reconstruction. It supports schema v1 for already captured sessions and schema v2 for production sessions.

Schema v1 support is intentionally limited: it can recover session-local video, poses, intrinsics, and depth, but its sidecar has no Unix/AR clock anchor. It therefore cannot reliably align epoch-timestamped GPS, taps, or observation events. A v1 job is marked `geo_semantic_degraded` and cannot publish geo-aligned semantic/map assets unless an independently validated clock/georeference exists. Schema v2 requires color/pose event skew no greater than 100 ms and a geo anchor no older than two seconds; frames outside those bounds remain in the audit set but are excluded from geo-semantic fusion.

For each chunk it:

- demuxes MOV frames with FFmpeg/PyAV and preserves presentation timestamps;
- parses JSONL headers, poses, intrinsics, tracking, clock anchors, and sample-append events;
- decodes each little-endian depth envelope and LZFSE plane;
- decodes confidence planes when present;
- matches color, depth, pose, and geo samples by declared timestamp with configurable maximum skew;
- scales intrinsics to the actual decoded frame and applies the declared orientation/crop transform;
- emits a single internal `CapturedFrameData` model plus validation flags;
- optionally writes normalized JPEG/PNG/NPZ intermediates for debugging and generic tooling.

This replaces the false assumption that `EnvScanManifest.capturedFrames` is the primary recorder output. Discrete JPEG/PNG frames remain supported as a legacy/import format, but the background chunk recorder is canonical.

Golden fixtures cover:

- one complete LiDAR chunk;
- one color-only chunk;
- thermal sidecar-only interval;
- dropped video samples;
- a rotated portrait scan;
- depth with padded row stride;
- expired/incomplete last chunk;
- clock-anchor correlation with a user tap.

### Reconstruction stages

The first production pipeline is staged and resumable:

1. **Decode and quality selection:** reject corrupt frames, blur, extreme motion, limited tracking, unusable exposure, and insufficient overlap while preserving reasons.
2. **Per-session reconstruction:** use ARKit poses/intrinsics and mesh anchors as priors; fuse LiDAR depth where confidence permits; run visual feature matching/photogrammetry to refine geometry and camera poses.
3. **Geo alignment:** anchor the local session with timestamp-paired GPS/heading and refine against existing cell geometry.
4. **Cross-session registration:** align accepted session geometry, reject low-fitness outliers, and preserve every source transform.
5. **Geometry products:** produce point cloud (`PLY/LAZ`), map mesh (`GLB`), and a splat/neural view asset when the chosen renderer is production-ready.
6. **Semantic products:** project user taps, confirmed litter masks, model detections, and negative context into the geometry; produce independently versioned litter masks/footprints and severity statistics.
7. **LOD products:** generate close geometry, mid-zoom footprints/clusters, and far-zoom cell health tiles.
8. **Quality gate and publish:** compare completeness, alignment error, coverage, artifact provenance, and semantic confidence to thresholds; upload candidates; atomically activate only a passing version.

Outputs are immutable. A newly activated version supersedes the prior map version without deleting its provenance.

### DGX Spark hardware decision

The current Open3D 0.18 aarch64 wheel segfaults in the exact operations the skeleton uses. Production does not pretend this is solved.

The recommended first deployment is:

- Next.js/Vercel: control plane and job ledger;
- R2: raw and reconstructed object storage;
- a small x86 worker: Open3D registration/fusion step while the documented DGX crash remains;
- DGX Spark: artifact ingestion orchestration, model inference/training, splat/semantic processing, and any reconstruction stages that pass hardware verification;
- PostgreSQL: state, provenance, and active asset versions.

The x86 stage can later be removed when either a source-built Open3D, a different registration library, or a CUDA/PyTorch implementation passes the minimal crash repro and a real-session quality test on DGX Spark. Paying for this narrow worker provides more immediate utility than deploying the dormant Ktor server.

### Asset publication and map read path

The publication endpoint creates one reconstruction-version parent plus all candidate asset rows in one transaction and activates the version only after every required output's size and SHA-256 are verified. The current fencing token is checked again inside the activation transaction. Map APIs return:

- active reconstruction version and bounds;
- signed/CDN-safe asset URLs appropriate to zoom/device capability;
- litter semantic version and per-cell severity;
- fallback observation footprints when detailed geometry is absent;
- source freshness and confidence.

The app never renders an unactivated partial version.

## Production map language by zoom

The map communicates the same truth at different visual resolutions. Transitions cross-fade over at least one zoom level; layers do not abruptly pop.

### Close object view: zoom 18+

- Render reconstructed ground/objects where available, with a simplified mobile mesh or splat preview.
- Show litter semantics as a translucent red overlay or red edge treatment over the actual geometry, not as a generic pin.
- Large discarded objects remain recognizable: couch silhouette, broken bicycle frame, detached tire, mattress, dumped bags.
- Each item's true footprint and orientation drive size. A tiny chip bag may occupy only a few geographic pixels, but receives a two-to-three-screen-pixel visibility floor so it remains a truthful red speck rather than disappearing.
- User-confirmed but not yet fully reconstructed items use a hollow proportional footprint and best evidence image on tap.
- Cleaned/superseded observations disappear from the active red layer while historical versions remain auditable.

### Street/detail blend: zoom 16.5–18

- Cross-fade geometry into oriented footprints, litter clusters, and reconstructed issue-area silhouettes.
- Preserve the distribution: a line of litter along a fence remains linear; scattered wrappers remain dispersed; a pile remains concentrated.
- Coverage appears as subtle merged organic paint under litter, never as a square grid.
- A tap opens the best frame, estimated materials/weight, capture confidence, scan freshness, and reconstruction status.

### Neighborhood health view: zoom 13–16.5

- Replace individual objects with adaptive cell-health polygons based on observations, estimated burden, coverage quality, and staleness.
- Cells retain hard enough shared boundaries to show adjacent score differences; do not blur point density across clean and dirty blocks.
- High burden uses deep red, then red-orange and amber; verified clean uses muted green/teal; unscanned remains neutral/fogged rather than falsely clean.
- Cell size aggregates progressively with zoom while respecting real ledger boundaries.

### City/regional view: below zoom 13

- Render a weather-map/choropleth surface from aggregated cell scores.
- Preserve high local contrast where neighboring areas differ, with scale-aware aggregation and a visible legend.
- Suppress object icons and legacy spot pins.
- Show coverage/freshness as an independent texture or filter so “dirty,” “clean,” and “unknown” cannot be confused.

The live camera zoom, not a stale target zoom, drives LOD aggregation. `currentMapZoom` must feed cell-size selection and layer visibility on every meaningful camera change.

## Semantic litter model

Geometry and cleanliness are separate versioned products:

- **Geometry layer:** surfaces, objects, and world alignment.
- **Semantic layer:** litter masks/footprints, item class, confidence, source, severity, and lifecycle.
- **Health layer:** zoom-dependent aggregation of active semantic observations plus scan freshness/coverage.

This separation lets a reviewer correct “car tire” to “not litter” without rebuilding the entire mesh, and lets a cleanup supersede the red overlay while retaining the underlying street geometry.

The semantic pipeline prioritizes user-confirmed taps as strong labels, model-confirmed localized detections as provisional labels until confidence requirements pass, and whole-frame classifications only as weak routing signals. Parent-context suppression is mandatory for vehicle parts, installed fixtures, bins in normal use, plants, and construction materials.

## Marketing and onboarding storyboard

The production visual language yields a three-panel vertical campaign and an in-app tutorial. Generated artwork should resemble the real interface closely enough to guide implementation, while being labeled as a concept until the production UI matches it.

### Panel 1 — Scan the environment

**Headline:** `Open Environment Scanner`

**Supporting copy:** `Walk naturally. The heading cone shows where you are facing, and the map fills as the ground is captured.`

Visual: phone camera facing an outdoor sidewalk/lot. A subtle perspective grid sits only on the ground. The lower-right minimap is bright and readable, with a white user puck, broad translucent cone, irregular brighter beam, and three tiers of accepted green/teal coverage aligned to the camera grid.

### Panel 2 — Identify litter

**Headline:** `Tap What Needs Attention`

**Supporting copy:** `Tap visible litter to mark it. Move closer to help identify materials, size, and condition.`

Visual: several real litter examples in the camera view. One has a dashed orange `Checking…` reticle, one says `Move closer to identify`, and one has a confirmed red outline. A normal car tire remains unmarked, demonstrating contextual intelligence.

### Panel 3 — Build the living map

**Headline:** `Every Scan Improves the Map`

**Supporting copy:** `LiDAR, photos, position, and scan coverage become a shared 3D record of what is clean and what needs work.`

Visual: transition from close reconstructed street geometry with recognizable red-overlay litter, through oriented footprints, to a high-contrast neighborhood health/weather map. A small status line reads `Scan uploaded • 3D map processing` rather than implying instant reconstruction.

### Map zoom explainer

One horizontal or four-card graphic shows the same location at four scales:

1. `Object View` — recognizable reconstructed objects with red semantic overlays;
2. `Street View` — proportional litter footprints and clusters;
3. `Neighborhood View` — contrasting cell-health blocks;
4. `City View` — weather-map cleanliness surface.

The same couch, bike, tire, and small snack bag appear progressively abstracted, proving that the display changes representation without changing the underlying truth.

## Observability and operations

Every log and metric uses session ID, artifact ID, and job ID, never full presigned URLs.

Minimum metrics:

- sessions started/local-finalized/backend-verified;
- total and pending bytes by device/network;
- artifact attempts, retry reasons, age, and verification failures;
- expected versus received video/sidecar/depth/evidence counts;
- LiDAR-capable sessions missing depth;
- decode rejection by reason and timestamp skew distribution;
- reconstruction queue wait, stage duration, failure, and publish latency;
- map asset fetch/render failures by version;
- automatic candidates, user taps, confirmations, rejections, and vehicle-context suppressions;
- report-photo collision/idempotent retry rate.

Internal session diagnostics present a redacted artifact checklist and a copyable session ID. User-facing failures say what will happen next: `Saved—will upload on Wi-Fi`, `Upload paused—plug in or allow cellular`, or `One file needs retry`.

## Verification and release gates

### Automated tests

- Tap coordinate mapping across portrait orientation and safe-area/device sizes.
- Tap state reducer: success, unknown, no raycast, retry, confirm, reject, and persistence after relaunch.
- Vehicle-context rule: attached car tire is suppressed; detached roadside tire can be user-confirmed.
- Summary counts litter and features separately.
- Cone always renders with valid heading; beam gracefully handles zero, one, two, or many ray hits.
- Minimap projection keeps puck/cone/coverage aligned across pan/recenter and pixel densities.
- Coverage LOD responds to live map zoom.
- Artifact state-machine and retry classification tests.
- Relaunch recovery at three crash points: after local chunk finalization, after R2 PUT but before acknowledgment, and after acknowledgment but before local deletion.
- Session ownership and cross-user presign denial tests.
- Legacy session-artifact presign requests without an owned artifact row are rejected, including authenticated old-client requests.
- Duplicate artifact completion and report-photo upload are idempotent.
- Manifest/inventory reconciliation includes artifacts uploaded after local scan completion.
- Partial orphan recovery reconciles a missing file row even when other rows for the same session already exist.
- Session sealing rejects a missing chunk/kind outcome, deduplicates repeated artifact registration, and creates a new revision for a genuinely late artifact.
- Server-owned capability/schema rules prevent a client-declared optional flag from hiding a required stream or recorder failure.
- Schema v1/v2 chunk decoder golden fixtures and matrix/orientation/depth-scale assertions.
- Privacy holdback prevents an indoor-overlapping segment from receiving a ticket.
- Raw chunks, candidate crops, and report photos share the 30-second privacy holdback and deletion fallback.
- Reconstruction publication is atomic and map APIs never expose a partial version.
- A leased reconstruction job resumes idempotently after worker termination without publishing duplicate or mixed-version assets.
- An expired worker's stale fencing token cannot publish after a replacement lease starts, and a corrupt output object cannot activate.

### Physical iPhone release gate

One continuous 60–90 second outdoor scan is recorded, plus focused 30–60 second clips for individual UX defects. Each clip begins about three seconds before the action and continues at least five seconds after the result.

The release session must demonstrate:

- safe-area pills fully visible;
- readable minimap in daylight;
- user puck and persistent cone rotating with the phone;
- live beam changing reach without replacing the cone;
- green camera bar absent and grid confined to surfaces;
- a clear litter tap produces immediate feedback, persists a candidate, and uploads its crop/metadata;
- an unrecognized object still offers user confirmation;
- an attached car tire does not auto-promote;
- coverage tiers visibly change with distance/quality;
- completion survives Wi-Fi loss and reports local/upload state honestly;
- all color, sidecar, depth, confidence, geo, tap, mesh, and manifest outcomes required by the backend's capability/schema rules are accounted for, and every present artifact is verified;
- a supported LiDAR device either delivers mesh-anchor data or records the explicit performance/capability reason it was disabled;
- no privacy-discarded interval appears in R2 or public Vercel Blob storage;
- DGX/x86 workers decode the exact session and publish a versioned test asset;
- the mobile and web map render the activated close, mid, and far representations.

### Production acceptance criteria

The increment is complete only when a test session can be followed by one session ID from the phone's local queue, through backend artifact rows and R2 checksums, through a reconstruction job and output provenance, to an activated map version. No manual R2 prefix search or database repair may be required.

## Delivery sequence

1. **Immediate scanner reliability:** restore cone; remove green bar; fix safe area and minimap brightness; implement explicit tap candidates; stop auto-reporting broad whole-frame classifications; fix report-photo collision.
2. **Durable ingest contract:** add backend session/artifact tables and APIs; migrate mobile queue to per-artifact state; stream uploads; add acknowledgment, retry, privacy holdback, and user status.
3. **Capture schema v2:** add clock anchors, actual video-sample events, geo/gate metadata, depth confidence, orientation/calibration declarations, and golden fixtures.
4. **DGX decoder:** implement MOV/JSONL/LZFSE normalization, validation report, and real-session ingest.
5. **Reconstruction orchestration:** add job leasing, verified x86 registration workaround, DGX processing, semantic projection, LOD generation, and atomic publication.
6. **Map consumption:** add active-asset API and close/mid/far layers on mobile and web; wire live zoom to aggregation.
7. **Marketing concepts:** generate the scanner, litter-identification, living-map, and four-zoom visuals from the production visual contract, then compare them with real screenshots during UI implementation.

Each phase leaves the prior phase useful and observable. Scanner fixes do not wait for reconstruction; durable capture does not claim reconstruction; map publication does not activate incomplete assets.

## Superseded assumptions

This specification consolidates and corrects earlier design notes where they conflict:

- the scene-aware beam does **not** replace the heading cone;
- best-effort unqueued raw frames are not an acceptable reconstruction feed;
- a one-time R2 manifest is not the authoritative artifact inventory;
- `capturedFrames` JPEG/PNG entries are not the canonical format when the native chunk recorder is active;
- R2 object naming is not a job queue;
- whole-frame classification is not litter localization;
- the current DGX Open3D skeleton is not an end-to-end production pipeline;
- deploying the existing Ktor server does not solve capture delivery or reconstruction.
