# harnax-cli 设计规格

## 概述

harnax-cli 是一个 TypeScript 编写的命令行工具，以 npm 包形式发布。它为 Harnax Admin REST API 提供完整的 CLI 接口，覆盖 Agent、模型、工具、技能（Skill）、MCP Server、会话、渠道、环境变量、定时任务、API Key、租户、用户等全部资源的管理操作。

## 目标用户

- **开发者**：日常开发调试，快速操作平台资源，CI/CD 集成
- **平台管理员**：管理租户、用户、模型配置、环境变量等全局资源

## 技术栈

| 维度 | 选型 | 版本 | 理由 |
|------|------|------|------|
| 语言 | TypeScript | 5.x | 类型安全，开发体验好 |
| 运行时 | Node.js | >= 18 | 内置 fetch，无需额外 HTTP 库 |
| CLI 框架 | commander.js | ^13 | 成熟、轻量、子命令支持好 |
| 构建工具 | tsup | ^8 | 快速，ESM + CJS 双格式输出 |
| 表格输出 | cli-table3 | ^0.6 | 终端表格渲染 |
| 终端颜色 | chalk | ^5 | 终端着色 |
| 配置管理 | 原生 fs | — | 读写 `~/.harnax/` 目录下的 JSON 文件 |
| 测试 | vitest | ^3 | 快速，TS 原生支持 |
| 代码风格 | ESLint + Prettier | — | 代码规范 |

## 项目位置

```
harnax/                      ← 项目根目录
├── harnax-cli/              ← 新建模块（与 harnax-admin、harnax-agent 同级）
├── harnax-admin/
├── harnax-agent/
├── harnax-channel/
├── harnax-client/
├── ...
└── pom.xml
```

注意：harnax-cli 是独立的 npm/TypeScript 项目，不参与 Maven 构建，与 Java/Kotlin 模块完全解耦。

## 项目结构

```
harnax-cli/
├── package.json
├── tsconfig.json
├── tsup.config.ts
├── vitest.config.ts
├── .eslintrc.json
├── .prettierrc
├── README.md
│
├── src/
│   ├── index.ts                      # 入口文件（#!/usr/bin/env node）
│   ├── commands/
│   │   ├── auth.ts                   # login / logout / refresh-token
│   │   ├── config.ts                 # config set/get/list/use-profile
│   │   ├── agent.ts                  # agent list/get/create/update/delete/toggle
│   │   ├── model.ts                  # model list/get/create/update/delete/toggle
│   │   ├── model-provider.ts         # model-provider list/get/create/update/delete/toggle/test/stats
│   │   ├── tool.ts                   # tool list/get/update/delete/toggle/available/builtin/env-params
│   │   ├── skill.ts                  # skill list/get/create/update/delete/toggle/batch
│   │   ├── skill-repo.ts             # skill-repo list/get/create/update/delete/toggle/active/fetch
│   │   ├── mcp.ts                    # mcp list/get/create/update/delete/toggle/test/list-tools
│   │   ├── session.ts                # session list/get/create/update/delete/toggle/config
│   │   ├── channel.ts                # channel list/get/create/update/delete/toggle
│   │   ├── envvar.ts                 # env-var list/get/create/update/delete/toggle
│   │   ├── task.ts                   # task list/get/create/update/delete/toggle/start/pause/trigger/logs
│   │   ├── apikey.ts                 # api-key list/get/create/update/delete/toggle/regenerate
│   │   ├── tenant.ts                 # tenant list/get/create/delete/toggle/users/add-user/remove-user/update-role
│   │   ├── user.ts                   # user list/get/create/update/delete/toggle
│   │   └── health.ts                 # health / info
│   ├── client/
│   │   └── admin-api.ts             # Admin REST API HTTP 客户端
│   ├── config/
│   │   └── cli-config.ts            # 配置与凭证管理（~/.harnax/）
│   └── output/
│       ├── formatter.ts              # 输出格式化（json / table 切换）
│       └── table.ts                  # 表格渲染工具
│
└── tests/
    ├── commands/
    │   ├── agent.test.ts
    │   ├── model.test.ts
    │   └── ...
    ├── client/
    │   └── admin-api.test.ts
    └── config/
        └── cli-config.test.ts
```

