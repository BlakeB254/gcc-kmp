import type { NextConfig } from 'next'

const nextConfig: NextConfig = {
  // Turbopack is now default in Next.js 16, config moved to top-level
  turbopack: {
    // Turbopack-specific options if needed
  },

  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: '*.public.blob.vercel-storage.com',
      },
      {
        protocol: 'https',
        hostname: 'api.mapbox.com',
      },
    ],
  },

  // Enable PWA capabilities
  async headers() {
    return [
      {
        source: '/manifest.json',
        headers: [
          {
            key: 'Content-Type',
            value: 'application/manifest+json',
          },
        ],
      },
      {
        // Apple fetches this with no file extension. Wrong Content-Type
        // (or a catch-all applinks path of "*") makes every website QR
        // open the iOS app instead of Safari.
        source: '/.well-known/apple-app-site-association',
        headers: [
          {
            key: 'Content-Type',
            value: 'application/json',
          },
        ],
      },
    ]
  },
}

export default nextConfig
