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
    ]
  },
}

export default nextConfig