## 命令体系

### 全局选项

所有命令共享以下全局选项：

| 选项 | 说明 | 默认值 |
|------|------|--------|
| `--server-url <url>` | 覆盖配置中的 Admin 服务地址 | 配置文件中的值 |
| `--output <format>` | 输出格式：`json` 或 `table` | `table` |
| `--profile <name>` | 使用指定 profile | `default` |
| `--verbose` | 显示 HTTP 请求/响应详情 | `false` |
| `-h, --help` | 帮助信息 | — |

### 命令树

```
harnax
├── login                              # POST /api/admin/auth/login
├── logout                             # POST /api/admin/auth/logout，清除本地凭证
├── config
│   ├── set <key> <value>              #   设置配置项
│   ├── get <key>                      #   读取配置项
│   ├── list                           #   列出所有配置
│   └── use-profile <name>             #   切换 profile
│
├── agent                              # === Agent 管理 ===
│   ├── list [--name X] [--status N] [--page N] [--size N]
│   │                                  #   GET /api/admin/agents/page
│   ├── get <id>                       #   GET /api/admin/agents/{id}
│   ├── create --name X --model-id X --system-prompt X [...]
│   │                                  #   POST /api/admin/agents
│   ├── update <id> [--name X] [--model-id X] [...]
│   │                                  #   PUT /api/admin/agents/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/agents/{id}
│   └── toggle <id>                    #   PUT /api/admin/agents/toggle/{id}
│
├── model                              # === 模型管理 ===
│   ├── list [--name X] [--provider-id X] [--model-type X] [--status N]
│   │                                  #   GET /api/admin/models/page
│   ├── get <id>                       #   GET /api/admin/models/{id}
│   ├── create --name X --provider-id X --model-type X [...]
│   │                                  #   POST /api/admin/models
│   ├── update <id> [...]              #   PUT /api/admin/models/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/models/{id}
│   └── toggle <id>                    #   PUT /api/admin/models/toggle/{id}
│
├── model-provider                     # === 模型提供商管理 ===
│   ├── list [--name X] [--type X] [--status N]
│   │                                  #   GET /api/admin/model-providers/page
│   ├── get <id>                       #   GET /api/admin/model-providers/{id}
│   ├── create --name X --type X [...]
│   │                                  #   POST /api/admin/model-providers
│   ├── update <id> [...]              #   PUT /api/admin/model-providers/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/model-providers/{id}
│   ├── toggle <id>                    #   PUT /api/admin/model-providers/toggle/{id}
│   ├── test <id>                      #   POST /api/admin/model-providers/{id}/test
│   └── stats <id>                     #   GET /api/admin/model-providers/{id}/stats
│
├── tool                               # === 工具管理 ===
│   ├── list [--keyword X] [--type BUILTIN|CUSTOM|HTTP] [--status N]
│   │                                  #   GET /api/admin/tools/page
│   ├── get <id>                       #   GET /api/admin/tools/{id}
│   ├── update <id> [...]              #   PUT /api/admin/tools/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/tools/{id}
│   ├── toggle <id>                    #   PUT /api/admin/tools/toggle/{id}
│   ├── available [--type X]           #   GET /api/admin/tools/available
│   ├── builtin                        #   GET /api/admin/tools/builtin
│   └── env-params <id>                #   GET /api/admin/tools/{id}/required-env-params
│
├── skill                              # === 技能管理 ===
│   ├── list [--name X] [--repository-id X] [--status N]
│   │                                  #   GET /api/admin/skills/page
│   ├── get <id>                       #   GET /api/admin/skills/{id}
│   ├── create --name X --repository-id X [--description X] [--skillmd X]
│   │                                  #   POST /api/admin/skills
│   ├── update <id> [...]              #   PUT /api/admin/skills/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/skills/{id}
│   ├── toggle <id> --status N         #   PUT /api/admin/skills/toggle/{id}
│   └── batch --repository-id X --names a,b,c
│                                      #   POST /api/admin/skills/batch
│
├── skill-repo                         # === 技能仓库管理 ===
│   ├── list [--name X] [--status N]   #   GET /api/admin/skill-repositories/page
│   ├── get <id>                       #   GET /api/admin/skill-repositories/{id}
│   ├── create --name X --url X [--branch X] [--description X]
│   │                                  #   POST /api/admin/skill-repositories
│   ├── update <id> [...]              #   PUT /api/admin/skill-repositories/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/skill-repositories/{id}
│   ├── toggle <id> --status N         #   PUT /api/admin/skill-repositories/toggle/{id}
│   ├── active                         #   GET /api/admin/skill-repositories/active
│   └── fetch <id>                     #   GET /api/admin/skill-repositories/fetch/{id}
│
├── mcp                                # === MCP Server 管理 ===
│   ├── list [--keyword X] [--type stdio|sse|streamablehttp] [--status N]
│   │                                  #   GET /api/admin/mcp/page
│   ├── get <id>                       #   GET /api/admin/mcp/{id}
│   ├── create --name X --type X [--command X] [--url X] [...]
│   │                                  #   POST /api/admin/mcp
│   ├── update <id> [...]              #   PUT /api/admin/mcp/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/mcp/{id}
│   ├── toggle <id>                    #   PUT /api/admin/mcp/toggle/{id}
│   ├── test <id>                      #   POST /api/admin/mcp/{id}/connectivity-test
│   └── list-tools <id>                #   GET /api/admin/mcp/{id}/list_tools
│
├── session                            # === 会话管理 ===
│   ├── list [--keyword X] [--status N]
│   │                                  #   GET /api/admin/sessions/page
│   ├── get <id>                       #   GET /api/admin/sessions/{id}
│   ├── create --title X --agent-id X [--model-id X] [...]
│   │                                  #   POST /api/admin/sessions
│   ├── update <id> [...]              #   PUT /api/admin/sessions/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/sessions/{id}
│   ├── toggle <id>                    #   PUT /api/admin/sessions/toggle/{id}
│   └── config
│       ├── get <session-id>           #   GET /api/admin/sessions/{id}/config
│       └── update <session-id> [...]  #   PUT /api/admin/sessions/{id}/config
│
├── channel                            # === 渠道管理 ===
│   ├── list [--keyword X] [--type X] [--status N]
│   │                                  #   GET /api/admin/channels/page
│   ├── get <id>                       #   GET /api/admin/channels/{id}
│   ├── create --name X --type X [...]
│   │                                  #   POST /api/admin/channels
│   ├── update <id> [...]              #   PUT /api/admin/channels/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/channels/{id}
│   └── toggle <id>                    #   PUT /api/admin/channels/toggle/{id}
│
├── env-var                            # === 环境变量管理 ===
│   ├── list [--keyword X]             #   GET /api/admin/env-variables/page
│   ├── get <id>                       #   GET /api/admin/env-variables/{id}
│   ├── create --key X --value X [--sensitive] [--enabled]
│   │                                  #   POST /api/admin/env-variables
│   ├── update <id> [...]              #   PUT /api/admin/env-variables/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/env-variables/{id}
│   └── toggle <id>                    #   PUT /api/admin/env-variables/{id}/toggle
│
├── task                               # === 定时任务管理 ===
│   ├── list [--name X] [--agent-id X] [--task-status X]
│   │                                  #   GET /api/admin/agent-tasks/page
│   ├── get <id>                       #   GET /api/admin/agent-tasks/{id}
│   ├── create --name X --agent-id X [...]
│   │                                  #   POST /api/admin/agent-tasks
│   ├── update <id> [...]              #   PUT /api/admin/agent-tasks/{id}
│   ├── delete <id>                    #   DELETE /api/admin/agent-tasks/{id}
│   ├── toggle <id> --status N         #   POST /api/admin/agent-tasks/toggle/{id}
│   ├── start <id>                     #   POST /api/admin/agent-tasks/{id}/start
│   ├── pause <id>                     #   POST /api/admin/agent-tasks/{id}/pause
│   ├── trigger <id>                   #   POST /api/admin/agent-tasks/{id}/trigger
│   └── logs <task-id> [--status X] [--page N] [--size N]
│                                      #   GET /api/admin/agent-tasks/{id}/logs
│
├── api-key                            # === API Key 管理 ===
│   ├── list [--keyword X] [--enabled]
│   │                                  #   GET /api/admin/api-keys/page
│   ├── get <id>                       #   GET /api/admin/api-keys/{id}
│   ├── create --name X [...]          #   POST /api/admin/api-keys
│   ├── update <id> [...]              #   PUT /api/admin/api-keys/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/api-keys/{id}
│   ├── toggle <id>                    #   PUT /api/admin/api-keys/toggle/{id}
│   ├── regenerate <id>                #   POST /api/admin/api-keys/{id}/regenerate
│   └── permanent                      #   GET /api/admin/api-keys/my-permanent-key
│
├── tenant                             # === 租户管理 ===
│   ├── list [--name X] [--status N]   #   GET /api/admin/tenant
│   ├── get <id>                       #   GET /api/admin/tenant/{id}
│   ├── create --name X [...]          #   POST /api/admin/tenant
│   ├── delete <id>                    #   DELETE /api/admin/tenant/{id}
│   ├── toggle <id> --status N         #   PUT /api/admin/tenant/{id}/status
│   ├── users <id> [--page N] [--size N]
│   │                                  #   GET /api/admin/tenant/{id}/users
│   ├── add-user <tenant-id> <user-id>
│   │                                  #   POST /api/admin/tenant/{id}/users
│   ├── remove-user <tenant-id> <user-id>
│   │                                  #   DELETE /api/admin/tenant/{id}/users/{userId}
│   └── update-role <tenant-id> <user-id> --role X
│                                      #   PUT /api/admin/tenant/{id}/users/{userId}/role
│
├── user                               # === 用户管理 ===
│   ├── list [--keyword X] [--status N]
│   │                                  #   GET /api/admin/users/page
│   ├── get <id>                       #   GET /api/admin/users/{id}
│   ├── create --username X --password X [...]
│   │                                  #   POST /api/admin/users
│   ├── update <id> [...]              #   PUT /api/admin/users/update/{id}
│   ├── delete <id>                    #   DELETE /api/admin/users/{id}
│   └── toggle <id>                    #   PUT /api/admin/users/toggle/{id}
│
└── health                             # GET /api/admin/health
```

