import React from 'react'
import {
  AbsoluteFill,
  Img,
  Video,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
  staticFile,
  spring,
  Easing,
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
  opacity?: number
}> = ({ src, zoomStart = 1.0, zoomEnd = 1.15, panX = 0, panY = 0, opacity = 1 }) => {
  const frame = useCurrentFrame()
  const { durationInFrames } = useVideoConfig()

  const progress = interpolate(frame, [0, durationInFrames], [0, 1], {
    extrapolateRight: 'clamp',
  })

  const scale = interpolate(progress, [0, 1], [zoomStart, zoomEnd])
  const translateX = interpolate(progress, [0, 1], [0, panX])
  const translateY = interpolate(progress, [0, 1], [0, panY])

  return (
    <div style={{ position: 'absolute', inset: 0, opacity, overflow: 'hidden' }}>
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
 * Phone UI - Shows block claim card with large phone frame, normal content scale
 * NO MAP - Just a clean block detail card
 */
const SuperZoomedClaimUI: React.FC<{
  frame: number
  fps: number
  isPortrait: boolean
  phase: 'claim' | 'claimed'
}> = ({ frame, fps, isPortrait, phase }) => {
  // Normal content scale - no zooming that causes cut-off
  const contentScale = 1.0

  // Subtle camera drift for visual interest
  const cameraY = interpolate(
    frame,
    [0, 40, 80],
    [0, -2, -3],
    { extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic) }
  )

  const currentFocus = { x: 0, y: cameraY }

  // Claim button press
  const buttonPressed = phase === 'claim' && frame > 75
  const buttonScale = buttonPressed ? 0.95 : 1
  const buttonGlow = 15 + Math.sin(frame * 0.15) * 10

  return (
    <div
      style={{
        position: 'absolute',
        width: '100%',
        height: '100%',
        overflow: 'hidden',
        borderRadius: 40,
        background: 'linear-gradient(180deg, #111827 0%, #1f2937 100%)',
      }}
    >
      {/* Zoomed content container */}
      <div
        style={{
          position: 'absolute',
          width: '100%',
          height: '100%',
          transform: `scale(${contentScale}) translate(${currentFocus.x}%, ${currentFocus.y}%)`,
          transformOrigin: 'center center',
          display: 'flex',
          flexDirection: 'column',
          padding: 20,
          paddingTop: 50,
        }}
      >
        {phase === 'claim' ? (
          <>
            {/* Header badge */}
            <div style={{ textAlign: 'center', marginBottom: 16 }}>
              <div
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '8px 16px',
                  backgroundColor: 'rgba(212, 160, 23, 0.15)',
                  borderRadius: 20,
                  border: '2px solid rgba(212, 160, 23, 0.3)',
                }}
              >
                <span style={{ fontSize: 14 }}>🧹</span>
                <span style={{ fontSize: 12, color: colors.gold[400], fontWeight: 600 }}>
                  Available Now
                </span>
              </div>
            </div>

            {/* Block title */}
            <div style={{ textAlign: 'center', marginBottom: 20 }}>
              <div style={{ fontSize: 18, fontWeight: 700, color: 'white', marginBottom: 4 }}>
                W. Roosevelt Rd Block
              </div>
              <div style={{ fontSize: 11, color: '#9ca3af' }}>
                3800-3899 W. Roosevelt Road
              </div>
            </div>

            {/* Stats row */}
            <div
              style={{
                display: 'flex',
                justifyContent: 'center',
                gap: 24,
                marginBottom: 20,
              }}
            >
              <div
                style={{
                  backgroundColor: 'rgba(212, 160, 23, 0.1)',
                  borderRadius: 14,
                  padding: '14px 20px',
                  textAlign: 'center',
                  border: '2px solid rgba(212, 160, 23, 0.2)',
                }}
              >
                <div style={{ fontSize: 9, color: '#9ca3af', marginBottom: 4, fontWeight: 600 }}>
                  PAYOUT
                </div>
                <div style={{ fontSize: 24, fontWeight: 700, color: colors.gold[400] }}>$100</div>
              </div>
              <div
                style={{
                  backgroundColor: 'rgba(255, 255, 255, 0.05)',
                  borderRadius: 14,
                  padding: '14px 20px',
                  textAlign: 'center',
                  border: '2px solid rgba(255, 255, 255, 0.1)',
                }}
              >
                <div style={{ fontSize: 9, color: '#9ca3af', marginBottom: 4, fontWeight: 600 }}>
                  SESSION
                </div>
                <div style={{ fontSize: 24, fontWeight: 700, color: 'white' }}>2-4 hrs</div>
              </div>
            </div>

            {/* Claim button */}
            <div
              style={{
                backgroundColor: colors.gold[500],
                borderRadius: 14,
                padding: '16px 24px',
                textAlign: 'center',
                transform: `scale(${buttonScale})`,
                boxShadow: buttonPressed
                  ? `0 0 40px ${colors.gold[500]}`
                  : `0 0 ${buttonGlow}px rgba(212, 160, 23, 0.4)`,
                transition: 'box-shadow 0.2s',
              }}
            >
              <span style={{ fontSize: 15, fontWeight: 700, color: '#1a1a1a' }}>
                {buttonPressed ? '⏳ Claiming...' : '🧹 Claim This Block'}
              </span>
            </div>
          </>
        ) : (
          /* Success state */
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              flex: 1,
            }}
          >
            <div
              style={{
                width: 56,
                height: 56,
                borderRadius: 28,
                backgroundColor: colors.green[500],
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                marginBottom: 16,
                boxShadow: `0 0 30px ${colors.green[500]}80`,
              }}
            >
              <span style={{ fontSize: 28, color: 'white' }}>✓</span>
            </div>
            <div style={{ fontSize: 18, fontWeight: 700, color: 'white', marginBottom: 6 }}>
              Block Claimed!
            </div>
            <div style={{ fontSize: 12, color: colors.green[400] }}>24 hours to complete</div>
          </div>
        )}
      </div>

      {/* Vignette overlay */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background: 'radial-gradient(ellipse at center, transparent 50%, rgba(0,0,0,0.3) 100%)',
          pointerEvents: 'none',
        }}
      />
    </div>
  )
}

