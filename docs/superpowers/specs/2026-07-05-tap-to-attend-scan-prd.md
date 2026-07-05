# PRD: Tap-to-Attend — directing scan attention like camera tap-to-focus

Date: 2026-07-05
Status: proposed (Blake, during boundary-editor field session)
Origin: "unless we have added a feature that gives the user the ability to tap
what's in the camera view to drag the scene detection's attention to it,
similar to how on the camera app you tap an area to focus in that direction.
that would be really smart for the users to help speed up their scans"

## Problem

During an environment scan, detection is passive: the tick loop runs
whole-frame classification (VNClassifyImageRequest) plus near-field LiDAR
sampling, and the walk-up confirm flow only starts once a candidate crosses
the confidence gate. When a worker can SEE trash the model hasn't flagged
(low-contrast debris, partial occlusion, unusual objects), they currently
have no way to say "look THERE" — they either wait, reposition, or the item
is missed. There used to be a "tap to capture" placeholder on the simulator
fallback that suggested tap interaction but did nothing on device.

## Feature

One tap on the AR camera view = one attention request at that point:

1. **Visual ack (immediate)**: a focus reticle animates at the tap point
   (camera-app style square, shrink + settle, ~400ms) so the gesture always
   feels responsive even before results.
2. **Region-of-interest analysis (fast path)**: crop the current frame around
   the tap (~35% of frame side, clamped to bounds) and run the SAME
   classification stack on the crop:
   - VNClassifyImageRequest on the ROI (whole-image classifiers behave far
     better on tight crops — this is the accuracy win)
   - saliency-guided refinement (VNGenerateObjectnessBasedSaliencyImageRequest)
     to tighten the box around the salient object inside the ROI
3. **Geometry anchor**: ARKit raycast from the tap point → world position +
   LiDAR distance; same path the auto lock-on uses.
4. **Outcome**:
   - Detection above the (LOWERED) tap-assist threshold → spawn the standard
     lock-on candidate at the raycast anchor → existing walk-up confirm flow
     (weight/type estimates, minimap seed, world observation). Tap-assisted
     candidates get `origin: "user_tap"` in attributes.
   - Nothing found → reticle fades to a subtle shake; no candidate. Never
     block or toast — the cost of a miss must be near zero so tapping stays
     cheap.
5. **Attention bias (soft)**: for ~5s after a tap, the tick loop weights its
   frame analysis toward the tapped ROI (crop-first, whole-frame second) so a
   moving camera keeps "looking" where the user pointed.

## Why this is the right shape

- Reuses every existing subsystem: frame capture (pixelBufferOrNull), the
  Vision pipeline, ARKit raycast + LiDAR distance, candidate/lock-on flow,
  observed-item confirm, world-observation submit. The delta is a gesture,
  an ROI crop, and a threshold profile.
- Mirrors a gesture every phone user already knows (tap-to-focus), so it
  needs no onboarding.
- Speeds scans exactly where the model is weakest (Blake's stated goal).

## Scope

- **In**: env scan surface (native RealityKit + SceneKit fallback); reticle
  animation; ROI classify; raycast anchor; lowered-threshold candidate; 5s
  ROI bias; telemetry (`tap_assist` diagnostics counter + origin attribute).
- **Out (later)**: route-scan surface; multi-tap queueing; long-press for
  "definitely trash here" manual tagging without classification (worth a
  follow-up — gives workers full authority when the model refuses).

## UX details

- Tap target: anywhere on the camera view not covered by UI chrome; ignored
  while the confirm card is open or during Object Capture.
- Reticle: 72pt rounded square, white 80%, shrinks 1.2x→1.0x with spring,
  fades after result (green flash on hit, neutral fade on miss).
- Threshold: tap-assist confidence gate ≈ 60% of the auto gate (user intent
  is itself evidence).
- Haptic: light impact on hit (same as auto lock-on), none on miss.

## Acceptance

1. Tapping visible litter that auto-detect missed produces a lock-on
   candidate within 700ms on device in ≥70% of staged trials.
2. Tapping empty pavement produces no candidate and no error.
3. Confirmed tap-assisted items flow to the DB identically to auto items,
   with `origin: "user_tap"` in observation attributes.
4. No regression in tick-loop frame budget (ROI pass replaces, not adds to,
   the frame's whole-image pass during bias window).

## Build estimate

~1 day: gesture + reticle (2h), ROI crop + classify path (3h), raycast
anchor + candidate wiring (2h), bias window + telemetry + polish (3h).
