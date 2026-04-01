import React from 'react'
import {
  AbsoluteFill,
  interpolate,
  useCurrentFrame,
  spring,
  useVideoConfig,
  Easing,
} from 'remotion'
import { FadeIn } from '../animations/FadeIn'
import colors from '../../lib/colors'
import { fonts } from '../../lib/fonts'
import { fontSizes, VideoFormat } from '../../lib/typography'

interface Scene05Props {
  format: VideoFormat
}

/**
 * Join Page UI - Large phone frame, normal content scale
 * Phone frame is big on screen but content inside is readable and not cut off
 */
const SuperZoomedJoinUI: React.FC<{
  frame: number
  fps: number
  format: VideoFormat
}> = ({ frame, fps, format }) => {
  const isPortrait = format === 'portrait'

  // Normal scale - content fits inside phone without cut-off
  const contentScale = 1.0

  // Subtle camera movement for visual interest (much smaller offsets)
  // With normal scale, we just do gentle floating movement
  const cameraX = interpolate(
    frame,
    [0, 60, 120],
    [0, 0, 0],
    { extrapolateRight: 'clamp' }
  )

  // Gentle vertical drift as user progresses through form
  const cameraY = interpolate(
    frame,
    [0, 30, 70, 110],
    [0, 0, -3, -5],
    {
      extrapolateRight: 'clamp',
      easing: Easing.inOut(Easing.cubic),
    }
  )

  // Typing animation (synced to camera staying on email field)
  const typingProgress = interpolate(frame, [35, 75], [0, 1], { extrapolateRight: 'clamp' })
  const email = 'john@email.com'
  const typedChars = Math.floor(typingProgress * email.length)
  const displayEmail = email.slice(0, typedChars)
  const showCursor = frame % 20 < 10 && typingProgress < 1

  // Submit button animation
  const submitProgress = spring({
    frame: frame - 108,
    fps,
    config: { damping: 100, stiffness: 60 },
  })

  // Active field highlight
  const emailFieldActive = frame >= 25 && frame < 85
  const submitActive = frame >= 105

  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        overflow: 'hidden',
        position: 'relative',
        borderRadius: isPortrait ? 50 : 40,
        backgroundColor: '#111827',
      }}
    >
      {/* Super-scaled content with camera panning */}
      <div
        style={{
          position: 'absolute',
          top: '50%',
          left: '50%',
          transform: `translate(-50%, -50%) translate(${cameraX}%, ${cameraY}%) scale(${contentScale})`,
          width: '100%',
          height: '100%',
          transformOrigin: 'center center',
        }}
      >
        {/* Phone content at full size, camera moves around it */}
        <div
          style={{
            width: '100%',
            height: '100%',
            background: 'linear-gradient(to bottom, #1a1a1a 0%, #111827 100%)',
            display: 'flex',
            flexDirection: 'column',
            padding: 16,
            paddingTop: 45,
          }}
        >
          {/* Header */}
          <div style={{ textAlign: 'center', marginBottom: 16 }}>
            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                padding: '6px 12px',
                backgroundColor: 'rgba(212, 160, 23, 0.15)',
                borderRadius: 16,
                border: '2px solid rgba(212, 160, 23, 0.3)',
                marginBottom: 10,
              }}
            >
              <span style={{ fontSize: 11, color: colors.gold[400], fontWeight: 600 }}>
                ✨ Join the Movement
              </span>
            </div>

            <div
              style={{
                fontSize: 18,
                fontWeight: 700,
                color: 'white',
                marginBottom: 4,
                lineHeight: 1.2,
              }}
            >
              Secure Your Position
            </div>

            <div style={{ fontSize: 11, color: '#9ca3af' }}>Get early access</div>
          </div>

          {/* Step Indicator */}
          <div
            style={{
              display: 'flex',
              justifyContent: 'center',
              gap: 8,
              marginBottom: 16,
            }}
          >
            {[1, 2, 3].map((step) => (
              <div
                key={step}
                style={{
                  width: 8,
                  height: 8,
                  borderRadius: 4,
                  backgroundColor: step === 1 ? colors.gold[500] : '#374151',
                  boxShadow: step === 1 ? `0 0 8px ${colors.gold[500]}` : 'none',
                }}
              />
            ))}
          </div>

          {/* Form */}
          <div style={{ flex: 1 }}>
            {/* Email Field - Highlighted when active */}
            <div style={{ marginBottom: 14 }}>
              <label
                style={{
                  fontSize: 10,
                  color: emailFieldActive ? colors.gold[400] : '#9ca3af',
                  marginBottom: 6,
                  display: 'block',
                  fontWeight: 600,
                  transition: 'color 0.3s',
                }}
              >
                Email Address
              </label>
              <div
                style={{
                  backgroundColor: '#1f2937',
                  borderRadius: 10,
                  border: emailFieldActive
                    ? `2px solid ${colors.gold[500]}`
                    : '2px solid #374151',
                  padding: '12px 14px',
                  display: 'flex',
                  alignItems: 'center',
                  boxShadow: emailFieldActive
                    ? `0 0 20px rgba(212, 160, 23, 0.3), inset 0 0 10px rgba(212, 160, 23, 0.1)`
                    : 'none',
                  transition: 'all 0.3s',
                }}
              >
                <span
                  style={{
                    fontSize: 13,
                    color: 'white',
                    fontFamily: 'monospace',
                    fontWeight: 500,
                  }}
                >
                  {displayEmail}
                  {showCursor && (
                    <span
                      style={{
                        color: colors.gold[400],
                        fontWeight: 700,
                        marginLeft: 1,
                      }}
                    >
                      |
                    </span>
                  )}
                  {typingProgress === 0 && (
                    <span style={{ color: '#6b7280' }}>Type your email</span>
                  )}
                </span>
              </div>
            </div>

            {/* Country Selector */}
            <div style={{ marginBottom: 18 }}>
              <label
                style={{
                  fontSize: 10,
                  color: '#9ca3af',
                  marginBottom: 6,
                  display: 'block',
                  fontWeight: 600,
                }}
              >
                Country
              </label>
              <div
                style={{
                  backgroundColor: '#1f2937',
                  borderRadius: 10,
                  border: '2px solid #374151',
                  padding: '12px 14px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <span style={{ fontSize: 16 }}>🇺🇸</span>
                  <span style={{ fontSize: 12, color: 'white', fontWeight: 500 }}>
                    United States
                  </span>
                </div>
                <span style={{ color: '#6b7280', fontSize: 10 }}>▼</span>
              </div>
            </div>

            {/* Submit Button */}
            <div
              style={{
                backgroundColor: submitActive ? colors.green[500] : colors.gold[500],
                borderRadius: 12,
                padding: '14px 20px',
                textAlign: 'center',
                transform: `scale(${interpolate(submitProgress, [0, 1], [1, 0.95])})`,
                boxShadow: submitActive
                  ? `0 0 30px ${colors.green[500]}80`
                  : `0 0 ${15 + Math.sin(frame * 0.15) * 8}px rgba(212, 160, 23, 0.4)`,
                transition: 'background-color 0.3s',
              }}
            >
              <span
                style={{
                  fontSize: 13,
                  fontWeight: 700,
                  color: submitActive ? 'white' : '#1a1a1a',
                }}
              >
                {submitProgress > 0.5 ? '✓ Joined!' : 'Continue →'}
              </span>
            </div>
          </div>

          {/* Ranking Preview */}
          <div
            style={{
              marginTop: 14,
              padding: 12,
              backgroundColor: 'rgba(22, 163, 74, 0.15)',
              borderRadius: 12,
              border: '2px solid rgba(22, 163, 74, 0.3)',
            }}
          >
            <div
              style={{
                fontSize: 9,
                color: colors.green[400],
                marginBottom: 8,
                fontWeight: 600,
                textTransform: 'uppercase',
                letterSpacing: 1,
              }}
            >
              ⚡ Estimated Position
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-around' }}>
              <div style={{ textAlign: 'center' }}>
                <span
                  style={{
                    fontSize: 18,
                    fontWeight: 700,
                    color: 'white',
                    display: 'block',
                  }}
                >
                  #147
                </span>
                <div style={{ fontSize: 9, color: '#9ca3af' }}>🌍 Global</div>
              </div>
              <div style={{ textAlign: 'center' }}>
                <span
                  style={{
                    fontSize: 18,
                    fontWeight: 700,
                    color: 'white',
                    display: 'block',
                  }}
                >
                  #23
                </span>
                <div style={{ fontSize: 9, color: '#9ca3af' }}>📍 State</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Subtle vignette overlay */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background:
            'radial-gradient(ellipse at center, transparent 60%, rgba(0,0,0,0.3) 100%)',
          pointerEvents: 'none',
          borderRadius: isPortrait ? 50 : 40,
        }}
      />
    </div>
  )
}

