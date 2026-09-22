# harnax-cli 使用文档

Harnax Admin CLI —— 命令行管理 Harnax 平台资源。

## 安装

```bash
# 从源码编译
cd harnax-cli
make build
cp bin/harnax /usr/local/bin/

# 或 go install
go install github.com/agnetix/harnax-cli@latest

# 或打成平台插件包，投放给 admin 登记
make package        # → dist/harnax-<version>.harnaxcli.zip
```

## 快速开始

在 Agent 沙箱内无需任何准备：平台创建容器时注入 `HARNAX_URL` 与 `HARNAX_TOKEN`，命令开箱即用。
下面是沙箱外（本机、CI）的一次性配置：

```bash
# 1. 配置服务地址
harnax config set serverUrl http://localhost:8080

# 2. 登录
harnax login --username admin --password your-password

# 3. 开始使用
harnax agent list
harnax model list
harnax health
```

## 全局选项

| 选项 | 说明 | 默认值 |
|------|------|--------|
| `--server-url <url>` | 覆盖 Admin 服务地址 | `HARNAX_URL`，其次配置文件中的值 |
| `--output <format>` | 输出格式：`json` 或 `table` | `table` |
| `--profile <name>` | 使用指定 profile | `default` |
| `--verbose` | 显示 HTTP 请求/响应详情 | `false` |

## 配置管理

```bash
# 设置服务地址
harnax config set serverUrl http://localhost:8080

# 查看配置
harnax config list
harnax config get serverUrl

# 多环境切换
harnax config use-profile prod
harnax --profile dev agent list
```

配置文件存储在 `~/.harnax/` 目录下：
- `config.json` — 全局配置
- `credentials.json` — 登录凭证（权限 0600）

沙箱内可以完全没有这个目录：`HARNAX_URL` + `HARNAX_TOKEN` 等价于已登录的内部令牌模式。

## 认证

```bash
# 登录
harnax login --username admin --password your-password

# 登出
harnax logout
```

登录后 JWT token 自动保存，后续命令自动携带认证信息。

内部令牌模式（沙箱内）不需要登录：设置了 `HARNAX_TOKEN` 即以该令牌访问 admin 内部接口，
`harnax whoami` 会显示 `internal secret`。地址优先级：`--server-url` > `HARNAX_URL` > profile 的 `serverUrl`。

## 命令参考

### Agent 管理

```bash
harnax agent list [--name X] [--status N] [--page N] [--size N]
harnax agent get <id>
harnax agent create --name X --model-id X --system-prompt X
harnax agent update <id> [--name X] [--model-id X] [--system-prompt X]
harnax agent delete <id>
harnax agent toggle <id>
```

### 模型管理

```bash
harnax model list [--name X] [--provider-id X] [--model-type X] [--status N]
harnax model get <id>
harnax model create --name X --provider-id X --model-type X
harnax model update <id> [--name X]
harnax model delete <id>
harnax model toggle <id>
```

### 模型提供商管理

```bash
harnax model-provider list [--name X] [--type X] [--status N]
harnax model-provider get <id>
harnax model-provider create --name X --type X [--url X] [--api-key X]
harnax model-provider update <id> [--name X]
harnax model-provider delete <id>
harnax model-provider toggle <id>
harnax model-provider test <id>
harnax model-provider stats <id>
```

### 工具管理

工具全部为内置工具，由 admin 启动时按注解自动同步，不支持增删改与启停。

```bash
harnax tool list [--keyword X] [--status N]
harnax tool get <id>
harnax tool available
harnax tool builtin
harnax tool env-params <id>
```

### CLI 工具管理

CLI 工具是一个平台插件包 `<name>-<version>.harnaxcli.zip`：admin 启动时扫描 `harnax.cli.package-dir`，
按包内 `plugin.yaml` 登记 CLI，把 `payload/` 装进 Agent 沙箱镜像，并把包内 `skill/SKILL.md` 作为该 CLI 自带
的技能随选用关系一起加载。命令行侧只支持查看与启停，没有增删改入口。

```bash
harnax cli list [--name X] [--status N] [--page N] [--size N]
harnax cli get <id>
harnax cli toggle <id> [--status 0|1]
```

发布新 CLI（以 kubectl 为例）：准备 `plugin.yaml`、`skill/SKILL.md` 与 `payload/` 三件套后打包投放。

```bash
mkdir -p stage/skill stage/payload/usr/local/bin
cp SKILL.md stage/skill/
install -m 0755 kubectl stage/payload/usr/local/bin/kubectl   # 可执行位要靠 zip 携带
(cd stage && zip -X -r ../kubectl-1.30.0.harnaxcli.zip plugin.yaml skill payload)
# 丢进货架 cli-packages/dist/，再走一次 docker-new 的打包部署（脚本会把整架复制进构建输入目录）
```

### MCP Server 管理

```bash
harnax mcp list [--keyword X] [--type stdio|sse|streamablehttp] [--status N]
harnax mcp get <id>
harnax mcp create --name X --type X [--command X] [--url X]
harnax mcp update <id> [--name X] [--type X] [--command X] [--url X]
harnax mcp delete <id>
harnax mcp toggle <id>
harnax mcp test <id>
harnax mcp list-tools <id>
```

