/**
 * Generate Upsell Category Images using Gemini API
 *
 * This script uses Google's Gemini 2.0 Flash with native image generation
 * to create professional images for each sponsor calculator upsell category.
 *
 * Usage:
 *   npx tsx scripts/generate-upsell-images.ts
 *
 * Requirements:
 *   - GEMINI_API_KEY environment variable
 *   - Node.js 18+
 */

import { writeFile, mkdir } from 'fs/promises'
import { existsSync } from 'fs'
import path from 'path'

const GEMINI_API_KEY = process.env.GEMINI_API_KEY

if (!GEMINI_API_KEY) {
  console.error('❌ GEMINI_API_KEY environment variable is required')
  console.error('   Set it with: export GEMINI_API_KEY=your_api_key')
  process.exit(1)
}

// Output directory for generated images
const OUTPUT_DIR = path.join(process.cwd(), 'public/images/upsells/generated')

// Image generation prompts for each upsell category
const UPSELL_CATEGORIES = [
  {
    id: 'worker-branding',
    filename: 'worker-branding.png',
    prompt: `Create an image of diverse community workers in a Chicago neighborhood wearing bright yellow/gold branded safety vests with a company logo. They are cleaning a city street with brooms and equipment. The workers look proud and professional. Sunny day, urban setting with brick buildings. High quality, documentary style photography. Show 3-4 workers of different ethnicities working together.`,
  },
  {
    id: 'equipment-branding',
    filename: 'equipment-branding.png',
    prompt: `Create an image of branded cleaning equipment arranged neatly: yellow/gold colored brooms with logo wraps on handles, matching rakes, a rolling trash bin with company branding, and branded trash bags. Clean white/gray background or urban street setting. Commercial quality, well-lit, showing premium equipment branding opportunity.`,
  },
  {
    id: 'street-advertising',
    filename: 'street-advertising.png',
    prompt: `Create an image of an urban Chicago street scene showing professional street-level advertising: a "This block cleaned by [SPONSOR]" yard sign on a freshly cleaned sidewalk, a vinyl banner hung between poles, and an A-frame sign on the sidewalk. Clean street, brick buildings in background. Sunny day. The signs are yellow/gold and white, looking professional and high-quality.`,
  },
  {
    id: 'digital-presence',
    filename: 'digital-presence.png',
    prompt: `Create an image of a modern social media mockup showing a smartphone displaying an Instagram post with before/after street cleaning photos. The post shows "@SponsorBrand" tagged. Include floating social media engagement icons (hearts, comments). Clean, modern tech aesthetic with blue/sky accent colors. Professional marketing visualization.`,
  },
  {
    id: 'enhanced-cleaning',
    filename: 'enhanced-cleaning.png',
    prompt: `Create an image showing dramatic before and after of pressure washing a dirty urban sidewalk. Left side shows grimy, stained concrete. Right side shows sparkling clean concrete. A worker in yellow/gold vest operating a pressure washer. Chicago urban setting. Bright, clear day. Documentary photography style showing transformation.`,
  },
  {
    id: 'seasonal-services',
    filename: 'seasonal-services.png',
    prompt: `Create an image showing seasonal cleaning services: workers in yellow/gold branded vests doing winter snow removal, shoveling a Chicago sidewalk with snow. Professional documentary style photography showing community service.`,
  },
  {
    id: 'green-services',
    filename: 'green-services.png',
    prompt: `Create an image of a beautiful urban community garden in Chicago with well-maintained raised garden beds full of vegetables and flowers. Workers in yellow/gold vests tending to tree wells with fresh mulch. Green, lush, vibrant. Shows neighborhood beautification and environmental stewardship. Sunny day, professional photography.`,
  },
  {
    id: 'business-engagement',
    filename: 'business-engagement.png',
    prompt: `Create an image of a friendly community interaction scene: a worker in a yellow/gold branded vest handing promotional flyers to a smiling resident on a clean Chicago sidewalk. The resident looks engaged and happy. Professional, warm, community-focused. Documentary style photography showing positive brand interaction.`,
  },
  {
    id: 'events',
    filename: 'events.png',
    prompt: `Create an image of a vibrant Chicago block party scene with a cleaning crew in yellow/gold branded vests keeping the event area clean. Festive decorations, happy diverse crowd, sponsor banners visible. Daytime summer event. Professional event photography showing community celebration and sponsor visibility.`,
  },
  {
    id: 'media',
    filename: 'media.png',
    prompt: `Create an image of a professional photography/videography scene: a photographer with a DSLR camera capturing workers cleaning a Chicago street. A drone flying overhead for aerial footage. Workers in yellow/gold vests looking professional. Modern media production setup. Shows content creation opportunity for sponsors.`,
  },
]

