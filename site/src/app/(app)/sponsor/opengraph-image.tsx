import { ImageResponse } from 'next/og'

export const runtime = 'edge'

export const alt = 'Sponsor a Block - $100 funds one block cleaning. 100% goes to workers.'
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
          background: 'linear-gradient(135deg, #155B92 0%, #0e3f66 50%, #0b3b1d 100%)',
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
            left: '-100px',
            width: '400px',
            height: '400px',
            borderRadius: '50%',
            background: 'rgba(22, 118, 58, 0.15)',
          }}
        />
        <div
          style={{
            position: 'absolute',
            bottom: '-80px',
            right: '-80px',
            width: '350px',
            height: '350px',
            borderRadius: '50%',
            background: 'rgba(34, 197, 94, 0.1)',
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
              background: 'rgba(34, 197, 94, 0.2)',
              borderRadius: '50px',
              padding: '12px 24px',
              marginBottom: '24px',
            }}
          >
            <span style={{ fontSize: '24px' }}>💚</span>
            <span style={{ color: '#22c55e', fontSize: '20px', fontWeight: 600 }}>
              100% TO WORKERS
            </span>
          </div>

          {/* Main headline */}
          <h1
            style={{
              fontSize: '72px',
              fontWeight: 800,
              color: 'white',
              margin: 0,
              marginBottom: '16px',
              lineHeight: 1.1,
              textShadow: '0 4px 20px rgba(0, 0, 0, 0.4)',
            }}
          >
            Sponsor a Block
          </h1>

          <p
            style={{
              fontSize: '36px',
              color: '#30A0CE',
              fontWeight: 700,
              margin: 0,
              marginBottom: '32px',
            }}
          >
            $100 = One Clean Block
          </p>

          {/* Impact stats */}
          <div
            style={{
              display: 'flex',
              gap: '32px',
              marginBottom: '40px',
            }}
          >
            <div
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                background: 'rgba(255, 255, 255, 0.1)',
                borderRadius: '16px',
                padding: '20px 32px',
              }}
            >
              <span style={{ fontSize: '36px', fontWeight: 800, color: 'white' }}>$100</span>
              <span style={{ color: 'rgba(255, 255, 255, 0.7)', fontSize: '16px' }}>
                Direct to Worker
              </span>
            </div>
            <div
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                background: 'rgba(255, 255, 255, 0.1)',
                borderRadius: '16px',
                padding: '20px 32px',
              }}
            >
              <span style={{ fontSize: '36px', fontWeight: 800, color: 'white' }}>1</span>
              <span style={{ color: 'rgba(255, 255, 255, 0.7)', fontSize: '16px' }}>
                Block Cleaned
              </span>
            </div>
            <div
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                background: 'rgba(255, 255, 255, 0.1)',
                borderRadius: '16px',
                padding: '20px 32px',
              }}
            >
              <span style={{ fontSize: '36px', fontWeight: 800, color: 'white' }}>∞</span>
              <span style={{ color: 'rgba(255, 255, 255, 0.7)', fontSize: '16px' }}>
                Community Impact
              </span>
            </div>
          </div>

          {/* Location */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
            }}
          >
            <span style={{ fontSize: '20px' }}>📍</span>
            <span style={{ color: 'rgba(255, 255, 255, 0.7)', fontSize: '20px' }}>
              Supporting North Lawndale, Chicago
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
            🧹 Global Community Cleaners — globalcommunitycleaners.org/sponsor
          </span>
        </div>
      </div>
    ),
    {
      ...size,
    }
  )
}
