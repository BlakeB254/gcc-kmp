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

interface Scene08Props {
  format: 'portrait' | 'landscape' | 'square'
}

// Animated Block Grid - Shows blocks "lighting up" as they get cleaned
const BlockGrid: React.FC<{ frame: number; count: number }> = ({ frame, count }) => {
  const gridSize = 6
  const totalBlocks = gridSize * gridSize

  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: `repeat(${gridSize}, 1fr)`,
        gap: 4,
        width: 280,
        height: 280,
      }}
    >
      {Array.from({ length: totalBlocks }).map((_, i) => {
        const row = Math.floor(i / gridSize)
        const col = i % gridSize
        const delay = (row + col) * 3
        const isLit = frame > delay && i < count

        const litProgress = interpolate(
          frame - delay,
          [0, 15],
          [0, 1],
          { extrapolateRight: 'clamp' }
        )

        return (
          <div
            key={i}
            style={{
              width: '100%',
              aspectRatio: '1',
              borderRadius: 4,
              backgroundColor: isLit
                ? `rgba(22, 163, 74, ${0.3 + litProgress * 0.7})`
                : 'rgba(255, 255, 255, 0.1)',
              border: `1px solid ${isLit ? colors.green[500] : 'rgba(255, 255, 255, 0.1)'}`,
              transform: `scale(${isLit ? 0.9 + litProgress * 0.1 : 1})`,
              boxShadow: isLit ? `0 0 ${litProgress * 20}px ${colors.green[500]}40` : 'none',
            }}
          />
        )
      })}
    </div>
  )
}

// Community Celebration - People icons appearing
const CommunityIcons: React.FC<{ frame: number }> = ({ frame }) => {
  const icons = ['👨‍👩‍👧', '🧑‍🤝‍🧑', '👵', '👨‍👦', '🏃', '🚴', '🧒', '👩‍🦱']

  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        justifyContent: 'center',
        gap: 16,
        maxWidth: 320,
      }}
    >
      {icons.map((icon, i) => {
        const delay = 60 + i * 8
        const progress = interpolate(frame - delay, [0, 20], [0, 1], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
        })

        return (
          <div
            key={i}
            style={{
              fontSize: 40,
              opacity: progress,
              transform: `scale(${progress}) translateY(${(1 - progress) * 20}px)`,
            }}
          >
            {icon}
          </div>
        )
      })}
    </div>
  )
}

// Environmental Impact Meter
const ImpactMeter: React.FC<{ frame: number; fps: number }> = ({ frame, fps }) => {
  const fillProgress = spring({
    frame: frame - 40,
    fps,
    config: { damping: 80, stiffness: 100 },
  })

  const items = [
    { label: 'Air Quality', value: 85, color: colors.sky[400] },
    { label: 'Street Safety', value: 92, color: colors.green[400] },
    { label: 'Community Pride', value: 97, color: colors.gold[400] },
  ]

  return (
    <div style={{ width: '100%', maxWidth: 300 }}>
      {items.map((item, i) => {
        const itemDelay = 50 + i * 15
        const itemProgress = interpolate(frame - itemDelay, [0, 30], [0, 1], {
          extrapolateRight: 'clamp',
        })

        return (
          <div key={item.label} style={{ marginBottom: 16 }}>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                fontSize: 14,
                color: 'white',
                marginBottom: 6,
                opacity: itemProgress,
              }}
            >
              <span>{item.label}</span>
              <span style={{ color: item.color, fontWeight: 700 }}>
                {Math.round(item.value * fillProgress * itemProgress)}%
              </span>
            </div>
            <div
              style={{
                height: 8,
                backgroundColor: 'rgba(255, 255, 255, 0.1)',
                borderRadius: 4,
                overflow: 'hidden',
              }}
            >
              <div
                style={{
                  width: `${item.value * fillProgress * itemProgress}%`,
                  height: '100%',
                  backgroundColor: item.color,
                  borderRadius: 4,
                  boxShadow: `0 0 10px ${item.color}60`,
                }}
              />
            </div>
          </div>
        )
      })}
    </div>
  )
}

