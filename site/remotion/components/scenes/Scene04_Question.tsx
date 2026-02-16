import React from 'react'
import { AbsoluteFill, interpolate, useCurrentFrame, spring, useVideoConfig, Easing } from 'remotion'
import colors from '../../lib/colors'
import { fonts } from '../../lib/fonts'
import { fontSizes, VideoFormat } from '../../lib/typography'

interface Scene04Props {
  format: VideoFormat
}

/**
 * Floating sparkle/money elements for visual interest
 */
const FloatingElement: React.FC<{
  emoji: string
  x: number
  y: number
  delay: number
  frame: number
  size: number
}> = ({ emoji, x, y, delay, frame, size }) => {
  const float = Math.sin((frame + delay) * 0.04) * 15
  const rotate = Math.sin((frame + delay) * 0.03) * 10
  const opacity = interpolate(frame - delay, [0, 30, 120, 150], [0, 0.6, 0.6, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  return (
    <div
      style={{
        position: 'absolute',
        left: `${x}%`,
        top: `${y}%`,
        fontSize: size,
        transform: `translateY(${float}px) rotate(${rotate}deg)`,
        opacity,
        pointerEvents: 'none',
      }}
    >
      {emoji}
    </div>
  )
}

/**
 * Radiating rings around the $100
 */
const RadiatingRing: React.FC<{
  frame: number
  delay: number
  color: string
}> = ({ frame, delay, color }) => {
  const progress = interpolate(frame - delay, [0, 60], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.out(Easing.quad),
  })

  return (
    <div
      style={{
        position: 'absolute',
        width: 300,
        height: 300,
        borderRadius: '50%',
        border: `4px solid ${color}`,
        transform: `scale(${0.5 + progress * 1.5})`,
        opacity: 1 - progress,
        pointerEvents: 'none',
      }}
    />
  )
}

/**
 * Scene04_Question - The hook question that introduces the value proposition
 * "What if cleaning streets actually paid well?" + $100 reveal
 *
 * Timeline (150 frames = 5 seconds) - DELIBERATE PACING
 * - Frame 0-30: Line 1 fades in slowly ("What if cleaning streets...")
 * - Frame 35-65: Line 2 fades in ("actually paid well?")
 * - Frame 75-105: $100 reveal with spring animation
 * - Frame 110-135: "per block cleaned" appears
 * - Frame 135-150: Hold for impact
 */
export const Scene04_Question: React.FC<Scene04Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const fs = fontSizes[format]
  const isPortrait = format === 'portrait'

  // Text animation - SLOWER fade in with more hold time
  const line1Opacity = interpolate(frame, [0, 25], [0, 1], { extrapolateRight: 'clamp' })
  const line1Y = interpolate(frame, [0, 25], [40, 0], { extrapolateRight: 'clamp' })

  const line2Opacity = interpolate(frame, [35, 60], [0, 1], { extrapolateRight: 'clamp' })
  const line2Y = interpolate(frame, [35, 60], [40, 0], { extrapolateRight: 'clamp' })

  // Money reveal with spring bounce - SLOWER, more dramatic
  const moneyProgress = spring({
    frame: frame - 75,
    fps,
    config: { damping: 80, stiffness: 80, mass: 1 }, // Slower, more weight
  })
  const moneyScale = interpolate(moneyProgress, [0, 1], [0.6, 1])
  const moneyOpacity = interpolate(moneyProgress, [0, 0.5], [0, 1], { extrapolateRight: 'clamp' })

  // Glow pulse on money - SLOWER pulse
  const glowPulse = Math.sin(frame * 0.08) * 0.3 + 0.7

  // Per block text - appears after $100 has impact time
  const perBlockOpacity = interpolate(frame, [110, 130], [0, 1], { extrapolateRight: 'clamp' })
  const perBlockY = interpolate(frame, [110, 130], [25, 0], { extrapolateRight: 'clamp' })

  // Floating decorative elements
  const floatingElements = [
    { emoji: '💵', x: 8, y: 15, delay: 0, size: 48 },
    { emoji: '✨', x: 88, y: 12, delay: 15, size: 40 },
    { emoji: '💰', x: 5, y: 45, delay: 30, size: 52 },
    { emoji: '⭐', x: 92, y: 40, delay: 45, size: 36 },
    { emoji: '💵', x: 10, y: 75, delay: 60, size: 44 },
    { emoji: '✨', x: 85, y: 70, delay: 75, size: 38 },
    { emoji: '💰', x: 15, y: 88, delay: 90, size: 46 },
    { emoji: '⭐', x: 80, y: 85, delay: 105, size: 42 },
  ]

  return (
    <AbsoluteFill
      style={{
        background: `linear-gradient(180deg, ${colors.green[900]} 0%, ${colors.green[800]} 50%, ${colors.green[900]} 100%)`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: isPortrait ? 50 : 80,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* Floating money/sparkle elements for visual interest */}
      {floatingElements.map((el, i) => (
        <FloatingElement
          key={i}
          emoji={el.emoji}
          x={el.x}
          y={el.y}
          delay={el.delay}
          frame={frame}
          size={el.size}
        />
      ))}

      {/* Background glow orbs - LARGER and more intense */}
      <div
        style={{
          position: 'absolute',
          top: '30%',
          left: '20%',
          width: 500,
          height: 500,
          background: `radial-gradient(circle, ${colors.gold[500]}35 0%, transparent 70%)`,
          borderRadius: '50%',
          filter: 'blur(80px)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          bottom: '20%',
          right: '15%',
          width: 450,
          height: 450,
          background: `radial-gradient(circle, ${colors.green[400]}25 0%, transparent 70%)`,
          borderRadius: '50%',
          filter: 'blur(80px)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          top: '50%',
          left: '50%',
          width: 600,
          height: 600,
          background: `radial-gradient(circle, ${colors.gold[400]}15 0%, transparent 60%)`,
          borderRadius: '50%',
          filter: 'blur(100px)',
          transform: 'translate(-50%, -50%)',
        }}
      />

      <div
        style={{
          textAlign: 'center',
          color: 'white',
          maxWidth: isPortrait ? '100%' : 1200,
          zIndex: 10,
          position: 'relative',
        }}
      >
        {/* Question line 1 */}
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: fs.h1,
            fontWeight: 500,
            opacity: line1Opacity,
            transform: `translateY(${line1Y}px)`,
            marginBottom: 16,
            letterSpacing: 1,
          }}
        >
          What if cleaning streets...
        </div>

        {/* Question line 2 */}
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.display,
            fontWeight: 400,
            opacity: line2Opacity,
            transform: `translateY(${line2Y}px)`,
            marginBottom: 50,
            textTransform: 'uppercase',
            letterSpacing: 3,
          }}
        >
          actually <span style={{ color: colors.gold[400] }}>paid well?</span>
        </div>

        {/* Money reveal - MASSIVE ZOOM */}
        <div
          style={{
            position: 'relative',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          {/* Radiating rings */}
          {moneyOpacity > 0 && (
            <>
              <RadiatingRing frame={frame} delay={80} color={`${colors.gold[400]}60`} />
              <RadiatingRing frame={frame} delay={95} color={`${colors.gold[400]}40`} />
              <RadiatingRing frame={frame} delay={110} color={`${colors.gold[400]}20`} />
            </>
          )}

          <div
            style={{
              fontFamily: fonts.heading,
              fontSize: isPortrait ? 220 : 180,
              fontWeight: 400,
              color: colors.gold[400],
              transform: `scale(${moneyScale})`,
              opacity: moneyOpacity,
              textShadow: `
                0 0 ${80 * glowPulse}px ${colors.gold[500]}90,
                0 0 ${120 * glowPulse}px ${colors.gold[500]}60,
                0 8px 40px rgba(0,0,0,0.5)
              `,
              letterSpacing: 6,
            }}
          >
            $100
          </div>
        </div>

        {/* Per block text */}
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: fs.h2,
            marginTop: 20,
            opacity: perBlockOpacity,
            transform: `translateY(${perBlockY}px)`,
            color: colors.green[200],
            fontWeight: 500,
          }}
        >
          per block cleaned
        </div>
      </div>
    </AbsoluteFill>
  )
}

export default Scene04_Question
