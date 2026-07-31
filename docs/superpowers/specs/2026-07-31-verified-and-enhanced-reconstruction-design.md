# Verified and Enhanced Reconstruction Design

**Date:** 2026-07-31  
**Status:** Approved design  
**Scope:** GCC environment-scan reconstruction, cleanup, publication, and World Twin presentation

## Objective

Make GCC neighborhood reconstructions easier for a person to recognize while preserving a strict boundary between measured scan evidence and generated visualization.

The authoritative reconstruction must contain only geometry supported by captured RGB, ARKit poses, LiDAR depth, and confidence data. Generative processing may improve visual continuity only in a separate derived asset that cannot alter evidence, measurements, map coverage, or litter locations.

## Product model

Every published cell or neighborhood version belongs to an immutable asset family:

```text
Raw scan bundle
    -> Base reconstruction
        -> Verified Scan
        -> Enhanced View
        -> Provenance and confidence manifest
```

### Verified Scan

The Verified Scan is the default and authoritative view. It:

- uses captured images, ARKit poses, camera calibration, LiDAR depth, and depth confidence;
- freezes ARKit poses by default;
- permits only bounded, smooth trajectory-level correction when diagnostics prove drift;
- removes unsupported geometry conservatively;
- leaves transparent holes where evidence is insufficient;
- preserves RGB-supported building surfaces beyond LiDAR range;
- stores confidence by spatial region; and
- is the only reconstruction allowed to support measurement, registration, coverage, or evidence placement.

### Enhanced View

The Enhanced View is a derived visualization. It:

- can be created only from an accepted Verified Scan;
- locks high-confidence observed content;
- may generate content only in weak or missing regions;
- carries a generated-region mask;
- records its verified parent, model, weights, parameters, and creation time;
- is always labeled **AI-enhanced visualization**; and
- cannot update map cells, litter coordinates, measurements, scan coverage, registration anchors, or later verified reconstructions.

### Compare mode

Compare mode synchronizes the camera between Verified Scan and Enhanced View and supports a wipe, opacity slider, or side-by-side presentation. Its purpose is to make generated changes obvious, not to create a third reconstruction product.

## World Twin experience

The GCC World Twin presents three explicit choices:

1. **Verified Scan** is the default tab.
2. **Enhanced View** is a separately labeled tab.
3. **Compare** synchronizes both assets for inspection.

Switching views preserves camera position when coordinate frames match. Litter pins and evidence photos always use verified coordinates. If an enhanced asset is absent or rejected, the tab says **Not generated yet** or **Enhancement rejected**; it never silently displays another asset.

Each view displays asset version, processing time, source-scan count, and quality status. The enhanced view additionally displays its verified parent and generated-content disclosure.

## Verified reconstruction pipeline

### 1. Frame audit

Rank and select frames using:

- ARKit tracking state;
- image sharpness and motion blur;
- exposure and white-balance stability;
- temporal and spatial coverage;
- camera translation and parallax;
- view overlap; and
- usable LiDAR depth confidence.

Reject limited-tracking frames, near duplicates, severe blur, large appearance discontinuities, and depth samples marked low confidence. Selection must retain route coverage rather than choosing only the sharpest images.

### 2. Pose and geometry preparation

ARKit/LiDAR poses remain frozen for the baseline recipe. Independent per-frame pose refinement is prohibited for published verified assets because it can make the splat inconsistent with the recorded trajectory.

If frozen-pose diagnostics reveal drift, a later correction may optimize a smooth trajectory with explicit limits on translation, rotation, velocity, and acceleration. The corrected trajectory must be exported alongside the splat and retain its relationship to the original poses.

LiDAR samples are fused with confidence weights. Inconsistent or low-confidence samples do not initialize or supervise geometry.

### 3. Base training

Training uses:

- bounded Gaussian population growth;
- antialiased rasterization;
- confidence-weighted LiDAR depth supervision;
- per-frame exposure compensation;
- fixed or explicitly exported camera trajectories; and
- periodic recoverable checkpoints.

Checkpointing must allow validation or graceful termination without discarding the complete run. GPU admission control prevents reconstruction training from overlapping other reserved generative workloads on the DGX Spark.

### 4. Evidence-based cleanup

Each Gaussian receives an evidence score derived from:

- opacity;
- physical scale;
- anisotropy;
- number and angular diversity of supporting camera views;
- color reprojection consistency;
- proximity to confidence-weighted LiDAR surfaces;
- connected-component membership; and
- distance from the observed camera corridor.

Cleanup generates three candidates:

- **Conservative:** removes only clearly unsupported floaters.
- **Balanced:** recommended publication candidate.
- **Diagnostic:** aggressive removal used to expose weak areas, never automatically published.

Buildings beyond LiDAR range survive when supported by multiple consistent RGB views. Disconnected, low-support splats are removed even when their opacity is high.

### 5. Verified quality gate

Validation renders:

- fixed recorded camera views;
- withheld recorded views;
- short interpolated paths between nearby recorded poses; and
- standardized sidewalk, facade, vegetation, vehicle, and ground-facing views.

