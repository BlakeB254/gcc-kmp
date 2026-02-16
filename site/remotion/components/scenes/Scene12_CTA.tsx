import React from 'react'
import {
  AbsoluteFill,
  interpolate,
  useCurrentFrame,
  spring,
  useVideoConfig,
} from 'remotion'
import { FadeIn } from '../animations/FadeIn'
import colors from '../../lib/colors'
import { fonts } from '../../lib/fonts'
import { fontSizes, VideoFormat } from '../../lib/typography'

interface Scene12Props {
  format: VideoFormat
}

/**
 * Animated CTA Button - SLOWER, more impactful reveals
 */
const CTAButton: React.FC<{
  text: string
  subtext: string
  color: string
  icon: string
  delay: number
  frame: number
  fps: number
  primary?: boolean
  format: VideoFormat
}> = ({ text, subtext, color, icon, delay, frame, fps, primary = false, format }) => {
  const entryProgress = spring({
    frame: frame - delay,
    fps,
    config: { damping: 100, stiffness: 60, mass: 1 }, // Slower, heavier
  })

  // SLOWER pulse effect for primary button
  const pulseIntensity = primary ? 1 + Math.sin((frame - delay) * 0.12) * 0.04 : 1

  // SLOWER glow pulse
  const glowSize = primary ? 30 + Math.sin((frame - delay) * 0.1) * 15 : 0

  // SLOWER shine effect moving across button
  const shineX = interpolate(
    (frame - delay) % 120, // Slower cycle
    [0, 60, 120],
    [-120, 200, 200],
    { extrapolateRight: 'clamp' }
  )

  const fs = fontSizes[format]

  return (
    <div
      style={{
        position: 'relative',
        padding: primary ? '24px 48px' : '18px 36px',
        borderRadius: 20,
        background: primary
          ? `linear-gradient(135deg, ${color} 0%, ${color}dd 100%)`
          : 'rgba(255, 255, 255, 0.05)',
        border: primary ? `3px solid ${color}` : `2px solid ${color}50`,
        display: 'flex',
        alignItems: 'center',
        gap: 18,
        transform: `scale(${entryProgress * pulseIntensity}) translateY(${(1 - entryProgress) * 30}px)`,
        opacity: entryProgress,
        overflow: 'hidden',
        boxShadow: primary
          ? `0 8px 40px ${color}50, 0 0 ${glowSize}px ${color}40`
          : 'none',
        cursor: 'pointer',
      }}
    >
      {/* Shine effect overlay */}
      {primary && (
        <div
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            width: 80,
            height: '100%',
            background:
              'linear-gradient(90deg, transparent, rgba(255,255,255,0.4), transparent)',
            transform: `translateX(${shineX}%)`,
          }}
        />
      )}

      {/* Icon container */}
      <div
        style={{
          width: primary ? 56 : 48,
          height: primary ? 56 : 48,
          borderRadius: '50%',
          backgroundColor: primary ? 'rgba(0,0,0,0.25)' : `${color}20`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: primary ? 28 : 24,
        }}
      >
        {icon}
      </div>

      {/* Text */}
      <div>
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: primary ? fs.bodyLarge : fs.body,
            fontWeight: 700,
            color: primary ? 'white' : color,
          }}
        >
          {text}
        </div>
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: primary ? fs.caption : 14,
            color: primary ? 'rgba(255,255,255,0.85)' : '#9ca3af',
            marginTop: 2,
          }}
        >
          {subtext}
        </div>
      </div>

      {/* Arrow */}
      <div
        style={{
          fontSize: primary ? 24 : 20,
          color: primary ? 'white' : color,
          marginLeft: 10,
          transform: `translateX(${Math.sin((frame - delay) * 0.3) * 4}px)`,
        }}
      >
        →
      </div>
    </div>
  )
}

/**
 * Urgency countdown visual
 */
const UrgencyBadge: React.FC<{ frame: number; format: VideoFormat }> = ({ frame, format }) => {
  const pulse = 1 + Math.sin(frame * 0.25) * 0.05
  const fs = fontSizes[format]

  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 10,
        padding: '10px 20px',
        backgroundColor: 'rgba(239, 68, 68, 0.15)',
        border: '2px solid rgba(239, 68, 68, 0.4)',
        borderRadius: 30,
        transform: `scale(${pulse})`,
      }}
    >
      <div
        style={{
          width: 10,
          height: 10,
          borderRadius: 5,
          backgroundColor: '#ef4444',
          boxShadow: '0 0 10px #ef4444',
          animation: 'pulse 1s infinite',
        }}
      />
      <span
        style={{
          fontFamily: fonts.body,
          fontSize: fs.caption,
          fontWeight: 600,
          color: '#ef4444',
        }}
      >
        LIMITED SPOTS
      </span>
    </div>
  )
}

