import { defineConfig } from '@playwright/test';
import 'dotenv/config';

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:8000',
    headless: (process.env.HEADLESS ?? 'true') !== 'false',
    viewport: { width: 1440, height: 900 },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    locale: 'zh-CN',
  },
  // json 报告供本地看板(src/dashboard)读取;html 报告供人工排查
  reporter: [
    ['list'],
    ['json', { outputFile: 'artifacts/last-run.json' }],
    ['html', { outputFolder: 'artifacts/html-report', open: 'never' }],
  ],
  outputDir: 'artifacts/test-output',
});
