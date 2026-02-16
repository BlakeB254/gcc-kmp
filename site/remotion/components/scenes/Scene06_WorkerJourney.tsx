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

interface Scene06Props {
  format: VideoFormat
}

/**
 * Block Claim UI - Large phone frame, normal content scale
 * No map - just a clean block card list UI
 */
const SuperZoomedBlockListUI: React.FC<{
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
    [0, 100],
    [0, 0],
    { extrapolateRight: 'clamp' }
  )

  // Subtle vertical drift as user scrolls through blocks
  const cameraY = interpolate(
    frame,
    [0, 40, 80, 100],
    [0, 0, -3, -5],
    { extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic) }
  )

  // Block selection animation
  const selectedBlockIndex = frame > 50 ? 1 : -1
  const claimProgress = spring({
    frame: frame - 95,
    fps,
    config: { damping: 100, stiffness: 60 },
  })

  const blocks = [
    { name: 'W. Roosevelt Rd', address: '3800-3899 W. Roosevelt Road', payout: 100 },
    { name: 'S. Pulaski Rd', address: '1500-1599 S. Pulaski Road', payout: 100 },
    { name: 'W. Cermak Rd', address: '4200-4299 W. Cermak Road', payout: 100 },
  ]

  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        overflow: 'hidden',
        position: 'relative',
        borderRadius: isPortrait ? 50 : 40,
        background: 'linear-gradient(180deg, #111827 0%, #1f2937 100%)',
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
        <div
          style={{
            width: '100%',
            height: '100%',
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
                backgroundColor: 'rgba(22, 163, 74, 0.15)',
                borderRadius: 16,
                border: '2px solid rgba(22, 163, 74, 0.3)',
                marginBottom: 10,
              }}
            >
              <span style={{ fontSize: 11, color: colors.green[400], fontWeight: 600 }}>
                📍 North Lawndale
              </span>
            </div>

            <div style={{ fontSize: 16, fontWeight: 700, color: 'white', marginBottom: 4 }}>
              Available Blocks
            </div>
            <div style={{ fontSize: 10, color: colors.gold[400] }}>
              12 blocks • $100 each
            </div>
          </div>

          {/* Block list */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, flex: 1 }}>
            {blocks.map((block, i) => {
              const isSelected = i === selectedBlockIndex
              const pulse = isSelected ? 1 + Math.sin(frame * 0.15) * 0.03 : 1

              return (
                <div
                  key={i}
                  style={{
                    backgroundColor: isSelected
                      ? 'rgba(212, 160, 23, 0.15)'
                      : 'rgba(255, 255, 255, 0.05)',
                    borderRadius: 12,
                    padding: '12px 14px',
                    border: isSelected
                      ? `2px solid ${colors.gold[500]}`
                      : '2px solid rgba(255, 255, 255, 0.1)',
                    transform: `scale(${pulse})`,
                    boxShadow: isSelected
                      ? `0 0 20px rgba(212, 160, 23, 0.3)`
                      : 'none',
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                    }}
                  >
                    <div>
                      <div
                        style={{
                          fontSize: 12,
                          fontWeight: 600,
                          color: isSelected ? 'white' : '#e5e7eb',
                          marginBottom: 2,
                        }}
                      >
                        {block.name}
                      </div>
                      <div style={{ fontSize: 9, color: '#9ca3af' }}>{block.address}</div>
                    </div>
                    <div
                      style={{
                        backgroundColor: isSelected
                          ? colors.gold[500]
                          : 'rgba(212, 160, 23, 0.2)',
                        borderRadius: 8,
                        padding: '6px 10px',
                      }}
                    >
                      <span
                        style={{
                          fontSize: 12,
                          fontWeight: 700,
                          color: isSelected ? '#1a1a1a' : colors.gold[400],
                        }}
                      >
                        ${block.payout}
                      </span>
                    </div>
                  </div>
                </div>
              )
            })}
          </div>

          {/* Claim button */}
          <div
            style={{
              marginTop: 14,
              backgroundColor:
                claimProgress > 0.5 ? colors.green[500] : colors.gold[500],
              borderRadius: 12,
              padding: '14px 20px',
              textAlign: 'center',
              transform: `scale(${claimProgress > 0 ? 0.95 : 1})`,
              boxShadow:
                claimProgress > 0.5
                  ? `0 0 30px ${colors.green[500]}80`
                  : `0 0 15px rgba(212, 160, 23, 0.4)`,
            }}
          >
            <span
              style={{
                fontSize: 13,
                fontWeight: 700,
                color: claimProgress > 0.5 ? 'white' : '#1a1a1a',
              }}
            >
              {claimProgress > 0.5 ? '✓ Block Claimed!' : '🧹 Claim Selected Block'}
            </span>
          </div>
        </div>
      </div>

      {/* Vignette */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background:
            'radial-gradient(ellipse at center, transparent 50%, rgba(0,0,0,0.3) 100%)',
          pointerEvents: 'none',
          borderRadius: isPortrait ? 50 : 40,
        }}
      />
    </div>
  )
}

