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

interface Scene09Props {
  format: VideoFormat
}

// Dollar bill floating animation - SLOWER with BIGGER bills
const FloatingBill: React.FC<{
  frame: number
  delay: number
  startX: number
  startY: number
  size?: number
}> = ({ frame, delay, startX, startY, size = 48 }) => {
  const progress = interpolate(frame - delay, [0, 90], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  const rotation = Math.sin((frame - delay) * 0.07) * 15 // Slower rotation

  return (
    <div
      style={{
        position: 'absolute',
        left: `${startX}%`,
        top: `${startY + progress * 30}%`,
        fontSize: size,
        transform: `rotate(${rotation}deg)`,
        opacity: interpolate(progress, [0, 0.1, 0.8, 1], [0, 0.7, 0.7, 0]),
      }}
    >
      💵
    </div>
  )
}

// Impact visualization - SLOWER, more impactful reveals
const ImpactVisualization: React.FC<{
  frame: number
  fps: number
  format: VideoFormat
}> = ({ frame, fps, format }) => {
  const dollarProgress = spring({
    frame,
    fps,
    config: { damping: 100, stiffness: 50, mass: 1 }, // Slower, heavier
  })

  const arrowProgress = interpolate(frame, [45, 75], [0, 1], {
    extrapolateRight: 'clamp',
    easing: Easing.out(Easing.cubic),
  })

  const workerProgress = spring({
    frame: frame - 70, // Later reveal
    fps,
    config: { damping: 100, stiffness: 60 }, // Slower
  })

  const fs = fontSizes[format]
  const isPortrait = format === 'portrait'

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: isPortrait ? 32 : 40,
      }}
    >
      {/* Dollar amount - MASSIVE for heavy zoom effect */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          transform: `scale(${dollarProgress})`,
          opacity: dollarProgress,
        }}
      >
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: isPortrait ? 140 : 110,
            fontWeight: 400,
            color: colors.gold[400],
            textShadow: `0 0 60px ${colors.gold[500]}70, 0 0 100px ${colors.gold[500]}40`,
            letterSpacing: 4,
          }}
        >
          $100
        </div>
        <div style={{
          fontFamily: fonts.body,
          fontSize: fs.bodyLarge,
          color: '#9ca3af',
          marginTop: 12,
        }}>
          Your Sponsorship
        </div>
      </div>

      {/* Arrow - BIGGER */}
      <div
        style={{
          fontSize: isPortrait ? 72 : 60,
          color: colors.green[400],
          opacity: arrowProgress,
          transform: `translateX(${(1 - arrowProgress) * -20}px)`,
          textShadow: `0 0 30px ${colors.green[500]}50`,
        }}
      >
        →
      </div>

      {/* Worker + Clean Block - LARGER emojis */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          transform: `scale(${workerProgress})`,
          opacity: workerProgress,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <span style={{ fontSize: isPortrait ? 88 : 72 }}>🧹</span>
          <span style={{ fontSize: isPortrait ? 88 : 72 }}>✨</span>
        </div>
        <div style={{
          fontFamily: fonts.body,
          fontSize: fs.h3,
          fontWeight: 600,
          color: colors.green[400],
          marginTop: 16,
        }}>
          1 Block Cleaned
        </div>
        <div style={{
          fontFamily: fonts.body,
          fontSize: fs.h3,
          fontWeight: 600,
          color: colors.gold[400],
        }}>
          1 Neighbor Employed
        </div>
      </div>
    </div>
  )
}

// Sponsor benefits list - SLOWER stagger
const BenefitItem: React.FC<{
  icon: string
  text: string
  delay: number
  frame: number
  format: VideoFormat
}> = ({ icon, text, delay, frame, format }) => {
  const progress = interpolate(frame - delay, [0, 30], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  const fs = fontSizes[format]
  const isPortrait = format === 'portrait'

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        opacity: progress,
        transform: `translateX(${(1 - progress) * 30}px)`,
      }}
    >
      <div
        style={{
          width: isPortrait ? 52 : 44,
          height: isPortrait ? 52 : 44,
          borderRadius: '50%',
          backgroundColor: 'rgba(255, 255, 255, 0.1)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: isPortrait ? 26 : 22,
        }}
      >
        {icon}
      </div>
      <span style={{
        fontFamily: fonts.body,
        fontSize: fs.body,
        fontWeight: 500,
        color: 'white',
      }}>
        {text}
      </span>
    </div>
  )
}

// Sponsor tiers badges - SLOWER reveal
const SponsorTierBadge: React.FC<{
  tier: string
  color: string
  count: string
  delay: number
  frame: number
  format: VideoFormat
}> = ({ tier, color, count, delay, frame, format }) => {
  const progress = interpolate(frame - delay, [0, 35], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  })

  const isPortrait = format === 'portrait'
  const fs = fontSizes[format]

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        opacity: progress,
        transform: `scale(${0.8 + progress * 0.2}) translateY(${(1 - progress) * 20}px)`,
      }}
    >
      <div
        style={{
          width: isPortrait ? 64 : 56,
          height: isPortrait ? 64 : 56,
          borderRadius: '50%',
          background: `linear-gradient(135deg, ${color} 0%, ${color}99 100%)`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: isPortrait ? 32 : 28,
          boxShadow: `0 0 20px ${color}40`,
          border: `2px solid ${color}`,
        }}
      >
        {tier === 'Bronze' ? '🥉' : tier === 'Silver' ? '🥈' : tier === 'Gold' ? '🥇' : '💎'}
      </div>
      <div style={{
        fontFamily: fonts.body,
        fontSize: fs.caption,
        color,
        fontWeight: 600,
        marginTop: 8,
      }}>
        {tier}
      </div>
      <div style={{
        fontFamily: fonts.body,
        fontSize: 12,
        color: '#6b7280',
      }}>
        {count}
      </div>
    </div>
  )
}

