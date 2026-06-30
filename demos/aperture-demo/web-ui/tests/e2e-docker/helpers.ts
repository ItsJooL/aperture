import { expect, type Page, type TestInfo } from '@playwright/test'

export type DemoUser = {
  username: string
  password: string
}

export const users = {
  superAdmin: { username: 'superadmin@system.local', password: 'changeme-local-only' },
  acmeAdmin: { username: 'admin@acme.com', password: 'AcmeAdmin123!' },
  acmeAccountant: { username: 'accountant@acme.com', password: 'Accountant123!' },
  acmeViewer: { username: 'viewer@acme.com', password: 'Viewer123!' },
  techStartAdmin: { username: 'admin@techstart.com', password: 'TechAdmin123!' },
} satisfies Record<string, DemoUser>

export function uniqueName(prefix: string, testInfo: TestInfo) {
  const slug = testInfo.title.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').slice(0, 32)
  return `${prefix} ${slug} ${Date.now()}`
}

export async function installPageGuards(page: Page) {
  const consoleErrors: string[] = []
  const failedResponses: string[] = []

  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })

  page.on('response', (response) => {
    const url = response.url()
    if (!url.includes('localhost:8080') && !url.includes('localhost:3780')) return
    if (response.status() >= 400) failedResponses.push(`${response.status()} ${response.request().method()} ${url}`)
  })

  return async () => {
    expect(failedResponses, 'failed UI/API responses').toEqual([])
    expect(consoleErrors, 'browser console errors').toEqual([])
  }
}

export async function login(page: Page, user: DemoUser) {
  await page.goto('/login')
  await page.getByLabel('Username').fill(user.username)
  await page.getByLabel('Password').fill(user.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 })
}

export async function logout(page: Page) {
  const logoutButton = page.getByRole('button', { name: /sign out/i })
  if (await logoutButton.isVisible({ timeout: 2000 }).catch(() => false)) {
    await logoutButton.click()
    await expect(page).toHaveURL(/\/login/, { timeout: 10000 })
    return
  }
  await page.evaluate(() => localStorage.clear())
  await page.goto('/login')
}

export async function expectTableContains(page: Page, text: string) {
  await expect(page.locator('table.responsive-table')).toBeVisible({ timeout: 15000 })
  await expect(page.locator('table.responsive-table').getByText(text, { exact: false }).first()).toBeVisible({ timeout: 15000 })
}

export async function searchAndExpectTableContains(page: Page, label: string, text: string, resource: string) {
  const response = page.waitForResponse((candidate) => {
    const url = candidate.url()
    return candidate.status() < 400 && url.includes(`/${resource}`) && url.includes('filter=')
  }, { timeout: 15000 })
  await page.getByLabel(label).fill(text)
  await response
  await expectTableContains(page, text)
}

export async function selectOptionContaining(page: Page, label: string, text: string) {
  const select = page.getByLabel(label)
  await expect(select).toBeVisible({ timeout: 15000 })
  const matchingOption = select.locator('option').filter({ hasText: text }).first()
  await expect(matchingOption, `option containing "${text}"`).toHaveCount(1, { timeout: 15000 })
  const value = await select.locator('option').evaluateAll((options, optionText) => {
    const option = options.find((candidate) => candidate.textContent?.includes(String(optionText))) as HTMLOptionElement | undefined
    return option?.value
  }, text)
  expect(value, `option containing "${text}"`).toBeTruthy()
  await select.selectOption(value as string)
}
