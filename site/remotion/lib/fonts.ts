/**
 * Font configuration for GCC Remotion Ad
 *
 * Uses Remotion's Google Fonts integration for distinctive typography
 * - Bebas Neue: Bold, condensed display font for headlines and impact text
 * - Inter: Clean, highly readable sans-serif for body text
 */

import { loadFont as loadBebasNeue } from '@remotion/google-fonts/BebasNeue'
import { loadFont as loadInter } from '@remotion/google-fonts/Inter'

// Load fonts with specific weights
const bebasNeue = loadBebasNeue()
const inter = loadInter('normal', {
  weights: ['400', '500', '600', '700', '800', '900'],
})

// Export font families for use throughout the project
export const fonts = {
  heading: bebasNeue.fontFamily,
  body: inter.fontFamily,

  // Specific use cases
  hero: bebasNeue.fontFamily,      // Main hook text, big reveals
  title: bebasNeue.fontFamily,     // Scene titles, section headers
  subtitle: inter.fontFamily,      // Subtitles, descriptions
  label: inter.fontFamily,         // Form labels, small text
  button: inter.fontFamily,        // CTA buttons, interactive elements
  stat: bebasNeue.fontFamily,      // Statistics, numbers
}

// Font stack fallbacks
export const fontStacks = {
  heading: `${fonts.heading}, Impact, "Arial Black", sans-serif`,
  body: `${fonts.body}, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif`,
}

export default fonts
