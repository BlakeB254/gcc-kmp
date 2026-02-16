/**
 * Typography system for GCC Remotion Ad
 *
 * Provides responsive font sizes and text styles optimized for
 * different video formats (portrait, landscape, square)
 */

import { fonts, fontStacks } from './fonts'

export type VideoFormat = 'portrait' | 'landscape' | 'square'

/**
 * Font size scale for each format
 * Sizes are optimized for readability at each aspect ratio
 */
export const fontSizes = {
  portrait: {
    // Display sizes for maximum impact
    hero: 96,       // Main hook text - DIRTY/CLEAN labels
    display: 72,    // Big reveals, $100 amount
    h1: 56,         // Scene titles
    h2: 40,         // Subtitles, secondary headers
    h3: 32,         // Section labels
    body: 24,       // Body text, descriptions
    bodyLarge: 28,  // Emphasized body text
    caption: 18,    // Small text, timestamps
    label: 16,      // Form labels, micro text
  },
  landscape: {
    hero: 80,
    display: 64,
    h1: 48,
    h2: 36,
    h3: 28,
    body: 20,
    bodyLarge: 24,
    caption: 16,
    label: 14,
  },
  square: {
    hero: 72,
    display: 56,
    h1: 44,
    h2: 32,
    h3: 26,
    body: 20,
    bodyLarge: 24,
    caption: 16,
    label: 14,
  },
}

/**
 * Phone mockup interior font sizes
 * Larger to compensate for the phone scale factor
 */
export const phoneFontSizes = {
  portrait: {
    title: 32,
    subtitle: 24,
    body: 20,
    label: 16,
    caption: 14,
    button: 20,
    stat: 24,
  },
  landscape: {
    title: 28,
    subtitle: 20,
    body: 18,
    label: 14,
    caption: 12,
    button: 18,
    stat: 22,
  },
  square: {
    title: 28,
    subtitle: 20,
    body: 18,
    label: 14,
    caption: 12,
    button: 18,
    stat: 22,
  },
}

/**
 * Text style presets for common use cases
 */
export const textStyles = {
  // Hero text - main hook, big reveals
  heroText: (format: VideoFormat) => ({
    fontFamily: fontStacks.heading,
    fontSize: fontSizes[format].hero,
    fontWeight: 400, // Bebas Neue is already bold
    lineHeight: 1.0,
    letterSpacing: 2,
    textTransform: 'uppercase' as const,
  }),

  // Display numbers - $100, stats
  displayNumber: (format: VideoFormat) => ({
    fontFamily: fontStacks.heading,
    fontSize: fontSizes[format].display,
    fontWeight: 400,
    lineHeight: 1.0,
    letterSpacing: 1,
  }),

  // Section titles
  title: (format: VideoFormat) => ({
    fontFamily: fontStacks.heading,
    fontSize: fontSizes[format].h1,
    fontWeight: 400,
    lineHeight: 1.1,
    letterSpacing: 1,
    textTransform: 'uppercase' as const,
  }),

  // Subtitles
  subtitle: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: fontSizes[format].h2,
    fontWeight: 600,
    lineHeight: 1.3,
  }),

  // Body text
  body: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: fontSizes[format].body,
    fontWeight: 400,
    lineHeight: 1.5,
  }),

  // Emphasized body
  bodyLarge: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: fontSizes[format].bodyLarge,
    fontWeight: 500,
    lineHeight: 1.4,
  }),

  // Captions and small text
  caption: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: fontSizes[format].caption,
    fontWeight: 400,
    lineHeight: 1.4,
  }),

  // Form labels
  label: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: fontSizes[format].label,
    fontWeight: 500,
    lineHeight: 1.3,
    textTransform: 'uppercase' as const,
    letterSpacing: 0.5,
  }),

  // CTA buttons
  button: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: fontSizes[format].body,
    fontWeight: 700,
    lineHeight: 1.2,
  }),

  // Statistic numbers
  stat: (format: VideoFormat) => ({
    fontFamily: fontStacks.heading,
    fontSize: fontSizes[format].h2,
    fontWeight: 400,
    lineHeight: 1.0,
  }),
}

/**
 * Phone interior text styles (scaled up for readability)
 */
export const phoneTextStyles = {
  title: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: phoneFontSizes[format].title,
    fontWeight: 700,
    lineHeight: 1.2,
  }),

  subtitle: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: phoneFontSizes[format].subtitle,
    fontWeight: 500,
    lineHeight: 1.3,
  }),

  body: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: phoneFontSizes[format].body,
    fontWeight: 400,
    lineHeight: 1.4,
  }),

  label: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: phoneFontSizes[format].label,
    fontWeight: 500,
    lineHeight: 1.3,
  }),

  caption: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: phoneFontSizes[format].caption,
    fontWeight: 400,
    lineHeight: 1.3,
  }),

  button: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: phoneFontSizes[format].button,
    fontWeight: 600,
    lineHeight: 1.2,
  }),

  stat: (format: VideoFormat) => ({
    fontFamily: fontStacks.body,
    fontSize: phoneFontSizes[format].stat,
    fontWeight: 700,
    lineHeight: 1.0,
  }),
}

export default textStyles
