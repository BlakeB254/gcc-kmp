import { ImageResponse } from 'next/og'

export const runtime = 'edge'

export const alt = 'Global Community Cleaners - Clean Streets. Real Money. Your Neighborhood.'
export const size = {
  width: 1200,
  height: 600, // Twitter prefers slightly different aspect ratio
}
export const contentType = 'image/png'

export default async function Image() {
  return new ImageResponse(
    (
      <div
        style={{
          height: '100%',
          width: '100%',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'linear-gradient(135deg, #0b3b1d 0%, #16763a 30%, #155B92 70%, #0e3f66 100%)',
          fontFamily: 'system-ui, sans-serif',
          position: 'relative',
          overflow: 'hidden',
        }}
      >
        {/* Background pattern */}
        <div
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            backgroundImage: 'radial-gradient(circle at 20% 80%, rgba(22, 118, 58, 0.15) 0%, transparent 50%), radial-gradient(circle at 80% 20%, rgba(255, 255, 255, 0.1) 0%, transparent 40%)',
          }}
        />

        {/* Content container */}
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '50px',
            textAlign: 'center',
            position: 'relative',
          }}
        >
          {/* Logo/Brand mark */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '16px',
              marginBottom: '24px',
            }}
          >
            <div
              style={{
                width: '64px',
                height: '64px',
                borderRadius: '16px',
                background: 'linear-gradient(135deg, #16763A 0%, #126431 100%)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '32px',
                boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)',
              }}
            >
              🧹
            </div>
            <span
              style={{
                fontSize: '40px',
                fontWeight: 800,
                color: 'white',
                textShadow: '0 4px 12px rgba(0, 0, 0, 0.3)',
                letterSpacing: '-1px',
              }}
            >
              Global Community Cleaners
            </span>
          </div>

          {/* Main headline */}
          <h1
            style={{
              fontSize: '64px',
              fontWeight: 800,
              color: 'white',
              margin: 0,
              marginBottom: '16px',
              lineHeight: 1.1,
              textShadow: '0 4px 20px rgba(0, 0, 0, 0.4)',
            }}
          >
            Clean Streets. Real Money.
          </h1>

          {/* Tagline */}
          <p
            style={{
              fontSize: '28px',
              color: '#30A0CE',
              fontWeight: 600,
              margin: 0,
              marginBottom: '32px',
              textShadow: '0 2px 8px rgba(0, 0, 0, 0.3)',
            }}
          >
            Earn $100 per block cleaning your neighborhood
          </p>

          {/* Stats badges */}
          <div
            style={{
              display: 'flex',
              gap: '20px',
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                background: 'rgba(255, 255, 255, 0.15)',
                borderRadius: '50px',
                padding: '10px 20px',
              }}
            >
              <span style={{ fontSize: '20px' }}>📍</span>
              <span style={{ color: 'white', fontSize: '18px', fontWeight: 600 }}>
                North Lawndale, Chicago
              </span>
            </div>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                background: 'rgba(22, 118, 58, 0.2)',
                borderRadius: '50px',
                padding: '10px 20px',
              }}
            >
              <span style={{ fontSize: '20px' }}>💰</span>
              <span style={{ color: '#30A0CE', fontSize: '18px', fontWeight: 600 }}>
                100% Goes to Workers
              </span>
            </div>
          </div>
        </div>
      </div>
    ),
    {
      ...size,
    }
  )
}
