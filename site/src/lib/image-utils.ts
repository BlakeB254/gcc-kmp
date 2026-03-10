/**
 * Server-side image processing utilities.
 *
 * Uses sharp (already installed) to auto-rotate images based on EXIF
 * orientation data and strip metadata. This fixes sideways/upside-down
 * photos taken on phones in landscape mode.
 */
import sharp from 'sharp'

/**
 * Auto-rotate image based on EXIF orientation, strip metadata, and
 * optionally resize to a max dimension. Returns a JPEG buffer.
 *
 * sharp.rotate() with no arguments reads the EXIF Orientation tag and
 * physically rotates the pixel data to match, then removes the tag so
 * every viewer renders it correctly — regardless of CSS or browser support.
 */
export async function processImage(
  input: Buffer | ArrayBuffer | Uint8Array,
  opts?: { maxDimension?: number; quality?: number }
): Promise<Buffer> {
  const maxDim = opts?.maxDimension ?? 2048
  const quality = opts?.quality ?? 85

  const buf = input instanceof Buffer ? input : Buffer.from(new Uint8Array(input))
  return sharp(buf)
    .rotate()               // auto-rotate from EXIF, then strip orientation tag
    .resize(maxDim, maxDim, {
      fit: 'inside',        // scale down only, preserve aspect ratio
      withoutEnlargement: true,
    })
    .jpeg({ quality, mozjpeg: true })
    .toBuffer()
}
