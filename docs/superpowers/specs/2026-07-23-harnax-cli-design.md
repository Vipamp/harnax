# harnax-cli 设计规格

## 概述

harnax-cli 是一个 Go 编写的命令行工具，编译为单个可执行文件分发。它为 Harnax Admin REST API 提供完整的 CLI 接口，覆盖 Agent、模型、工具、MCP Server、会话、渠道、环境变量、定时任务、API Key、租户、用户等全部资源的管理操作。

## 目标用户

- **开发者**：日常开发调试，快速操作平台资源，CI/CD 集成
- **平台管理员**：管理租户、用户、模型配置、环境变量等全局资源

## 技术栈

| 维度 | 选型 | 版本 | 理由 |
|------|------|------|------|
| 语言 | Go | >= 1.22 | 编译为单文件，跨平台，零运行时依赖 |
| CLI 框架 | cobra | ^1.8 | Go CLI 标准框架，子命令、自动补全、文档生成 |
| HTTP 客户端 | net/http | 标准库 | 零外部依赖 |
| JSON 处理 | encoding/json | 标准库 | 零外部依赖 |
| 表格输出 | tablewriter | ^0.0.5 | 终端表格渲染 |
| 终端颜色 | fatih/color | ^1.18 | 终端着色 |
| 配置管理 | viper | ^1.19 | 配置文件读写，与 cobra 深度集成 |
| 构建发布 | goreleaser | — | 跨平台编译（linux/darwin/windows），GitHub Release |
| 测试 | go test | 标准库 | 内置测试框架 |
| 代码风格 | golangci-lint | — | 静态分析 + 格式化 |

## 项目位置

```
harnax/                      ← 项目根目录
├── harnax-cli/              ← 新建 Go 模块（与 harnax-admin、harnax-agent 同级）
├── harnax-admin/
├── harnax-agent/
├── harnax-channel/
├── harnax-client/
├── ...
└── pom.xml
```

注意：harnax-cli 是独立的 Go module，不参与 Maven 构建，与 Java/Kotlin 模块完全解耦。

## 项目结构

```
harnax-cli/
├── go.mod
├── go.sum
├── main.go                          # 入口
├── Makefile                         # build / test / lint
├── .goreleaser.yml                  # 跨平台发布配置
├── .golangci.yml                    # lint 配置
├── README.md
│
├── cmd/                             # cobra 命令定义
│   ├── root.go                      # 根命令 + 全局 flag
│   ├── auth.go                      # login / logout
│   ├── config.go                    # config set/get/list/use-profile
│   ├── agent.go                     # agent list/get/create/update/delete/toggle
│   ├── model.go                     # model list/get/create/update/delete/toggle
│   ├── model_provider.go            # model-provider list/get/create/update/delete/toggle/test/stats
│   ├── tool.go                      # tool list/get/update/delete/toggle/available/builtin/env-params
│   ├── mcp.go                       # mcp list/get/create/update/delete/toggle/test/list-tools
│   ├── session.go                   # session list/get/create/update/delete/toggle/config
│   ├── channel.go                   # channel list/get/create/update/delete/toggle
│   ├── envvar.go                    # env-var list/get/create/update/delete/toggle
│   ├── task.go                      # task list/get/create/update/delete/toggle/start/pause/trigger/logs
│   ├── apikey.go                    # api-key list/get/create/update/delete/toggle/regenerate
│   ├── tenant.go                    # tenant list/get/create/delete/toggle/users/add-user/remove-user/update-role
│   ├── user.go                      # user list/get/create/update/delete/toggle
│   └── health.go                    # health / info
│
├── internal/
│   ├── client/
│   │   └── admin.go                 # Admin REST API HTTP 客户端
│   ├── config/
│   │   └── config.go                # 配置与凭证管理（~/.harnax/）
│   └── output/
│       ├── formatter.go             # 输出格式化（json / table 切换）
│       └── table.go                 # 表格渲染工具
│
└── internal/client/testdata/        # 测试用 mock 响应
    └── ...
```

## 命令体系

### 全局 Flag

所有命令共享以下全局 flag：

| Flag | 说明 | 默认值 |
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
├── config.yaml           # 全局配置（权限 0644）
├── credentials.json      # 凭证信息（权限 0600）
└── profiles/             # 多环境 profile
    ├── dev.yaml
    └── prod.yaml
```

使用 viper 管理配置，支持 YAML 格式。凭证单独存储为 JSON，与 API 响应格式一致。

### config.yaml

```yaml
currentProfile: default
defaultOutput: table
profiles:
  default:
    serverUrl: http://localhost:8080
  prod:
    serverUrl: https://admin.harnax.com
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

### 核心结构

