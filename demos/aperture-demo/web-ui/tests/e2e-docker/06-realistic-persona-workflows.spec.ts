import { expect, test } from '@playwright/test'
import { expectTableContains, installPageGuards, login, logout, searchAndExpectTableContains, selectOptionContaining, uniqueName, users } from './helpers'

test.setTimeout(60000)

const total642 = /[€$]642\.00/

test('Acme accountant creates customer, product and linked invoice', async ({ page }, testInfo) => {
  const assertCleanPage = await installPageGuards(page)
  const customerName = uniqueName('E2E Customer', testInfo)
  const productName = uniqueName('E2E Product', testInfo)
  const sku = `E2E-${Date.now()}`

  await login(page, users.acmeAccountant)

  await page.getByRole('link', { name: /^customers$/i }).click()
  await expect(page).toHaveURL(/\/customers$/)
  await page.getByRole('button', { name: 'New customer' }).click()
  await page.getByLabel('Full name').fill(customerName)
  await page.getByLabel('Email').fill(`${sku.toLowerCase()}@example.com`)
  await page.getByLabel('Phone').fill('+353 1 555 0199')
  await page.getByRole('button', { name: 'Save customer' }).click()
  await expect(page.getByText('Customer created')).toBeVisible({ timeout: 10000 })
  await searchAndExpectTableContains(page, 'Search customers', customerName, 'customers')

  await page.getByRole('link', { name: /^products$/i }).click()
  await expect(page).toHaveURL(/\/products$/)
  await page.getByRole('button', { name: 'New product' }).click()
  await page.getByLabel('Product name').fill(productName)
  await page.getByLabel('SKU').fill(sku)
  await page.getByLabel('Category').fill('Services')
  await page.getByLabel('Unit price').fill('321')
  await page.getByLabel('Description').fill('Created by Docker Playwright persona flow')
  await page.getByRole('button', { name: 'Save product' }).click()
  await expect(page.getByText('Product created')).toBeVisible({ timeout: 10000 })
  await searchAndExpectTableContains(page, 'Search products', productName, 'products')

  await page.getByRole('link', { name: /^invoices$/i }).click()
  await expect(page).toHaveURL(/\/invoices$/)
  await page.locator('header').getByRole('link', { name: 'Create invoice' }).click()
  await expect(page).toHaveURL(/\/invoices\/new$/)
  await selectOptionContaining(page, 'Customer', customerName)
  await page.getByRole('button', { name: 'Continue' }).click()
  await selectOptionContaining(page, 'Product', productName)
  await page.getByLabel('Quantity').fill('2')
  await expect(page.getByText(total642).first()).toBeVisible()
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByText(customerName).first()).toBeVisible()
  await expect(page.getByText(productName).first()).toBeVisible()
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByText(/invoice for [€$]642\.00/)).toBeVisible()
  await page.getByRole('button', { name: 'Create invoice' }).click()
  await expect(page).toHaveURL(/\/invoices\/[^/]+$/, { timeout: 15000 })
  await expect(page.getByText(total642).first()).toBeVisible({ timeout: 15000 })

  await logout(page)
  await assertCleanPage()
})

test('viewer can browse tenant data but cannot create or access admin', async ({ page }) => {
  const assertCleanPage = await installPageGuards(page)

  await login(page, users.acmeViewer)
  await page.getByRole('link', { name: /^customers$/i }).click()
  await expect(page.getByRole('button', { name: 'New customer' })).toHaveCount(0)
  await expectTableContains(page, 'Acme')

  await page.getByRole('link', { name: /^suppliers$/i }).click()
  await expect(page.getByRole('button', { name: 'New supplier' })).toHaveCount(0)
  await expectTableContains(page, 'Acme')

  await page.goto('/admin')
  await expect(page).toHaveURL(/\/forbidden$/)
  await expect(page.getByRole('heading', { name: /permission/i })).toBeVisible()

  await logout(page)
  await assertCleanPage()
})

test('tenant admin can create suppliers and seeded suppliers are visible', async ({ page }, testInfo) => {
  const assertCleanPage = await installPageGuards(page)
  const supplierName = uniqueName('E2E Supplier', testInfo)

  await login(page, users.acmeAdmin)
  await page.getByRole('link', { name: /^suppliers$/i }).click()
  await expect(page).toHaveURL(/\/suppliers$/)
  await expectTableContains(page, 'Acme Supplies')
  await expectTableContains(page, 'Northwind Office Supplies')

  await page.getByRole('button', { name: 'New supplier' }).click()
  await page.getByLabel('Company name').fill(supplierName)
  await page.getByRole('button', { name: 'Save supplier' }).click()
  await expect(page.getByText('Supplier created')).toBeVisible({ timeout: 10000 })
  await searchAndExpectTableContains(page, 'Search suppliers', supplierName, 'suppliers')

  await logout(page)
  await assertCleanPage()
})

test('super admin can open tenant administration and see seeded tenants', async ({ page }) => {
  const assertCleanPage = await installPageGuards(page)

  await login(page, users.superAdmin)
  await page.getByRole('link', { name: /^admin$/i }).click()
  await expect(page).toHaveURL(/\/admin$/)
  await expect(page.getByLabel('Admin tenant context')).toHaveValue(/.+/, { timeout: 15000 })
  await expect(page.locator('table.responsive-table').getByText('accountant@acme.com').first()).toBeVisible({ timeout: 15000 })
  await page.getByRole('tab', { name: 'Tenants' }).click()
  await expect(page.locator('table.responsive-table').getByText('Acme Corp').first()).toBeVisible({ timeout: 15000 })
  await expect(page.locator('table.responsive-table').getByText('TechStart Inc').first()).toBeVisible({ timeout: 15000 })

  await logout(page)
  await assertCleanPage()
})

test('demo persona picker and tenant switcher change real sessions', async ({ page }) => {
  const assertCleanPage = await installPageGuards(page)

  await page.goto('/login')
  await page.getByRole('button', { name: /Acme accountant/i }).click()
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 })
  await expect(page.getByLabel('Demo persona')).toHaveValue('acme-accountant')

  await page.getByLabel('Demo tenant').selectOption('TechStart Inc')
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 })
  await expect(page.getByLabel('Demo persona')).toHaveValue('techstart-admin', { timeout: 15000 })
  await expect(page.getByText('admin@techstart.com')).toBeVisible({ timeout: 15000 })

  await logout(page)
  await assertCleanPage()
})
