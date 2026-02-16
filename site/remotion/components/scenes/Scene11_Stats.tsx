import React from 'react'
import { AbsoluteFill, Sequence } from 'remotion'
import { CounterAnimation } from '../animations/CounterAnimation'
import { FadeIn } from '../animations/FadeIn'
import colors from '../../lib/colors'

interface Scene11Props {
  format: 'portrait' | 'landscape' | 'square'
}

interface StatItemProps {
  value: number
  label: string
  prefix?: string
  suffix?: string
  color: string
  delay: number
  duration: number
  fontSize: number
}

const StatItem: React.FC<StatItemProps> = ({
  value,
  label,
  prefix,
  suffix,
  color,
  delay,
  duration,
  fontSize,
}) => (
  <Sequence from={delay}>
    <FadeIn delay={0} direction="up">
      <div style={{ textAlign: 'center' }}>
        <CounterAnimation
          from={0}
          to={value}
          durationInFrames={duration}
          prefix={prefix}
          suffix={suffix}
          fontSize={fontSize}
          color={color}
        />
        <div
          style={{
            fontSize: fontSize * 0.3,
            color: colors.text.secondary,
            marginTop: 10,
            fontWeight: 500,
          }}
        >
          {label}
        </div>
      </div>
    </FadeIn>
  </Sequence>
)

export const Scene11_Stats: React.FC<Scene11Props> = ({ format }) => {
  const isPortrait = format === 'portrait'
  const fontSize = isPortrait ? 72 : 64

  const stats = [
    {
      value: 847,
      label: 'Blocks Cleaned',
      color: colors.green[600],
    },
    {
      value: 84700,
      label: 'Paid to Workers',
      prefix: '$',
      color: colors.gold[500],
    },
    {
      value: 42,
      label: 'Workers Employed',
      color: colors.sky[500],
    },
    {
      value: 12,
      label: 'Tons of Litter Removed',
      suffix: '+',
      color: colors.brown[600],
    },
  ]

  return (
    <AbsoluteFill
      style={{
        backgroundColor: 'white',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: isPortrait ? 40 : 60,
      }}
    >
      {/* Header */}
      <FadeIn delay={0}>
        <div
          style={{
            fontSize: isPortrait ? 40 : 36,
            fontWeight: 700,
            color: colors.text.primary,
            marginBottom: isPortrait ? 60 : 40,
            textAlign: 'center',
          }}
        >
          Our Impact So Far
        </div>
      </FadeIn>

      {/* Stats Grid */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: isPortrait ? '1fr 1fr' : 'repeat(4, 1fr)',
          gap: isPortrait ? 50 : 40,
          width: '100%',
          maxWidth: isPortrait ? 600 : 1400,
        }}
      >
        {stats.map((stat, index) => (
          <StatItem
            key={stat.label}
            value={stat.value}
            label={stat.label}
            prefix={stat.prefix}
            suffix={stat.suffix}
            color={stat.color}
            delay={15 + index * 20}
            duration={60}
            fontSize={fontSize}
          />
        ))}
      </div>
    </AbsoluteFill>
  )
}

export default Scene11_Stats
