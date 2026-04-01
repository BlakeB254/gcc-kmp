import React from 'react'
import { AbsoluteFill, interpolate, useCurrentFrame, spring, useVideoConfig, Img, staticFile } from 'remotion'
import colors from '../../lib/colors'
import { fonts } from '../../lib/fonts'
import { fontSizes, VideoFormat } from '../../lib/typography'

interface Scene13Props {
  format: VideoFormat
}

export const Scene13_LogoTagline: React.FC<Scene13Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  // SLOWER, more memorable logo reveal
  const logoScale = spring({
    frame,
    fps,
    config: { damping: 100, stiffness: 50, mass: 1 }, // Slower, heavier
  })

  // SLOWER tagline and CTA reveals with more dwell time
  const taglineOpacity = interpolate(frame, [35, 60], [0, 1], { extrapolateRight: 'clamp' })
  const ctaOpacity = interpolate(frame, [70, 95], [0, 1], { extrapolateRight: 'clamp' })

  const isPortrait = format === 'portrait'
  const fs = fontSizes[format]

  return (
    <AbsoluteFill
      style={{
        background: `linear-gradient(135deg, ${colors.green[800]} 0%, ${colors.green[900]} 100%)`,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: isPortrait ? 60 : 80,
      }}
    >
      {/* Logo - Real logo image with HEAVY ZOOM for impact */}
      <div
        style={{
          transform: `scale(${logoScale})`,
          marginBottom: isPortrait ? 60 : 50,
        }}
      >
        <div
          style={{
            width: isPortrait ? 320 : 240,
            height: isPortrait ? 320 : 240,
            borderRadius: 60,
            backgroundColor: 'white',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 30px 80px rgba(0,0,0,0.4), 0 0 80px rgba(22, 163, 74, 0.3)',
            overflow: 'hidden',
            padding: 24,
          }}
        >
          <Img
            src={staticFile('assets/gcc-logo.png')}
            style={{
              width: '100%',
              height: '100%',
              objectFit: 'contain',
            }}
          />
        </div>
      </div>

      {/* Tagline */}
      <div
        style={{
          textAlign: 'center',
          opacity: taglineOpacity,
        }}
      >
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.display,
            fontWeight: 400,
            color: 'white',
            marginBottom: 12,
            textTransform: 'uppercase',
            letterSpacing: 4,
          }}
        >
          Clean Streets.
        </div>
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.display,
            fontWeight: 400,
            color: colors.gold[400],
            textTransform: 'uppercase',
            letterSpacing: 4,
          }}
        >
          Real Money.
        </div>
      </div>

      {/* CTA - LARGER buttons for impact */}
      <div
        style={{
          marginTop: isPortrait ? 70 : 50,
          opacity: ctaOpacity,
          display: 'flex',
          flexDirection: isPortrait ? 'column' : 'row',
          gap: 24,
        }}
      >
        <div
          style={{
            fontFamily: fonts.body,
            backgroundColor: colors.gold[500],
            color: 'white',
            padding: '22px 52px',
            borderRadius: 18,
            fontSize: fs.h3,
            fontWeight: 700,
            boxShadow: `0 8px 30px ${colors.gold[500]}50`,
          }}
        >
          Start Earning Today
        </div>
        <div
          style={{
            fontFamily: fonts.body,
            backgroundColor: 'transparent',
            color: 'white',
            padding: '22px 52px',
            borderRadius: 18,
            fontSize: fs.h3,
            fontWeight: 700,
            border: '3px solid white',
          }}
        >
          Sponsor a Block
        </div>
      </div>

      {/* Website */}
      <div
        style={{
          marginTop: isPortrait ? 40 : 30,
          opacity: ctaOpacity,
          fontFamily: fonts.body,
          fontSize: fs.body,
          fontWeight: 500,
          color: colors.green[300],
        }}
      >
        globalcommunitycleaners.org
      </div>
    </AbsoluteFill>
  )
}

export default Scene13_LogoTagline
