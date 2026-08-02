/**
 * LLM spec generator (design-time tool, route B).
 *
 * Usage:
 *   npm run gen -- --url /agent --goal "测试 Agent 列表页的新建/编辑/删除" [--name agent-crud] [--login]
 *
 * Flow: open the page with Playwright → capture a DOM digest → ask the LLM to
 * write a Playwright spec → save to generated/<name>.spec.ts for human review.
 * Reviewed specs are moved into tests/ and run WITHOUT any LLM involvement.
 */
import 'dotenv/config';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { chromium, type Page } from 'playwright';
import OpenAI from 'openai';

interface Args {
  url: string;
  goal: string;
  name: string;
  login: boolean;
}

function parseArgs(): Args {
  const argv = process.argv.slice(2);
  const get = (flag: string): string | undefined => {
    const i = argv.indexOf(flag);
    return i >= 0 ? argv[i + 1] : undefined;
  };
  const url = get('--url') ?? '/';
  const goal = get('--goal');
  if (!goal) {
    console.error('Usage: npm run gen -- --url /agent --goal "测试目标" [--name my-case] [--login]');
    process.exit(1);
  }
  const name = get('--name') ?? url.replace(/\W+/g, '-').replace(/^-|-$/g, '') || 'case';
  return { url, goal, name, login: argv.includes('--login') };
}

/** Compact DOM digest: interactive elements + headings + visible text. */
async function capturePageDigest(page: Page): Promise<string> {
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(800);
  return page.evaluate(() => {
    const isVisible = (el: Element): boolean => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    };
    const lines: string[] = [`URL: ${location.pathname}`, `TITLE: ${document.title}`, ''];

    const selector = [
      'a[href]', 'button', 'input', 'textarea', 'select',
      '[role="button"]', '[role="tab"]', '[role="menuitem"]',
      '.ant-select-selector', '.ant-table-thead th', '.ant-modal-title',
      'h1', 'h2', 'h3', 'label',
    ].join(',');

    for (const el of Array.from(document.querySelectorAll(selector))) {
      if (!isVisible(el)) continue;
      const tag = el.tagName.toLowerCase();
      const id = el.id ? `#${el.id}` : '';
      const name = el.getAttribute('name') ? ` name=${el.getAttribute('name')}` : '';
      const ph = el.getAttribute('placeholder') ? ` placeholder="${el.getAttribute('placeholder')}"` : '';
      const role = el.getAttribute('role') ? ` role=${el.getAttribute('role')}` : '';
      const text = (el.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 50);
      lines.push(`<${tag}${id}${name}${role}${ph}> ${text}`.trim());
    }
    return lines.slice(0, 250).join('\n');
  });
}

const SYSTEM_PROMPT = `你是资深测试工程师,为 harnax 管理后台(Ant Design Pro + React,中文界面)编写 Playwright 测试。

产出要求:
1. 只输出一个完整的 TypeScript 文件内容,不要输出解释文字或 markdown 围栏。
2. 使用 @playwright/test 的 test/expect;从 './helpers.js' 导入 login/fillLoginForm/getTokenInfo(登录预置直接 await login(page))。
3. 定位优先级: getByRole > getByPlaceholder > getByText > CSS(仅限稳定的 #id 或 .ant-* 结构类)。
4. Ant Design 惯例: 表格 .ant-table-row;弹窗 .ant-modal;确认框 .ant-popconfirm 中点"确 定";消息提示 .ant-message。
5. 测试数据用时间戳后缀避免撞名(如 \`ui-test-\${Date.now()}\`),用例结束前清理自己创建的数据。
6. 每个 expect 配 timeout,页面跳转用 expect(page).toHaveURL。
7. 文件顶部用注释写明用例覆盖点,便于人工审核。`;

async function main(): Promise<void> {
  const args = parseArgs();
  const apiKey = process.env.LLM_API_KEY;
  if (!apiKey) {
    console.error('LLM_API_KEY is required in .env for spec generation.');
    process.exit(1);
  }

  const baseUrl = process.env.BASE_URL ?? 'http://localhost:8000';
  console.log(`[gen] opening ${baseUrl}${args.url} ...`);

  const browser = await chromium.launch({ headless: (process.env.HEADLESS ?? 'true') !== 'false' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  let digest: string;
  try {
    if (args.login) {
      // Same login flow as tests/helpers.ts, so the digest reflects the logged-in UI
      await page.goto(`${baseUrl}/login`);
      await page.locator('#username').fill(process.env.TEST_USERNAME ?? 'admin');
      await page.locator('#password').fill(process.env.TEST_PASSWORD ?? 'admin123');
      await page.locator('#captcha').fill(process.env.CAPTCHA_MASTER_CODE ?? 'UI_TEST_2026');
      await page.getByRole('button', { name: /登\s*录/ }).click();
      await page.waitForURL(/welcome|\/$/, { timeout: 15_000 });
    }
    await page.goto(`${baseUrl}${args.url}`);
    digest = await capturePageDigest(page);
  } finally {
    await browser.close();
  }

  console.log('[gen] page digest captured, asking LLM ...');
  const client = new OpenAI({ baseURL: process.env.LLM_BASE_URL, apiKey });
  const resp = await client.chat.completions.create({
    model: process.env.LLM_MODEL ?? 'deepseek-chat',
    temperature: 0,
    messages: [
      { role: 'system', content: SYSTEM_PROMPT },
      {
        role: 'user',
        content: `## 测试目标\n${args.goal}\n\n## 参考:现有 helpers(tests/helpers.ts)\n${readHelpers()}\n\n## 页面结构摘要(${args.url})\n${digest}\n\n请输出完整的 spec 文件内容:`,
      },
    ],
  });

  let code = resp.choices[0]?.message?.content ?? '';
  code = code.replace(/^```(?:ts|typescript)?\s*/m, '').replace(/```\s*$/m, '').trim();

  mkdirSync('generated', { recursive: true });
  const outPath = `generated/${args.name}.spec.ts`;
  writeFileSync(outPath, code + '\n');
  console.log(`[gen] draft written to ${outPath}`);
  console.log('[gen] 请人工审核后移动到 tests/ 目录固化: mv ' + outPath + ' tests/');
}

function readHelpers(): string {
  try {
    return readFileSync('tests/helpers.ts', 'utf-8');
  } catch {
    return '(helpers.ts not found)';
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
