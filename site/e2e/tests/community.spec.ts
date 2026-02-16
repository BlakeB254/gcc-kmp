import { test, expect } from '@playwright/test'

test.describe('Impact Page', () => {
  test('impact page loads correctly', async ({ page }) => {
    await page.goto('/impact')

    // Check page title
    await expect(page).toHaveTitle(/Impact|GCC/i)

    // Check main heading
    await expect(page.getByRole('heading', { level: 1 })).toContainText(/impact/i)
  })

  test('shows impact statistics', async ({ page }) => {
    await page.goto('/impact')

    // Look for stat cards
    const blocksCleanedStat = page.getByText(/blocks cleaned/i)
    const workersEmployedStat = page.getByText(/workers employed/i)

    await expect(blocksCleanedStat.first()).toBeVisible()
    await expect(workersEmployedStat.first()).toBeVisible()
  })

  test('shows recent activity', async ({ page }) => {
    await page.goto('/impact')

    // Look for activity section
    const activitySection = page.getByText(/recent activity/i)
    await expect(activitySection.first()).toBeVisible()
  })

  test('shows environmental impact', async ({ page }) => {
    await page.goto('/impact')

    // Look for environmental section
    const envSection = page.getByText(/environmental/i)
    await expect(envSection.first()).toBeVisible()
  })
})

test.describe('Residents Page', () => {
  test('residents page loads', async ({ page }) => {
    await page.goto('/residents')

    // Check page title
    await expect(page).toHaveTitle(/Resident|GCC/i)

    // Check main heading
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  })

  test('shows community benefits', async ({ page }) => {
    await page.goto('/residents')

    // Look for benefit cards
    const cleanerStreets = page.getByText(/cleaner streets/i)
    await expect(cleanerStreets.first()).toBeVisible()
  })

  test('shows ways to get involved', async ({ page }) => {
    await page.goto('/residents')

    // Look for involvement section
    const involvedSection = page.getByText(/ways to/i)
    await expect(involvedSection.first()).toBeVisible()
  })

  test('shows FAQ section', async ({ page }) => {
    await page.goto('/residents')

    // Look for FAQ
    const faqSection = page.getByText(/frequently asked/i)
    await expect(faqSection.first()).toBeVisible()
  })
})

test.describe('Nominate Page', () => {
  test('nominate page loads', async ({ page }) => {
    await page.goto('/nominate')

    // Check page title
    await expect(page).toHaveTitle(/Nominate|GCC/i)

    // Check main heading
    await expect(page.getByRole('heading', { level: 1 })).toContainText(/nominate/i)
  })

  test('shows nomination form', async ({ page }) => {
    await page.goto('/nominate')

    // Check for form fields
    const nameField = page.getByLabel(/name/i)
    const emailField = page.getByLabel(/email/i)
    const addressField = page.getByLabel(/address|street/i)

    await expect(nameField).toBeVisible()
    await expect(emailField).toBeVisible()
    await expect(addressField).toBeVisible()
  })

  test('form has urgency selector', async ({ page }) => {
    await page.goto('/nominate')

    // Look for urgency field
    const urgencyField = page.getByLabel(/urgency/i)
    await expect(urgencyField).toBeVisible()
  })

  test('form has submit button', async ({ page }) => {
    await page.goto('/nominate')

    // Look for submit button
    const submitButton = page.getByRole('button', { name: /submit|nominate/i })
    await expect(submitButton).toBeVisible()
  })
})

test.describe('Business Page', () => {
  test('business page loads', async ({ page }) => {
    await page.goto('/business')

    // Check page title
    await expect(page).toHaveTitle(/Business|GCC/i)

    // Check main heading
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  })

  test('shows sponsorship programs', async ({ page }) => {
    await page.goto('/business')

    // Look for programs section
    const programsSection = page.getByText(/programs/i)
    await expect(programsSection.first()).toBeVisible()
  })

  test('shows employee matching program', async ({ page }) => {
    await page.goto('/business')

    // Look for employee matching
    const matchingSection = page.getByText(/employee matching/i)
    await expect(matchingSection.first()).toBeVisible()
  })

  test('shows ROI calculator preview', async ({ page }) => {
    await page.goto('/business')

    // Look for calculator
    const calculatorSection = page.getByText(/calculate|impact/i)
    await expect(calculatorSection.first()).toBeVisible()
  })
})

test.describe('Business Dashboard Page', () => {
  test('business dashboard loads', async ({ page }) => {
    await page.goto('/business/dashboard')

    // Check page title
    await expect(page).toHaveTitle(/Dashboard|Business|GCC/i)

    // Check for dashboard elements
    const dashboardHeader = page.getByText(/dashboard/i)
    await expect(dashboardHeader.first()).toBeVisible()
  })

  test('shows sponsorship stats', async ({ page }) => {
    await page.goto('/business/dashboard')

    // Look for stats
    const blocksSponsored = page.getByText(/blocks sponsored/i)
    await expect(blocksSponsored.first()).toBeVisible()
  })
})
