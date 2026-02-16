import { ImageResponse } from 'next/og'

export const runtime = 'edge'

export const alt = 'Become a GCC Worker - Earn $100 per block cleaning your neighborhood'
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
          background: 'linear-gradient(135deg, #1a472a 0%, #2d5a3d 50%, #1a472a 100%)',
          fontFamily: 'system-ui, sans-serif',
          position: 'relative',
          overflow: 'hidden',
        }}
      >
        {/* Background accents */}
        <div
          style={{
            position: 'absolute',
            top: '-100px',
            right: '-100px',
            width: '400px',
            height: '400px',
            borderRadius: '50%',
            background: 'rgba(212, 175, 55, 0.1)',
          }}
        />
        <div
          style={{
            position: 'absolute',
            bottom: '-50px',
            left: '-50px',
            width: '300px',
            height: '300px',
            borderRadius: '50%',
            background: 'rgba(255, 255, 255, 0.05)',
          }}
        />

        {/* Content */}
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '60px',
            textAlign: 'center',
          }}
        >
          {/* Badge */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              background: 'rgba(212, 175, 55, 0.2)',
              borderRadius: '50px',
              padding: '12px 24px',
              marginBottom: '24px',
            }}
          >
            <span style={{ fontSize: '24px' }}>💪</span>
            <span style={{ color: '#D4AF37', fontSize: '20px', fontWeight: 600 }}>
              NOW HIRING
            </span>
          </div>

          {/* Main headline */}
          <h1
            style={{
              fontSize: '80px',
              fontWeight: 800,
              color: 'white',
              margin: 0,
              marginBottom: '16px',
              lineHeight: 1,
              textShadow: '0 4px 20px rgba(0, 0, 0, 0.4)',
            }}
          >
            Earn $100
          </h1>

          <p
            style={{
              fontSize: '40px',
              color: '#D4AF37',
              fontWeight: 700,
              margin: 0,
              marginBottom: '32px',
            }}
          >
            Per Block You Clean
          </p>

          {/* Benefits */}
          <div
            style={{
              display: 'flex',
              gap: '24px',
              marginBottom: '40px',
            }}
          >
            {['Same-day Pay', 'Flexible Hours', 'Your Neighborhood'].map((benefit) => (
              <div
                key={benefit}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  background: 'rgba(255, 255, 255, 0.1)',
                  borderRadius: '12px',
                  padding: '12px 20px',
                }}
              >
                <span style={{ color: '#22c55e', fontSize: '20px' }}>✓</span>
                <span style={{ color: 'white', fontSize: '18px', fontWeight: 500 }}>
                  {benefit}
                </span>
              </div>
            ))}
          </div>

          {/* Location */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
            }}
          >
            <span style={{ fontSize: '24px' }}>📍</span>
            <span style={{ color: 'rgba(255, 255, 255, 0.8)', fontSize: '22px' }}>
              North Lawndale, Chicago (60623 & 60624)
            </span>
          </div>
        </div>

        {/* Bottom bar */}
        <div
          style={{
            position: 'absolute',
            bottom: 0,
            left: 0,
            right: 0,
            height: '50px',
            background: 'rgba(0, 0, 0, 0.4)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <span style={{ color: 'rgba(255, 255, 255, 0.7)', fontSize: '18px' }}>
            🧹 Global Community Cleaners — globalcommunitycleaners.org
          </span>
        </div>
      </div>
    ),
    {
      ...size,
    }
  )
}
