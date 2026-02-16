/**
 * Audio configuration for GCC Remotion Ad
 *
 * Provides beat timing for scene synchronization
 * and audio file references
 */

import { FPS } from './timing'

/**
 * Main music configuration
 * BPM = Beats Per Minute (standard for drill: 130-150)
 */
export const MUSIC_CONFIG = {
  src: 'audio/beat.mp3',
  bpm: 140,
  volume: 0.75,
}

/**
 * Calculate frames per beat based on BPM and FPS
 */
export const BEAT_DURATION_SECONDS = 60 / MUSIC_CONFIG.bpm
export const BEAT_DURATION_FRAMES = Math.round(BEAT_DURATION_SECONDS * FPS)

/**
 * Convert beat number to frame number
 */
export function beatToFrame(beat: number): number {
  return Math.round(beat * BEAT_DURATION_FRAMES)
}

/**
 * Convert seconds to nearest beat
 */
export function secondsToBeat(seconds: number): number {
  return Math.round(seconds / BEAT_DURATION_SECONDS)
}

/**
 * Beat markers for scene transitions
 * Major scene changes should align with these beats
 */
export const beatMarkers = {
  // 0s - Hook reveal
  hookReveal: beatToFrame(1),

  // ~1.7s - Split screen swipe
  splitSwipe: beatToFrame(4),

  // ~3.4s - App demo phone entrance
  phoneEntrance: beatToFrame(8),

  // ~6.9s - Question text reveal
  questionReveal: beatToFrame(16),

  // ~10.3s - Worker journey start
  workerStart: beatToFrame(24),

  // ~13.7s - Payment reveal
  paymentReveal: beatToFrame(32),

  // ~17.1s - Impact section
  impactStart: beatToFrame(40),

  // ~20.6s - Sponsor side
  sponsorStart: beatToFrame(48),

  // ~24s - Testimonial
  testimonialStart: beatToFrame(56),

  // ~27.4s - Stats animation
  statsStart: beatToFrame(64),

  // ~30.9s - CTA
  ctaStart: beatToFrame(72),

  // ~34.3s - Logo finale
  logoStart: beatToFrame(80),
}

/**
 * Sound effect configurations
 */
export const SOUND_EFFECTS = {
  whoosh: {
    src: 'audio/whoosh.mp3',
    volume: 0.5,
    duration: 0.3, // seconds
  },
  cash: {
    src: 'audio/cash.mp3',
    volume: 0.6,
    duration: 0.8,
  },
  success: {
    src: 'audio/success.mp3',
    volume: 0.5,
    duration: 1.0,
  },
  typing: {
    src: 'audio/typing.mp3',
    volume: 0.3,
    duration: 2.0,
  },
}

/**
 * Scene audio cues (frame triggers for sound effects)
 */
export const audioCues = {
  // Scene transitions
  transitions: [
    { frame: beatToFrame(4), effect: 'whoosh' },
    { frame: beatToFrame(8), effect: 'whoosh' },
    { frame: beatToFrame(16), effect: 'whoosh' },
    { frame: beatToFrame(24), effect: 'whoosh' },
    { frame: beatToFrame(32), effect: 'whoosh' },
    { frame: beatToFrame(40), effect: 'whoosh' },
    { frame: beatToFrame(48), effect: 'whoosh' },
  ],

  // Payment reveal
  payment: {
    frame: beatToFrame(32) + 15, // Slightly after transition
    effect: 'cash' as const,
  },

  // Success checkmark
  success: {
    frame: beatToFrame(32) + 30,
    effect: 'success' as const,
  },

  // Form typing
  typing: {
    frame: beatToFrame(8) + 10,
    effect: 'typing' as const,
  },
}

export default {
  MUSIC_CONFIG,
  BEAT_DURATION_FRAMES,
  beatToFrame,
  beatMarkers,
  SOUND_EFFECTS,
  audioCues,
}
