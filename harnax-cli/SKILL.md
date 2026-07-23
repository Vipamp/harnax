# Harnax Admin CLI

通过 `harnax` 命令行管理 Harnax 平台的全部资源。支持 Agent、模型、工具、MCP Server、会话、渠道、环境变量、定时任务、API Key、租户、用户的增删改查操作。

## 前置条件

使用前必须配置服务地址并登录：

```bash
harnax config set serverUrl http://localhost:8080
harnax login --username admin --password your-password
```

登录成功后 token 自动保存到 `~/.harnax/credentials.json`，后续命令自动携带认证。

## 全局选项

所有命令均支持以下全局选项：

```
--server-url <url>    覆盖服务地址（不修改配置）
--output json|table   输出格式，默认 table
--profile <name>      使用指定环境 profile
--verbose             打印 HTTP 请求/响应详情
```

**重要**：在脚本和自动化场景中，始终使用 `--output json` 获取结构化数据，便于 jq 等工具解析。

## 认证命令

### login

```bash
harnax login --username <username> --password <password>
```

- `--username`（必需）：登录用户名
- `--password`（必需）：登录密码
- 成功后保存 JWT token 到本地，后续命令自动使用

### logout

```bash
harnax logout
```

清除本地凭证。

## 配置命令

### config set

```bash
harnax config set <key> <value>
```

支持的 key：`serverUrl`、`defaultOutput`

### config get

```bash
harnax config get <key>
```

### config list

```bash
harnax config list
```

以 JSON 格式输出所有配置。

### config use-profile

```bash
harnax config use-profile <profile-name>
```

切换到指定环境 profile。不同 profile 可配置不同的 serverUrl。

## Agent 管理

### 查询 Agent 列表

```bash
harnax agent list [--name <name>] [--status <0|1>] [--page <n>] [--size <n>]
```

- `--name`：按名称过滤
- `--status`：按状态过滤（1=启用，0=禁用）
- `--page`：页码，默认 1
- `--size`：每页条数，默认 10

输出表格：ID、Name、Model ID、Status、Created

### 查看 Agent 详情

```bash
harnax agent get <id>
```

输出 key-value 格式的详细信息，包含 System Prompt。

### 创建 Agent

```bash
harnax agent create --name <name> --model-id <id> --system-prompt <prompt>
```

- `--name`（必需）：Agent 名称
- `--model-id`（必需）：关联的模型 ID
- `--system-prompt`（必需）：系统提示词

### 更新 Agent

```bash
harnax agent update <id> [--name <name>] [--model-id <id>] [--system-prompt <prompt>]
```

仅更新指定的字段。

### 删除 Agent

```bash
harnax agent delete <id>
```

### 切换 Agent 状态

```bash
harnax agent toggle <id>
```

在启用和禁用之间切换。

## 模型管理

### 查询模型列表

```bash
harnax model list [--name <name>] [--provider-id <id>] [--model-type <type>] [--status <0|1>] [--page <n>] [--size <n>]
```

输出表格：ID、Name、Provider ID、Model Type、Status、Created

### 查看模型详情

```bash
harnax model get <id>
```

输出包含所有能力标记（Support Internet/Reasoning/Tool/MCP/Vision）。

### 创建模型

```bash
harnax model create --name <name> --provider-id <id> --model-type <type> \
  [--support-internet] [--support-reasoning] [--support-tool] [--support-mcp] [--support-vision]
```

- `--name`（必需）：模型名称
- `--provider-id`（必需）：提供商 ID
- `--model-type`（必需）：模型类型
- `--support-*`：布尔 flag，标记模型能力

### 更新模型

```bash
harnax model update <id> [--name <name>] [--provider-id <id>] [--model-type <type>] [--support-*]
```

### 删除/切换状态

```bash
harnax model delete <id>
harnax model toggle <id>
```

## 模型提供商管理

### 查询列表

```bash
harnax model-provider list [--name <name>] [--type <type>] [--status <0|1>] [--page <n>] [--size <n>]
```

### CRUD 操作

```bash
harnax model-provider get <id>
harnax model-provider create --name <name> --type <type> [--url <url>] [--api-key <key>]
harnax model-provider update <id> [--name <name>] [--type <type>] [--url <url>]
harnax model-provider delete <id>
harnax model-provider toggle <id>
```

### 连通性测试

```bash
harnax model-provider test <id>
```

向提供商发送测试请求，验证配置是否正确。