/**
 * Payment Success UI - Large phone frame, normal content scale
 */
const SuperZoomedPaymentUI: React.FC<{
  frame: number
  fps: number
  isPortrait: boolean
}> = ({ frame, fps, isPortrait }) => {
  // Normal content scale - no zooming that causes cut-off
  const contentScale = 1.0

  // Checkmark animation
  const checkProgress = spring({
    frame,
    fps,
    config: { damping: 80, stiffness: 100 },
  })

  // Amount reveal
  const amountProgress = spring({
    frame: frame - 15,
    fps,
    config: { damping: 80, stiffness: 80 },
  })

  // Glow pulse
  const glowPulse = 1 + Math.sin(frame * 0.1) * 0.3

  // Confetti
  const confetti = Array.from({ length: 20 }, (_, i) => ({
    x: 50 + Math.cos(i * 0.8) * 40,
    y: interpolate(frame, [10, 90], [30, 70 + (i % 5) * 8], { extrapolateRight: 'clamp' }),
    rotation: frame * (2 + (i % 3)),
    color: ['#d4a017', '#16a34a', '#0ea5e9', '#f59e0b'][i % 4],
    opacity: interpolate(frame, [10, 25, 70, 90], [0, 1, 1, 0], { extrapolateRight: 'clamp' }),
  }))

  return (
    <div
      style={{
        position: 'absolute',
        width: '100%',
        height: '100%',
        overflow: 'hidden',
        borderRadius: 40,
        background: 'linear-gradient(180deg, #111827 0%, #1f2937 100%)',
      }}
    >
      {/* Zoomed content */}
      <div
        style={{
          position: 'absolute',
          width: '100%',
          height: '100%',
          transform: `scale(${contentScale})`,
          transformOrigin: 'center center',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {/* Confetti */}
        {confetti.map((c, i) => (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: `${c.x}%`,
              top: `${c.y}%`,
              width: 8,
              height: 8,
              backgroundColor: c.color,
              transform: `rotate(${c.rotation}deg)`,
              opacity: c.opacity,
              borderRadius: i % 2 === 0 ? '50%' : 2,
            }}
          />
        ))}

        {/* Success checkmark */}
        <div
          style={{
            width: 60,
            height: 60,
            borderRadius: 30,
            backgroundColor: colors.green[500],
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            transform: `scale(${checkProgress})`,
            boxShadow: `0 0 ${40 * glowPulse}px ${colors.green[500]}80`,
            marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 32, color: 'white' }}>✓</span>
        </div>

        {/* Amount */}
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: 48,
            fontWeight: 700,
            color: colors.gold[400],
            transform: `scale(${amountProgress})`,
            opacity: amountProgress,
            textShadow: `0 0 ${30 * glowPulse}px ${colors.gold[500]}80`,
          }}
        >
          +$100
        </div>

        <div
          style={{
            fontSize: 12,
            color: colors.green[400],
            marginTop: 6,
            opacity: amountProgress,
          }}
        >
          Deposited instantly
        </div>
      </div>

      {/* Vignette */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background: 'radial-gradient(ellipse at center, transparent 30%, rgba(0,0,0,0.5) 100%)',
          pointerEvents: 'none',
        }}
      />
    </div>
  )
}

