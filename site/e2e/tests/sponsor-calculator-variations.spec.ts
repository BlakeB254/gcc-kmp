import { test, expect } from '@playwright/test'

const CARD_SLUGS = [
  'worker-branding',
  'equipment-branding',
  'street-advertising',
  'digital-presence',
  'enhanced-cleaning',
  'seasonal-services',
  'green-services',
  'business-engagement',
  'events',
  'media',
] as const

const SPONSOR_SLUGS = ['monday-coffee', 'del-kar', 'barneys', 'bpp-cubs'] as const

test.describe('Sponsor calculator variation showcases', () => {
  test('all 50 upsell images resolve with HTTP 200', async ({ request, baseURL }) => {
    const base = baseURL ?? 'http://localhost:7350'
    for (const slug of CARD_SLUGS) {
      const baseResp = await request.get(`${base}/images/upsells/${slug}-base.jpg`)
      expect(baseResp.status(), `base image for ${slug}`).toBe(200)
      for (const sponsor of SPONSOR_SLUGS) {
        const r = await request.get(`${base}/images/upsells/${slug}-${sponsor}.jpg`)
        expect(r.status(), `${slug}-${sponsor} image`).toBe(200)
      }
    }
  })

  test('clicking a sponsor chip on Branding tab sets aria-pressed', async ({ page }) => {
    await page.goto('/sponsor/calculator')

    // The calculator is step-based — the Branding step is step 2 in the flow.
    // Navigate into Branding: first select a block count, then hit Next.
    // If the page exposes a tabs control, prefer that; otherwise follow the flow.
    // We wait for any element that contains "Your Brand on the Frontlines"
    // (the Worker Branding card title) to appear.

    // Increment blocks to at least 1 and proceed.
    const plus = page.getByRole('button', { name: /^\+$|increase|add block/i }).first()
    if (await plus.count()) {
      await plus.click().catch(() => {})
    }
    const nextBtn = page.getByRole('button', { name: /next|continue/i }).first()
    if (await nextBtn.count()) {
      await nextBtn.click()
    }

    // Wait for Branding content
    await page.waitForSelector('text=Your Brand on the Frontlines', { timeout: 15_000 })

    // At least one Monday Coffee chip should be visible
    const chip = page.getByRole('button', { name: 'Del Kar Pharmacy' }).first()
    await chip.scrollIntoViewIfNeeded()
    await chip.click()
    await expect(chip).toHaveAttribute('aria-pressed', 'true')
  })
})