### 查看统计

```bash
harnax model-provider stats <id>
```

查看该提供商下的模型统计信息。

## 工具管理

### 查询工具列表

```bash
harnax tool list [--keyword <keyword>] [--type BUILTIN|CUSTOM|HTTP] [--status <0|1>] [--page <n>] [--size <n>]
```

输出表格：ID、Name、Type、Status、Read Only

### 查看详情

```bash
harnax tool get <id>
```

### 更新/删除/切换

```bash
harnax tool update <id> [--name <name>]
harnax tool delete <id>
harnax tool toggle <id>
```

### 查看可用工具

```bash
harnax tool available [--type BUILTIN|CUSTOM|HTTP]
```

列出所有已启用的工具，可按类型过滤。

### 查看内置工具

```bash
harnax tool builtin
```

### 查看工具所需环境变量

```bash
harnax tool env-params <id>
```

## MCP Server 管理

### 查询 MCP Server 列表

```bash
harnax mcp list [--keyword <keyword>] [--type stdio|sse|streamablehttp] [--status <0|1>] [--page <n>] [--size <n>]
```

输出表格：ID、Name、Type、Status、Created

### 查看详情

```bash
harnax mcp get <id>
```

输出包含 Command、URL 等配置信息。

### 创建 MCP Server

```bash
harnax mcp create --name <name> --type <stdio|sse|streamablehttp> \
  [--command <cmd>] [--url <url>] [--headers '<json>'] [--env-params '<json>']
```

- `--name`（必需）：名称
- `--type`（必需）：类型（stdio / sse / streamablehttp）
- `--command`：stdio 类型的启动命令
- `--url`：sse / streamablehttp 类型的 URL
- `--headers`：JSON 格式的 HTTP 头
- `--env-params`：JSON 格式的环境参数

示例：

```bash
# stdio 类型
harnax mcp create --name "filesystem" --type stdio --command "npx -y @modelcontextprotocol/server-filesystem /tmp"

# SSE 类型
harnax mcp create --name "remote-tools" --type sse --url "http://localhost:3000/sse"
```

### 更新/删除/切换

```bash
harnax mcp update <id> [--name <name>] [--type <type>] [--command <cmd>] [--url <url>]
harnax mcp delete <id>
harnax mcp toggle <id>
```

### 连通性测试

```bash
harnax mcp test <id>
```

测试 MCP Server 是否可连接。

### 查看 MCP 提供的工具列表

```bash
harnax mcp list-tools <id>
```

输出表格：Name、Description。列出该 MCP Server 暴露的所有工具。

## 会话管理

### 查询会话列表

```bash
harnax session list [--keyword <keyword>] [--status <0|1>] [--page <n>] [--size <n>]
```

### CRUD 操作

```bash
harnax session get <id>
harnax session create --title <title> --agent-id <id> [--model-id <id>]
harnax session update <id> [--title <title>] [--model-id <id>]
harnax session delete <id>
harnax session toggle <id>
```

### 会话配置

```bash
# 查看配置
harnax session config get <session-id>

# 更新配置
harnax session config update <session-id> [--enable-think] [--enable-search] [--enable-plan] [--permission-mode <mode>]
```

permission-mode 可选值：DEFAULT、BYPASS、ACCEPT_EDITS、EXPLORE、DONT_ASK

## 渠道管理

```bash
harnax channel list [--keyword <keyword>] [--type <type>] [--status <0|1>] [--page <n>] [--size <n>]
harnax channel get <id>
harnax channel create --name <name> --type <type> [--config '<json>']
harnax channel update <id> [--name <name>] [--config '<json>']
harnax channel delete <id>
harnax channel toggle <id>
```

## 环境变量管理

### 查询列表

```bash
harnax env-var list [--keyword <keyword>] [--page <n>] [--size <n>]
```

输出表格：ID、Key、Sensitive、Enabled、Created

### 创建环境变量

```bash
harnax env-var create --key <key> --value <value> [--sensitive] [--enabled]
```

- `--key`（必需）：变量名
- `--value`（必需）：变量值
- `--sensitive`：标记为敏感（加密存储）
- `--enabled`：创建时即启用

### 更新/删除/切换

```bash
harnax env-var update <id> [--key <key>] [--value <value>]
harnax env-var delete <id>
harnax env-var toggle <id>
```

## 定时任务管理

### 查询任务列表

```bash
harnax task list [--name <name>] [--agent-id <id>] [--task-status <status>] [--page <n>] [--size <n>]
```

