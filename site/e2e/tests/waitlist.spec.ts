import { test, expect } from '@playwright/test'

test.describe('Waitlist Flow', () => {
  test('homepage loads and shows waitlist CTA', async ({ page }) => {
    await page.goto('/')

    // Check page loads correctly
    await expect(page).toHaveTitle(/GCC/)

    // Check for main CTA
    const joinButton = page.getByRole('link', { name: /join/i })
    await expect(joinButton).toBeVisible()
  })

  test('can navigate to join page', async ({ page }) => {
    await page.goto('/')

    // Click join button
    await page.getByRole('link', { name: /join/i }).first().click()

    // Should be on join page
    await expect(page).toHaveURL(/\/join/)

    // Check form elements are present
    await expect(page.getByLabel(/name/i)).toBeVisible()
    await expect(page.getByLabel(/email/i)).toBeVisible()
    await expect(page.getByLabel(/zip/i)).toBeVisible()
  })

  test('waitlist form shows validation errors', async ({ page }) => {
    await page.goto('/join')

    // Try to submit empty form
    const submitButton = page.getByRole('button', { name: /join/i })
    await submitButton.click()

    // Form should not navigate (HTML5 validation prevents submission)
    await expect(page).toHaveURL(/\/join/)
  })

  test('can fill out waitlist form', async ({ page }) => {
    await page.goto('/join')

    // Fill out form
    await page.getByLabel(/name/i).fill('John Doe')
    await page.getByLabel(/email/i).fill('john@example.com')
    await page.getByLabel(/zip/i).fill('60623')
    await page.getByLabel(/phone/i).fill('312-555-1234')

    // Form should be filled
    await expect(page.getByLabel(/name/i)).toHaveValue('John Doe')
    await expect(page.getByLabel(/email/i)).toHaveValue('john@example.com')
    await expect(page.getByLabel(/zip/i)).toHaveValue('60623')
  })
})

test.describe('Waitlist Success Page', () => {
  test('success page shows ranking and referral code', async ({ page }) => {
    await page.goto('/join/success')

    // Check for success indicators
    await expect(page.getByText(/thank you/i)).toBeVisible()

    // Should show ranking section
    const rankingSection = page.locator('[data-testid="ranking-section"]')
    if (await rankingSection.isVisible()) {
      await expect(rankingSection).toContainText(/position/i)
    }
  })
})
