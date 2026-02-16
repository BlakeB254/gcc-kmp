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

interface Scene10Props {
  format: 'portrait' | 'landscape' | 'square'
}

// Typing animation for quote
const TypedQuote: React.FC<{
  text: string
  frame: number
  startFrame: number
  charsPerFrame: number
}> = ({ text, frame, startFrame, charsPerFrame }) => {
  const adjustedFrame = Math.max(0, frame - startFrame)
  const typedChars = Math.min(text.length, Math.floor(adjustedFrame * charsPerFrame))
  const displayText = text.slice(0, typedChars)
  const showCursor = frame % 20 < 10 && typedChars < text.length

  return (
    <span>
      {displayText}
      {showCursor && (
        <span
          style={{
            display: 'inline-block',
            width: 3,
            height: '1em',
            backgroundColor: colors.gold[400],
            marginLeft: 2,
            verticalAlign: 'text-bottom',
          }}
        />
      )}
    </span>
  )
}

// Worker avatar with animation
const WorkerAvatar: React.FC<{ frame: number; fps: number }> = ({ frame, fps }) => {
  const scaleProgress = spring({
    frame,
    fps,
    config: { damping: 80, stiffness: 120 },
  })

  const glowPulse = 0.5 + Math.sin(frame * 0.1) * 0.3

  return (
    <div
      style={{
        position: 'relative',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      {/* Glow ring */}
      <div
        style={{
          position: 'absolute',
          width: 130,
          height: 130,
          borderRadius: '50%',
          background: `radial-gradient(circle, ${colors.green[500]}${Math.round(glowPulse * 40).toString(16).padStart(2, '0')} 0%, transparent 70%)`,
          transform: `scale(${scaleProgress})`,
        }}
      />

      {/* Avatar container */}
      <div
        style={{
          width: 100,
          height: 100,
          borderRadius: '50%',
          background: `linear-gradient(135deg, ${colors.green[600]} 0%, ${colors.green[800]} 100%)`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          transform: `scale(${scaleProgress})`,
          boxShadow: `0 8px 32px ${colors.green[900]}80`,
          border: `3px solid ${colors.green[400]}`,
        }}
      >
        <span style={{ fontSize: 48 }}>👷</span>
      </div>

      {/* Verified badge */}
      <div
        style={{
          position: 'absolute',
          bottom: 0,
          right: 0,
          width: 32,
          height: 32,
          borderRadius: '50%',
          backgroundColor: colors.gold[500],
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: 16,
          transform: `scale(${interpolate(frame, [20, 40], [0, 1], { extrapolateRight: 'clamp' })})`,
          boxShadow: `0 4px 12px ${colors.gold[600]}60`,
        }}
      >
        ✓
      </div>
    </div>
  )
}

// Stats badges for the worker
const WorkerStat: React.FC<{
  value: string
  label: string
  delay: number
  frame: number
  color: string
}> = ({ value, label, delay, frame, color }) => {
  const progress = interpolate(frame - delay, [0, 20], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        padding: '12px 16px',
        backgroundColor: 'rgba(255, 255, 255, 0.05)',
        borderRadius: 12,
        opacity: progress,
        transform: `translateY(${(1 - progress) * 20}px)`,
        border: `1px solid ${color}30`,
      }}
    >
      <div style={{ fontSize: 24, fontWeight: 700, color }}>{value}</div>
      <div style={{ fontSize: 11, color: '#9ca3af', marginTop: 2 }}>{label}</div>
    </div>
  )
}

// Quote marks decoration
const QuoteMark: React.FC<{ side: 'left' | 'right'; opacity: number }> = ({
  side,
  opacity,
}) => (
  <div
    style={{
      fontSize: 120,
      fontFamily: 'Georgia, serif',
      color: colors.gold[500],
      opacity: opacity * 0.2,
      lineHeight: 1,
      position: 'absolute',
      [side]: side === 'left' ? -20 : -20,
      [side === 'left' ? 'top' : 'bottom']: side === 'left' ? -30 : -60,
      transform: side === 'right' ? 'rotate(180deg)' : 'none',
    }}
  >
    "
  </div>
)

