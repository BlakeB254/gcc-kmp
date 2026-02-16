import React from 'react'
import { interpolate, useCurrentFrame, spring, useVideoConfig, Sequence } from 'remotion'
import colors from '../../lib/colors'

interface PhoneMockupProps {
  children: React.ReactNode
  scale?: number
  angle?: number // 3D rotation angle
  showShadow?: boolean
  animateIn?: boolean
  delay?: number
  /** Interior content scale multiplier for readable text */
  interiorScale?: number
}

export const PhoneMockup: React.FC<PhoneMockupProps> = ({
  children,
  scale = 1,
  angle = 0,
  showShadow = true,
  animateIn = true,
  delay = 0,
  interiorScale = 1,
}) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  // Entry animation
  const entryProgress = animateIn
    ? spring({
        frame: frame - delay,
        fps,
        config: { damping: 100, stiffness: 150, mass: 0.8 },
      })
    : 1

  const entryScale = interpolate(entryProgress, [0, 1], [0.8, 1])
  const entryOpacity = interpolate(entryProgress, [0, 1], [0, 1], { extrapolateRight: 'clamp' })
  const entryY = interpolate(entryProgress, [0, 1], [50, 0])

  // Phone dimensions (iPhone 14 Pro proportions)
  const phoneWidth = 375 * scale
  const phoneHeight = 812 * scale
  const bezelRadius = 50 * scale
  const notchWidth = 150 * scale
  const notchHeight = 34 * scale

  return (
    <div
      style={{
        opacity: entryOpacity,
        transform: `
          translateY(${entryY}px)
          scale(${entryScale})
          perspective(1200px)
          rotateY(${angle}deg)
        `,
        transformStyle: 'preserve-3d',
      }}
    >
      {/* Shadow */}
      {showShadow && (
        <div
          style={{
            position: 'absolute',
            top: phoneHeight * 0.1,
            left: phoneWidth * 0.15,
            width: phoneWidth * 0.7,
            height: phoneHeight,
            background: 'radial-gradient(ellipse, rgba(0,0,0,0.4) 0%, transparent 70%)',
            filter: 'blur(30px)',
            transform: 'translateZ(-50px)',
            opacity: 0.6,
          }}
        />
      )}

      {/* Phone body */}
      <div
        style={{
          position: 'relative',
          width: phoneWidth,
          height: phoneHeight,
          backgroundColor: '#1a1a1a',
          borderRadius: bezelRadius,
          padding: 10 * scale,
          boxShadow: `
            0 0 0 2px #333,
            0 0 0 4px #1a1a1a,
            0 25px 50px -12px rgba(0, 0, 0, 0.5)
          `,
        }}
      >
        {/* Screen */}
        <div
          style={{
            width: '100%',
            height: '100%',
            backgroundColor: '#000',
            borderRadius: (bezelRadius - 10) * scale,
            overflow: 'hidden',
            position: 'relative',
          }}
        >
          {/* Dynamic Island / Notch */}
          <div
            style={{
              position: 'absolute',
              top: 12 * scale,
              left: '50%',
              transform: 'translateX(-50%)',
              width: notchWidth,
              height: notchHeight,
              backgroundColor: '#000',
              borderRadius: notchHeight / 2,
              zIndex: 10,
            }}
          />

          {/* Screen content with interior scaling for readability */}
          <div
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              right: 0,
              bottom: 0,
              overflow: 'hidden',
            }}
          >
            <div
              style={{
                width: `${100 / interiorScale}%`,
                height: `${100 / interiorScale}%`,
                transform: `scale(${interiorScale})`,
                transformOrigin: 'top left',
              }}
            >
              {children}
            </div>
          </div>

          {/* Home indicator */}
          <div
            style={{
              position: 'absolute',
              bottom: 8 * scale,
              left: '50%',
              transform: 'translateX(-50%)',
              width: 134 * scale,
              height: 5 * scale,
              backgroundColor: 'rgba(255,255,255,0.3)',
              borderRadius: 3 * scale,
            }}
          />
        </div>

        {/* Side buttons */}
        <div
          style={{
            position: 'absolute',
            right: -3 * scale,
            top: phoneHeight * 0.25,
            width: 3 * scale,
            height: 60 * scale,
            backgroundColor: '#333',
            borderRadius: 2 * scale,
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: -3 * scale,
            top: phoneHeight * 0.2,
            width: 3 * scale,
            height: 30 * scale,
            backgroundColor: '#333',
            borderRadius: 2 * scale,
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: -3 * scale,
            top: phoneHeight * 0.3,
            width: 3 * scale,
            height: 50 * scale,
            backgroundColor: '#333',
            borderRadius: 2 * scale,
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: -3 * scale,
            top: phoneHeight * 0.42,
            width: 3 * scale,
            height: 50 * scale,
            backgroundColor: '#333',
            borderRadius: 2 * scale,
          }}
        />
      </div>
    </div>
  )
}

export default PhoneMockup
