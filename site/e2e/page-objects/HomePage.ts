import { type Page, type Locator, expect } from '@playwright/test'

export class HomePage {
  readonly page: Page
  readonly heroHeading: Locator
  readonly joinWaitlistButton: Locator
  readonly startEarningButton: Locator
  readonly workerLoginLink: Locator
  readonly navItems: Locator
  readonly faqSection: Locator
  readonly footer: Locator

  constructor(page: Page) {
    this.page = page
    this.heroHeading = page.locator('h1').first()
    // Hero collapsed to a single CTA — /join is now reached via the community section ("Join Movement").
    this.joinWaitlistButton = page.getByRole('link', { name: /join (waitlist|movement)/i }).first()
    this.startEarningButton = page.getByRole('link', { name: /start earning/i }).first()
    this.workerLoginLink = page.getByRole('link', { name: /worker login/i }).first()
    this.navItems = page.locator('nav button')
    this.faqSection = page.locator('#faq')
    this.footer = page.locator('footer')
  }

  async goto() {
    await this.page.goto('/')
    await this.page.waitForLoadState('networkidle')
  }

  async expectLoaded() {
    await expect(this.page).toHaveTitle(/GCC|Global Community Cleaners/i)
    await expect(this.heroHeading).toBeVisible()
  }

  async scrollToSection(sectionId: string) {
    await this.page.evaluate((id) => {
      const element = document.getElementById(id)
      if (element) {
        element.scrollIntoView({ behavior: 'smooth' })
      }
    }, sectionId)
    // Wait for scroll to complete
    await this.page.waitForTimeout(500)
  }

  async navigateToJoinWaitlist() {
    await this.joinWaitlistButton.click()
    await expect(this.page).toHaveURL(/\/join/)
  }

  async navigateToStartEarning() {
    await this.startEarningButton.click()
    await expect(this.page).toHaveURL(/\/worker\/signup/)
  }

  async navigateToWorkerLogin() {
    await this.workerLoginLink.click()
    await expect(this.page).toHaveURL(/\/worker\/login/)
  }

  async checkFAQSectionVisible() {
    await this.scrollToSection('faq')
    await expect(this.faqSection).toBeVisible()
  }

  async expandFAQItem(index: number) {
    const faqButtons = this.faqSection.locator('button')
    await faqButtons.nth(index).click()
  }
}