## 认证与配置

### 配置存储

```
~/.harnax/
├── config.json           # 全局配置（权限 0644）
├── credentials.json      # 凭证信息（权限 0600）
└── profiles/             # 多环境 profile
    ├── dev.json
    └── prod.json
```

### config.json

```json
{
  "currentProfile": "default",
  "defaultOutput": "table",
  "profiles": {
    "default": {
      "serverUrl": "http://localhost:8080"
    },
    "prod": {
      "serverUrl": "https://admin.harnax.com"
    }
  }
}
```

### credentials.json

```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "...",
  "expiresAt": 1722000000,
  "tenantId": 1,
  "username": "admin"
}
```

### 认证流程

1. **登录**：`harnax login --username admin --password xxx`
   - 调用 `POST /api/admin/auth/login`
   - 存储 JWT token 到 `~/.harnax/credentials.json`
   - 文件权限设置为 `0600`

2. **自动附加**：后续所有命令自动从 `credentials.json` 读取 token
   - 设置 `Authorization: Bearer <token>` 请求头

3. **Token 刷新**：token 过期时自动尝试 `POST /api/admin/auth/refresh-token`
   - 刷新失败则提示用户重新登录

4. **登出**：`harnax logout`
   - 调用 `POST /api/admin/auth/logout`
   - 清除本地 `credentials.json`