/**
 * CTA Sticker that pops up
 */
const CTASticker: React.FC<{
  frame: number
  fps: number
  isPortrait: boolean
  visible: boolean
}> = ({ frame, fps, isPortrait, visible }) => {
  const popProgress = spring({
    frame: visible ? frame : 0,
    fps,
    config: { damping: 60, stiffness: 150 },
  })

  const wobble = Math.sin(frame * 0.2) * 3

  if (!visible) return null

  return (
    <div
      style={{
        position: 'absolute',
        top: isPortrait ? '20%' : '25%',
        left: '50%',
        transform: `translateX(-50%) scale(${popProgress}) rotate(${wobble}deg)`,
        zIndex: 30,
      }}
    >
      <div
        style={{
          backgroundColor: colors.gold[500],
          padding: '16px 32px',
          borderRadius: 20,
          boxShadow: '0 8px 30px rgba(0,0,0,0.4)',
          border: '4px solid white',
        }}
      >
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: isPortrait ? 32 : 28,
            fontWeight: 700,
            color: 'white',
            textAlign: 'center',
            textShadow: '2px 2px 4px rgba(0,0,0,0.3)',
          }}
        >
          CLAIM THIS BLOCK!
        </div>
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: isPortrait ? 18 : 16,
            color: 'rgba(255,255,255,0.9)',
            textAlign: 'center',
            marginTop: 4,
          }}
        >
          Earn $100 • 2-4 hour session
        </div>
      </div>
    </div>
  )
}

/**
 * Phone frame that slides in/out
 */
const SlidingPhone: React.FC<{
  children: React.ReactNode
  frame: number
  fps: number
  isPortrait: boolean
  slideIn: boolean
  slideInStart: number
  slideOutStart?: number
}> = ({ children, frame, fps, isPortrait, slideIn, slideInStart, slideOutStart }) => {
  // Slide in animation
  const slideInProgress = spring({
    frame: frame - slideInStart,
    fps,
    config: { damping: 80, stiffness: 60 },
  })

  // Slide out animation (if specified)
  let slideOutProgress = 0
  if (slideOutStart && frame >= slideOutStart) {
    slideOutProgress = spring({
      frame: frame - slideOutStart,
      fps,
      config: { damping: 100, stiffness: 80 },
    })
  }

  const yOffset = slideIn
    ? interpolate(slideInProgress, [0, 1], [800, 0]) + interpolate(slideOutProgress, [0, 1], [0, 800])
    : 800

  const opacity = interpolate(slideInProgress, [0, 0.3], [0, 1], { extrapolateRight: 'clamp' })
    * interpolate(slideOutProgress, [0, 0.5], [1, 0], { extrapolateRight: 'clamp' })

  // EXTRA LARGE phone frame - dominates viewport
  const phoneWidth = isPortrait ? 520 : 420
  const phoneHeight = isPortrait ? 1040 : 820

  return (
    <div
      style={{
        position: 'absolute',
        bottom: -60,
        left: '50%',
        transform: `translateX(-50%) translateY(${yOffset}px)`,
        opacity,
        zIndex: 25,
      }}
    >
      {/* Phone frame */}
      <div
        style={{
          width: phoneWidth,
          height: phoneHeight,
          backgroundColor: '#0a0a0a',
          borderRadius: 56,
          padding: 12,
          boxShadow: '0 40px 100px rgba(0,0,0,0.7)',
          border: '6px solid #222',
        }}
      >
        {/* Screen */}
        <div
          style={{
            width: '100%',
            height: '100%',
            borderRadius: 44,
            overflow: 'hidden',
            position: 'relative',
          }}
        >
          {children}
        </div>

        {/* Notch */}
        <div
          style={{
            position: 'absolute',
            top: 20,
            left: '50%',
            transform: 'translateX(-50%)',
            width: 140,
            height: 36,
            backgroundColor: '#0a0a0a',
            borderRadius: 20,
          }}
        />
      </div>
    </div>
  )
}

/**
 * Slide transition between scenes - Shows the after image wiping in
 */