interface GeminiResponse {
  candidates?: Array<{
    content?: {
      parts?: Array<{
        text?: string
        inlineData?: {
          mimeType: string
          data: string
        }
      }>
    }
  }>
  error?: {
    message: string
    code: number
  }
}

/**
 * Generate an image using Gemini 2.0 Flash experimental model
 */
async function generateImage(prompt: string, filename: string): Promise<Buffer | null> {
  console.log(`\n🎨 Generating: ${filename}`)
  console.log(`   Prompt: ${prompt.substring(0, 80)}...`)

  try {
    // Gemini 2.0 Flash with image generation - using the correct config
    const response = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=${GEMINI_API_KEY}`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          contents: [
            {
              parts: [
                {
                  text: prompt,
                },
              ],
            },
          ],
          generationConfig: {
            temperature: 1,
            topK: 40,
            topP: 0.95,
            maxOutputTokens: 8192,
          },
          // Request image generation mode
          safetySettings: [
            {
              category: 'HARM_CATEGORY_HARASSMENT',
              threshold: 'BLOCK_MEDIUM_AND_ABOVE',
            },
            {
              category: 'HARM_CATEGORY_HATE_SPEECH',
              threshold: 'BLOCK_MEDIUM_AND_ABOVE',
            },
            {
              category: 'HARM_CATEGORY_SEXUALLY_EXPLICIT',
              threshold: 'BLOCK_MEDIUM_AND_ABOVE',
            },
            {
              category: 'HARM_CATEGORY_DANGEROUS_CONTENT',
              threshold: 'BLOCK_MEDIUM_AND_ABOVE',
            },
          ],
        }),
      }
    )

    const data: GeminiResponse = await response.json()

    if (!response.ok || data.error) {
      console.error(`   ❌ API Error: ${data.error?.message || response.status}`)

      // Try with Google AI Studio's imagen endpoint
      return await generateWithImagen(prompt, filename)
    }

    // Check for image in response
    const imagePart = data.candidates?.[0]?.content?.parts?.find(
      (part) => part.inlineData?.mimeType?.startsWith('image/')
    )

    if (imagePart?.inlineData?.data) {
      console.log(`   ✅ Generated successfully`)
      return Buffer.from(imagePart.inlineData.data, 'base64')
    }

    // If no image, Gemini 2.0 Flash might return text instead
    const textPart = data.candidates?.[0]?.content?.parts?.find((part) => part.text)
    if (textPart?.text) {
      console.log(`   ℹ️ Model returned text instead of image`)
      console.log(`   Response: ${textPart.text.substring(0, 100)}...`)
    }

    // Try Imagen as fallback
    return await generateWithImagen(prompt, filename)

  } catch (error) {
    console.error(`   ❌ Error: ${error}`)
    return null
  }
}

/**
 * Generate an image using Google's Imagen 4.0 API
 */
async function generateWithImagen(prompt: string, filename: string): Promise<Buffer | null> {
  console.log(`   🔄 Trying Imagen 4.0...`)

  // Try different Imagen 4.0 model variants
  const imagenModels = [
    'imagen-4.0-fast-generate-001',
    'imagen-4.0-generate-001',
  ]

  for (const model of imagenModels) {
    try {
      console.log(`   Trying model: ${model}`)

      // Imagen 4.0 uses the generateImages endpoint
      const response = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateImages?key=${GEMINI_API_KEY}`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            prompt: prompt,
            config: {
              numberOfImages: 1,
              aspectRatio: '16:9',
              outputOptions: {
                mimeType: 'image/png',
              },
            },
          }),
        }
      )

      if (!response.ok) {
        const errorText = await response.text()
        console.log(`   Model ${model} failed: ${response.status} - ${errorText.substring(0, 100)}`)
        continue
      }

      const data = await response.json()

      // Check for generated images in response
      if (data.generatedImages?.[0]?.image?.imageBytes) {
        console.log(`   ✅ Generated with ${model}`)
        return Buffer.from(data.generatedImages[0].image.imageBytes, 'base64')
      }

      // Alternative response format
      if (data.images?.[0]?.bytesBase64Encoded) {
        console.log(`   ✅ Generated with ${model}`)
        return Buffer.from(data.images[0].bytesBase64Encoded, 'base64')
      }

      console.log(`   Model ${model} returned no image data`)

    } catch (error) {
      console.log(`   Model ${model} error: ${error}`)
    }
  }

  // List available models if all failed
  console.log(`   ℹ️ Checking available image models...`)
  try {
    const modelsResp = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models?key=${GEMINI_API_KEY}`
    )
    const modelsData = await modelsResp.json()

    const imageModels = modelsData.models?.filter((m: { name: string; supportedGenerationMethods?: string[] }) =>
      m.name.includes('imagen') ||
      m.supportedGenerationMethods?.includes('generateImage') ||
      m.supportedGenerationMethods?.includes('generateImages')
    )

    if (imageModels?.length > 0) {
      console.log(`   Available image models: ${imageModels.map((m: { name: string }) => m.name).join(', ')}`)
      console.log(`   Supported methods:`)
      imageModels.slice(0, 3).forEach((m: { name: string; supportedGenerationMethods?: string[] }) => {
        console.log(`     ${m.name}: ${m.supportedGenerationMethods?.join(', ') || 'N/A'}`)
      })
    }
  } catch (e) {
    console.log(`   Could not list models: ${e}`)
  }

  return null
}

/**
 * Save image buffer to file
 */
async function saveImage(buffer: Buffer, filename: string): Promise<void> {
  const filepath = path.join(OUTPUT_DIR, filename)
  await writeFile(filepath, buffer)
  console.log(`   💾 Saved: ${filepath}`)
}

/**
 * Main execution
 */
async function main() {
  console.log('🚀 GCC Upsell Image Generator')
  console.log('================================')
  console.log(`📁 Output directory: ${OUTPUT_DIR}`)

  // Create output directory if needed
  if (!existsSync(OUTPUT_DIR)) {
    await mkdir(OUTPUT_DIR, { recursive: true })
    console.log('   Created output directory')
  }

  // First, check what models are available
  console.log('\n📋 Checking available Gemini models...')
  try {
    const modelsResp = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models?key=${GEMINI_API_KEY}`
    )
    const modelsData = await modelsResp.json()

    const relevantModels = modelsData.models?.filter((m: { name: string }) =>
      m.name.includes('gemini-2') || m.name.includes('imagen')
    ).map((m: { name: string }) => m.name)

    if (relevantModels?.length > 0) {
      console.log(`   Found: ${relevantModels.slice(0, 5).join(', ')}${relevantModels.length > 5 ? '...' : ''}`)
    }
  } catch (e) {
    console.log(`   Could not list models: ${e}`)
  }

  const results = {
    success: [] as string[],
    failed: [] as string[],
  }

  for (const category of UPSELL_CATEGORIES) {
    const imageBuffer = await generateImage(category.prompt, category.filename)

    if (imageBuffer) {
      await saveImage(imageBuffer, category.filename)
      results.success.push(category.id)
    } else {
      results.failed.push(category.id)
    }

    // Rate limiting - wait between requests
    await new Promise(resolve => setTimeout(resolve, 2000))
  }

  console.log('\n================================')
  console.log('📊 Generation Summary')
  console.log('================================')
  console.log(`✅ Success: ${results.success.length}/${UPSELL_CATEGORIES.length}`)

  if (results.success.length > 0) {
    console.log(`   Generated: ${results.success.join(', ')}`)
  }

  if (results.failed.length > 0) {
    console.log(`❌ Failed: ${results.failed.length}`)
    console.log(`   Failed: ${results.failed.join(', ')}`)
    console.log('\n💡 Alternatives:')
    console.log('   1. Use Vertex AI with Imagen 3 (requires Google Cloud project)')
    console.log('   2. Use ComfyUI/FLUX locally: /home/codex450/ai-tools/generation/ComfyUI')
    console.log('   3. Keep the current SVG placeholders (they work well!)')
    console.log('   4. Use stock photos from Unsplash or similar')
  }

  if (results.success.length > 0) {
    console.log('\n📝 Next steps:')
    console.log('   1. Review generated images in:', OUTPUT_DIR)
    console.log('   2. Move approved images to public/images/upsells/')
    console.log('   3. Update section components to use .png instead of .svg')
  }
}

main().catch(console.error)
