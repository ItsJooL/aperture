import { expect, test } from '@playwright/test'

test.setTimeout(30000)

async function login(page: import('@playwright/test').Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.waitForURL((url) => !url.pathname.endsWith('/login'), { timeout: 15000 })
}

async function logout(page: import('@playwright/test').Page) {
  await page.evaluate(() => localStorage.clear())
  await page.goto('/login')
}

async function navigateToCustomers(page: import('@playwright/test').Page) {
  await page.goto('/customers')
  await page.waitForURL('**/customers', { timeout: 10000 })
  // Wait for loading state to resolve before asserting content
  await expect(page.locator('table.responsive-table, .empty-state')).toBeVisible({ timeout: 15000 })
}

test('Acme admin cannot see TechStart customers', async ({ page }) => {
  await login(page, 'admin@acme.com', 'AcmeAdmin123!')
  await navigateToCustomers(page)

  // TechStart customers must NOT appear in the Acme tenant
  await expect(page.getByText('DevCorp Ltd')).not.toBeVisible()
  await expect(page.getByText('BuildFast Inc')).not.toBeVisible()

  await logout(page)
})

test('TechStart admin cannot see Acme customers', async ({ page }) => {
  await login(page, 'admin@techstart.com', 'TechAdmin123!')
  await navigateToCustomers(page)

  // Acme customers must NOT appear in the TechStart tenant
  await expect(page.getByText('Acme Corp HQ')).not.toBeVisible()

  await logout(page)
})