### 多环境 Profile

```bash
harnax config use-profile prod       # 切换到 prod 环境
harnax config set serverUrl http://x  # 设置当前 profile 的配置
harnax --profile dev agent list       # 单次命令使用指定 profile
```

## AdminApiClient 设计

### 核心接口

```typescript
class AdminApiClient {
  constructor(private baseUrl: string, private token?: string)

  // 通用 CRUD 封装
  async list<T>(path: string, params?: Record<string, string>): Promise<ResultVo<Page<T>>>
  async get<T>(path: string, id: string | number): Promise<ResultVo<T>>
  async create<T>(path: string, body: unknown): Promise<ResultVo<T>>
  async update<T>(path: string, id: string | number, body: unknown): Promise<ResultVo<T>>
  async delete(path: string, id: string | number): Promise<ResultVo<void>>
  async toggle(path: string, id: string | number, status?: number): Promise<ResultVo<void>>

  // 自定义请求（用于非标准 CRUD 的接口）
  async request<T>(method: string, path: string, options?: RequestOptions): Promise<ResultVo<T>>
}
```

### ResultVo 响应结构

与后端保持一致：

```typescript
interface ResultVo<T> {
  code: number       // 200 = 成功
  message: string
  data: T | null
  timestamp: number
}

interface Page<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}
```

