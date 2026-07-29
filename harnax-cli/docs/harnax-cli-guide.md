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
```

## 快速开始

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
| `--server-url <url>` | 覆盖 Admin 服务地址 | 配置文件中的值 |
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

## 认证

```bash
# 登录
harnax login --username admin --password your-password

# 登出
harnax logout
```

登录后 JWT token 自动保存，后续命令自动携带认证信息。

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

```bash
harnax tool list [--keyword X] [--type BUILTIN|CUSTOM|HTTP] [--status N]
harnax tool get <id>
harnax tool update <id> [--name X]
harnax tool delete <id>
harnax tool toggle <id>
harnax tool available [--type X]
harnax tool builtin
harnax tool env-params <id>
```

### CLI 工具管理

CLI 工具（如 kubectl、gh、awscli）会在构建 agent sandbox 镜像时自动安装，关联的 skill 会在运行时自动加载到 agent。

```bash
harnax cli list [--name X] [--status N] [--page N] [--size N]
harnax cli get <id>
harnax cli create --name X --install-script X [--version X] [--check-command X] [--skill-ids 1,2] [--public]
harnax cli update <id> [--name X] [--install-script X] [--skill-ids 1,2]
harnax cli delete <id>
harnax cli toggle <id> [--status 0|1]
```

示例：

```bash
# 注册 kubectl，并关联 id=5 的使用教学 skill
harnax cli create --name kubectl --version 1.30.0 \
  --install-script 'curl -LO "https://dl.k8s.io/release/v1.30.0/bin/linux/amd64/kubectl" && install -m 0755 kubectl /usr/local/bin/kubectl && rm kubectl' \
  --check-command 'kubectl version --client' \
  --skill-ids 5
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

# 测试
make test

# 代码检查
make lint

# 安装到本地
make install
```
