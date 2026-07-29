import { expect, type Page } from '@playwright/test';

export const USERNAME = process.env.TEST_USERNAME ?? 'admin';
export const PASSWORD = process.env.TEST_PASSWORD ?? 'admin123';
export const CAPTCHA_MASTER_CODE = process.env.CAPTCHA_MASTER_CODE ?? 'UI_TEST_2026';

/**
 * Fill the login form (账户密码登录 tab) and submit.
 * The captcha field uses the master code, which requires the backend to be
 * started with CAPTCHA_TEST_MASTER_CODE set to the same value.
 */
export async function fillLoginForm(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login');
  // ProForm renders inputs with id = field name
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#captcha').fill(CAPTCHA_MASTER_CODE);
  await page.getByRole('button', { name: /登\s*录/ }).click();
}

/** Log in as the test user and wait until the main layout is visible. */
export async function login(page: Page): Promise<void> {
  await fillLoginForm(page, USERNAME, PASSWORD);
  await expect(page).toHaveURL(/welcome|\/$/, { timeout: 15_000 });
}

export async function getTokenInfo(page: Page): Promise<Record<string, unknown> | null> {
  const raw = await page.evaluate(() => localStorage.getItem('tokenInfo'));
  return raw ? JSON.parse(raw) : null;
}
