/**
 * Image Optimization Script
 * Creates mobile-optimized versions of key images
 */

const sharp = require('sharp')
const path = require('path')
const fs = require('fs')

const IMAGES_DIR = path.join(__dirname, '../public/images')
const OUTPUT_DIR = path.join(__dirname, '../public/images/optimized')

// Sizes for responsive images
const SIZES = {
  mobile: 640,
  tablet: 1024,
  desktop: 1920,
}

// Images to optimize
const IMAGES = ['hero-community.jpg', 'hero-worker.jpg', 'community-street.jpg', 'sweeping-action.jpg']

async function optimizeImage(filename) {
  const inputPath = path.join(IMAGES_DIR, filename)
  const baseName = path.parse(filename).name

  console.log(`Optimizing ${filename}...`)

  for (const [sizeName, width] of Object.entries(SIZES)) {
    // JPEG version
    const jpegOutput = path.join(OUTPUT_DIR, `${baseName}-${sizeName}.jpg`)
    await sharp(inputPath)
      .resize(width, null, { withoutEnlargement: true })
      .jpeg({ quality: 80, progressive: true })
      .toFile(jpegOutput)
    console.log(`  Created: ${baseName}-${sizeName}.jpg`)

    // WebP version (better compression)
    const webpOutput = path.join(OUTPUT_DIR, `${baseName}-${sizeName}.webp`)
    await sharp(inputPath)
      .resize(width, null, { withoutEnlargement: true })
      .webp({ quality: 80 })
      .toFile(webpOutput)
    console.log(`  Created: ${baseName}-${sizeName}.webp`)
  }

  // Get file sizes for comparison
  const originalSize = fs.statSync(inputPath).size
  const mobileSize = fs.statSync(path.join(OUTPUT_DIR, `${baseName}-mobile.jpg`)).size
  console.log(
    `  Original: ${(originalSize / 1024 / 1024).toFixed(2)}MB -> Mobile: ${(mobileSize / 1024).toFixed(0)}KB`
  )
}

async function main() {
  // Create output directory if it doesn't exist
  if (!fs.existsSync(OUTPUT_DIR)) {
    fs.mkdirSync(OUTPUT_DIR, { recursive: true })
  }

  for (const image of IMAGES) {
    const imagePath = path.join(IMAGES_DIR, image)
    if (fs.existsSync(imagePath)) {
      await optimizeImage(image)
    } else {
      console.log(`Skipping ${image} (not found)`)
    }
  }

  console.log('\nDone! Optimized images saved to public/images/optimized/')
}

main().catch(console.error)