/**
 * Floating element animation
 */
const FloatingElement: React.FC<{
  children: React.ReactNode
  frame: number
  delay: number
  x: number
  y: number
  size?: number
}> = ({ children, frame, delay, x, y, size = 56 }) => {
  const float = Math.sin((frame + delay) * 0.05) * 15

  return (
    <div
      style={{
        position: 'absolute',
        left: `${x}%`,
        top: `${y}%`,
        transform: `translateY(${float}px)`,
        opacity: 0.6,
        fontSize: size,
      }}
    >
      {children}
    </div>
  )
}

/**
 * Quick stat - SLOWER reveal
 */
const QuickStat: React.FC<{
  value: string
  label: string
  delay: number
  frame: number
  format: VideoFormat
}> = ({ value, label, delay, frame, format }) => {
  const progress = interpolate(frame - delay, [0, 30], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })
  const fs = fontSizes[format]

  return (
    <div
      style={{
        textAlign: 'center',
        opacity: progress,
        transform: `translateY(${(1 - progress) * 15}px)`,
      }}
    >
      <div
        style={{
          fontFamily: fonts.heading,
          fontSize: fs.h2,
          fontWeight: 400,
          color: colors.gold[400],
        }}
      >
        {value}
      </div>
      <div style={{ fontFamily: fonts.body, fontSize: fs.caption, color: '#6b7280' }}>
        {label}
      </div>
    </div>
  )
}

/**
 * QR Code - SLOWER reveal
 */
const QRCodeVisual: React.FC<{ frame: number; format: VideoFormat }> = ({ frame, format }) => {
  const opacity = interpolate(frame, [70, 95], [0, 1], { extrapolateRight: 'clamp' })
  const fs = fontSizes[format]

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        opacity,
        transform: `translateY(${interpolate(frame, [70, 95], [20, 0], { extrapolateRight: 'clamp' })}px)`,
      }}
    >
      <div
        style={{
          width: 80,
          height: 80,
          backgroundColor: 'white',
          borderRadius: 12,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: 8,
          boxShadow: '0 4px 20px rgba(0,0,0,0.3)',
        }}
      >
        {/* QR Code pattern */}
        <div
          style={{
            width: 60,
            height: 60,
            display: 'grid',
            gridTemplateColumns: 'repeat(6, 1fr)',
            gap: 2,
          }}
        >
          {Array.from({ length: 36 }, (_, i) => (
            <div
              key={i}
              style={{
                backgroundColor:
                  [0, 1, 5, 6, 7, 11, 24, 25, 29, 30, 31, 35].includes(i)
                    ? '#000'
                    : Math.random() > 0.5
                    ? '#000'
                    : 'transparent',
                borderRadius: 1,
              }}
            />
          ))}
        </div>
      </div>
      <span style={{ fontFamily: fonts.body, fontSize: fs.caption, color: '#9ca3af' }}>
        Scan to join
      </span>
    </div>
  )
}

