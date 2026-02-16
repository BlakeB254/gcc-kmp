import { type Page, type Locator, expect } from '@playwright/test'

export class WorkerSignupPage {
  readonly page: Page
  readonly firstNameInput: Locator
  readonly lastNameInput: Locator
  readonly emailInput: Locator
  readonly phoneInput: Locator
  readonly addressInput: Locator
  readonly over18Checkbox: Locator
  readonly backgroundCheckCheckbox: Locator
  readonly termsCheckbox: Locator
  readonly continueButton: Locator
  readonly submitButton: Locator
  readonly stepIndicator: Locator

  constructor(page: Page) {
    this.page = page
    this.firstNameInput = page.getByLabel(/first name/i)
    this.lastNameInput = page.getByLabel(/last name/i)
    this.emailInput = page.getByLabel(/email/i)
    this.phoneInput = page.getByLabel(/phone/i)
    this.addressInput = page.getByLabel(/address/i)
    this.over18Checkbox = page.getByLabel(/18/i)
    this.backgroundCheckCheckbox = page.getByLabel(/background/i)
    this.termsCheckbox = page.getByLabel(/terms|agree/i)
    this.continueButton = page.getByRole('button', { name: /continue|next/i })
    this.submitButton = page.getByRole('button', { name: /submit|apply/i })
    this.stepIndicator = page.locator('[data-testid="step-indicator"], .step-indicator')
  }

  async goto() {
    await this.page.goto('/worker/signup')
    await this.page.waitForLoadState('networkidle')
  }

  async expectLoaded() {
    await expect(this.page).toHaveURL(/\/worker\/signup/)
  }

  async fillStep1(data: { firstName: string; lastName: string }) {
    await this.firstNameInput.fill(data.firstName)
    await this.lastNameInput.fill(data.lastName)
    await this.continueButton.click()
  }

  async fillStep2(data: { email: string; phone: string; address?: string }) {
    await this.emailInput.fill(data.email)
    await this.phoneInput.fill(data.phone)
    if (data.address) {
      await this.addressInput.fill(data.address)
    }
    await this.continueButton.click()
  }

  async fillStep3() {
    if (await this.over18Checkbox.isVisible()) {
      await this.over18Checkbox.check()
    }
    if (await this.backgroundCheckCheckbox.isVisible()) {
      await this.backgroundCheckCheckbox.check()
    }
    await this.continueButton.click()
  }

  async fillStep4() {
    if (await this.termsCheckbox.isVisible()) {
      await this.termsCheckbox.check()
    }
  }

  async submitApplication() {
    await this.submitButton.click()
  }

  async expectOnStep(stepNumber: number) {
    // Check step indicator or URL
    const stepText = this.page.getByText(new RegExp(`step ${stepNumber}|${stepNumber} of`, 'i'))
    if (await stepText.isVisible()) {
      await expect(stepText).toBeVisible()
    }
  }
}

export class WorkerPendingPage {
  readonly page: Page
  readonly pendingMessage: Locator
  readonly statusText: Locator

  constructor(page: Page) {
    this.page = page
    this.pendingMessage = page.getByText(/pending|review|processing/i)
    this.statusText = page.locator('[data-testid="status"], .status-message')
  }

  async expectLoaded() {
    await expect(this.page).toHaveURL(/\/worker\/pending/)
    await expect(this.pendingMessage).toBeVisible()
  }
}

export class WorkerDashboardPage {
  readonly page: Page
  readonly welcomeMessage: Locator
  readonly availableJobs: Locator
  readonly earningsDisplay: Locator
  readonly statsSection: Locator

  constructor(page: Page) {
    this.page = page
    this.welcomeMessage = page.getByText(/welcome|dashboard/i)
    this.availableJobs = page.locator('[data-testid="available-jobs"], .job-list')
    this.earningsDisplay = page.locator('[data-testid="earnings"], .earnings-display')
    this.statsSection = page.locator('[data-testid="stats"], .stats-section')
  }

  async goto() {
    await this.page.goto('/worker/dashboard')
    await this.page.waitForLoadState('networkidle')
  }

  async expectLoaded() {
    await expect(this.page).toHaveURL(/\/worker\/dashboard/)
  }

  async expectStatsVisible() {
    await expect(this.statsSection).toBeVisible()
  }
}
