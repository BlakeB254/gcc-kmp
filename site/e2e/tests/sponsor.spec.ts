import { test, expect } from '@playwright/test'

test.describe('Sponsor Benefits Page', () => {
  test('benefits page loads correctly', async ({ page }) => {
    await page.goto('/sponsor/benefits')

    // Check page title
    await expect(page).toHaveTitle(/Sponsor|GCC/i)

    // Check main heading
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  })

  test('shows tax deductible messaging', async ({ page }) => {
    await page.goto('/sponsor/benefits')

    // Look for tax deductible mention
    await expect(page.getByText(/tax.?deductible/i)).toBeVisible()
  })

  test('shows sponsorship tiers', async ({ page }) => {
    await page.goto('/sponsor/benefits')

    // Look for tier cards
    const bronzeTier = page.getByText(/bronze/i)
    const silverTier = page.getByText(/silver/i)
    const goldTier = page.getByText(/gold/i)
    const platinumTier = page.getByText(/platinum/i)

    await expect(bronzeTier.first()).toBeVisible()
    await expect(silverTier.first()).toBeVisible()
    await expect(goldTier.first()).toBeVisible()
    await expect(platinumTier.first()).toBeVisible()
  })

  test('has CTA to sponsor', async ({ page }) => {
    await page.goto('/sponsor/benefits')

    // Look for sponsor CTA
    const sponsorLink = page.getByRole('link', { name: /sponsor now/i })
    await expect(sponsorLink.first()).toBeVisible()
  })
})

test.describe('Sponsor Wall Page', () => {
  test('sponsor wall loads', async ({ page }) => {
    await page.goto('/sponsor/wall')

    // Check page title
    await expect(page).toHaveTitle(/Sponsor Wall|GCC/i)

    // Check main heading
    await expect(page.getByRole('heading', { name: /sponsor/i })).toBeVisible()
  })

  test('shows sponsor statistics', async ({ page }) => {
    await page.goto('/sponsor/wall')

    // Look for stats
    const blocksSponsored = page.getByText(/blocks sponsored/i)
    await expect(blocksSponsored.first()).toBeVisible()
  })

  test('displays sponsor cards', async ({ page }) => {
    await page.goto('/sponsor/wall')

    // Look for sponsor cards or names
    // The mock data has sponsors like "Chicago Community Trust"
    const sponsorElement = page.locator('article, [class*="card"], [class*="sponsor"]')
    await expect(sponsorElement.first()).toBeVisible()
  })
})

test.describe('Sponsor Main Page', () => {
  test('sponsor page loads', async ({ page }) => {
    await page.goto('/sponsor')

    // Check page title
    await expect(page).toHaveTitle(/Sponsor|GCC/i)
  })

  test('shows block selection', async ({ page }) => {
    await page.goto('/sponsor')

    // Look for block count or selection UI
    const blocksElement = page.getByText(/block/i)
    await expect(blocksElement.first()).toBeVisible()
  })
})

test.describe('Sponsor Success Page', () => {
  test('success page loads', async ({ page }) => {
    await page.goto('/sponsor/success')

    // Check for success indicators
    await expect(page.getByText(/thank you|success|confirmed/i).first()).toBeVisible()
  })
})
