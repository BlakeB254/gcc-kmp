import { type Page, type Locator, expect } from '@playwright/test'

export class SponsorPage {
  readonly page: Page
  readonly blockSelection: Locator
  readonly nameInput: Locator
  readonly emailInput: Locator
  readonly companyInput: Locator
  readonly messageInput: Locator
  readonly anonymousCheckbox: Locator
  readonly singleOption: Locator
  readonly monthlyOption: Locator
  readonly checkoutButton: Locator
  readonly blockCards: Locator

  constructor(page: Page) {
    this.page = page
    this.blockSelection = page.locator('[data-testid="block-selection"]')
    this.nameInput = page.getByLabel(/name/i).first()
    this.emailInput = page.getByLabel(/email/i).first()
    this.companyInput = page.getByLabel(/company|organization/i)
    this.messageInput = page.getByLabel(/message/i)
    this.anonymousCheckbox = page.getByLabel(/anonymous/i)
    this.singleOption = page.getByText(/one-time/i)
    this.monthlyOption = page.getByText(/monthly/i)
    this.checkoutButton = page.getByRole('button', { name: /sponsor|checkout/i })
    this.blockCards = page.locator('button').filter({ hasText: /NL-\d+/ })
  }

  async goto() {
    await this.page.goto('/sponsor')
    await this.page.waitForLoadState('networkidle')
  }

  async expectLoaded() {
    await expect(this.page).toHaveURL(/\/sponsor/)
    await expect(this.nameInput).toBeVisible()
  }

  async selectBlock(index: number = 0) {
    const blocks = this.blockCards
    if ((await blocks.count()) > index) {
      await blocks.nth(index).click()
    }
  }

  async selectSponsorType(type: 'single' | 'monthly') {
    if (type === 'single') {
      await this.singleOption.click()
    } else {
      await this.monthlyOption.click()
    }
  }

  async fillSponsorInfo(data: {
    name: string
    email: string
    company?: string
    message?: string
    anonymous?: boolean
  }) {
    await this.nameInput.fill(data.name)
    await this.emailInput.fill(data.email)

    if (data.company) {
      await this.companyInput.fill(data.company)
    }

    if (data.message) {
      await this.messageInput.fill(data.message)
    }

    if (data.anonymous) {
      await this.anonymousCheckbox.check()
    }
  }

  async expectCheckoutButtonEnabled() {
    await expect(this.checkoutButton).toBeEnabled()
  }

  async expectCheckoutButtonDisabled() {
    await expect(this.checkoutButton).toBeDisabled()
  }

  async clickCheckout() {
    await this.checkoutButton.click()
  }
}