const SlideTransition: React.FC<{
  frame: number
  startFrame: number
  duration: number
  direction?: 'left' | 'right'
  imageSrc?: string
}> = ({ frame, startFrame, duration, direction = 'left', imageSrc }) => {
  const progress = interpolate(frame, [startFrame, startFrame + duration], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.inOut(Easing.cubic),
  })

  const xOffset = direction === 'left' ? -100 * progress : 100 * progress

  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        transform: `translateX(${100 + xOffset}%)`,
        zIndex: 15,
        overflow: 'hidden',
      }}
    >
      {imageSrc ? (
        <>
          <Img
            src={imageSrc}
            style={{
              width: '100%',
              height: '100%',
              objectFit: 'cover',
            }}
          />
          {/* Light gradient overlay for smooth transition */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background: 'linear-gradient(180deg, rgba(0,0,0,0.3) 0%, rgba(0,0,0,0.1) 50%, rgba(0,0,0,0.4) 100%)',
            }}
          />
        </>
      ) : (
        <div
          style={{
            width: '100%',
            height: '100%',
            backgroundColor: colors.green[900],
          }}
        />
      )}
    </div>
  )
}

/**
 * Scene01_VideoHook - FULL 15-SECOND STORY ARC
 *
 * Timeline (450 frames = 15 seconds at 30fps):
 * - 0-90 (0-3s): Before/dirty scene with "DIRTY" label
 * - 60-120 (2-4s): CTA sticker pops up
 * - 90-180 (3-6s): Phone slides up, super-zoomed claim UI
 * - 150-210 (5-7s): Claim button pressed, success animation
 * - 200-240 (6.7-8s): Phone slides away
 * - 220-300 (7.3-10s): Slide transition to clean scene
 * - 280-360 (9.3-12s): Clean scene with "CLEAN" label
 * - 320-400 (10.7-13.3s): Phone slides up with $100 paid
 * - 380-450 (12.7-15s): Community celebration scene
 */