export const Scene12_CTA: React.FC<Scene12Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const isPortrait = format === 'portrait'
  const fs = fontSizes[format]

  const floatingElements = [
    { emoji: '💵', x: 8, y: 18, delay: 0, size: 64 },
    { emoji: '🧹', x: 88, y: 12, delay: 20, size: 56 },
    { emoji: '✨', x: 12, y: 72, delay: 40, size: 52 },
    { emoji: '🏠', x: 85, y: 78, delay: 60, size: 60 },
    { emoji: '🌟', x: 5, y: 42, delay: 80, size: 48 },
    { emoji: '💪', x: 92, y: 48, delay: 100, size: 56 },
  ]

  return (
    <AbsoluteFill
      style={{
        background: `linear-gradient(180deg, #111827 0%, ${colors.green[950]} 50%, ${colors.gold[950]} 100%)`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexDirection: 'column',
        padding: isPortrait ? 40 : 60,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* Floating elements - LARGER */}
      {floatingElements.map((el, i) => (
        <FloatingElement key={i} frame={frame} delay={el.delay} x={el.x} y={el.y} size={el.size}>
          {el.emoji}
        </FloatingElement>
      ))}

      {/* Radial gradient backgrounds */}
      <div
        style={{
          position: 'absolute',
          top: '25%',
          left: '50%',
          width: 600,
          height: 600,
          background: `radial-gradient(circle, ${colors.green[500]}15 0%, transparent 60%)`,
          transform: 'translate(-50%, -50%)',
          filter: 'blur(50px)',
        }}
      />

      {/* Urgency badge */}
      <div style={{ marginBottom: 20 }}>
        <FadeIn delay={0}>
          <UrgencyBadge frame={frame} format={format} />
        </FadeIn>
      </div>

      {/* Main headline */}
      <FadeIn delay={5}>
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: isPortrait ? fs.h1 : fs.display,
            fontWeight: 400,
            color: 'white',
            textAlign: 'center',
            marginBottom: 8,
            textTransform: 'uppercase',
            letterSpacing: 3,
          }}
        >
          Ready to Make an
        </div>
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: isPortrait ? fs.display : 80,
            fontWeight: 400,
            background: `linear-gradient(135deg, ${colors.gold[400]} 0%, ${colors.green[400]} 100%)`,
            backgroundClip: 'text',
            WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent',
            textAlign: 'center',
            marginBottom: isPortrait ? 30 : 40,
            textTransform: 'uppercase',
            letterSpacing: 4,
          }}
        >
          Impact?
        </div>
      </FadeIn>

      {isPortrait ? (
        // Portrait layout - stacked CTAs
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 18,
            width: '100%',
            maxWidth: 380,
          }}
        >
          {/* Worker CTA - Primary with strong pulse - SLOWER */}
          <CTAButton
            text="Start Earning"
            subtext="$100 per block cleaned"
            color={colors.green[500]}
            icon="🧹"
            delay={25}
            frame={frame}
            fps={fps}
            primary
            format={format}
          />

          {/* Sponsor CTA - SLOWER */}
          <CTAButton
            text="Fund a Street"
            subtext="Employ your neighbors"
            color={colors.gold[500]}
            icon="💝"
            delay={50}
            frame={frame}
            fps={fps}
            format={format}
          />

          {/* Join waitlist - SLOWER */}
          <CTAButton
            text="Join Waitlist"
            subtext="Be first in your city"
            color={colors.sky[500]}
            icon="🌍"
            delay={75}
            frame={frame}
            fps={fps}
            format={format}
          />
        </div>
      ) : (
        // Landscape layout - side by side with QR
        <div style={{ display: 'flex', gap: 40, alignItems: 'center', marginBottom: 30 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
            {/* Worker CTA - Primary - SLOWER */}
            <CTAButton
              text="Start Earning Today"
              subtext="$100 per block cleaned"
              color={colors.green[500]}
              icon="🧹"
              delay={25}
              frame={frame}
              fps={fps}
              primary
              format={format}
            />

            {/* Sponsor CTA - SLOWER */}
            <CTAButton
              text="Fund a Street"
              subtext="Employ your neighbors"
              color={colors.gold[500]}
              icon="💝"
              delay={50}
              frame={frame}
              fps={fps}
              format={format}
            />
          </div>

          {/* QR Code for landscape */}
          <QRCodeVisual frame={frame} format={format} />
        </div>
      )}

      {/* Pre-launch messaging - SLOWER reveal */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 12,
          marginTop: isPortrait ? 28 : 20,
          padding: '20px 32px',
          backgroundColor: 'rgba(255, 255, 255, 0.03)',
          borderRadius: 20,
          border: '1px solid rgba(255, 255, 255, 0.06)',
          opacity: interpolate(frame, [100, 125], [0, 1], { extrapolateRight: 'clamp' }),
        }}
      >
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.h2,
            fontWeight: 400,
            color: colors.gold[400],
            textAlign: 'center',
            letterSpacing: 2,
          }}
        >
          LAUNCHING SOON
        </div>
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: fs.body,
            color: '#9ca3af',
            textAlign: 'center',
          }}
        >
          Be first in line • Early member perks • Limited spots
        </div>
      </div>

      {/* Website URL - SLOWER reveal */}
      <div
        style={{
          position: 'absolute',
          bottom: isPortrait ? 45 : 35,
          opacity: interpolate(frame, [130, 155], [0, 1], { extrapolateRight: 'clamp' }),
        }}
      >
        <div
          style={{
            fontFamily: fonts.body,
            fontSize: fs.body,
            fontWeight: 600,
            color: 'white',
            padding: '10px 28px',
            backgroundColor: 'rgba(255, 255, 255, 0.1)',
            borderRadius: 35,
            border: '1px solid rgba(255, 255, 255, 0.2)',
          }}
        >
          globalcommunitycleaners.org
        </div>
      </div>
    </AbsoluteFill>
  )
}

export default Scene12_CTA
