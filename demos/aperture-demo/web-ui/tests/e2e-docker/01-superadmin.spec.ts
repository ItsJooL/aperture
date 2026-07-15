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
  // Clear auth state so subsequent tests start clean.
  // The shell nav is expected to expose a logout button; fall back to direct
  // localStorage clear + navigation if the button is not yet present.
  const logoutButton = page.getByRole('button', { name: /sign out|log out/i })
  if (await logoutButton.isVisible({ timeout: 2000 }).catch(() => false)) {
    await logoutButton.click()
    await page.waitForURL('**/login', { timeout: 10000 })
  } else {
    await page.evaluate(() => localStorage.clear())
    await page.goto('/login')
  }
}

test('superadmin can sign in and see admin panel with tenant list', async ({ page }) => {
  await login(page, 'superadmin@aperture.local', 'changeme-local-only')

  // Should land on /dashboard (or somewhere other than /login)
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 10000 })

  // Admin nav link is visible for superadmin
  const adminLink = page.getByRole('link', { name: /^admin$/i })
  await expect(adminLink).toBeVisible({ timeout: 10000 })

  // Navigate to the admin panel
  await adminLink.click()
  await page.waitForURL('**/admin', { timeout: 10000 })

  // Switch to the Tenants tab and assert Acme Corp is listed
  await page.getByRole('tab', { name: 'Tenants' }).click()
  await expect(page.locator('table.responsive-table').getByText('Acme Corp').first()).toBeVisible({ timeout: 10000 })

  await logout(page)
})
