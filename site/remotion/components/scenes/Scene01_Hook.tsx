import React from 'react'
import {
  AbsoluteFill,
  Img,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
  staticFile,
} from 'remotion'
import colors from '../../lib/colors'
import { fonts } from '../../lib/fonts'
import { fontSizes, VideoFormat } from '../../lib/typography'

interface Scene01Props {
  format: VideoFormat
}

/**
 * Ken Burns effect - slow zoom and pan on images
 */
const KenBurnsImage: React.FC<{
  src: string
  zoomStart?: number
  zoomEnd?: number
  panX?: number
  panY?: number
}> = ({ src, zoomStart = 1.0, zoomEnd = 1.15, panX = 0, panY = 0 }) => {
  const frame = useCurrentFrame()
  const { durationInFrames } = useVideoConfig()

  const progress = frame / durationInFrames
  const scale = interpolate(progress, [0, 1], [zoomStart, zoomEnd], {
    extrapolateRight: 'clamp',
  })
  const translateX = interpolate(progress, [0, 1], [0, panX], {
    extrapolateRight: 'clamp',
  })
  const translateY = interpolate(progress, [0, 1], [0, panY], {
    extrapolateRight: 'clamp',
  })

  return (
    <div
      style={{
        position: 'absolute',
        width: '100%',
        height: '100%',
        overflow: 'hidden',
      }}
    >
      <Img
        src={src}
        style={{
          width: '100%',
          height: '100%',
          objectFit: 'cover',
          transform: `scale(${scale}) translate(${translateX}px, ${translateY}px)`,
        }}
      />
    </div>
  )
}

/**
 * Scene01_Hook - Before/After split screen with real imagery
 * Duration: 2 seconds (60 frames at 30fps)
 *
 * Animation Timeline:
 * - Frame 0-10: DIRTY label fades in (full screen before image)
 * - Frame 15-30: Split animation (divider sweeps right)
 * - Frame 25-35: DIRTY fades out completely
 * - Frame 35-45: CLEAN fades in (only after DIRTY is gone)
 * - Frame 45-55: Bottom tagline appears
 */