/**
 * Cleaning Progress UI - Large phone frame, normal content scale
 */
const SuperZoomedCleaningUI: React.FC<{
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
    [0, 100],
    [0, 0],
    { extrapolateRight: 'clamp' }
  )

  // Subtle vertical drift as progress fills
  const cameraY = interpolate(
    frame,
    [0, 50, 100],
    [0, -2, -4],
    { extrapolateRight: 'clamp', easing: Easing.inOut(Easing.cubic) }
  )

  // Progress animation
  const progressPercent = interpolate(frame, [0, 120], [25, 75], {
    extrapolateRight: 'clamp',
  })

  // Camera capture
  const showCameraSuccess = frame > 95 && frame < 150
  const cameraScale = spring({
    frame: frame - 95,
    fps,
    config: { damping: 100, stiffness: 60 },
  })

  const checkpoints = [
    { name: 'Start Point', status: 'completed' },
    { name: 'Checkpoint 1', status: 'completed' },
    { name: 'Checkpoint 2', status: frame > 45 ? 'current' : 'pending' },
    { name: 'End Point', status: 'pending' },
  ]

  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        overflow: 'hidden',
        position: 'relative',
        borderRadius: isPortrait ? 50 : 40,
        background: 'linear-gradient(180deg, #111827 0%, #1f2937 100%)',
      }}
    >
      {/* Camera success overlay */}
      {showCameraSuccess && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            backgroundColor: 'rgba(22, 163, 74, 0.3)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 10,
            opacity: interpolate(frame, [95, 105, 135, 150], [0, 1, 1, 0], {
              extrapolateRight: 'clamp',
            }),
          }}
        >
          <div
            style={{
              width: 80,
              height: 80,
              borderRadius: 40,
              backgroundColor: colors.green[500],
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              transform: `scale(${cameraScale})`,
              boxShadow: `0 0 40px ${colors.green[500]}80`,
            }}
          >
            <span style={{ fontSize: 36, color: 'white' }}>✓</span>
          </div>
        </div>
      )}

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
            display: 'flex',
            flexDirection: 'column',
            padding: 16,
            paddingTop: 45,
          }}
        >
          {/* Header */}
          <div style={{ marginBottom: 14 }}>
            <div style={{ fontSize: 10, color: '#9ca3af', marginBottom: 4 }}>
              W. Roosevelt Rd Block
            </div>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <div style={{ fontSize: 18, fontWeight: 700, color: 'white' }}>
                {Math.round(progressPercent)}% Complete
              </div>
              <div
                style={{
                  backgroundColor: colors.gold[500],
                  borderRadius: 8,
                  padding: '5px 10px',
                }}
              >
                <span style={{ fontSize: 13, fontWeight: 700, color: '#1a1a1a' }}>$100</span>
              </div>
            </div>

            {/* Progress bar */}
            <div
              style={{
                marginTop: 10,
                height: 8,
                backgroundColor: '#374151',
                borderRadius: 4,
                overflow: 'hidden',
              }}
            >
              <div
                style={{
                  width: `${progressPercent}%`,
                  height: '100%',
                  backgroundColor: colors.green[500],
                  borderRadius: 4,
                  boxShadow: `0 0 10px ${colors.green[500]}80`,
                }}
              />
            </div>
          </div>

          {/* Checkpoints */}
          <div style={{ flex: 1 }}>
            <div
              style={{
                fontSize: 9,
                color: '#6b7280',
                marginBottom: 10,
                fontWeight: 600,
                textTransform: 'uppercase',
                letterSpacing: 1,
              }}
            >
              Checkpoints
            </div>

            {checkpoints.map((cp, i) => (
              <div
                key={i}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  marginBottom: 10,
                  opacity: cp.status === 'pending' ? 0.5 : 1,
                }}
              >
                <div
                  style={{
                    width: 24,
                    height: 24,
                    borderRadius: 12,
                    backgroundColor:
                      cp.status === 'completed'
                        ? colors.green[500]
                        : cp.status === 'current'
                        ? colors.gold[500]
                        : '#374151',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    border:
                      cp.status === 'current' ? `2px solid ${colors.gold[300]}` : 'none',
                    boxShadow:
                      cp.status === 'current' ? `0 0 12px ${colors.gold[500]}60` : 'none',
                  }}
                >
                  {cp.status === 'completed' ? (
                    <span style={{ color: 'white', fontSize: 12 }}>✓</span>
                  ) : (
                    <span style={{ color: 'white', fontSize: 10 }}>{i + 1}</span>
                  )}
                </div>
                <div>
                  <div
                    style={{
                      fontSize: 11,
                      fontWeight: cp.status === 'current' ? 600 : 400,
                      color: cp.status === 'current' ? 'white' : '#9ca3af',
                    }}
                  >
                    {cp.name}
                  </div>
                  {cp.status === 'current' && (
                    <div style={{ fontSize: 9, color: colors.gold[400] }}>
                      📸 Take photo to continue
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>

          {/* Camera Button */}
          <div
            style={{
              backgroundColor: showCameraSuccess ? colors.green[500] : colors.gold[500],
              borderRadius: 12,
              padding: '14px 20px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 10,
              transform: `scale(${showCameraSuccess ? 0.95 : 1})`,
              boxShadow: showCameraSuccess
                ? `0 0 25px ${colors.green[500]}80`
                : `0 0 15px rgba(212, 160, 23, 0.4)`,
            }}
          >
            <span style={{ fontSize: 18 }}>{showCameraSuccess ? '✓' : '📸'}</span>
            <span
              style={{
                fontSize: 13,
                fontWeight: 700,
                color: showCameraSuccess ? 'white' : '#1a1a1a',
              }}
            >
              {showCameraSuccess ? 'Photo Captured!' : 'Take Photo'}
            </span>
          </div>
        </div>
      </div>

      {/* Vignette */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background:
            'radial-gradient(ellipse at center, transparent 50%, rgba(0,0,0,0.3) 100%)',
          pointerEvents: 'none',
          borderRadius: isPortrait ? 50 : 40,
        }}
      />
    </div>
  )
}

