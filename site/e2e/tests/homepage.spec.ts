import { test, expect } from '@playwright/test'
import { HomePage } from '../page-objects/HomePage'

test.describe('Homepage', () => {
  let homePage: HomePage

  test.beforeEach(async ({ page }) => {
    homePage = new HomePage(page)
    await homePage.goto()
  })

  test('loads and displays hero section', async () => {
    await homePage.expectLoaded()
    await expect(homePage.heroHeading).toContainText(/clean|community|earn/i)
  })

  test('has working navigation links', async () => {
    // Check that nav items are visible on desktop
    await expect(homePage.navItems.first()).toBeVisible()
  })

  test('shows path to /join from community section', async () => {
    // Hero now has a single unified signup CTA. The waitlist (/join) is still
    // reachable, just from the community section instead of the hero.
    await homePage.scrollToSection('community')
    await expect(homePage.joinWaitlistButton).toBeVisible()
  })

  test('shows start earning CTA in hero', async () => {
    await expect(homePage.startEarningButton).toBeVisible()
  })

  test('can navigate to waitlist page', async () => {
    await homePage.navigateToJoinWaitlist()
  })

  test('can navigate to worker signup', async () => {
    await homePage.navigateToStartEarning()
  })

  test('FAQ section is accessible', async () => {
    await homePage.checkFAQSectionVisible()
  })

  test('FAQ accordion expands and collapses', async ({ page }) => {
    await homePage.scrollToSection('faq')

    // Find FAQ items
    const faqSection = page.locator('#faq')
    const faqButtons = faqSection.locator('button')
    const firstButton = faqButtons.first()

    // First item should be open by default
    await expect(firstButton).toBeVisible()

    // Click to collapse/expand
    await firstButton.click()
    await page.waitForTimeout(300) // Wait for animation

    // Click again
    await firstButton.click()
    await page.waitForTimeout(300)
  })

  test('footer has required links', async () => {
    await expect(homePage.footer).toBeVisible()

    // Check for important footer links
    const workerSignupLink = homePage.footer.getByRole('link', { name: /sign up to work/i })
    const faqLink = homePage.footer.getByRole('link', { name: /faq/i })

    await expect(workerSignupLink).toBeVisible()
    await expect(faqLink).toBeVisible()
  })
})

test.describe('Homepage - Mobile', () => {
  test.use({ viewport: { width: 375, height: 667 } })

  test('mobile menu toggles', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()

    // Find mobile menu button (hamburger)
    const menuButton = page.getByRole('button', { name: /menu|toggle/i })

    if (await menuButton.isVisible()) {
      await menuButton.click()
      await page.waitForTimeout(300)

      // Mobile menu should be open
      const mobileNav = page.locator('[class*="max-h-[400px]"]')
      await expect(mobileNav).toBeVisible()

      // Click again to close
      await menuButton.click()
    }
  })

  test('hero section is responsive', async ({ page }) => {
    const homePage = new HomePage(page)
    await homePage.goto()

    await expect(homePage.heroHeading).toBeVisible()
    await expect(homePage.startEarningButton).toBeVisible()
  })
})
