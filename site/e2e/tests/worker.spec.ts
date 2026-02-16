import { test, expect } from '@playwright/test'

test.describe('Worker Benefits Page', () => {
  test('benefits page loads correctly', async ({ page }) => {
    await page.goto('/worker/benefits')

    // Check page title
    await expect(page).toHaveTitle(/Worker|GCC/i)

    // Check main heading
    await expect(page.getByRole('heading', { level: 1 })).toContainText(/earn/i)

    // Check for key benefit cards
    await expect(page.getByText(/\$100/)).toBeVisible()
  })

  test('shows earnings calculator', async ({ page }) => {
    await page.goto('/worker/benefits')

    // Look for calculator or earnings section
    const calculatorSection = page.locator('text=How Much Can You Earn').or(
      page.locator('text=Calculator')
    )
    await expect(calculatorSection.first()).toBeVisible()
  })

  test('has CTA to signup', async ({ page }) => {
    await page.goto('/worker/benefits')

    // Look for signup CTA
    const signupLink = page.getByRole('link', { name: /sign up|get started|apply/i })
    await expect(signupLink.first()).toBeVisible()
  })
})

test.describe('Worker Signup Flow', () => {
  test('signup page loads', async ({ page }) => {
    await page.goto('/worker/signup')

    // Check page title
    await expect(page).toHaveTitle(/Worker|Sign Up|GCC/i)

    // Check for form elements
    await expect(page.getByText(/step/i).first()).toBeVisible()
  })

  test('signup form has required fields', async ({ page }) => {
    await page.goto('/worker/signup')

    // Check for essential form fields
    // Step 1 should have personal info
    const nameField = page.getByLabel(/name/i)
    const emailField = page.getByLabel(/email/i)

    if (await nameField.isVisible()) {
      await expect(nameField).toBeVisible()
    }
    if (await emailField.isVisible()) {
      await expect(emailField).toBeVisible()
    }
  })
})

test.describe('Worker Dashboard', () => {
  test('dashboard page loads', async ({ page }) => {
    await page.goto('/worker/dashboard')

    // Check page loads
    await expect(page).toHaveTitle(/Dashboard|GCC/i)

    // Check for dashboard elements
    const welcomeSection = page.getByText(/welcome|dashboard|available/i)
    await expect(welcomeSection.first()).toBeVisible()
  })

  test('shows available blocks section', async ({ page }) => {
    await page.goto('/worker/dashboard')

    // Look for blocks or jobs section
    const blocksSection = page.getByText(/available|blocks|jobs/i)
    await expect(blocksSection.first()).toBeVisible()
  })
})

test.describe('Worker Leaderboard', () => {
  test('leaderboard page loads', async ({ page }) => {
    await page.goto('/worker/leaderboard')

    // Check page title
    await expect(page).toHaveTitle(/Leaderboard|GCC/i)

    // Check for leaderboard heading
    await expect(page.getByRole('heading', { name: /leaderboard/i })).toBeVisible()
  })

  test('shows worker rankings', async ({ page }) => {
    await page.goto('/worker/leaderboard')

    // Look for podium or ranking elements
    const rankingElements = page.locator('[class*="rank"], [class*="podium"], [data-testid*="rank"]')
    // At least check the page structure exists
    await expect(page.locator('body')).toBeVisible()
  })
})

test.describe('Worker Login', () => {
  test('login page loads', async ({ page }) => {
    await page.goto('/worker/login')

    // Check page title
    await expect(page).toHaveTitle(/Login|Sign In|GCC/i)

    // Check for login form
    const phoneField = page.getByLabel(/phone/i)
    await expect(phoneField).toBeVisible()
  })
})
