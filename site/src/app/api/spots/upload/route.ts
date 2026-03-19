/**
 * POST /api/spots/upload - Upload spot report photos
 * Accepts multipart form data with JPEG images.
 * Auto-rotates based on EXIF orientation (fixes landscape phone photos).
 * Stores in Vercel Blob and returns public URLs.
 */
import { NextRequest, NextResponse } from 'next/server'
import { put } from '@vercel/blob'
import { verifyMobileToken } from '@/lib/mobile-auth'
import { processImage } from '@/lib/image-utils'

export const dynamic = 'force-dynamic'
export const maxDuration = 30

export async function POST(request: NextRequest) {
  try {
    // Authenticate via mobile Bearer token
    const authHeader = request.headers.get('authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return NextResponse.json({ data: null, success: false, message: 'Authorization required' }, { status: 401 })
    }
    const token = authHeader.substring(7)
    const auth = await verifyMobileToken(token)
    if (!auth) {
      return NextResponse.json({ data: null, success: false, message: 'Invalid token' }, { status: 401 })
    }

    const formData = await request.formData()
    const urls: string[] = []

    for (const [key, value] of formData.entries()) {
      if (!(value instanceof File)) continue

      // Auto-rotate from EXIF, strip metadata, cap at 2048px
      const raw = await value.arrayBuffer()
      const processed = await processImage(raw)

      const timestamp = Date.now()
      const filename = `spots/${auth.workerId}/${timestamp}-${key}.jpg`

      const blob = await put(filename, processed, {
        access: 'public',
        contentType: 'image/jpeg',
      })

      urls.push(blob.url)
    }

    return NextResponse.json({
      data: { urls },
      success: true,
      message: null,
    })
  } catch (error) {
    console.error('Photo upload error:', error)
    return NextResponse.json({ data: null, success: false, message: 'Failed to upload photos' }, { status: 500 })
  }
}