// Before/After Photo Cards
const TransformationCard: React.FC<{ frame: number }> = ({ frame }) => {
  const slideProgress = interpolate(frame, [20, 50], [0, 1], {
    extrapolateRight: 'clamp',
    easing: Easing.out(Easing.cubic),
  })

  return (
    <div
      style={{
        display: 'flex',
        gap: 12,
        perspective: '1000px',
      }}
    >
      {/* Before */}
      <div
        style={{
          width: 140,
          height: 180,
          borderRadius: 12,
          background: 'linear-gradient(180deg, #4b5563 0%, #374151 100%)',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          transform: `translateX(${(1 - slideProgress) * 50}px) rotateY(${(1 - slideProgress) * 20}deg)`,
          opacity: slideProgress,
          border: '2px solid rgba(255, 255, 255, 0.1)',
          position: 'relative',
          overflow: 'hidden',
        }}
      >
        <div style={{ fontSize: 48 }}>🗑️</div>
        <div style={{ fontSize: 12, color: '#9ca3af', marginTop: 8 }}>Before</div>
        <div
          style={{
            position: 'absolute',
            bottom: 0,
            left: 0,
            right: 0,
            height: 4,
            backgroundColor: '#ef4444',
          }}
        />
      </div>

      {/* Arrow */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          color: colors.gold[400],
          fontSize: 28,
          opacity: interpolate(frame, [40, 55], [0, 1], { extrapolateRight: 'clamp' }),
          transform: `translateX(${interpolate(frame, [40, 55], [-10, 0], { extrapolateRight: 'clamp' })}px)`,
        }}
      >
        →
      </div>

      {/* After */}
      <div
        style={{
          width: 140,
          height: 180,
          borderRadius: 12,
          background: `linear-gradient(180deg, ${colors.green[800]} 0%, ${colors.green[900]} 100%)`,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          transform: `translateX(${(1 - slideProgress) * -50}px) rotateY(${(1 - slideProgress) * -20}deg)`,
          opacity: slideProgress,
          border: `2px solid ${colors.green[600]}`,
          position: 'relative',
          overflow: 'hidden',
          boxShadow: `0 0 30px ${colors.green[500]}30`,
        }}
      >
        <div style={{ fontSize: 48 }}>✨</div>
        <div style={{ fontSize: 12, color: colors.green[300], marginTop: 8 }}>After</div>
        <div
          style={{
            position: 'absolute',
            bottom: 0,
            left: 0,
            right: 0,
            height: 4,
            backgroundColor: colors.green[500],
          }}
        />
      </div>
    </div>
  )
}

export const Scene08_Impact: React.FC<Scene08Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const isPortrait = format === 'portrait'

  // Floating particles
  const particles = Array.from({ length: 20 }, (_, i) => ({
    x: 10 + Math.random() * 80,
    y: 10 + Math.random() * 80,
    size: 4 + Math.random() * 8,
    speed: 0.3 + Math.random() * 0.5,
    offset: Math.random() * 100,
  }))

  return (
    <AbsoluteFill
      style={{
        background: `linear-gradient(135deg, #111827 0%, ${colors.green[950]} 50%, #111827 100%)`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexDirection: 'column',
        padding: 40,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      {/* Floating particles */}
      {particles.map((p, i) => (
        <div
          key={i}
          style={{
            position: 'absolute',
            left: `${p.x}%`,
            top: `${p.y + Math.sin((frame + p.offset) * 0.05) * 5}%`,
            width: p.size,
            height: p.size,
            borderRadius: '50%',
            backgroundColor: colors.green[500],
            opacity: 0.2 + Math.sin((frame + p.offset) * 0.03) * 0.1,
          }}
        />
      ))}

      {/* Title */}
      <FadeIn delay={0}>
        <div
          style={{
            fontSize: isPortrait ? 36 : 44,
            fontWeight: 800,
            color: 'white',
            textAlign: 'center',
            marginBottom: isPortrait ? 20 : 30,
          }}
        >
          <span style={{ color: colors.green[400] }}>Clean Streets.</span>
          <br />
          Thriving Communities.
        </div>
      </FadeIn>

      {isPortrait ? (
        // Portrait layout - stacked
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 30,
          }}
        >
          <TransformationCard frame={frame} />
          <ImpactMeter frame={frame} fps={fps} />
          <CommunityIcons frame={frame} />
        </div>
      ) : (
        // Landscape layout - side by side
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 60,
            width: '100%',
          }}
        >
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 24,
            }}
          >
            <BlockGrid frame={frame} count={28} />
            <div style={{ fontSize: 14, color: '#9ca3af' }}>
              <span style={{ color: colors.green[400], fontWeight: 700 }}>847</span> blocks cleaned
            </div>
          </div>

          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 24,
            }}
          >
            <TransformationCard frame={frame} />
            <ImpactMeter frame={frame} fps={fps} />
          </div>

          <CommunityIcons frame={frame} />
        </div>
      )}

      {/* Bottom tagline */}
      <div
        style={{
          position: 'absolute',
          bottom: isPortrait ? 50 : 40,
          left: 0,
          right: 0,
          textAlign: 'center',
          opacity: interpolate(frame, [120, 140], [0, 1], { extrapolateRight: 'clamp' }),
        }}
      >
        <div style={{ fontSize: 18, color: '#9ca3af' }}>
          Every block cleaned is a <span style={{ color: colors.gold[400], fontWeight: 600 }}>neighbor employed</span>
        </div>
      </div>
    </AbsoluteFill>
  )
}

export default Scene08_Impact
