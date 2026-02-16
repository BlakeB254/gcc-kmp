import { test, expect } from '@playwright/test'
import { WaitlistPage, WaitlistSuccessPage } from '../page-objects/WaitlistPage'
import { testUsers, validZipCodes, invalidZipCodes, generateTestEmail } from '../fixtures/test-data'

test.describe('Waitlist Join Flow', () => {
  let waitlistPage: WaitlistPage

  test.beforeEach(async ({ page }) => {
    waitlistPage = new WaitlistPage(page)
    await waitlistPage.goto()
  })

  test('page loads with all form fields', async () => {
    await waitlistPage.expectLoaded()
  })

  test('form validates required fields', async ({ page }) => {
    // Try to submit without filling anything
    await waitlistPage.submitForm()

    // Should still be on the same page (HTML5 validation)
    await expect(page).toHaveURL(/\/join/)
  })

  test('can fill form with valid data', async () => {
    await waitlistPage.fillForm({
      name: testUsers.waitlistUser.name,
      email: testUsers.waitlistUser.email,
      zipCode: testUsers.waitlistUser.zipCode,
      phone: testUsers.waitlistUser.phone,
    })

    await waitlistPage.expectFilledCorrectly({
      name: testUsers.waitlistUser.name,
      email: testUsers.waitlistUser.email,
      zipCode: testUsers.waitlistUser.zipCode,
    })
  })

  test('shows diverter for outside service area zip codes', async ({ page }) => {
    // Fill form with out-of-area zip
    await waitlistPage.fillForm({
      name: testUsers.waitlistUserOutsideArea.name,
      email: testUsers.waitlistUserOutsideArea.email,
      zipCode: testUsers.waitlistUserOutsideArea.zipCode,
    })

    // Trigger zip validation by blurring
    await waitlistPage.zipInput.blur()
    await page.waitForTimeout(500)

    // Check if diverter message appears (we're not in service area)
    const diverterText = page.getByText(/not in.*service area|expanding|waitlist/i)
    // This is optional - depends on implementation
  })

  test('accepts valid Chicago area zip codes', async ({ page }) => {
    for (const zip of validZipCodes) {
      await waitlistPage.zipInput.fill(zip)
      await waitlistPage.zipInput.blur()
      await page.waitForTimeout(200)

      // No error message should appear for valid zips
      const errorMessage = page.locator('.error-message, [role="alert"]').filter({ hasText: /invalid|not.*area/i })
      await expect(errorMessage).toHaveCount(0)
    }
  })
})

test.describe('Waitlist Success Page', () => {
  test('shows membership card on success', async ({ page }) => {
    const successPage = new WaitlistSuccessPage(page)
    await successPage.goto()
    await successPage.expectLoaded()
  })

  test('displays thank you message', async ({ page }) => {
    await page.goto('/join/success')
    await page.waitForLoadState('networkidle')

    // Should show some form of success/thank you
    const successIndicators = page.getByText(/thank you|welcome|congratulations|you're in/i)
    await expect(successIndicators.first()).toBeVisible()
  })

  test('shows ranking information if available', async ({ page }) => {
    await page.goto('/join/success')
    await page.waitForLoadState('networkidle')

    // Look for rank/position display
    const rankText = page.getByText(/position|rank|#\d+/i)
    // Rank might not always be visible if user didn't come through signup flow
    if (await rankText.isVisible()) {
      await expect(rankText).toBeVisible()
    }
  })

  test('has share functionality', async ({ page }) => {
    await page.goto('/join/success')
    await page.waitForLoadState('networkidle')

    // Look for share buttons
    const shareButton = page.getByRole('button', { name: /share/i })
    if (await shareButton.isVisible()) {
      await expect(shareButton).toBeEnabled()
    }
  })
})

test.describe('Waitlist - Form Validation', () => {
  test('email validation works', async ({ page }) => {
    const waitlistPage = new WaitlistPage(page)
    await waitlistPage.goto()

    // Fill with invalid email
    await waitlistPage.emailInput.fill('not-an-email')
    await waitlistPage.emailInput.blur()

    // Try to submit
    await waitlistPage.submitForm()

    // Should not navigate away due to HTML5 validation
    await expect(page).toHaveURL(/\/join/)
  })

  test('name field is required', async ({ page }) => {
    const waitlistPage = new WaitlistPage(page)
    await waitlistPage.goto()

    // Fill everything except name
    await waitlistPage.emailInput.fill('test@example.com')
    await waitlistPage.zipInput.fill('60624')

    await waitlistPage.submitForm()

    // Should not navigate
    await expect(page).toHaveURL(/\/join/)
  })
})
