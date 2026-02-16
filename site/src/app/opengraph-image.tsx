import { ImageResponse } from 'next/og'

export const runtime = 'edge'

export const alt = 'Global Community Cleaners - Clean Streets. Real Money. Your Neighborhood.'
export const size = {
  width: 1200,
  height: 630,
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
          background: 'linear-gradient(135deg, #1a472a 0%, #2d5a3d 30%, #8B7355 70%, #6B5344 100%)',
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
            backgroundImage: 'radial-gradient(circle at 20% 80%, rgba(212, 175, 55, 0.15) 0%, transparent 50%), radial-gradient(circle at 80% 20%, rgba(255, 255, 255, 0.1) 0%, transparent 40%)',
          }}
        />

        {/* Content container */}
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '60px',
            textAlign: 'center',
            position: 'relative',
          }}
        >
          {/* Logo/Brand mark */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '20px',
              marginBottom: '30px',
            }}
          >
            <div
              style={{
                width: '80px',
                height: '80px',
                borderRadius: '20px',
                background: 'linear-gradient(135deg, #D4AF37 0%, #C5A028 100%)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: '40px',
                boxShadow: '0 8px 32px rgba(0, 0, 0, 0.3)',
              }}
            >
              🧹
            </div>
            <span
              style={{
                fontSize: '48px',
                fontWeight: 800,
                color: 'white',
                textShadow: '0 4px 12px rgba(0, 0, 0, 0.3)',
                letterSpacing: '-1px',
              }}
            >
              GCC
            </span>
          </div>

          {/* Main headline */}
          <h1
            style={{
              fontSize: '72px',
              fontWeight: 800,
              color: 'white',
              margin: 0,
              marginBottom: '20px',
              lineHeight: 1.1,
              textShadow: '0 4px 20px rgba(0, 0, 0, 0.4)',
              maxWidth: '900px',
            }}
          >
            Clean Streets.
            <br />
            Real Money.
          </h1>

          {/* Tagline */}
          <p
            style={{
              fontSize: '32px',
              color: '#D4AF37',
              fontWeight: 600,
              margin: 0,
              marginBottom: '40px',
              textShadow: '0 2px 8px rgba(0, 0, 0, 0.3)',
            }}
          >
            Earn $100 per block in your neighborhood
          </p>

          {/* Stats badges */}
          <div
            style={{
              display: 'flex',
              gap: '24px',
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                background: 'rgba(255, 255, 255, 0.15)',
                borderRadius: '50px',
                padding: '12px 24px',
                backdropFilter: 'blur(10px)',
              }}
            >
              <span style={{ fontSize: '24px' }}>📍</span>
              <span style={{ color: 'white', fontSize: '20px', fontWeight: 600 }}>
                North Lawndale, Chicago
              </span>
            </div>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                background: 'rgba(212, 175, 55, 0.2)',
                borderRadius: '50px',
                padding: '12px 24px',
              }}
            >
              <span style={{ fontSize: '24px' }}>💰</span>
              <span style={{ color: '#D4AF37', fontSize: '20px', fontWeight: 600 }}>
                100% to Workers
              </span>
            </div>
          </div>
        </div>

        {/* Bottom bar */}
        <div
          style={{
            position: 'absolute',
            bottom: 0,
            left: 0,
            right: 0,
            height: '60px',
            background: 'rgba(0, 0, 0, 0.3)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '8px',
          }}
        >
          <span style={{ color: 'rgba(255, 255, 255, 0.7)', fontSize: '18px' }}>
            globalcommunitycleaners.org
          </span>
        </div>
      </div>
    ),
    {
      ...size,
    }
  )
}
