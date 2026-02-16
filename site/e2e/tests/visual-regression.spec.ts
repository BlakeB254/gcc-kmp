import { test, expect } from '@playwright/test'
import { HomePage } from '../page-objects/HomePage'

/**
 * Visual Regression Tests
 *
 * These tests capture screenshots for visual comparison.
 * Run `npx playwright test --update-snapshots` to update baselines.
 */

test.describe('Visual Regression - Desktop', () => {
  test.use({ viewport: { width: 1280, height: 720 } })

  test('homepage hero section', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()

    // Wait for animations to settle
    await page.waitForTimeout(2000)

    await expect(page).toHaveScreenshot('homepage-hero-desktop.png', {
      maxDiffPixels: 500, // Allow some pixel variance
      fullPage: false,
    })
  })

  test('homepage full page', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()

    // Wait for all content to load
    await page.waitForTimeout(3000)

    await expect(page).toHaveScreenshot('homepage-full-desktop.png', {
      maxDiffPixels: 1000,
      fullPage: true,
    })
  })

  test('FAQ section expanded', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()
    await homePage.scrollToSection('faq')

    await page.waitForTimeout(1000)

    await expect(page.locator('#faq')).toHaveScreenshot('faq-section-desktop.png', {
      maxDiffPixels: 200,
    })
  })

  test('waitlist join page', async ({ page }) => {
    await page.goto('/join')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1000)

    await expect(page).toHaveScreenshot('waitlist-join-desktop.png', {
      maxDiffPixels: 300,
    })
  })

  test('sponsor page', async ({ page }) => {
    await page.goto('/sponsor')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1000)

    await expect(page).toHaveScreenshot('sponsor-page-desktop.png', {
      maxDiffPixels: 500,
    })
  })
})

test.describe('Visual Regression - Mobile', () => {
  test.use({ viewport: { width: 375, height: 667 } })

  test('homepage hero section mobile', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()

    await page.waitForTimeout(2000)

    await expect(page).toHaveScreenshot('homepage-hero-mobile.png', {
      maxDiffPixels: 500,
    })
  })

  test('mobile navigation open', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()

    // Open mobile menu
    const menuButton = page.getByRole('button', { name: /menu|toggle/i })
    if (await menuButton.isVisible()) {
      await menuButton.click()
      await page.waitForTimeout(500)

      await expect(page).toHaveScreenshot('mobile-nav-open.png', {
        maxDiffPixels: 200,
      })
    }
  })

  test('waitlist join page mobile', async ({ page }) => {
    await page.goto('/join')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(1000)

    await expect(page).toHaveScreenshot('waitlist-join-mobile.png', {
      maxDiffPixels: 300,
    })
  })
})

test.describe('Visual Regression - Components', () => {
  test('header scroll state', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()

    // Scroll down to trigger header state change
    await page.evaluate(() => window.scrollBy(0, 200))
    await page.waitForTimeout(500)

    const header = page.locator('header')
    await expect(header).toHaveScreenshot('header-scrolled.png', {
      maxDiffPixels: 100,
    })
  })

  test('footer section', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()

    // Scroll to footer
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
    await page.waitForTimeout(500)

    const footer = page.locator('footer')
    await expect(footer).toHaveScreenshot('footer.png', {
      maxDiffPixels: 200,
    })
  })
})

test.describe('Visual Regression - Dark Mode', () => {
  test.use({ colorScheme: 'dark' })

  test('homepage respects color scheme', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()

    await page.waitForTimeout(2000)

    // GCC doesn't have dark mode, but this test ensures the page
    // still renders correctly with system dark mode preference
    await expect(page).toHaveScreenshot('homepage-dark-preference.png', {
      maxDiffPixels: 500,
    })
  })
})
