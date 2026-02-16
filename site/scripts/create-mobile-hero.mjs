/**
 * Create a portrait-oriented mobile hero image from the landscape source.
 * This extracts a vertical slice centered on the main subjects.
 */
import sharp from 'sharp';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, '..', 'public', 'images');

async function createMobileHero() {
  const sourcePath = path.join(publicDir, 'hero-community.jpg');
  const outputDir = path.join(publicDir, 'optimized');

  // Get source image metadata
  const metadata = await sharp(sourcePath).metadata();
  console.log(`Source image: ${metadata.width}x${metadata.height}`);

  // Target: 640x360 (16:9 landscape aspect ratio)
  // Maximum horizontal coverage - shows nearly full scene width
  // 16:9 captures ~100% of source width (full landscape view)
  const targetWidth = 640;
  const targetHeight = 360;

  // Calculate crop region from center of the source image
  // We want to extract a portrait slice that captures the main subjects
  const sourceAspect = metadata.width / metadata.height;
  const targetAspect = targetWidth / targetHeight;

  let cropWidth, cropHeight, cropLeft, cropTop;

  if (sourceAspect > targetAspect) {
    // Source is wider - crop from sides, use full height
    cropHeight = metadata.height;
    cropWidth = Math.round(cropHeight * targetAspect);
    cropTop = 0;
    // Center horizontally (can adjust if subjects are off-center)
    cropLeft = Math.round((metadata.width - cropWidth) / 2);
  } else {
    // Source is taller - crop from top/bottom, use full width
    cropWidth = metadata.width;
    cropHeight = Math.round(cropWidth / targetAspect);
    cropLeft = 0;
    cropTop = Math.round((metadata.height - cropHeight) / 2);
  }

  console.log(`Crop region: ${cropWidth}x${cropHeight} at (${cropLeft}, ${cropTop})`);

  // Create the portrait mobile image
  const croppedImage = sharp(sourcePath)
    .extract({
      left: cropLeft,
      top: cropTop,
      width: cropWidth,
      height: cropHeight,
    })
    .resize(targetWidth, targetHeight, {
      fit: 'cover',
      position: 'center',
    });

  // Save as WebP (primary)
  await croppedImage
    .clone()
    .webp({ quality: 80 })
    .toFile(path.join(outputDir, 'hero-community-mobile.webp'));
  console.log('Created: hero-community-mobile.webp');

  // Save as JPEG (fallback)
  await croppedImage
    .clone()
    .jpeg({ quality: 80, progressive: true })
    .toFile(path.join(outputDir, 'hero-community-mobile.jpg'));
  console.log('Created: hero-community-mobile.jpg');

  // Verify the output
  const outputMeta = await sharp(path.join(outputDir, 'hero-community-mobile.jpg')).metadata();
  console.log(`Output image: ${outputMeta.width}x${outputMeta.height}`);
}

createMobileHero().catch(console.error);
