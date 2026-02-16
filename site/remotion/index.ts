/**
 * GCC Remotion Advertisement
 *
 * Entry point for Remotion compositions.
 *
 * Compositions:
 * - GCCAd-Portrait (1080x1920) - TikTok, Instagram Reels, YouTube Shorts
 * - GCCAd-Landscape (1920x1080) - YouTube, LinkedIn, Website
 * - GCCAd-Square (1080x1080) - Instagram Feed, Facebook
 *
 * Preview: npx remotion preview remotion/index.ts
 * Render: npx remotion render remotion/index.ts GCCAd-Portrait out/gcc-ad-portrait.mp4
 */

import { registerRoot } from 'remotion'
import { RemotionRoot } from './Root'

registerRoot(RemotionRoot)