### 会话管理

```bash
harnax session list [--keyword X] [--status N]
harnax session get <id>
harnax session create --title X --agent-id X [--model-id X]
harnax session update <id> [--title X] [--model-id X]
harnax session delete <id>
harnax session toggle <id>
harnax session config get <session-id>
harnax session config update <session-id> [--enable-think] [--enable-search] [--enable-plan] [--permission-mode X]
```

### 渠道管理

```bash
harnax channel list [--keyword X] [--type X] [--status N]
harnax channel get <id>
harnax channel create --name X --type X [--config X]
harnax channel update <id> [--name X] [--config X]
harnax channel delete <id>
harnax channel toggle <id>
```

### 环境变量管理

```bash
harnax env-var list [--keyword X]
harnax env-var get <id>
harnax env-var create --key X --value X [--sensitive] [--enabled]
harnax env-var update <id> [--key X] [--value X]
harnax env-var delete <id>
harnax env-var toggle <id>
```

### 定时任务管理

```bash
harnax task list [--name X] [--agent-id X] [--task-status X]
harnax task get <id>
harnax task create --name X --agent-id X [--cron X] [--input X]
harnax task update <id> [--name X] [--cron X] [--input X]
harnax task delete <id>
harnax task toggle <id> --status N
harnax task start <id>
harnax task pause <id>
harnax task trigger <id>
harnax task logs <task-id> [--status X]
```

### API Key 管理

```bash
harnax api-key list [--keyword X]
harnax api-key get <id>
harnax api-key create --name X
harnax api-key update <id> [--name X]
harnax api-key delete <id>
harnax api-key toggle <id>
harnax api-key regenerate <id>
harnax api-key permanent
```

### 租户管理

```bash
harnax tenant list [--name X] [--status N]
harnax tenant get <id>
harnax tenant create --name X [--description X]
harnax tenant delete <id>
harnax tenant toggle <id> --status N
harnax tenant users <id>
harnax tenant add-user <tenant-id> <user-id>
harnax tenant remove-user <tenant-id> <user-id>
harnax tenant update-role <tenant-id> <user-id> --role X
```

### 用户管理

```bash
harnax user list [--keyword X] [--status N]
harnax user get <id>
harnax user create --username X --password X [--phone X] [--email X]
harnax user update <id> [--username X] [--phone X] [--email X]
harnax user delete <id>
harnax user toggle <id>
```

### 健康检查

```bash
harnax health
harnax health info
```

## 输出格式

### Table 模式（默认）

```
$ harnax agent list
+----+------------+----------+--------+----------------------+
| ID | NAME       | MODEL ID | STATUS | CREATED              |
+----+------------+----------+--------+----------------------+
|  1 | 代码助手    |       10 | Active | 2024-01-15 10:30:00  |
|  2 | 文档生成器  |       11 | Active | 2024-01-16 14:20:00  |
+----+------------+----------+--------+----------------------+
Showing 1-2 of 2 results (page 1/1)
```

### JSON 模式

```bash
$ harnax agent list --output json
{
  "code": 200,
  "message": "success",
  "data": {
    "list": [...],
    "total": 2,
    "pageNum": 1,
    "pageSize": 10
  }
}
```

JSON 模式适合脚本管道和自动化场景：

```bash
# 获取所有 agent 的名称
harnax agent list --output json | jq -r '.data.list[].name'

# 获取特定 agent 的 model ID
harnax agent get 1 --output json | jq '.data.modelId'
```

## Exit Code

| Code | 含义 |
|------|------|
| 0 | 成功 |
| 1 | 通用错误（API 返回非 200） |
| 2 | 认证错误（token 过期或无效） |
| 3 | 网络错误（无法连接服务器） |
| 4 | 参数错误（缺少必需参数） |

## 典型工作流

### 创建并配置 Agent

```bash
# 1. 查看可用模型
harnax model list

# 2. 创建 Agent
harnax agent create --name "代码助手" --model-id 10 --system-prompt "你是一个专业的代码助手"

# 3. 查看可用工具
harnax tool available

# 4. 查看 Agent 详情
harnax agent get 1
```

### MCP Server 管理

```bash
# 1. 创建 MCP Server
harnax mcp create --name "文件系统" --type stdio --command "npx -y @modelcontextprotocol/server-filesystem /tmp"

# 2. 测试连通性
harnax mcp test 1

# 3. 查看 MCP 提供的工具列表
harnax mcp list-tools 1
```

### 环境变量管理

```bash
# 1. 创建敏感环境变量
harnax env-var create --key API_KEY --value sk-xxx --sensitive --enabled

# 2. 查看环境变量列表
harnax env-var list

# 3. 启用/禁用
harnax env-var toggle 1
```

## 开发

```bash
# 构建
make build

# 打平台插件包
make package

# 测试
make test

# 代码检查
make lint

# 安装到本地
make install
```