export const Scene01_VideoHook: React.FC<Scene01Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const fs = fontSizes[format]
  const isPortrait = format === 'portrait'

  // === PHASE CALCULATIONS ===

  // Before scene (0-300)
  const beforeOpacity = interpolate(frame, [220, 300], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  // After/clean scene (280-380)
  const afterOpacity = interpolate(frame, [280, 320], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  // Celebration scene (380-450)
  const celebrationOpacity = interpolate(frame, [360, 400], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  // DIRTY label (0-90)
  const dirtyOpacity = interpolate(frame, [0, 30, 70, 100], [0, 1, 1, 0], {
    extrapolateRight: 'clamp',
  })

  // CTA Sticker (60-180)
  const showCTASticker = frame >= 60 && frame < 180

  // Phone #1 - Claim flow (90-240)
  const showPhone1 = frame >= 90 && frame < 240
  const phone1Phase = frame < 180 ? 'claim' : 'claimed'

  // Phone #2 - Payment (320-450)
  const showPhone2 = frame >= 320

  // CLEAN label (300-380)
  const cleanOpacity = interpolate(frame, [300, 330, 360, 390], [0, 1, 1, 0], {
    extrapolateRight: 'clamp',
  })

  // Celebration text (400-450)
  const celebrationTextOpacity = interpolate(frame, [400, 430], [0, 1], {
    extrapolateRight: 'clamp',
  })

  return (
    <AbsoluteFill style={{ backgroundColor: '#000' }}>
      {/* Before/dirty street */}
      {beforeOpacity > 0 && (
        <div style={{ position: 'absolute', inset: 0, opacity: beforeOpacity }}>
          <KenBurnsImage
            src={staticFile('assets/before-street.jpg')}
            zoomStart={1.0}
            zoomEnd={1.1}
            panX={-15}
            panY={-10}
          />
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background: 'linear-gradient(180deg, rgba(0,0,0,0.6) 0%, rgba(0,0,0,0.2) 40%, rgba(0,0,0,0.2) 60%, rgba(0,0,0,0.7) 100%)',
            }}
          />
        </div>
      )}

      {/* After/clean street - VIDEO of clean street */}
      {afterOpacity > 0 && celebrationOpacity < 1 && (
        <div style={{ position: 'absolute', inset: 0, opacity: afterOpacity * (1 - celebrationOpacity) }}>
          <Img
            src={staticFile('assets/after-street.jpg')}
            style={{
              width: '100%',
              height: '100%',
              objectFit: 'cover',
            }}
          />
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background: 'linear-gradient(180deg, rgba(0,0,0,0.4) 0%, rgba(0,0,0,0.1) 40%, rgba(0,0,0,0.1) 60%, rgba(0,0,0,0.5) 100%)',
            }}
          />
        </div>
      )}

      {/* Community celebration scene - VIDEO of residents waving/high-fiving */}
      {celebrationOpacity > 0 && (
        <div style={{ position: 'absolute', inset: 0, opacity: celebrationOpacity }}>
          <Img
            src={staticFile('assets/community-celebration.jpg')}
            style={{
              width: '100%',
              height: '100%',
              objectFit: 'cover',
            }}
          />
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background: 'linear-gradient(180deg, rgba(0,0,0,0.3) 0%, rgba(0,0,0,0.1) 50%, rgba(0,0,0,0.4) 100%)',
            }}
          />
        </div>
      )}

      {/* Slide transition - wipes to after-street image */}
      <SlideTransition
        frame={frame}
        startFrame={220}
        duration={80}
        direction="left"
        imageSrc={staticFile('assets/after-street.jpg')}
      />

      {/* DIRTY label */}
      <div
        style={{
          position: 'absolute',
          top: isPortrait ? '18%' : '22%',
          left: '50%',
          transform: 'translate(-50%, -50%)',
          opacity: dirtyOpacity,
          textAlign: 'center',
          zIndex: 10,
        }}
      >
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.hero,
            fontWeight: 400,
            color: colors.brown[200],
            letterSpacing: 12,
            textShadow: '6px 6px 30px rgba(0,0,0,0.9)',
            textTransform: 'uppercase',
          }}
        >
          DIRTY
        </div>
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: fs.h2,
            color: 'rgba(255,255,255,0.9)',
            marginTop: 12,
            textShadow: '3px 3px 15px rgba(0,0,0,0.9)',
          }}
        >
          Our streets need help
        </div>
      </div>

      {/* CTA Sticker */}
      <CTASticker
        frame={frame - 60}
        fps={fps}
        isPortrait={isPortrait}
        visible={showCTASticker}
      />

      {/* Phone #1 - Claim flow with SUPER ZOOM */}
      {showPhone1 && (
        <SlidingPhone
          frame={frame}
          fps={fps}
          isPortrait={isPortrait}
          slideIn={true}
          slideInStart={90}
          slideOutStart={200}
        >
          <SuperZoomedClaimUI
            frame={frame - 90}
            fps={fps}
            isPortrait={isPortrait}
            phase={phone1Phase as 'claim' | 'claimed'}
          />
        </SlidingPhone>
      )}

      {/* CLEAN label */}
      <div
        style={{
          position: 'absolute',
          top: isPortrait ? '18%' : '22%',
          left: '50%',
          transform: 'translate(-50%, -50%)',
          opacity: cleanOpacity,
          textAlign: 'center',
          zIndex: 10,
        }}
      >
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.hero,
            fontWeight: 400,
            color: colors.green[400],
            letterSpacing: 12,
            textShadow: `6px 6px 30px rgba(0,0,0,0.7), 0 0 60px ${colors.green[500]}50`,
            textTransform: 'uppercase',
          }}
        >
          CLEAN
        </div>
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: fs.h2,
            color: 'rgba(255,255,255,0.95)',
            marginTop: 12,
            textShadow: '3px 3px 15px rgba(0,0,0,0.7)',
          }}
        >
          Community pride restored
        </div>
      </div>

      {/* Phone #2 - Payment success with SUPER ZOOM */}
      {showPhone2 && (
        <SlidingPhone
          frame={frame}
          fps={fps}
          isPortrait={isPortrait}
          slideIn={true}
          slideInStart={320}
        >
          <SuperZoomedPaymentUI
            frame={frame - 330}
            fps={fps}
            isPortrait={isPortrait}
          />
        </SlidingPhone>
      )}

      {/* Celebration text overlay */}
      <div
        style={{
          position: 'absolute',
          bottom: isPortrait ? 120 : 80,
          left: 0,
          right: 0,
          textAlign: 'center',
          opacity: celebrationTextOpacity,
          zIndex: 30,
        }}
      >
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.h1,
            fontWeight: 400,
            color: 'white',
            textShadow: '4px 4px 20px rgba(0,0,0,0.8)',
            textTransform: 'uppercase',
            letterSpacing: 4,
          }}
        >
          You clean.{' '}
          <span style={{ color: colors.gold[400] }}>You earn.</span>
        </div>
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: fs.body,
            color: colors.green[300],
            marginTop: 12,
            textShadow: '2px 2px 10px rgba(0,0,0,0.8)',
          }}
        >
          Community thanks you.
        </div>
      </div>
    </AbsoluteFill>
  )
}

export default Scene01_VideoHook