### 错误处理

- `code !== 200`：抛出 `ApiError`，显示 `message`
- 网络错误：抛出 `NetworkError`，显示连接失败信息
- 401 响应：自动尝试刷新 token，失败则提示重新登录

## 输出格式

### Table 模式（默认）

```
$ harnax agent list
┌──────────┬──────────────┬────────────┬──────────┬─────────────────────┐
│ ID       │ Name         │ Model      │ Status   │ Created             │
├──────────┼──────────────┼────────────┼──────────┼─────────────────────┤
│ 1        │ 代码助手      │ gpt-4o     │ Active   │ 2024-01-15 10:30:00 │
│ 2        │ 文档生成器    │ claude-3.5 │ Active   │ 2024-01-16 14:20:00 │
│ 3        │ 测试助手      │ gpt-4o-m   │ Disabled │ 2024-01-17 09:15:00 │
└──────────┴──────────────┴────────────┴──────────┴─────────────────────┘
Showing 1-3 of 3 results (page 1/1)
```

### JSON 模式

```
$ harnax agent list --output json
{
  "code": 200,
  "message": "success",
  "data": {
    "list": [
      { "id": 1, "name": "代码助手", "modelId": 10, "status": 1, ... },
      ...
    ],
    "total": 3,
    "pageNum": 1,
    "pageSize": 10
  }
}
```

### 输出原则

- 默认 table 模式，人类友好
- `--output json` 输出原始 API 响应，适合脚本管道和 agent 消费
- `get` 单条记录时 table 模式展示 key-value 详情

## 错误处理

### 终端输出

- 错误信息以红色输出到 `stderr`
- 正常结果输出到 `stdout`
- `--verbose` 模式显示完整 HTTP 请求/响应

### Exit Code

| Code | 含义 |
|------|------|
| 0 | 成功 |
| 1 | 通用错误（API 返回非 200） |
| 2 | 认证错误（token 过期或无效） |
| 3 | 网络错误（无法连接服务器） |
| 4 | 参数错误（缺少必需参数） |

### 示例

```
$ harnax agent get 999
Error: Agent not found (code: 404)

$ harnax agent list
Error: Authentication expired. Please run 'harnax login' to re-authenticate.

$ harnax mcp test 5
Error: Connection test failed (code: 500)
  Details: Connection refused to http://invalid-host:3000
  Request: POST /api/admin/mcp/5/connectivity-test
```

## Skill 管理

CLI 提供完整的 Skill 和 Skill Repository 管理能力，覆盖 harnax 平台的技能市场功能：

### Skill 操作

| 命令 | API 端点 | 说明 |
|------|----------|------|
| `skill list` | `GET /api/admin/skills/page` | 分页查询技能列表 |
| `skill get <id>` | `GET /api/admin/skills/{id}` | 查看技能详情（含 SKILL.md 内容） |
| `skill create` | `POST /api/admin/skills` | 创建技能 |
| `skill update <id>` | `PUT /api/admin/skills/update/{id}` | 更新技能 |
| `skill delete <id>` | `DELETE /api/admin/skills/{id}` | 删除技能 |
| `skill toggle <id>` | `PUT /api/admin/skills/toggle/{id}` | 启用/禁用 |
| `skill batch` | `POST /api/admin/skills/batch` | 批量保存技能 |

