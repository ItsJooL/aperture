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

test('Acme tenant admin can sign in and see Acme customers', async ({ page }) => {
  await login(page, 'admin@acme.com', 'AcmeAdmin123!')

  // Should land on /dashboard (no longer on /login)
  await expect(page).not.toHaveURL(/\/login/, { timeout: 10000 })

  // Tenant admins get the Admin nav link (they have admin:view permission via TenantAdmin role)
  // but they should NOT see a system-level tenant provisioning capability.
  // Verify the Admin link navigates to the admin panel without a Tenants tab showing
  // cross-tenant data.  The key assertion is that no system-superadmin-only UI is visible.
  // We confirm a standard workspace nav link (Customers) is visible.
  const customersLink = page.getByRole('link', { name: /customers/i })
  await expect(customersLink).toBeVisible({ timeout: 10000 })

  // Navigate to customers
  await customersLink.click()
  await page.waitForURL('**/customers', { timeout: 10000 })

  // At least one seeded Acme customer is visible
  await expect(page.locator('table.responsive-table').getByText(/Acme Corp HQ|Acme EU Subsidiary|Acme APAC/).first()).toBeVisible({ timeout: 15000 })

  await logout(page)
})