/**
 * Success Page UI - Large phone frame, normal content scale
 */
const SuperZoomedSuccessUI: React.FC<{
  frame: number
  fps: number
  format: VideoFormat
}> = ({ frame, fps, format }) => {
  const isPortrait = format === 'portrait'

  // Normal scale - content fits inside phone without cut-off
  const contentScale = 1.0

  // Gentle camera movement for visual interest
  const cameraX = interpolate(
    frame,
    [0, 60],
    [0, 0],
    { extrapolateRight: 'clamp' }
  )

  // Subtle vertical drift as rankings reveal
  const cameraY = interpolate(
    frame,
    [0, 30, 60],
    [0, -2, -4],
    { extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic) }
  )

  // Staggered ranking animations
  const globalRankProgress = spring({
    frame: frame - 25,
    fps,
    config: { damping: 100, stiffness: 60 },
  })
  const usaRankProgress = spring({
    frame: frame - 35,
    fps,
    config: { damping: 100, stiffness: 60 },
  })
  const stateRankProgress = spring({
    frame: frame - 45,
    fps,
    config: { damping: 100, stiffness: 60 },
  })

  const rankings = [
    {
      label: 'Global',
      rank: 147,
      emoji: '🌍',
      progress: globalRankProgress,
      color: '#f59e0b',
    },
    {
      label: 'USA',
      rank: 89,
      emoji: '🇺🇸',
      progress: usaRankProgress,
      color: '#3b82f6',
    },
    {
      label: 'Illinois',
      rank: 23,
      emoji: '📍',
      progress: stateRankProgress,
      color: '#10b981',
    },
  ]

  // Confetti
  const showConfetti = frame < 45
  const confettiPieces = Array.from({ length: 20 }, (_, i) => ({
    x: Math.sin(i * 0.4) * 80 + 50,
    y: interpolate(frame, [0, 45], [-10, 120 + (i % 5) * 15]),
    rotation: frame * (1.5 + (i % 2)) * (i % 2 === 0 ? 1 : -1),
    color: ['#16763a', '#16a34a', '#0ea5e9', '#f59e0b', '#ec4899'][i % 5],
    size: 8 + (i % 4) * 3,
  }))

  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        overflow: 'hidden',
        position: 'relative',
        borderRadius: isPortrait ? 50 : 40,
        backgroundColor: '#111827',
      }}
    >
      {/* Confetti layer (above content) */}
      {showConfetti &&
        confettiPieces.map((piece, i) => (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: `${piece.x}%`,
              top: piece.y,
              width: piece.size,
              height: piece.size,
              backgroundColor: piece.color,
              transform: `rotate(${piece.rotation}deg)`,
              borderRadius: i % 2 === 0 ? '50%' : 2,
              opacity: interpolate(frame, [35, 45], [1, 0], {
                extrapolateRight: 'clamp',
              }),
              zIndex: 10,
            }}
          />
        ))}

      {/* Super-scaled content with camera panning */}
      <div
        style={{
          position: 'absolute',
          top: '50%',
          left: '50%',
          transform: `translate(-50%, -50%) translate(${cameraX}%, ${cameraY}%) scale(${contentScale})`,
          width: '100%',
          height: '100%',
          transformOrigin: 'center center',
        }}
      >
        <div
          style={{
            width: '100%',
            height: '100%',
            background: 'linear-gradient(to bottom, #1a1a1a 0%, #111827 100%)',
            display: 'flex',
            flexDirection: 'column',
            padding: 16,
            paddingTop: 45,
          }}
        >
          {/* Header */}
          <div style={{ textAlign: 'center', marginBottom: 12 }}>
            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '6px 12px',
                backgroundColor: 'rgba(22, 163, 74, 0.15)',
                borderRadius: 14,
                border: '2px solid rgba(22, 163, 74, 0.3)',
                marginBottom: 8,
              }}
            >
              <div
                style={{
                  width: 6,
                  height: 6,
                  borderRadius: 3,
                  backgroundColor: colors.green[500],
                  boxShadow: `0 0 8px ${colors.green[500]}`,
                }}
              />
              <span style={{ fontSize: 10, color: colors.green[400], fontWeight: 600 }}>
                Position Locked
              </span>
            </div>

            <div
              style={{
                fontSize: 16,
                fontWeight: 700,
                color: 'white',
                lineHeight: 1.2,
              }}
            >
              Welcome to the
            </div>
            <div
              style={{
                fontSize: 18,
                fontWeight: 700,
                color: colors.gold[400],
              }}
            >
              Movement! 🎉
            </div>
          </div>

          {/* Tier Badge */}
          <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 14 }}>
            <div
              style={{
                padding: '10px 20px',
                background: 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)',
                borderRadius: 14,
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                boxShadow: '0 4px 20px rgba(245, 158, 11, 0.4)',
              }}
            >
              <span style={{ fontSize: 14 }}>👑</span>
              <span style={{ fontSize: 12, fontWeight: 700, color: 'white' }}>
                FOUNDER TIER
              </span>
              <span style={{ fontSize: 14 }}>👑</span>
            </div>
          </div>

          {/* Rankings */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {rankings.map((r) => (
              <div
                key={r.label}
                style={{
                  backgroundColor: `${r.color}15`,
                  borderRadius: 10,
                  border: `2px solid ${r.color}30`,
                  padding: '10px 14px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  transform: `translateX(${interpolate(r.progress, [0, 1], [-30, 0])}px)`,
                  opacity: r.progress,
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <div
                    style={{
                      width: 32,
                      height: 32,
                      borderRadius: 8,
                      backgroundColor: `${r.color}20`,
                      border: `2px solid ${r.color}40`,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    <span style={{ fontSize: 14 }}>{r.emoji}</span>
                  </div>
                  <span style={{ color: 'white', fontSize: 12, fontWeight: 600 }}>
                    {r.label}
                  </span>
                </div>
                <span
                  style={{
                    fontSize: 18,
                    fontWeight: 700,
                    color: r.color,
                  }}
                >
                  #{r.rank}
                </span>
              </div>
            ))}
          </div>

          {/* Referral Code */}
          <div
            style={{
              marginTop: 'auto',
              padding: 12,
              backgroundColor: 'rgba(212, 160, 23, 0.15)',
              borderRadius: 10,
              border: '2px solid rgba(212, 160, 23, 0.3)',
            }}
          >
            <div
              style={{
                fontSize: 9,
                color: '#9ca3af',
                marginBottom: 6,
                fontWeight: 600,
                textTransform: 'uppercase',
                letterSpacing: 1,
              }}
            >
              Your Referral Code
            </div>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <span
                style={{
                  fontSize: 14,
                  fontWeight: 700,
                  color: colors.gold[400],
                  fontFamily: 'monospace',
                  letterSpacing: 2,
                }}
              >
                JOHND147
              </span>
              <div
                style={{
                  padding: '6px 12px',
                  backgroundColor: colors.gold[500],
                  borderRadius: 8,
                  color: '#1a1a1a',
                  fontSize: 10,
                  fontWeight: 700,
                }}
              >
                📋 Copy
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Vignette overlay */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background:
            'radial-gradient(ellipse at center, transparent 60%, rgba(0,0,0,0.3) 100%)',
          pointerEvents: 'none',
          borderRadius: isPortrait ? 50 : 40,
        }}
      />
    </div>
  )
}

/**
 * Super-sized phone frame for the zoomed UIs
 */
const SuperPhoneFrame: React.FC<{
  children: React.ReactNode
  isPortrait: boolean
  opacity: number
  entryDelay: number
  frame: number
  fps: number
}> = ({ children, isPortrait, opacity, entryDelay, frame, fps }) => {
  const entryProgress = spring({
    frame: frame - entryDelay,
    fps,
    config: { damping: 100, stiffness: 50, mass: 1.2 },
  })

  // EXTRA LARGE phone frame - dominates viewport
  const phoneWidth = isPortrait ? 480 : 400
  const phoneHeight = isPortrait ? 960 : 780

  return (
    <div
      style={{
        opacity: opacity * entryProgress,
        transform: `translateY(${(1 - entryProgress) * 50}px) scale(${0.8 + entryProgress * 0.2})`,
      }}
    >
      {/* Phone frame */}
      <div
        style={{
          width: phoneWidth,
          height: phoneHeight,
          backgroundColor: '#1a1a1a',
          borderRadius: isPortrait ? 55 : 45,
          padding: 10,
          boxShadow: `
            0 40px 100px rgba(0, 0, 0, 0.6),
            0 0 0 2px rgba(255, 255, 255, 0.1),
            inset 0 0 30px rgba(0, 0, 0, 0.3),
            0 0 60px ${colors.gold[500]}30
          `,
          position: 'relative',
        }}
      >
        {/* Screen */}
        <div
          style={{
            width: '100%',
            height: '100%',
            borderRadius: isPortrait ? 50 : 40,
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
            top: 18,
            left: '50%',
            transform: 'translateX(-50%)',
            width: isPortrait ? 100 : 80,
            height: 28,
            backgroundColor: '#1a1a1a',
            borderRadius: 14,
            zIndex: 20,
          }}
        />
      </div>
    </div>
  )
}

export const Scene05_AppDemo: React.FC<Scene05Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const isPortrait = format === 'portrait'

  // Scene is 120 frames (4 seconds for sign-up) + transitions
  // Join page: frames 0-110, Success page: frames 90-120 (overlap for crossfade)
  const showJoinPage = frame < 115
  const joinPageOpacity = interpolate(frame, [0, 15, 95, 115], [0, 1, 1, 0], {
    extrapolateRight: 'clamp',
  })
  const successPageOpacity = interpolate(frame, [105, 120, 200, 210], [0, 1, 1, 0.8], {
    extrapolateRight: 'clamp',
  })

  return (
    <AbsoluteFill
      style={{
        background: `linear-gradient(135deg, ${colors.green[900]} 0%, #111827 50%, ${colors.brown[900]} 100%)`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexDirection: isPortrait ? 'column' : 'row',
        padding: 40,
        gap: 40,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* Animated background orbs */}
      <div
        style={{
          position: 'absolute',
          top: '5%',
          left: '5%',
          width: 400,
          height: 400,
          background: `radial-gradient(circle, ${colors.gold[500]}20 0%, transparent 60%)`,
          borderRadius: '50%',
          transform: `translate(${Math.sin(frame * 0.02) * 20}px, ${Math.cos(frame * 0.02) * 20}px)`,
          filter: 'blur(40px)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          bottom: '10%',
          right: '10%',
          width: 350,
          height: 350,
          background: `radial-gradient(circle, ${colors.green[500]}20 0%, transparent 60%)`,
          borderRadius: '50%',
          transform: `translate(${Math.cos(frame * 0.025) * 25}px, ${Math.sin(frame * 0.025) * 25}px)`,
          filter: 'blur(40px)',
        }}
      />

      {/* Text content (landscape only) */}
      {!isPortrait && (
        <FadeIn delay={10} direction="left">
          <div style={{ maxWidth: 450, zIndex: 5 }}>
            <div
              style={{
                fontFamily: fonts.heading,
                fontSize: fontSizes[format].h1,
                fontWeight: 400,
                color: 'white',
                marginBottom: 16,
                lineHeight: 1.2,
                textTransform: 'uppercase',
                letterSpacing: 2,
              }}
            >
              Join the
              <br />
              <span style={{ color: colors.gold[400] }}>Global Movement</span>
            </div>
            <div
              style={{
                fontFamily: fonts.body,
                fontSize: fontSizes[format].body,
                color: '#9ca3af',
                lineHeight: 1.6,
              }}
            >
              Secure your position worldwide.
              <br />
              Your rank is locked in forever.
            </div>
          </div>
        </FadeIn>
      )}

      {/* Phone with Join Page - SUPER ZOOMED */}
      {showJoinPage && joinPageOpacity > 0 && (
        <div
          style={{
            position: 'absolute',
            transform: isPortrait ? 'translateY(0)' : 'translateX(25%)',
            zIndex: 10,
          }}
        >
          <SuperPhoneFrame
            isPortrait={isPortrait}
            opacity={joinPageOpacity}
            entryDelay={8}
            frame={frame}
            fps={fps}
          >
            <SuperZoomedJoinUI frame={Math.max(0, frame - 12)} fps={fps} format={format} />
          </SuperPhoneFrame>
        </div>
      )}

      {/* Phone with Success Page - SUPER ZOOMED */}
      {successPageOpacity > 0 && (
        <div
          style={{
            position: 'absolute',
            transform: isPortrait ? 'translateY(0)' : 'translateX(25%)',
            zIndex: 15,
          }}
        >
          <SuperPhoneFrame
            isPortrait={isPortrait}
            opacity={successPageOpacity}
            entryDelay={105}
            frame={frame}
            fps={fps}
          >
            <SuperZoomedSuccessUI
              frame={Math.max(0, frame - 110)}
              fps={fps}
              format={format}
            />
          </SuperPhoneFrame>
        </div>
      )}

      {/* Title for portrait mode */}
      {isPortrait && (
        <div
          style={{
            position: 'absolute',
            top: 50,
            left: 0,
            right: 0,
            textAlign: 'center',
            zIndex: 5,
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
              Join the Movement
            </div>
          </FadeIn>
        </div>
      )}

      {/* CTA text at bottom for portrait */}
      {isPortrait && (
        <div
          style={{
            position: 'absolute',
            bottom: 60,
            left: 0,
            right: 0,
            textAlign: 'center',
            zIndex: 5,
          }}
        >
          <FadeIn delay={15} direction="up">
            <div
              style={{
                fontFamily: fonts.body,
                fontSize: fontSizes[format].bodyLarge,
                color: colors.gold[400],
                fontWeight: 600,
              }}
            >
              Secure your rank now →
            </div>
          </FadeIn>
        </div>
      )}
    </AbsoluteFill>
  )
}

export default Scene05_AppDemo
