import { type Page, type Locator, expect } from '@playwright/test'

export class WaitlistPage {
  readonly page: Page
  readonly nameInput: Locator
  readonly emailInput: Locator
  readonly zipInput: Locator
  readonly phoneInput: Locator
  readonly submitButton: Locator
  readonly form: Locator

  constructor(page: Page) {
    this.page = page
    this.nameInput = page.getByLabel(/name/i)
    this.emailInput = page.getByLabel(/email/i)
    this.zipInput = page.getByLabel(/zip/i)
    this.phoneInput = page.getByLabel(/phone/i)
    this.submitButton = page.getByRole('button', { name: /join/i })
    this.form = page.locator('form')
  }

  async goto() {
    await this.page.goto('/join')
    await this.page.waitForLoadState('networkidle')
  }

  async expectLoaded() {
    await expect(this.nameInput).toBeVisible()
    await expect(this.emailInput).toBeVisible()
    await expect(this.zipInput).toBeVisible()
  }

  async fillForm(data: {
    name: string
    email: string
    zipCode: string
    phone?: string
  }) {
    await this.nameInput.fill(data.name)
    await this.emailInput.fill(data.email)
    await this.zipInput.fill(data.zipCode)
    if (data.phone) {
      await this.phoneInput.fill(data.phone)
    }
  }

  async submitForm() {
    await this.submitButton.click()
  }

  async expectFormValidation(field: 'name' | 'email' | 'zip') {
    const inputs = {
      name: this.nameInput,
      email: this.emailInput,
      zip: this.zipInput,
    }
    await expect(inputs[field]).toBeFocused()
  }

  async expectFilledCorrectly(data: {
    name: string
    email: string
    zipCode: string
  }) {
    await expect(this.nameInput).toHaveValue(data.name)
    await expect(this.emailInput).toHaveValue(data.email)
    await expect(this.zipInput).toHaveValue(data.zipCode)
  }
}

export class WaitlistSuccessPage {
  readonly page: Page
  readonly membershipCard: Locator
  readonly rankDisplay: Locator
  readonly tierBadge: Locator
  readonly shareButtons: Locator
  readonly downloadButton: Locator

  constructor(page: Page) {
    this.page = page
    this.membershipCard = page.locator('[data-testid="membership-card"], .membership-card').first()
    this.rankDisplay = page.locator('[data-testid="rank-display"], [data-rank]').first()
    this.tierBadge = page.locator('[data-tier]').first()
    this.shareButtons = page.locator('[data-testid="share-buttons"], .share-buttons').first()
    this.downloadButton = page.getByRole('button', { name: /download|save/i }).first()
  }

  async goto() {
    await this.page.goto('/join/success')
    await this.page.waitForLoadState('networkidle')
  }

  async expectLoaded() {
    // Success page should show thank you message or membership card
    const thankYou = this.page.getByText(/thank you|congratulations|welcome/i)
    await expect(thankYou).toBeVisible()
  }

  async expectMembershipCardVisible() {
    await expect(this.membershipCard).toBeVisible()
  }

  async expectTierDisplayed() {
    const tier = await this.tierBadge.getAttribute('data-tier')
    expect(tier).toBeTruthy()
  }

  async clickDownloadCard() {
    if (await this.downloadButton.isVisible()) {
      await this.downloadButton.click()
    }
  }
}