### Skill Repository 操作

| 命令 | API 端点 | 说明 |
|------|----------|------|
| `skill-repo list` | `GET /api/admin/skill-repositories/page` | 分页查询仓库列表 |
| `skill-repo get <id>` | `GET /api/admin/skill-repositories/{id}` | 查看仓库详情 |
| `skill-repo create` | `POST /api/admin/skill-repositories` | 创建仓库（GIT/NPM/ZIP） |
| `skill-repo update <id>` | `PUT /api/admin/skill-repositories/update/{id}` | 更新仓库 |
| `skill-repo delete <id>` | `DELETE /api/admin/skill-repositories/{id}` | 删除仓库 |
| `skill-repo toggle <id>` | `PUT /api/admin/skill-repositories/toggle/{id}` | 启用/禁用 |
| `skill-repo active` | `GET /api/admin/skill-repositories/active` | 活跃仓库列表 |
| `skill-repo fetch <id>` | `GET /api/admin/skill-repositories/fetch/{id}` | 拉取远程技能列表 |

### 典型工作流

```bash
# 1. 创建 NPM 类型的技能仓库
harnax skill-repo create --name "my-skills" --url "https://registry.npmjs.org/@harnax/my-skills"

# 2. 从远程仓库拉取可用技能
harnax skill-repo fetch 1

# 3. 批量导入技能
harnax skill batch --repository-id 1 --names "code-review,doc-generator,test-helper"

# 4. 查看已导入的技能
harnax skill list --repository-id 1

# 5. 查看某个技能的 SKILL.md 内容
harnax skill get 5 --output json | jq '.data.skillmd'
```

## package.json

```json
{
  "name": "@harnax/cli",
  "version": "0.1.0",
  "description": "Harnax Admin CLI - manage agents, models, tools, skills, MCP servers and more",
  "type": "module",
  "bin": {
    "harnax": "./dist/index.js"
  },
  "files": [
    "dist/"
  ],
  "scripts": {
    "build": "tsup",
    "dev": "tsup --watch",
    "test": "vitest",
    "test:run": "vitest run",
    "lint": "eslint src/ --ext .ts",
    "format": "prettier --write src/",
    "prepublishOnly": "npm run build"
  },
  "keywords": ["harnax", "cli", "agent", "admin"],
  "license": "UNLICENSED",
  "engines": {
    "node": ">=18"
  },
  "dependencies": {
    "commander": "^13.0.0",
    "cli-table3": "^0.6.5",
    "chalk": "^5.4.0"
  },
  "devDependencies": {
    "typescript": "^5.7.0",
    "tsup": "^8.4.0",
    "vitest": "^3.0.0",
    "@types/node": "^22.0.0",
    "eslint": "^9.0.0",
    "prettier": "^3.4.0"
  }
}
```

## 实现优先级

分三个阶段交付：

### Phase 1：基础框架 + 核心命令

- 项目脚手架（tsup、tsconfig、eslint）
- `AdminApiClient` 通用 CRUD
- `CliConfig` 配置管理
- 输出格式化（table + json）
- `login` / `logout` / `config`
- `agent` 全部子命令
- `health`

### Phase 2：资源管理全覆盖

- `model` + `model-provider`
- `tool`
- `skill` + `skill-repo`
- `mcp`
- `session`
- `env-var`

### Phase 3：运维与高级功能

- `task`（定时任务管理）
- `api-key`
- `tenant` + `user`
- `channel`
- 完善测试覆盖
- README 文档

## 非功能性约束

- **零 Spring 依赖**：纯 Node.js/TypeScript，与 Java 后端完全解耦
- **无外部状态**：所有状态存储在 `~/.harnax/` 本地目录
- **幂等操作**：重复执行不会产生副作用（依赖后端幂等性）
- **CI/CD 友好**：JSON 输出 + exit code + 环境变量配置支持
