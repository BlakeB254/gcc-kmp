import { test, expect } from '@playwright/test'

test('precipitation legend renders on the bounties map', async ({ page }) => {
  await page.goto('/bounties')
  const legend = page.getByTestId('precip-legend')
  await expect(legend).toBeVisible()
  await expect(legend).toContainText('Light')
  await expect(legend).toContainText('Extreme')
})