export const Scene01_Hook: React.FC<Scene01Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { durationInFrames } = useVideoConfig()

  // Animate divider position (starts at frame 15 to give DIRTY time to show)
  const dividerPosition = interpolate(frame, [15, 35], [0, 50], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  // Before section width animation (starts full, ends at 50%)
  const beforeWidth = interpolate(frame, [15, 35], [100, 50], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  // After section reveal (only visible after split starts)
  const afterOpacity = interpolate(frame, [20, 35], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  // DIRTY label: Appears quickly, then fades out during split
  const dirtyOpacity = interpolate(frame, [0, 8, 25, 35], [0, 1, 1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })
  const dirtyScale = interpolate(frame, [0, 8], [0.7, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  // CLEAN label: Only appears AFTER DIRTY is gone (no overlap)
  const cleanOpacity = interpolate(frame, [38, 48], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })
  const cleanScale = interpolate(frame, [38, 48], [0.7, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  const fs = fontSizes[format]

  return (
    <AbsoluteFill style={{ backgroundColor: '#000' }}>
      {/* Before section (left side) - contains BEFORE image */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: `${beforeWidth}%`,
          height: '100%',
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            width: format === 'portrait' ? '1080px' : format === 'landscape' ? '1920px' : '1080px',
            height: '100%',
          }}
        >
          {/* Ken Burns effect on before street image */}
          <KenBurnsImage
            src={staticFile('assets/before-street.jpg')}
            zoomStart={1.0}
            zoomEnd={1.1}
            panY={-15}
          />

          {/* Dark overlay for text contrast */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background:
                'linear-gradient(180deg, rgba(0,0,0,0.5) 0%, rgba(0,0,0,0.2) 40%, rgba(0,0,0,0.2) 60%, rgba(0,0,0,0.6) 100%)',
            }}
          />
        </div>
      </div>

      {/* After section (right side) - contains AFTER image */}
      <div
        style={{
          position: 'absolute',
          right: 0,
          top: 0,
          width: '50%',
          height: '100%',
          overflow: 'hidden',
          opacity: afterOpacity,
        }}
      >
        <div
          style={{
            position: 'absolute',
            right: 0,
            top: 0,
            width: format === 'portrait' ? '1080px' : format === 'landscape' ? '1920px' : '1080px',
            height: '100%',
          }}
        >
          {/* Ken Burns effect on after street image */}
          <KenBurnsImage
            src={staticFile('assets/after-street.jpg')}
            zoomStart={1.1}
            zoomEnd={1.0}
            panY={15}
          />

          {/* Light overlay for text contrast */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background:
                'linear-gradient(180deg, rgba(0,0,0,0.3) 0%, rgba(0,0,0,0) 40%, rgba(0,0,0,0) 60%, rgba(0,0,0,0.4) 100%)',
            }}
          />
        </div>
      </div>

      {/* DIRTY label - Full screen center, fades out before CLEAN appears */}
      <div
        style={{
          position: 'absolute',
          top: '50%',
          left: '25%', // Center of left half when split
          transform: `translate(-50%, -50%) scale(${dirtyScale})`,
          opacity: dirtyOpacity,
          textAlign: 'center',
          zIndex: 5,
        }}
      >
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.hero,
            fontWeight: 400,
            color: colors.brown[200],
            letterSpacing: 12,
            textShadow: '6px 6px 30px rgba(0,0,0,0.9), 0 0 60px rgba(0,0,0,0.7)',
            textTransform: 'uppercase',
          }}
        >
          DIRTY
        </div>
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: fs.h2,
            fontWeight: 500,
            color: 'rgba(255,255,255,0.9)',
            marginTop: 12,
            textShadow: '3px 3px 15px rgba(0,0,0,0.9)',
          }}
        >
          Neglected streets
        </div>
      </div>

      {/* CLEAN label - Right side center, only appears after DIRTY fades out */}
      <div
        style={{
          position: 'absolute',
          top: '50%',
          left: '75%', // Center of right half when split
          transform: `translate(-50%, -50%) scale(${cleanScale})`,
          opacity: cleanOpacity,
          textAlign: 'center',
          zIndex: 5,
        }}
      >
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.hero,
            fontWeight: 400,
            color: colors.green[400],
            letterSpacing: 12,
            textShadow: '6px 6px 30px rgba(0,0,0,0.7), 0 0 60px rgba(22, 163, 74, 0.5)',
            textTransform: 'uppercase',
          }}
        >
          CLEAN
        </div>
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: fs.h2,
            fontWeight: 500,
            color: 'rgba(255,255,255,0.95)',
            marginTop: 12,
            textShadow: '3px 3px 15px rgba(0,0,0,0.7)',
          }}
        >
          Community pride
        </div>
      </div>

      {/* Animated divider */}
      {dividerPosition > 0 && (
        <div
          style={{
            position: 'absolute',
            left: `${dividerPosition}%`,
            top: 0,
            bottom: 0,
            width: 8,
            transform: 'translateX(-50%)',
            background: `linear-gradient(180deg, ${colors.gold[400]} 0%, ${colors.gold[500]} 50%, ${colors.gold[400]} 100%)`,
            boxShadow: `0 0 25px ${colors.gold[500]}, 0 0 50px rgba(212, 160, 23, 0.6)`,
            zIndex: 10,
          }}
        />
      )}

      {/* Bottom tagline - appears after split completes */}
      {frame > 48 && (
        <div
          style={{
            position: 'absolute',
            bottom: format === 'portrait' ? 100 : 50,
            left: 0,
            right: 0,
            textAlign: 'center',
            opacity: interpolate(frame, [48, 58], [0, 1], { extrapolateRight: 'clamp' }),
            transform: `translateY(${interpolate(frame, [48, 58], [25, 0], { extrapolateRight: 'clamp' })}px)`,
            zIndex: 15,
          }}
        >
          <div
            style={{
              fontFamily: fonts.body,
              fontSize: fs.h3,
              fontWeight: 700,
              color: 'white',
              textShadow: '3px 3px 15px rgba(0,0,0,0.9)',
              padding: '16px 32px',
              backgroundColor: 'rgba(0,0,0,0.5)',
              borderRadius: 40,
              display: 'inline-block',
              border: `2px solid ${colors.gold[500]}40`,
            }}
          >
            What if cleaning <span style={{ color: colors.gold[400] }}>paid?</span>
          </div>
        </div>
      )}
    </AbsoluteFill>
  )
}

export default Scene01_Hook