/**
 * Super-sized phone frame
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
            0 0 60px ${colors.green[500]}25
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

export const Scene06_WorkerJourney: React.FC<Scene06Props> = ({ format }) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const isPortrait = format === 'portrait'
  const fs = fontSizes[format]

  // Phase transitions (11 second scene = 330 frames)
  const showBlockListPhase = frame < 165
  const blockListOpacity = interpolate(frame, [0, 20, 145, 165], [0, 1, 1, 0], {
    extrapolateRight: 'clamp',
  })
  const cleaningOpacity = interpolate(frame, [155, 180, 305, 330], [0, 1, 1, 0], {
    extrapolateRight: 'clamp',
  })

  return (
    <AbsoluteFill
      style={{
        background: `linear-gradient(135deg, #111827 0%, ${colors.brown[900]} 100%)`,
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
          top: '10%',
          right: '5%',
          width: 350,
          height: 350,
          background: `radial-gradient(circle, ${colors.green[500]}20 0%, transparent 60%)`,
          borderRadius: '50%',
          transform: `translate(${Math.sin(frame * 0.02) * 20}px, ${Math.cos(frame * 0.02) * 20}px)`,
          filter: 'blur(40px)',
        }}
      />
      <div
        style={{
          position: 'absolute',
          bottom: '15%',
          left: '10%',
          width: 300,
          height: 300,
          background: `radial-gradient(circle, ${colors.gold[500]}15 0%, transparent 60%)`,
          borderRadius: '50%',
          transform: `translate(${Math.cos(frame * 0.025) * 25}px, ${Math.sin(frame * 0.025) * 25}px)`,
          filter: 'blur(40px)',
        }}
      />

      {/* Title for portrait */}
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
                fontSize: fs.h1,
                fontWeight: 400,
                color: 'white',
                textTransform: 'uppercase',
                letterSpacing: 3,
              }}
            >
              {showBlockListPhase ? 'Claim a Block' : 'Clean & Document'}
            </div>
          </FadeIn>
        </div>
      )}

      {/* Text for landscape */}
      {!isPortrait && (
        <FadeIn delay={5} direction="left">
          <div style={{ maxWidth: 450, zIndex: 5 }}>
            <div
              style={{
                fontFamily: fonts.heading,
                fontSize: fs.h1,
                fontWeight: 400,
                color: 'white',
                marginBottom: 20,
                lineHeight: 1.1,
                textTransform: 'uppercase',
                letterSpacing: 2,
              }}
            >
              {showBlockListPhase ? (
                <>
                  Pick a block.
                  <br />
                  <span style={{ color: colors.gold[400] }}>Earn $100.</span>
                </>
              ) : (
                <>
                  GPS-guided.
                  <br />
                  <span style={{ color: colors.green[400] }}>Photo verified.</span>
                </>
              )}
            </div>
            <div
              style={{
                fontFamily: fonts.body,
                fontSize: fs.body,
                color: '#9ca3af',
                lineHeight: 1.6,
              }}
            >
              {showBlockListPhase
                ? 'Browse available blocks. First come, first served.'
                : 'Follow the route. Document your work. Get paid instantly.'}
            </div>
          </div>
        </FadeIn>
      )}

      {/* Block list phase - SUPER ZOOMED */}
      {blockListOpacity > 0 && (
        <div
          style={{
            position: 'absolute',
            transform: isPortrait ? 'translateY(0)' : 'translateX(25%)',
            zIndex: 10,
          }}
        >
          <SuperPhoneFrame
            isPortrait={isPortrait}
            opacity={blockListOpacity}
            entryDelay={5}
            frame={frame}
            fps={fps}
          >
            <SuperZoomedBlockListUI
              frame={Math.max(0, frame - 10)}
              fps={fps}
              format={format}
            />
          </SuperPhoneFrame>
        </div>
      )}

      {/* Cleaning progress phase - SUPER ZOOMED */}
      {cleaningOpacity > 0 && (
        <div
          style={{
            position: 'absolute',
            transform: isPortrait ? 'translateY(0)' : 'translateX(25%)',
            zIndex: 15,
          }}
        >
          <SuperPhoneFrame
            isPortrait={isPortrait}
            opacity={cleaningOpacity}
            entryDelay={160}
            frame={frame}
            fps={fps}
          >
            <SuperZoomedCleaningUI
              frame={Math.max(0, frame - 165)}
              fps={fps}
              format={format}
            />
          </SuperPhoneFrame>
        </div>
      )}

      {/* Bottom CTA for portrait */}
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
          <FadeIn delay={40} direction="up">
            <div style={{ fontSize: 16, color: '#9ca3af' }}>
              {showBlockListPhase ? '4 checkpoints • 2-4 hour sessions' : 'AI verifies your work'}
            </div>
          </FadeIn>
        </div>
      )}
    </AbsoluteFill>
  )
}

export default Scene06_WorkerJourney