```go
// internal/client/admin.go

type AdminClient struct {
    BaseURL    string
    Token      string
    HTTPClient *http.Client
}

func NewAdminClient(baseURL, token string) *AdminClient

// 通用 CRUD 封装
func (c *AdminClient) List(ctx context.Context, path string, params map[string]string) (*ResultVo, error)
func (c *AdminClient) Get(ctx context.Context, path string, id any) (*ResultVo, error)
func (c *AdminClient) Create(ctx context.Context, path string, body any) (*ResultVo, error)
func (c *AdminClient) Update(ctx context.Context, path string, id any, body any) (*ResultVo, error)
func (c *AdminClient) Delete(ctx context.Context, path string, id any) (*ResultVo, error)
func (c *AdminClient) Toggle(ctx context.Context, path string, id any, status *int) (*ResultVo, error)

// 自定义请求（用于非标准 CRUD 的接口）
func (c *AdminClient) Request(ctx context.Context, method, path string, body any) (*ResultVo, error)
```

### ResultVo 响应结构

与后端保持一致：

```go
type ResultVo struct {
    Code      int             `json:"code"`
    Message   string          `json:"message"`
    Data      json.RawMessage `json:"data"`
    Timestamp int64           `json:"timestamp"`
}

type Page struct {
    List     json.RawMessage `json:"list"`
    Total    int             `json:"total"`
    PageNum  int             `json:"pageNum"`
    PageSize int             `json:"pageSize"`
}

func (r *ResultVo) IsSuccess() bool { return r.Code == 200 }
func (r *ResultVo) DecodeData(v any) error { return json.Unmarshal(r.Data, v) }
func (r *ResultVo) DecodeList(v any) error // 解析 Page.List
```

### 错误处理

- `code != 200`：返回 `APIError`，包含 `Code` 和 `Message`
- 网络错误：返回 `NetworkError`，包含底层 error
- HTTP 401：自动尝试刷新 token，失败则返回 `AuthError` 提示重新登录

## 输出格式

### Table 模式（默认）

```
$ harnax agent list
+----+------------+------------+----------+----------------------+
| ID | NAME       | MODEL      | STATUS   | CREATED              |
+----+------------+------------+----------+----------------------+
|  1 | 代码助手    | gpt-4o     | Active   | 2024-01-15 10:30:00  |
|  2 | 文档生成器  | claude-3.5 | Active   | 2024-01-16 14:20:00  |
|  3 | 测试助手    | gpt-4o-m   | Disabled | 2024-01-17 09:15:00  |
+----+------------+------------+----------+----------------------+
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

## Go Module 与构建

### go.mod

```
module github.com/agnetix/harnax-cli

go 1.22

require (
    github.com/spf13/cobra v1.8.1
    github.com/spf13/viper v1.19.0
    github.com/olekukonko/tablewriter v0.0.5
    github.com/fatih/color v1.18.0
)
```

### Makefile

```makefile
.PHONY: build test lint clean

BINARY := harnax
VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
LDFLAGS := -ldflags "-s -w -X main.version=$(VERSION)"

build:
	go build $(LDFLAGS) -o bin/$(BINARY) .

test:
	go test ./... -v -race

lint:
	golangci-lint run

clean:
	rm -rf bin/

install: build
	cp bin/$(BINARY) $(GOPATH)/bin/
```

### goreleaser 跨平台编译

```yaml
# .goreleaser.yml
builds:
  - binary: harnax
    goos: [linux, darwin, windows]
    goarch: [amd64, arm64]
    ldflags: ["-s -w -X main.version={{.Version}}"]

archives:
  - format: tar.gz
    name_template: "harnax_{{ .Version }}_{{ .Os }}_{{ .Arch }}"
    format_overrides:
      - goos: windows
        format: zip
```

### 分发方式

```bash
# 从源码安装
go install github.com/agnetix/harnax-cli@latest

# 下载预编译二进制（goreleaser 产出）
# Linux amd64
curl -L https://github.com/agnetix/harnax-cli/releases/download/v0.1.0/harnax_0.1.0_linux_amd64.tar.gz | tar xz
sudo mv harnax /usr/local/bin/

# macOS arm64
brew install agnetix/tap/harnax   # 可选：Homebrew tap
```

## 实现优先级

分三个阶段交付：

### Phase 1：基础框架 + 核心命令

- 项目脚手架（go mod、cobra root、Makefile、golangci-lint）
- `AdminClient` 通用 CRUD
- `config` 配置与凭证管理（viper）
- 输出格式化（table + json）
- `login` / `logout`
- `agent` 全部子命令
- `health`

### Phase 2：资源管理全覆盖

- `model` + `model-provider`
- `tool`
- `mcp`
- `session`
- `env-var`

### Phase 3：运维与高级功能

- `task`（定时任务管理）
- `api-key`
- `tenant` + `user`
- `channel`
- 完善测试覆盖
- goreleaser 发布配置
- README 文档

## 非功能性约束

- **零 Java/Spring 依赖**：纯 Go，与后端完全解耦
- **单文件分发**：`go build` 产出单个二进制文件，无运行时依赖
- **跨平台**：通过 goreleaser 支持 Linux / macOS / Windows
- **无外部状态**：所有状态存储在 `~/.harnax/` 本地目录
- **幂等操作**：重复执行不会产生副作用（依赖后端幂等性）
- **CI/CD 友好**：JSON 输出 + exit code + 环境变量配置支持
