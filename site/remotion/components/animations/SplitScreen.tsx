import React from 'react'
import { interpolate, useCurrentFrame, spring, useVideoConfig } from 'remotion'

interface SplitScreenProps {
  leftContent: React.ReactNode
  rightContent: React.ReactNode
  leftLabel?: string
  rightLabel?: string
  dividerPosition?: number // 0-100, percentage from left
  animateDivider?: boolean
}

export const SplitScreen: React.FC<SplitScreenProps> = ({
  leftContent,
  rightContent,
  leftLabel = 'BEFORE',
  rightLabel = 'AFTER',
  dividerPosition = 50,
  animateDivider = true,
}) => {
  const frame = useCurrentFrame()
  const { fps, width, height } = useVideoConfig()

  // Animate divider from 0 to final position
  const dividerProgress = animateDivider
    ? spring({
        frame,
        fps,
        config: {
          damping: 100,
          stiffness: 100,
          mass: 1,
        },
      })
    : 1

  const currentDividerPosition = interpolate(
    dividerProgress,
    [0, 1],
    [0, dividerPosition]
  )

  // Divider handle animation
  const handleOpacity = interpolate(
    frame,
    [20, 30],
    [0, 1],
    {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    }
  )

  return (
    <div
      style={{
        position: 'relative',
        width: '100%',
        height: '100%',
        overflow: 'hidden',
      }}
    >
      {/* Right side (full background) */}
      <div
        style={{
          position: 'absolute',
          top: 0,
          left: 0,
          width: '100%',
          height: '100%',
        }}
      >
        {rightContent}
        {/* Right label */}
        <div
          style={{
            position: 'absolute',
            bottom: 40,
            right: 40,
            backgroundColor: 'rgba(22, 163, 74, 0.9)',
            color: 'white',
            padding: '12px 24px',
            fontSize: 24,
            fontWeight: 'bold',
            borderRadius: 8,
            opacity: handleOpacity,
          }}
        >
          {rightLabel}
        </div>
      </div>

      {/* Left side (clipped) */}
      <div
        style={{
          position: 'absolute',
          top: 0,
          left: 0,
          width: `${currentDividerPosition}%`,
          height: '100%',
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            width: `${(100 / currentDividerPosition) * 100}%`,
            height: '100%',
          }}
        >
          {leftContent}
        </div>
        {/* Left label */}
        <div
          style={{
            position: 'absolute',
            bottom: 40,
            left: 40,
            backgroundColor: 'rgba(139, 110, 90, 0.9)',
            color: 'white',
            padding: '12px 24px',
            fontSize: 24,
            fontWeight: 'bold',
            borderRadius: 8,
            opacity: handleOpacity,
          }}
        >
          {leftLabel}
        </div>
      </div>

      {/* Divider line */}
      <div
        style={{
          position: 'absolute',
          top: 0,
          left: `${currentDividerPosition}%`,
          width: 4,
          height: '100%',
          backgroundColor: 'white',
          transform: 'translateX(-50%)',
          boxShadow: '0 0 20px rgba(0,0,0,0.5)',
        }}
      >
        {/* Handle */}
        <div
          style={{
            position: 'absolute',
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -50%)',
            width: 48,
            height: 48,
            borderRadius: '50%',
            backgroundColor: 'white',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 4px 20px rgba(0,0,0,0.3)',
            opacity: handleOpacity,
          }}
        >
          <div style={{ display: 'flex', gap: 4 }}>
            <div style={{ width: 3, height: 20, backgroundColor: '#666', borderRadius: 2 }} />
            <div style={{ width: 3, height: 20, backgroundColor: '#666', borderRadius: 2 }} />
          </div>
        </div>
      </div>
    </div>
  )
}

export default SplitScreen