Metrics include image similarity, depth error, temporal flicker, floater ratio, retained spatial coverage, and asset size. During the pilot, a human reviewer must confirm that buildings, sidewalks, vegetation, vehicles, and relevant litter evidence remain recognizable.

A failed candidate is retained for diagnosis but is not published. The last-known-good verified asset remains available atomically.

## Enhanced reconstruction pipeline

Enhancement begins only after the verified quality gate passes.

1. Render controlled trajectories based on recorded poses.
2. Provide verified RGB, opacity, depth, and confidence masks to the enhancement model.
3. Lock high-confidence pixels and permit generation only inside weak-region masks.
4. Enforce temporal consistency across generated views.
5. Distill the enhanced views into a separate 3D representation.
6. Compare enhanced renders against verified anchors and reject spatial movement of buildings, sidewalks, vehicles, litter evidence, or street geometry.
7. Publish the asset, generated-region mask, and provenance together.

Generated output is never fed back into verified training, scan registration, mapping, or evidence extraction.

## Additional-scan strategy

More iterations cannot recover surfaces absent from the capture. The system should request additional scans when diagnostics identify:

- facades observed from only one direction;
- occluded sidewalk or curb regions;
- vegetation with poor multi-view consistency;
- insufficient sideways parallax;
- gaps in confident LiDAR coverage; or
- abrupt route turns with limited tracking.

Preferred rescans overlap an existing route while approaching from the opposite walking direction and include slower lateral motion near weak areas. New sessions are registered into the same cell frame and improve the next immutable verified version.

## Manifest and provenance

Every asset version records:

- asset ID, cell IDs, parent IDs, and asset kind;
- source session IDs and capture/build versions;
- capture and processing timestamps;
- selected and rejected frame counts with reasons;
- baseline, tracking, depth-confidence, and overlap statistics;
- training recipe, code revision, parameters, and checkpoint;
- cleanup thresholds and evidence-score distribution;
- validation metrics, review state, and publication decision;
- verified parent for enhanced assets;
- enhancement model and parameter versions; and
- generated-region mask location and checksum.

## Processing states and failure handling

```text
Queued -> Auditing -> Training -> Cleaning -> Validating -> Published
                                               \-> Rejected
```

Each failure records a reason and preserves logs and the latest valid checkpoint. Publication uses temporary objects plus validation and atomic activation, so the viewer never receives a partial PLY or SOG file. Failed work never replaces the last-known-good asset.

The reconstruction scheduler has one GPU-owning job at a time and checks memory and active reservations before dispatch. CPU-only auditing, manifest generation, and planning may run while the GPU is reserved elsewhere.

## Testing

### Unit and property tests

- Frame ranking preserves spatial coverage and rejects known degraded frames.
- Confidence parsing and weighting are deterministic.
- Evidence scores retain multi-view-supported facades and reject isolated floaters.
- Cleanup variants are ordered from conservative to aggressive.
- Enhanced assets cannot become verified parents or evidence sources.
- Publication refuses missing, malformed, partial, or unvalidated artifacts.

### Reconstruction regression tests

- Synthetic surfaces with injected floaters test geometric cleanup.
- Fixed-pose renders compare each recipe with a stored baseline.
- Withheld-view and interpolated-path renders measure consistency.
- Known scan sessions exercise sidewalk, facade, vegetation, vehicle, and litter views.

### Viewer tests

- Verified, Enhanced, and Compare resolve the correct immutable assets.
- Verified is the default.
- Enhanced disclosures and provenance remain visible.
- Camera synchronization works when coordinate frames match.
- Missing or rejected enhancements show an explicit state.
- Evidence markers resolve only from verified coordinates.

### Operational tests

- GPU admission control respects other sessions.
- Interrupted training resumes from a checkpoint.
- Atomic publication retains the last-known-good asset on failure.
- Desktop, mobile, and Tailscale loading remain within defined asset-size and startup budgets.

## Delivery phases

### Phase 1: Verified Scan refinement

- Recoverable checkpoints
- Frozen-pose production recipe
- Multi-view evidence scoring
- Three cleanup strengths
- Recorded-camera presets
- Automated comparison reports
- Conservative publication gate

### Phase 2: World Twin presentation

- Verified and Enhanced tabs
- Explicit unavailable/rejected states
- Provenance and quality summaries
- Camera synchronization
- Compare mode

The Enhanced tab may initially display **Not generated yet**.

### Phase 3: Governed enhancement

- Weak-region mask generation
- Controlled trajectory rendering
- Temporally consistent enhancement
- Separate 3D distillation
- Generated-region visualization
- Enhanced quality gate and publication

## Success criteria

- Verified views preserve recognizable buildings, sidewalks, vegetation, vehicles, and relevant litter evidence across recorded and nearby interpolated viewpoints.
- Floater ratio improves without materially reducing supported coverage.
- Every enhanced pixel or Gaussian is traceable to a verified parent and generated mask.
- Users can always tell which product they are viewing.
- Generated geometry never affects evidence, measurement, mapping, or future verified reconstruction.
- Reconstruction work does not contend with separately reserved DGX GPU workloads.
