# Scan → World-Splat Registration Contract

How a phone scan session's artifacts carry enough metadata for the DGX
reconstruction pipeline to (a) build a local splat/reconstruction from the
capture, and (b) register that local result into the shared **world splat
layer** so neighboring sessions merge into larger splats.

Audience: the DGX-side pipeline (backend/ scan index + reconstruction
prototype) and anyone changing the mobile capture path. The mobile writer and
this contract must move in lockstep.

## The three coordinate/time systems

Every session involves three frames that must be bridged explicitly:

| System | Space | Clock | Written by |
|---|---|---|---|
| Pose sidecar (`chunk-NNN.jsonl`) | AR-local meters, `gravityAndHeading` (+X=east, +Y=up, −Z=true north) | ARKit `frame.timestamp` (boot-relative seconds) | `ArSessionRecorder.swift` |
| GPS path (`manifest.path[]`) | WGS84 lat/lng | Epoch milliseconds | `EnvScanSessionRecorder` |
| World splat layer (DGX) | Geo-anchored (project convention) | — | DGX pipeline |

## The bridge: `manifest.geoRefEvents[]`

Each `GeoRefEvent` is one AR↔world pairing taken at the instant a usable GPS
fix arrived (the same fixes that drive `updateGeoReference` on-device):

```json
{
  "atMs": 1720715400123,          // epoch ms (same clock as path[].atMs)
  "lat": 41.851, "lng": -87.706,  // the GPS fix
  "accuracyMeters": 4.2,          // fix quality — weight pairings by this
  "arX": 1.23, "arY": 0.02, "arZ": -5.67,   // AR-local camera position
  "arTimestampSeconds": 12345.678 // ARKit clock (same clock as sidecar t)
}
```

Registration recipe (DGX side):

1. **Clock alignment**: each event gives one `(atMs, arTimestampSeconds)`
   pair; the chunk sidecar header additionally carries `(epochMs, arT)` at
   chunk open (schema 2). Fit `epochMs ≈ a + 1000·arT` over all pairs (a is
   the boot-epoch offset; drift is negligible over a session).
2. **Spatial alignment**: AR-local is already gravity-aligned and
   compass-north-aligned (`gravityAndHeading`), so the nominal mapping is a
   pure translation: `east = (lng−lng₀)·111320·cos(lat₀)`,
   `north = (lat−lat₀)·111320`, with AR `+X=east`, `−Z=north`. In practice
   the compass bias rotates the AR frame by an unknown few degrees — solve a
   2D similarity (translation + rotation, scale fixed at 1) least-squares
   over all events, weighted by `1/accuracyMeters²`. ≥3 well-spread events
   over-determine it; sessions with <2 events or a degenerate spread should
   be treated as **local-only** (do not merge into the world layer).
3. **Pose stream placement**: apply the solved transform to every sidecar
   pose; splat within the AR frame, then re-anchor the finished splat with
   the same transform.

`manifest.arFrameConvention` declares the axes and matrix layout
(`gravityAndHeading:+x=east,+y=up,-z=north;meters;colmajor`). Sidecar
`transform[16]` is camera-to-world simd_float4x4 flattened column-major;
`intrinsics[9]` column-major (fx at index 0) for the SOURCE image dimensions
in the header — the video is downscaled to `videoWidth×videoHeight`, so
scale intrinsics by `videoWidth/sourceWidth` before use.

## Artifact inventory per session (R2: `raw/{locationCellId}/{sessionId}/`)

- `chunk-NNN.mov` — HEVC video, long edge 1280, source-timed from ARKit
  timestamps (frame PTS is on the sidecar `t` clock).
- `chunk-NNN.jsonl` — header (schema, session, dims, `epochMs`+`arT` clock
  anchor) + per-frame `{t, transform[16], intrinsics[9], tracking, thermal}`.
- `chunk-NNN.depth` — LZFSE depth keyframes at 2Hz (LiDAR devices only);
  length-prefixed binary records.
- `manifest.json` — `EnvScanManifest`: GPS path (~1Hz, heading + accuracy),
  `geoRefEvents`, credited coverage cells, observed items, photos,
  diagnostics tail.

## Quality gating for the world layer

Merge a session's splat into the world layer only when:
- the similarity solve's residual is within the median GPS accuracy of its
  events (a big residual = compass bias too large or a mid-session re-pair
  discontinuity — split the session at the discontinuity and solve per
  segment);
- sidecar `tracking == 0` (NORMAL) for the frames used; discard LIMITED
  spans;
- the session's `schemaVersion`/`captureSchema` are understood.

Mid-session geo-ref re-pairs are visible as jumps between consecutive
`geoRefEvents` whose AR delta disagrees with their GPS delta — segment the
pose stream at those points rather than averaging across them.
