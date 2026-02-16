import React from 'react'
import {
  AbsoluteFill,
  interpolate,
  useCurrentFrame,
  spring,
  useVideoConfig,
  Easing,
} from 'remotion'
import { PhoneMockup } from '../animations/PhoneMockup'
import { FadeIn } from '../animations/FadeIn'
import colors from '../../lib/colors'
import { fonts } from '../../lib/fonts'
import { fontSizes, phoneFontSizes, VideoFormat } from '../../lib/typography'
import { beatToFrame, BEAT_DURATION_FRAMES } from '../../lib/audio'

interface Scene07Props {
  format: VideoFormat
}

/**
 * Payment Success Mockup - SLOWER PACING for impact
 * Scene duration: 150 frames (5 seconds)
 */
const PaymentSuccessMockup: React.FC<{ frame: number; fps: number; format: VideoFormat }> = ({
  frame,
  fps,
  format,
}) => {
  const fs = phoneFontSizes[format]

  // Animation phases - SLOWER timing
  const checkmarkProgress = spring({
    frame,
    fps,
    config: { damping: 100, stiffness: 60, mass: 1 }, // Slower, more weight
  })

  const amountReveal = spring({
    frame: frame - 25, // Later reveal
    fps,
    config: { damping: 100, stiffness: 80 }, // Slower
  })

  const detailsReveal = interpolate(frame, [50, 75], [0, 1], { extrapolateRight: 'clamp' }) // Later

  // Flash effect on $100 reveal - SLOWER
  const flashProgress = frame > 25 && frame < 45
  const flashOpacity = flashProgress ? interpolate(frame, [25, 35, 45], [0, 0.6, 0]) : 0

  // Shake effect on amount - SLOWER
  const shakeX = frame > 25 && frame < 50 ? Math.sin(frame * 0.6) * (50 - frame) * 0.4 : 0

  // Ripple effects - SLOWER expansion
  const ripple1 = interpolate(frame, [0, 50], [0, 1], {
    extrapolateRight: 'clamp',
    easing: Easing.out(Easing.quad),
  })
  const ripple2 = interpolate(frame, [12, 62], [0, 1], {
    extrapolateRight: 'clamp',
    easing: Easing.out(Easing.quad),
  })
  const ripple3 = interpolate(frame, [24, 74], [0, 1], {
    extrapolateRight: 'clamp',
    easing: Easing.out(Easing.quad),
  })

  // Confetti particles - SLOWER fall
  const confettiParticles = Array.from({ length: 25 }, (_, i) => ({
    x: 50 + Math.cos(i * 0.5) * 45,
    y: interpolate(
      frame,
      [15, 120], // Longer animation
      [35, 35 + Math.sin(i) * 50 + i * 3],
      { extrapolateRight: 'clamp' }
    ),
    rotation: frame * (1.5 + (i % 3)) * (i % 2 === 0 ? 1 : -1), // Slower rotation
    opacity: interpolate(frame, [15, 35, 100, 120], [0, 1, 1, 0], { extrapolateRight: 'clamp' }),
    color: ['#d4a017', '#16a34a', '#0ea5e9', '#f59e0b', '#10b981'][i % 5],
    size: 10 + (i % 4) * 4, // Larger sizes: 10-22px
    delay: i * 0.5,
  }))

  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        background: 'linear-gradient(180deg, #111827 0%, #1f2937 100%)',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* Flash overlay for beat sync */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          backgroundColor: colors.gold[400],
          opacity: flashOpacity,
          pointerEvents: 'none',
        }}
      />

      {/* Ripple effects */}
      {[ripple1, ripple2, ripple3].map((ripple, i) => (
        <div
          key={i}
          style={{
            position: 'absolute',
            top: '32%',
            left: '50%',
            width: 280,
            height: 280,
            borderRadius: '50%',
            border: `3px solid ${colors.green[500]}`,
            transform: `translate(-50%, -50%) scale(${ripple * (1 + i * 0.3)})`,
            opacity: 1 - ripple,
          }}
        />
      ))}

      {/* Confetti - larger */}
      {confettiParticles.map((p, i) => (
        <div
          key={i}
          style={{
            position: 'absolute',
            left: `${p.x}%`,
            top: `${p.y}%`,
            width: p.size,
            height: p.size,
            backgroundColor: p.color,
            borderRadius: i % 3 === 0 ? '50%' : 3,
            transform: `rotate(${p.rotation}deg)`,
            opacity: p.opacity,
          }}
        />
      ))}

      {/* Success checkmark - larger */}
      <div
        style={{
          width: 110,
          height: 110,
          borderRadius: 55,
          backgroundColor: colors.green[500],
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          transform: `scale(${checkmarkProgress})`,
          marginBottom: 20,
          boxShadow: `0 0 50px ${colors.green[500]}70`,
        }}
      >
        <svg
          viewBox="0 0 24 24"
          width={55}
          height={55}
          style={{
            strokeDasharray: 100,
            strokeDashoffset: interpolate(checkmarkProgress, [0, 1], [100, 0]),
          }}
        >
          <path
            d="M5 13l4 4L19 7"
            fill="none"
            stroke="white"
            strokeWidth={3.5}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </div>

      {/* Title */}
      <div
        style={{
          fontSize: fs.title,
          fontWeight: 700,
          color: 'white',
          marginBottom: 6,
          transform: `translateY(${interpolate(amountReveal, [0, 1], [15, 0])}px)`,
          opacity: amountReveal,
        }}
      >
        Payment Sent!
      </div>

      {/* Amount - larger with shake effect */}
      <div
        style={{
          fontFamily: fonts.heading,
          fontSize: 72, // Extra large
          fontWeight: 800,
          color: colors.gold[400],
          transform: `scale(${interpolate(amountReveal, [0, 1], [0.7, 1])}) translateX(${shakeX}px)`,
          opacity: amountReveal,
          textShadow: `0 4px 30px ${colors.gold[500]}50`,
          marginBottom: 6,
          letterSpacing: 2,
        }}
      >
        $100
      </div>

      <div
        style={{
          fontSize: fs.body,
          color: colors.green[400],
          opacity: detailsReveal,
          marginBottom: 24,
        }}
      >
        Deposited to your bank account
      </div>

      {/* Transaction details card */}
      <div
        style={{
          backgroundColor: 'rgba(255, 255, 255, 0.05)',
          borderRadius: 16,
          padding: 18,
          width: '85%',
          opacity: detailsReveal,
          transform: `translateY(${interpolate(detailsReveal, [0, 1], [15, 0])}px)`,
        }}
      >
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            marginBottom: 10,
            paddingBottom: 10,
            borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
          }}
        >
          <span style={{ fontSize: fs.caption, color: '#9ca3af' }}>Block Cleaned</span>
          <span style={{ fontSize: fs.caption, color: 'white', fontWeight: 500 }}>
            W. Roosevelt Rd
          </span>
        </div>

        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            marginBottom: 10,
            paddingBottom: 10,
            borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
          }}
        >
          <span style={{ fontSize: fs.caption, color: '#9ca3af' }}>Time</span>
          <span style={{ fontSize: fs.caption, color: 'white', fontWeight: 500 }}>42 minutes</span>
        </div>

        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <span style={{ fontSize: fs.caption, color: '#9ca3af' }}>Transfer Speed</span>
          <span style={{ fontSize: fs.caption, color: colors.green[400], fontWeight: 500 }}>
            Instant ⚡
          </span>
        </div>
      </div>

      {/* Time indicator - SLOWER reveal */}
      <div
        style={{
          position: 'absolute',
          bottom: 90,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          opacity: interpolate(frame, [80, 100], [0, 1], { extrapolateRight: 'clamp' }),
        }}
      >
        <span style={{ fontSize: 28 }}>⚡</span>
        <span style={{ fontSize: fs.body, color: '#9ca3af' }}>
          Arrived in{' '}
          <span style={{ color: colors.gold[400], fontWeight: 700 }}>30 seconds</span>
        </span>
      </div>
    </div>
  )
}

