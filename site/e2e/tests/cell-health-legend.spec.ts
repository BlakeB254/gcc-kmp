import { test, expect } from '@playwright/test'

test('cell-health legend renders on the bounties map', async ({ page }) => {
  await page.goto('/bounties')
  const legend = page.getByTestId('cell-health-legend')
  await expect(legend).toBeVisible()
  await expect(legend).toContainText('Clean')
  await expect(legend).toContainText('Never scanned')
})

test('layer toggle hides and shows the cell-health choropleth overlay', async ({ page }) => {
  await page.goto('/bounties')
  await expect(page.getByTestId('cell-health-legend')).toBeVisible()
  await page.getByTestId('cell-health-toggle').click()
  await expect(page.getByTestId('cell-health-legend')).toHaveCount(0)
  await page.getByTestId('cell-health-toggle').click()
  await expect(page.getByTestId('cell-health-legend')).toBeVisible()
})
