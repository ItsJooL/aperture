import { expect, test } from '@playwright/test'

test('user can sign in and reach dashboard', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('Username').fill('superadmin@framework.local')
  await page.getByLabel('Password').fill('changeme-local-only')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 10000 })
})