/**
 * Money Animation Background Component - Larger emoji rain
 */
const MoneyRain: React.FC<{ frame: number }> = ({ frame }) => {
  const bills = Array.from({ length: 20 }, (_, i) => ({
    x: 5 + (i * 5) % 100,
    startY: -30 - (i * 25),
    rotation: Math.sin(frame * 0.05 + i) * 25,
    delay: i * 4,
    size: 48 + (i % 3) * 16, // Larger: 48-80px
  }))

  return (
    <>
      {bills.map((bill, i) => {
        const y = interpolate(
          frame - bill.delay,
          [0, 100],
          [bill.startY, 110],
          { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }
        )
        const opacity = interpolate(
          frame - bill.delay,
          [0, 15, 85, 100],
          [0, 0.4, 0.4, 0],
          { extrapolateRight: 'clamp' }
        )

        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: `${bill.x}%`,
              top: `${y}%`,
              transform: `rotate(${bill.rotation}deg)`,
              opacity,
              fontSize: bill.size,
            }}
          >
            💵
          </div>
        )
      })}
    </>
  )
}

export const Scene07_Payment: React.FC<Scene07Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const isPortrait = format === 'portrait'
  // HEAVY ZOOM - make phone dominate the screen for readability
  const phoneScale = isPortrait ? 1.2 : 1.0

  return (
    <AbsoluteFill
      style={{
        background: `linear-gradient(135deg, ${colors.green[900]} 0%, #111827 50%, ${colors.gold[900]}40 100%)`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexDirection: isPortrait ? 'column' : 'row',
        padding: 40,
        gap: 60,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* Money rain background - larger emoji */}
      <MoneyRain frame={frame} />

      {/* Glowing orb decorations */}
      <div
        style={{
          position: 'absolute',
          top: '10%',
          left: '20%',
          width: 350,
          height: 350,
          background: `radial-gradient(circle, ${colors.gold[500]}25 0%, transparent 70%)`,
          borderRadius: '50%',
          filter: 'blur(50px)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          bottom: '20%',
          right: '10%',
          width: 300,
          height: 300,
          background: `radial-gradient(circle, ${colors.green[500]}25 0%, transparent 70%)`,
          borderRadius: '50%',
          filter: 'blur(50px)',
        }}
      />

      {/* Title for portrait */}
      {isPortrait && (
        <div
          style={{
            position: 'absolute',
            top: 40,
            left: 0,
            right: 0,
            textAlign: 'center',
          }}
        >
          <FadeIn delay={0}>
            <div
              style={{
                fontFamily: fonts.heading,
                fontSize: fontSizes[format].h1,
                fontWeight: 400,
                color: 'white',
                textTransform: 'uppercase',
                letterSpacing: 2,
              }}
            >
              Get Paid <span style={{ color: colors.gold[400] }}>Instantly</span>
            </div>
          </FadeIn>
        </div>
      )}

      {/* Text for landscape */}
      {!isPortrait && (
        <FadeIn delay={5} direction="left">
          <div style={{ maxWidth: 450, zIndex: 10 }}>
            <div
              style={{
                fontFamily: fonts.heading,
                fontSize: fontSizes[format].h1,
                fontWeight: 400,
                color: 'white',
                marginBottom: 20,
                lineHeight: 1.1,
                textTransform: 'uppercase',
                letterSpacing: 2,
              }}
            >
              $100 in your
              <br />
              <span style={{ color: colors.gold[400] }}>pocket. Fast.</span>
            </div>
            <div
              style={{
                fontFamily: fonts.body,
                fontSize: fontSizes[format].body,
                color: '#9ca3af',
                lineHeight: 1.6,
                marginBottom: 24,
              }}
            >
              AI reviews your work in seconds.
              <br />
              Money hits your bank instantly.
            </div>

            {/* Stats */}
            <div style={{ display: 'flex', gap: 40 }}>
              <div>
                <div
                  style={{
                    fontFamily: fonts.heading,
                    fontSize: fontSizes[format].h2,
                    fontWeight: 400,
                    color: colors.green[400],
                  }}
                >
                  &lt;30s
                </div>
                <div style={{ fontSize: fontSizes[format].caption, color: '#6b7280' }}>
                  AI Review Time
                </div>
              </div>
              <div>
                <div
                  style={{
                    fontFamily: fonts.heading,
                    fontSize: fontSizes[format].h2,
                    fontWeight: 400,
                    color: colors.gold[400],
                  }}
                >
                  Instant
                </div>
                <div style={{ fontSize: fontSizes[format].caption, color: '#6b7280' }}>
                  Bank Transfer
                </div>
              </div>
            </div>
          </div>
        </FadeIn>
      )}

      {/* Phone mockup - SLOWER entrance */}
      <div style={{ zIndex: 10 }}>
        <PhoneMockup scale={phoneScale} animateIn={true} delay={12}>
          <PaymentSuccessMockup frame={Math.max(0, frame - 18)} fps={fps} format={format} />
        </PhoneMockup>
      </div>

      {/* Bottom text for portrait - SLOWER fade */}
      {isPortrait && (
        <div
          style={{
            position: 'absolute',
            bottom: 50,
            left: 0,
            right: 0,
            textAlign: 'center',
          }}
        >
          <FadeIn delay={55} direction="up">
            <div
              style={{
                fontFamily: fonts.body,
                fontSize: fontSizes[format].body,
                color: '#9ca3af',
              }}
            >
              AI-verified • Direct deposit • No waiting
            </div>
          </FadeIn>
        </div>
      )}
    </AbsoluteFill>
  )
}

export default Scene07_Payment
