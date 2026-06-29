import { test, expect } from '@playwright/test'

test('precipitation legend renders on the bounties map', async ({ page }) => {
  await page.goto('/bounties')
  const legend = page.getByTestId('precip-legend')
  await expect(legend).toBeVisible()
  await expect(legend).toContainText('Light')
  await expect(legend).toContainText('Extreme')
})

test('layer toggle hides and shows the precipitation overlay', async ({ page }) => {
  await page.goto('/bounties')
  await expect(page.getByTestId('precip-legend')).toBeVisible()
  await page.getByTestId('precip-toggle').click()
  await expect(page.getByTestId('precip-legend')).toHaveCount(0)
  await page.getByTestId('precip-toggle').click()
  await expect(page.getByTestId('precip-legend')).toBeVisible()
})
