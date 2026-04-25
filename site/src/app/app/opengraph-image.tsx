import { ImageResponse } from 'next/og'
import { readFile } from 'fs/promises'
import { join } from 'path'

export const runtime = 'nodejs'

export const alt =
  'Get the GCC app — see every block, pay your neighbor, earn $100 per block.'
export const size = { width: 1200, height: 630 }
export const contentType = 'image/png'

export default async function Image() {
  const logoData = await readFile(join(process.cwd(), 'public/images/gcc-logo.png'))
  const logoSrc = `data:image/png;base64,${logoData.toString('base64')}`

  return new ImageResponse(
    (
      <div
        style={{
          height: '100%',
          width: '100%',
          display: 'flex',
          background: 'linear-gradient(135deg, #0E5226 0%, #16763A 50%, #0A3A1C 100%)',
          position: 'relative',
          overflow: 'hidden',
        }}
      >
        {/* Soft radial highlight upper-left */}
        <div
          style={{
            position: 'absolute',
            top: -160,
            left: -120,
            width: 540,
            height: 540,
            borderRadius: '50%',
            background: 'rgba(255, 255, 255, 0.06)',
          }}
        />
        {/* Amber accent — references the app's heatmap glow */}
        <div
          style={{
            position: 'absolute',
            bottom: -120,
            right: -100,
            width: 460,
            height: 460,
            borderRadius: '50%',
            background: 'rgba(255, 107, 53, 0.18)',
          }}
        />

        {/* Left column: hero copy */}
        <div
          style={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            padding: '60px',
          }}
        >
          <img
            src={logoSrc}
            width={88}
            height={88}
            style={{ marginBottom: 28 }}
            alt="GCC"
          />
          <div
            style={{
              fontSize: 76,
              fontWeight: 800,
              color: 'white',
              letterSpacing: '-0.02em',
              lineHeight: 1.05,
              marginBottom: 18,
            }}
          >
            Get the GCC app.
          </div>
          <div
            style={{
              fontSize: 32,
              color: 'rgba(255,255,255,0.92)',
              lineHeight: 1.25,
              marginBottom: 36,
            }}
          >
            See every block. Pay your neighbor. Earn $100 per block.
          </div>

          {/* Stats strip */}
          <div style={{ display: 'flex', gap: 28 }}>
            {[
              { v: '$100', l: 'per block' },
              { v: '100%', l: 'to worker' },
              { v: 'Same-day', l: 'payout' },
            ].map((s) => (
              <div
                key={s.v}
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  background: 'rgba(255,255,255,0.10)',
                  borderRadius: 18,
                  padding: '16px 22px',
                  minWidth: 140,
                }}
              >
                <span style={{ color: 'white', fontSize: 32, fontWeight: 800 }}>{s.v}</span>
                <span
                  style={{
                    color: 'rgba(255,255,255,0.75)',
                    fontSize: 16,
                    marginTop: 4,
                  }}
                >
                  {s.l}
                </span>
              </div>
            ))}
          </div>
        </div>

        {/* Right column: amber-glow heatmap mark + URL */}
        <div
          style={{
            width: 360,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            borderLeft: '1px solid rgba(255,255,255,0.12)',
            position: 'relative',
            zIndex: 2,
          }}
        >
          {/* Heatmap radial mark */}
          <div
            style={{
              width: 240,
              height: 240,
              borderRadius: '50%',
              background:
                'radial-gradient(circle at center, rgba(255,107,53,0.85) 0%, rgba(255,107,53,0.35) 35%, rgba(255,107,53,0) 70%)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              marginBottom: 28,
            }}
          >
            <div
              style={{
                width: 92,
                height: 92,
                borderRadius: '50%',
                background: '#FF6B35',
                boxShadow: '0 8px 30px rgba(255, 107, 53, 0.5)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: 'white',
                fontSize: 44,
                fontWeight: 800,
              }}
            >
              ●
            </div>
          </div>
          <div
            style={{
              color: 'rgba(255,255,255,0.92)',
              fontSize: 22,
              fontWeight: 700,
              letterSpacing: '0.06em',
              textTransform: 'uppercase',
              marginBottom: 8,
            }}
          >
            Live heatmap
          </div>
          <div
            style={{
              color: 'rgba(255,255,255,0.7)',
              fontSize: 17,
              textAlign: 'center',
              padding: '0 24px',
              lineHeight: 1.4,
            }}
          >
            Every block scheduled, cleaning, or done — refreshed in real time.
          </div>
        </div>

        {/* Footer URL bar */}
        <div
          style={{
            position: 'absolute',
            left: 0,
            right: 0,
            bottom: 0,
            background: 'rgba(0,0,0,0.4)',
            padding: '14px 60px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            color: 'rgba(255,255,255,0.85)',
            fontSize: 18,
          }}
        >
          <span>globalcommunitycleaners.org/app</span>
          <span>iOS · Android · Free</span>
        </div>
      </div>
    ),
    { ...size },
  )
}
