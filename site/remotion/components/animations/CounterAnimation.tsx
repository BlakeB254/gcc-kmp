import React from 'react'
import { interpolate, useCurrentFrame } from 'remotion'

interface CounterAnimationProps {
  from: number
  to: number
  durationInFrames: number
  prefix?: string
  suffix?: string
  fontSize?: number
  color?: string
  fontWeight?: string | number
}

export const CounterAnimation: React.FC<CounterAnimationProps> = ({
  from,
  to,
  durationInFrames,
  prefix = '',
  suffix = '',
  fontSize = 72,
  color = '#16a34a',
  fontWeight = 'bold',
}) => {
  const frame = useCurrentFrame()

  const currentValue = interpolate(
    frame,
    [0, durationInFrames],
    [from, to],
    {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    }
  )

  // Format number with commas
  const formattedValue = Math.round(currentValue).toLocaleString()

  return (
    <div
      style={{
        fontSize,
        fontWeight,
        color,
        fontFamily: 'system-ui, -apple-system, sans-serif',
        fontVariantNumeric: 'tabular-nums',
      }}
    >
      {prefix}
      {formattedValue}
      {suffix}
    </div>
  )
}

export default CounterAnimation
