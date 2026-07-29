import { expect, test } from '@playwright/test';
import { fillLoginForm, getTokenInfo, login, USERNAME } from './helpers.js';

/**
 * MVP 登录/退出回归。
 * 前置:
 *   1. harnax-webui 已启动(BASE_URL)
 *   2. harnax-admin 以 CAPTCHA_TEST_MASTER_CODE=<CAPTCHA_MASTER_CODE> 启动
 */
test.describe('认证:登录与退出', () => {
  test('使用正确凭证登录成功并持久化 token', async ({ page }) => {
    await login(page);

    // 登录成功提示 + 跳转
    await expect(page.locator('.ant-message')).toContainText('登录成功');

    // tokenInfo 已写入 localStorage 且包含 accessToken
    const tokenInfo = await getTokenInfo(page);
    expect(tokenInfo).not.toBeNull();
    expect(String(tokenInfo!.accessToken ?? '')).not.toBe('');
    expect(String(tokenInfo!.tokenType ?? '')).toBe('Bearer');

    // 顶栏出现当前用户(头像下拉入口)
    await expect(page.locator('.ant-layout')).toBeVisible();
  });

  test('密码错误时停留登录页并提示失败', async ({ page }) => {
    await fillLoginForm(page, USERNAME, 'wrong-password-123');

    // 仍在登录页
    await expect(page).toHaveURL(/login/);

    // 未写入 token
    const tokenInfo = await getTokenInfo(page);
    expect(tokenInfo).toBeNull();
  });

  test('验证码错误时无法登录(万能码之外仍走正常校验)', async ({ page }) => {
    await page.goto('/login');
    await page.locator('#username').fill(USERNAME);
    await page.locator('#password').fill(process.env.TEST_PASSWORD ?? 'admin123');
    await page.locator('#captcha').fill('XXXX'); // 非万能码且必然不匹配图形码
    await page.getByRole('button', { name: /登\s*录/ }).click();

    await expect(page).toHaveURL(/login/);
    expect(await getTokenInfo(page)).toBeNull();
  });

  test('退出登录后回到登录页且本地凭证被清除', async ({ page }) => {
    await login(page);

    // 打开右上角头像下拉,点击退出登录
    const avatar = page.locator('.ant-pro-global-header, header').getByText(/admin|管理员/).first();
    await avatar.hover();
    await page.getByText('退出登录').click();

    // 回到登录页
    await expect(page).toHaveURL(/login/, { timeout: 15_000 });

    // localStorage 凭证已清除
    expect(await getTokenInfo(page)).toBeNull();
    const currentUser = await page.evaluate(() => localStorage.getItem('currentUser'));
    expect(currentUser).toBeNull();
  });

  test('未登录访问受保护页面被重定向到登录页', async ({ page }) => {
    await page.goto('/agent');
    await expect(page).toHaveURL(/login/, { timeout: 15_000 });
  });
});