export const Scene10_Testimonial: React.FC<Scene10Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const isPortrait = format === 'portrait'

  const quote =
    "This job changed my life. I went from struggling to pay rent to having real income, working in my own neighborhood. I'm not just cleaning streets—I'm building my community."

  const workerName = 'Marcus J.'
  const workerTitle = 'GCC Worker since 2024'

  const quoteOpacity = interpolate(frame, [30, 50], [0, 1], { extrapolateRight: 'clamp' })

  const stats = [
    { value: '47', label: 'Blocks Cleaned', color: colors.green[400] },
    { value: '$4,700', label: 'Earned', color: colors.gold[400] },
    { value: '⭐ 4.9', label: 'Rating', color: colors.sky[400] },
  ]

  return (
    <AbsoluteFill
      style={{
        background: `linear-gradient(180deg, #0f172a 0%, #1e293b 50%, #0f172a 100%)`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexDirection: 'column',
        padding: isPortrait ? 40 : 80,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* Subtle gradient orbs */}
      <div
        style={{
          position: 'absolute',
          top: '20%',
          left: '10%',
          width: 300,
          height: 300,
          background: `radial-gradient(circle, ${colors.green[500]}15 0%, transparent 70%)`,
          filter: 'blur(60px)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          bottom: '20%',
          right: '10%',
          width: 250,
          height: 250,
          background: `radial-gradient(circle, ${colors.gold[500]}15 0%, transparent 70%)`,
          filter: 'blur(60px)',
        }}
      />

      {isPortrait ? (
        // Portrait layout
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 24,
            width: '100%',
            maxWidth: 380,
          }}
        >
          {/* Worker avatar */}
          <WorkerAvatar frame={frame} fps={fps} />

          {/* Worker name and title */}
          <FadeIn delay={25}>
            <div style={{ textAlign: 'center' }}>
              <div style={{ fontSize: 24, fontWeight: 700, color: 'white' }}>
                {workerName}
              </div>
              <div style={{ fontSize: 14, color: colors.green[400], marginTop: 4 }}>
                {workerTitle}
              </div>
            </div>
          </FadeIn>

          {/* Stats */}
          <div style={{ display: 'flex', gap: 12 }}>
            {stats.map((stat, i) => (
              <WorkerStat
                key={stat.label}
                value={stat.value}
                label={stat.label}
                delay={60 + i * 15}
                frame={frame}
                color={stat.color}
              />
            ))}
          </div>

          {/* Quote */}
          <div
            style={{
              position: 'relative',
              marginTop: 16,
              padding: '0 10px',
            }}
          >
            <QuoteMark side="left" opacity={quoteOpacity} />
            <QuoteMark side="right" opacity={quoteOpacity} />
            <div
              style={{
                fontSize: 18,
                lineHeight: 1.6,
                color: 'white',
                textAlign: 'center',
                fontStyle: 'italic',
                opacity: quoteOpacity,
              }}
            >
              <TypedQuote
                text={quote}
                frame={frame}
                startFrame={50}
                charsPerFrame={0.8}
              />
            </div>
          </div>
        </div>
      ) : (
        // Landscape layout
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 60,
            width: '100%',
            maxWidth: 1200,
          }}
        >
          {/* Left side - Avatar and info */}
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 20,
              minWidth: 200,
            }}
          >
            <WorkerAvatar frame={frame} fps={fps} />
            <FadeIn delay={25}>
              <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: 28, fontWeight: 700, color: 'white' }}>
                  {workerName}
                </div>
                <div style={{ fontSize: 16, color: colors.green[400], marginTop: 4 }}>
                  {workerTitle}
                </div>
              </div>
            </FadeIn>
            <div style={{ display: 'flex', gap: 12 }}>
              {stats.map((stat, i) => (
                <WorkerStat
                  key={stat.label}
                  value={stat.value}
                  label={stat.label}
                  delay={60 + i * 15}
                  frame={frame}
                  color={stat.color}
                />
              ))}
            </div>
          </div>

          {/* Right side - Quote */}
          <div
            style={{
              flex: 1,
              position: 'relative',
              padding: '0 40px',
            }}
          >
            <QuoteMark side="left" opacity={quoteOpacity} />
            <QuoteMark side="right" opacity={quoteOpacity} />
            <div
              style={{
                fontSize: 26,
                lineHeight: 1.7,
                color: 'white',
                fontStyle: 'italic',
                opacity: quoteOpacity,
              }}
            >
              <TypedQuote
                text={quote}
                frame={frame}
                startFrame={50}
                charsPerFrame={0.9}
              />
            </div>
          </div>
        </div>
      )}

      {/* Bottom badge */}
      <div
        style={{
          position: 'absolute',
          bottom: isPortrait ? 40 : 30,
          opacity: interpolate(frame, [180, 200], [0, 1], { extrapolateRight: 'clamp' }),
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '8px 16px',
          backgroundColor: 'rgba(255, 255, 255, 0.05)',
          borderRadius: 20,
          border: `1px solid ${colors.green[500]}30`,
        }}
      >
        <span style={{ fontSize: 16 }}>✨</span>
        <span style={{ fontSize: 14, color: '#9ca3af' }}>
          Real stories. Real impact.
        </span>
      </div>
    </AbsoluteFill>
  )
}

export default Scene10_Testimonial
