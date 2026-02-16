/**
 * Timing utilities for GCC Remotion ad
 *
 * Video specs (PRE-LAUNCH VERSION):
 * - FPS: 30
 * - Duration: 60 seconds (extended hook tells full story)
 * - Deliberate pacing: each text/element gets time to breathe
 * - Removed: Impact, Testimonial, Stats scenes (past metrics not applicable pre-launch)
 */

export const FPS = 30

// Convert seconds to frames
export function secondsToFrames(seconds: number): number {
  return Math.round(seconds * FPS)
}

// Convert frames to seconds
export function framesToSeconds(frames: number): number {
  return frames / FPS
}

/**
 * Scene timing configuration - PRE-LAUNCH VERSION
 * PACING PRINCIPLE: Slow enough to read, absorb, and feel impact
 * Each text element needs ~2s minimum visibility
 */
export const sceneTiming = {
  // Scene 1: Hook - FULL STORY ARC (0-15s)
  // 15 SECONDS: Dirty scene (3s) → CTA sticker (1s) → Phone claim (3s) →
  // Phone away (1s) → Slide transition (2.5s) → Clean scene (1.5s) →
  // Phone $100 paid (2s) → Community celebration (2s)
  hook: {
    start: 0,
    end: 15,
    duration: 15,
    frames: secondsToFrames(15),
  },

  // Scene 2-3: App Demo - Sign up flow (15-22s)
  // Extended: slower form filling, time to read each field
  problem: {
    start: 15,
    end: 19,
    duration: 4,
    frames: secondsToFrames(4),
  },
  statistics: {
    start: 19,
    end: 22,
    duration: 3,
    frames: secondsToFrames(3),
  },

  // Scene 4: Question - What if cleaning paid? (22-27s)
  // Extended: question needs dwell time, $100 reveal needs impact
  question: {
    start: 22,
    end: 27,
    duration: 5,
    frames: secondsToFrames(5),
  },

  // Scene 5-6: Worker Journey - Claim block & Clean (27-38s)
  // Extended: show the process with breathing room
  workerIntro: {
    start: 27,
    end: 32,
    duration: 5,
    frames: secondsToFrames(5),
  },
  theClean: {
    start: 32,
    end: 38,
    duration: 6,
    frames: secondsToFrames(6),
  },

  // Scene 7: Payment - Get paid instantly (38-43s)
  // Extended: money reveal needs impact time
  payment: {
    start: 38,
    end: 43,
    duration: 5,
    frames: secondsToFrames(5),
  },

  // Scene 9: Sponsor Side - Your $100 employs a neighbor (43-49s)
  // Extended: benefits need time to be read
  sponsorSide: {
    start: 43,
    end: 49,
    duration: 6,
    frames: secondsToFrames(6),
  },

  // Scene 12: CTA - Start Earning / Fund a Street (49-55s)
  // Extended: CTAs need prominence and urgency time
  ctaWorkers: {
    start: 49,
    end: 55,
    duration: 6,
    frames: secondsToFrames(6),
  },

  // Scene 13: Logo & Tagline (55-60s)
  // Extended: brand close needs memorable dwell
  logoTagline: {
    start: 55,
    end: 60,
    duration: 5,
    frames: secondsToFrames(5),
  },

  // REMOVED FOR PRE-LAUNCH (showed past metrics):
  // - impact: "847 blocks cleaned"
  // - testimonial: "GCC Worker since 2024"
  // - statsAnimation: "Our Impact So Far"
}

// Total duration - extended hook tells full story
export const TOTAL_DURATION_SECONDS = 60
export const TOTAL_FRAMES = secondsToFrames(TOTAL_DURATION_SECONDS)

// Video formats
export const formats = {
  portrait: {
    width: 1080,
    height: 1920,
    aspectRatio: '9:16',
    fps: FPS,
    durationInFrames: TOTAL_FRAMES,
    description: 'TikTok, Instagram Reels, YouTube Shorts',
  },
  landscape: {
    width: 1920,
    height: 1080,
    aspectRatio: '16:9',
    fps: FPS,
    durationInFrames: TOTAL_FRAMES,
    description: 'YouTube, LinkedIn, Website',
  },
  square: {
    width: 1080,
    height: 1080,
    aspectRatio: '1:1',
    fps: FPS,
    durationInFrames: TOTAL_FRAMES,
    description: 'Instagram Feed, Facebook',
  },
}

export default sceneTiming