export const Scene09_SponsorSide: React.FC<Scene09Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const isPortrait = format === 'portrait'
  const fs = fontSizes[format]

  const benefits = [
    { icon: '📜', text: 'Tax-deductible contribution' },
    { icon: '🏆', text: 'Public recognition on sponsor wall' },
    { icon: '📊', text: 'Real-time impact tracking' },
    { icon: '🎖️', text: 'Exclusive sponsor badge' },
  ]

  const tiers = [
    { tier: 'Bronze', color: '#cd7f32', count: '1-4 blocks' },
    { tier: 'Silver', color: '#c0c0c0', count: '5-19 blocks' },
    { tier: 'Gold', color: '#ffd700', count: '20-49 blocks' },
    { tier: 'Platinum', color: '#e5e4e2', count: '50+ blocks' },
  ]

  return (
    <AbsoluteFill
      style={{
        background: `linear-gradient(135deg, ${colors.gold[950]} 0%, #111827 50%, ${colors.brown[950]} 100%)`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexDirection: 'column',
        padding: isPortrait ? 40 : 60,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* Floating money background - MORE and BIGGER bills */}
      {Array.from({ length: 12 }).map((_, i) => (
        <FloatingBill
          key={i}
          frame={frame}
          delay={i * 12}
          startX={5 + (i * 8) % 90}
          startY={-15 + (i % 4) * 8}
          size={40 + (i % 3) * 16}
        />
      ))}

      {/* Title */}
      <FadeIn delay={0}>
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.display,
            fontWeight: 400,
            color: 'white',
            textAlign: 'center',
            marginBottom: 8,
            textTransform: 'uppercase',
            letterSpacing: 3,
          }}
        >
          Your <span style={{ color: colors.gold[400] }}>$100</span> Employs
        </div>
        <div
          style={{
            fontFamily: fonts.heading,
            fontSize: fs.h1,
            fontWeight: 400,
            color: colors.green[400],
            textAlign: 'center',
            marginBottom: isPortrait ? 30 : 40,
            textTransform: 'uppercase',
            letterSpacing: 2,
          }}
        >
          a Neighbor
        </div>
      </FadeIn>

      {/* Impact visualization */}
      <ImpactVisualization frame={frame} fps={fps} format={format} />

      {isPortrait ? (
        // Portrait layout
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 28,
            marginTop: 40,
            width: '100%',
            maxWidth: 380,
          }}
        >
          {/* Benefits - SLOWER stagger */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            {benefits.map((benefit, i) => (
              <BenefitItem
                key={benefit.text}
                icon={benefit.icon}
                text={benefit.text}
                delay={90 + i * 18}
                frame={frame}
                format={format}
              />
            ))}
          </div>

          {/* Tier badges - LATER reveal */}
          <div
            style={{
              display: 'flex',
              justifyContent: 'center',
              gap: 16,
              marginTop: 16,
            }}
          >
            {tiers.map((t, i) => (
              <SponsorTierBadge
                key={t.tier}
                tier={t.tier}
                color={t.color}
                count={t.count}
                delay={145 + i * 12}
                frame={frame}
                format={format}
              />
            ))}
          </div>
        </div>
      ) : (
        // Landscape layout
        <div
          style={{
            display: 'flex',
            justifyContent: 'center',
            gap: 80,
            marginTop: 50,
            width: '100%',
          }}
        >
          {/* Benefits - SLOWER stagger */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
            <div style={{
              fontFamily: fonts.body,
              fontSize: fs.h3,
              color: '#9ca3af',
              marginBottom: 8,
            }}>
              Sponsor Benefits
            </div>
            {benefits.map((benefit, i) => (
              <BenefitItem
                key={benefit.text}
                icon={benefit.icon}
                text={benefit.text}
                delay={90 + i * 18}
                frame={frame}
                format={format}
              />
            ))}
          </div>

          {/* Tier badges - LATER reveal */}
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 20,
            }}
          >
            <div style={{
              fontFamily: fonts.body,
              fontSize: fs.h3,
              color: '#9ca3af',
            }}>
              Sponsorship Tiers
            </div>
            <div style={{ display: 'flex', gap: 24 }}>
              {tiers.map((t, i) => (
                <SponsorTierBadge
                  key={t.tier}
                  tier={t.tier}
                  color={t.color}
                  count={t.count}
                  delay={130 + i * 12}
                  frame={frame}
                  format={format}
                />
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Bottom CTA hint - SLOWER reveal */}
      <div
        style={{
          position: 'absolute',
          bottom: isPortrait ? 50 : 40,
          left: 0,
          right: 0,
          textAlign: 'center',
          opacity: interpolate(frame, [155, 175], [0, 1], { extrapolateRight: 'clamp' }),
        }}
      >
        <div style={{
          fontFamily: fonts.body,
          fontSize: fs.bodyLarge,
          fontWeight: 500,
          color: '#9ca3af',
        }}>
          Fund a street. <span style={{ color: colors.gold[400] }}>Change a life.</span>
        </div>
      </div>
    </AbsoluteFill>
  )
}

export default Scene09_SponsorSide
