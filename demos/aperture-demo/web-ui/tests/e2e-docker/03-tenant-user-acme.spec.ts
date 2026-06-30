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
  const logoutButton = page.getByRole('button', { name: /sign out|log out/i })
  if (await logoutButton.isVisible({ timeout: 2000 }).catch(() => false)) {
    await logoutButton.click()
    await page.waitForURL('**/login', { timeout: 10000 })
  } else {
    await page.evaluate(() => localStorage.clear())
    await page.goto('/login')
  }
}

async function checkCustomerListLoads(page: import('@playwright/test').Page) {
  const customersLink = page.getByRole('link', { name: /customers/i })
  await expect(customersLink).toBeVisible({ timeout: 10000 })
  await customersLink.click()
  await page.waitForURL('**/customers', { timeout: 10000 })

  // Wait for the table skeleton to disappear and actual rows to appear
  await expect(page.locator('table.responsive-table tbody tr')).not.toHaveCount(0, { timeout: 15000 })
}

test('Acme accountant can sign in and browse customers', async ({ page }) => {
  await login(page, 'accountant@acme.com', 'Accountant123!')

  await expect(page).not.toHaveURL(/\/login/, { timeout: 10000 })

  // Admin nav link should NOT be present for the Accountant role
  await expect(page.getByRole('link', { name: /^admin$/i })).not.toBeVisible({ timeout: 5000 })

  await checkCustomerListLoads(page)

  await logout(page)
})

test('Acme viewer can sign in and browse customers', async ({ page }) => {
  await login(page, 'viewer@acme.com', 'Viewer123!')

  await expect(page).not.toHaveURL(/\/login/, { timeout: 10000 })

  // Admin nav link should NOT be present for the Viewer role
  await expect(page.getByRole('link', { name: /^admin$/i })).not.toBeVisible({ timeout: 5000 })

  await checkCustomerListLoads(page)

  await logout(page)
})
