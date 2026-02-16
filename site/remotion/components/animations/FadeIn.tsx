import React from 'react'
import { interpolate, useCurrentFrame, spring, useVideoConfig } from 'remotion'

interface FadeInProps {
  children: React.ReactNode
  delay?: number
  duration?: number
  direction?: 'up' | 'down' | 'left' | 'right' | 'none'
  distance?: number
  style?: React.CSSProperties
}

export const FadeIn: React.FC<FadeInProps> = ({
  children,
  delay = 0,
  duration = 30,
  direction = 'up',
  distance = 30,
  style = {},
}) => {
  const frame = useCurrentFrame()
  const { fps } = useVideoConfig()

  const opacity = interpolate(
    frame - delay,
    [0, duration],
    [0, 1],
    {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    }
  )

  const springValue = spring({
    frame: frame - delay,
    fps,
    config: {
      damping: 100,
      stiffness: 200,
      mass: 0.5,
    },
  })

  let transform = ''
  switch (direction) {
    case 'up':
      transform = `translateY(${interpolate(springValue, [0, 1], [distance, 0])}px)`
      break
    case 'down':
      transform = `translateY(${interpolate(springValue, [0, 1], [-distance, 0])}px)`
      break
    case 'left':
      transform = `translateX(${interpolate(springValue, [0, 1], [distance, 0])}px)`
      break
    case 'right':
      transform = `translateX(${interpolate(springValue, [0, 1], [-distance, 0])}px)`
      break
    case 'none':
    default:
      transform = 'none'
  }

  return (
    <div
      style={{
        opacity,
        transform,
        ...style,
      }}
    >
      {children}
    </div>
  )
}

export default FadeIn
