# harnax-ui-test

harnax 管理后台的 UI 自动化测试模块。

设计路线:**LLM 生成用例脚本(一次性,人工审核后固化)+ 日常纯 Playwright 运行(零 LLM 消耗)**,
附带本地 Web 看板查看运行状态、结果与截图。

## 目录结构

```
harnax-ui-test/
├── tests/                  # 固化的正式用例(纯 Playwright,日常运行)
│   ├── helpers.ts          # 登录等公共预置
│   └── auth.spec.ts        # MVP:登录/退出 5 条用例
├── generated/              # LLM 生成的用例草稿(待人工审核,gitignore)
├── src/
│   ├── gen.ts              # 生成器:抓取页面结构 → LLM 产出 spec 草稿
│   └── dashboard/          # 本地看板(零依赖 Node HTTP 服务)
├── artifacts/              # 运行产物:JSON/HTML 报告、截图、历史记录(gitignore)
└── playwright.config.ts
```

## 前置条件

1. **前端**已启动:`cd harnax-webui && npm run start:dev`(默认 http://localhost:8000)
2. **后端** harnax-admin 以万能验证码模式启动(仅测试环境!):

   ```bash
   export CAPTCHA_TEST_MASTER_CODE=UI_TEST_2026
   # 再启动 harnax-admin
   ```

   说明:该开关默认关闭(配置为空即禁用),生产环境不设此环境变量则行为与原来完全一致。
3. 初始化本模块:

   ```bash
   cd harnax-ui-test
   npm install
   npm run install-browsers   # 下载 chromium
   cp .env.example .env       # 按需修改 BASE_URL / 账号 / 万能码
   ```

## 日常运行(不消耗 LLM)

```bash
npm test                 # 跑 tests/ 全部用例
npm run test:headed      # 有头模式,本地调试观察浏览器
npx playwright test --grep "登录"   # 按名称过滤
```

产物:
- `artifacts/last-run.json` — 机器可读结果(看板数据源)
- `artifacts/html-report/` — Playwright 官方 HTML 报告
- 失败用例自动截图 + trace

## 测试看板

```bash
npm run dashboard        # http://localhost:4600
```

功能:
- **▶ 运行全部用例** 按钮:一键触发,实时滚动日志
- 当前运行状态(idle / running / passed / failed)
- 历史运行列表(通过/失败/跳过统计),点击查看每条用例结果、错误信息与失败截图

## 用 LLM 生成新用例(路线 B 工作流)

```bash
# 1. 生成草稿(需 .env 配置 LLM_API_KEY;--login 表示先登录再抓取页面结构)
npm run gen -- --url /agent --goal "测试 Agent 列表页:新建一个 Agent,验证出现在列表,再删除它" --name agent-crud --login

# 2. 人工审核草稿
cat generated/agent-crud.spec.ts

# 3. 审核通过后固化(此后运行不再依赖 LLM)
mv generated/agent-crud.spec.ts tests/
npm test
```

生成器会把「页面可交互元素摘要 + helpers 源码 + 测试目标」交给 LLM(OpenAI 兼容协议,
DeepSeek/Qwen/GLM 均可),产出符合本仓库约定的 Playwright spec。

## MVP 用例清单(tests/auth.spec.ts)

| 用例 | 验证点 |
|---|---|
| 正确凭证登录成功 | 跳转、成功提示、localStorage tokenInfo 写入 |
| 密码错误 | 停留登录页、无 token |
| 验证码错误 | 万能码之外仍走正常校验,登录失败 |
| 退出登录 | 回到登录页、tokenInfo/currentUser 清除 |
| 未登录访问受保护页 | 重定向到 /login |

## 后续铺开建议

用 `npm run gen` 依次生成并审核:Agent CRUD → Model/Provider → 用户管理 → Session →
其余页面;每个页面一个 spec 文件,测试数据一律带时间戳后缀并在用例内自清理。