### CRUD 操作

```bash
harnax task get <id>
harnax task create --name <name> --agent-id <id> [--cron <expression>] [--input <input>]
harnax task update <id> [--name <name>] [--cron <expression>] [--input <input>]
harnax task delete <id>
harnax task toggle <id> --status <0|1>
```

### 调度控制

```bash
harnax task start <id>      # 启动调度
harnax task pause <id>      # 暂停调度
harnax task trigger <id>    # 手动触发一次执行
```

### 查看执行日志

```bash
harnax task logs <task-id> [--status <status>] [--page <n>] [--size <n>]
```

## API Key 管理

```bash
harnax api-key list [--keyword <keyword>] [--page <n>] [--size <n>]
harnax api-key get <id>
harnax api-key create --name <name>
harnax api-key update <id> [--name <name>]
harnax api-key delete <id>
harnax api-key toggle <id>
```

### 重新生成 Key

```bash
harnax api-key regenerate <id>
```

生成新的 API Key，旧 Key 立即失效。新 Key 仅显示一次。

### 查看永久 Key

```bash
harnax api-key permanent
```

## 租户管理

```bash
harnax tenant list [--name <name>] [--status <0|1>] [--page <n>] [--size <n>]
harnax tenant get <id>
harnax tenant create --name <name> [--description <desc>]
harnax tenant delete <id>
harnax tenant toggle <id> --status <0|1>
```

### 租户用户管理

```bash
harnax tenant users <tenant-id> [--page <n>] [--size <n>]    # 查看租户用户列表
harnax tenant add-user <tenant-id> <user-id>                   # 添加用户到租户
harnax tenant remove-user <tenant-id> <user-id>                # 从租户移除用户
harnax tenant update-role <tenant-id> <user-id> --role <role>  # 更新用户角色
```

## 用户管理

```bash
harnax user list [--keyword <keyword>] [--status <0|1>] [--page <n>] [--size <n>]
harnax user get <id>
harnax user create --username <username> --password <password> [--phone <phone>] [--email <email>]
harnax user update <id> [--username <username>] [--phone <phone>] [--email <email>]
harnax user delete <id>
harnax user toggle <id>
```

## 健康检查

```bash
harnax health            # 基本健康检查
harnax health info       # 查看版本信息
```

## 输出格式说明

### Table 模式（默认）

人类友好的表格输出，适合终端交互。

### JSON 模式

`--output json` 输出原始 API 响应，适合脚本和自动化：

```bash
# 获取所有 Agent 名称
harnax agent list --output json | jq -r '.data.list[].name'

# 获取特定 Agent 的 Model ID
harnax agent get 1 --output json | jq '.data.modelId'

# 统计启用的 Agent 数量
harnax agent list --status 1 --output json | jq '.data.total'
```

## Exit Code

| Code | 含义 |
|------|------|
| 0 | 成功 |
| 1 | 通用错误（API 返回非 200） |
| 2 | 认证错误（token 过期，需重新 login） |
| 3 | 网络错误（无法连接服务器） |
| 4 | 参数错误（缺少必需 flag） |

## 典型操作场景

### 场景 1：创建完整的 Agent 环境

```bash
# 查看可用模型
harnax model list

# 查看可用工具
harnax tool available

# 创建 Agent
harnax agent create --name "代码助手" --model-id 10 --system-prompt "你是一个专业的代码助手"

# 查看创建结果
harnax agent get <返回的id>
```

### 场景 2：配置 MCP Server 并验证

```bash
# 创建 stdio 类型的 MCP Server
harnax mcp create --name "文件系统" --type stdio \
  --command "npx -y @modelcontextprotocol/server-filesystem /workspace"

# 测试连通性
harnax mcp test 1

# 查看提供的工具
harnax mcp list-tools 1
```

### 场景 3：批量运维操作

```bash
# 查看所有禁用的 Agent
harnax agent list --status 0

# 批量启用
for id in $(harnax agent list --status 0 --output json | jq -r '.data.list[].id'); do
  harnax agent toggle $id
done
```

### 场景 4：环境变量配置

```bash
# 创建敏感环境变量（加密存储）
harnax env-var create --key OPENAI_API_KEY --value sk-xxx --sensitive --enabled

# 创建普通环境变量
harnax env-var create --key LOG_LEVEL --value debug --enabled

# 查看已配置的环境变量
harnax env-var list
```
