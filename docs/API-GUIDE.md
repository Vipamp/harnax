# Harnax API 接入指南

本文档面向外部开发者，介绍如何申请 API Key 并通过 HTTP 调用 Harnax 智能代理（Agent）服务。

---

## 一、申请 API Key

### 1.1 通过管理后台创建

1. 登录 Harnax 管理后台（地址由管理员提供）
2. 进入左侧菜单 **系统管理 → API Key 管理**
3. 点击右上角 **新建 API Key** 按钮
4. 填写表单：

| 字段 | 说明 | 是否必填 |
|------|------|---------|
| 名称 | Key 的唯一标识名称，如 `my-app-key` | 必填 |
| 权限范围 | 勾选所需权限（见下方权限说明） | 必填 |
| 租户 ID | 关联的租户（仅管理员可设置，普通用户自动绑定当前租户） | 可选 |
| 限流 | 每分钟最大请求次数，默认 60 | 可选 |
| 过期时间 | Key 的有效期，留空表示永不过期 | 可选 |

5. 点击 **确认创建**
6. **立即复制并保存弹窗中显示的完整 Key**（格式如 `hnx_sk_live_AbCdEf...xYz9`）

> **重要提示：** 完整 Key 仅在创建和重新生成时展示一次，关闭弹窗后将无法再次查看。请妥善保管。

### 1.2 通过 API 创建（管理员）

```bash
curl -X POST https://your-admin-host/api/api-keys \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-jwt-token>" \
  -d '{
    "name": "my-app-key",
    "scopes": "router:invoke,api:chat",
    "rateLimit": 100,
    "expiresAt": "2027-12-31T23:59:59"
  }'
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "name": "my-app-key",
    "rawKey": "hnx_sk_live_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
    "keyPrefix": "hnx_sk_live_AbCd...6789"
  }
}
```

### 1.3 权限范围（Scopes）

| Scope | 说明 | 使用场景 |
|-------|------|---------|
| `router:invoke` | 通过 Router 调用 Agent 的所有接口 | **必须包含此权限才能调用 Agent** |
| `api:chat` | 允许使用对话相关接口 | 按需申请 |
| `api:session` | 允许使用会话管理接口 | 按需申请 |

> 创建时至少需要 `router:invoke` 权限。多个权限用逗号分隔。

---

## 二、认证方式

### 2.1 API Key 认证（推荐）

在请求头中添加 `X-Api-Key`：

```
X-Api-Key: hnx_sk_live_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789
```

### 2.2 认证错误码

| HTTP 状态码 | 含义 |
|------------|------|
| 401 | API Key 无效、已禁用或已过期 |
| 403 | 权限不足（缺少所需 scope） |
| 429 | 请求过于频繁，触发限流 |

---

## 三、API 端点总览

所有端点的 Base URL 为 Router 服务地址（如 `https://your-router-host`）。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/router/agent/chat` | 发送消息（同步返回） |
| POST | `/api/router/agent/chat/stream` | 发送消息（SSE 流式返回） |
| POST | `/api/router/agent/command` | 发送控制命令（中断/清除等） |
| POST | `/api/router/agent/confirm` | 确认工具调用（SSE 流式返回） |
| DELETE | `/api/router/agent/session/{sessionId}` | 清除会话 |
| GET | `/api/router/agent/chat/history/{sessionId}` | 获取对话历史 |
| GET | `/api/router/agent/session/{sessionId}/plans` | 获取会话计划列表 |
| GET | `/api/router/agent/session/{sessionId}/current-plan` | 获取当前执行计划 |

---

## 四、核心接口详解

### 4.1 发送消息（同步模式）

适用于简单的问答场景，等待 Agent 完整响应后返回。

**请求：**

```bash
curl -X POST https://your-router-host/api/router/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: hnx_sk_live_xxxxx" \
  -d '{
    "sessionId": "my-session-001",
    "message": "你好，请介绍一下你自己",
    "requestId": "req-unique-123"
  }'
```

**请求参数：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | String | 会话 ID，同一 sessionId 保持上下文连续性。**必填** |
| `message` | String | 用户消息内容。**必填** |
| `imageUrls` | List\<String\> | 图片 URL 列表（多模态场景）。可选，默认空 |
| `requestId` | String | 请求幂等 ID，防止重复提交。可选，留空自动生成 UUID |

**响应：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "my-session-001",
    "content": "你好！我是一个 AI 助手，可以帮助你...",
    "thinking": "用户发送了一个简单的问候...",
    "tokenUsage": {
      "inputTokens": 15,
      "outputTokens": 120,
      "totalTokens": 135,
      "costTime": 2.5,
      "timestamp": 1719000000000
    }
  }
}
```

**响应字段说明：**

| 字段 | 说明 |
|------|------|
| `sessionId` | 会话 ID |
| `content` | Agent 的最终回复文本 |
| `thinking` | Agent 的思考过程（如有） |
| `tokenUsage` | Token 使用统计 |

---

### 4.2 发送消息（流式模式，推荐）

适用于需要实时展示 Agent 思考和回复过程的场景。使用 Server-Sent Events (SSE) 协议。

**请求：**

```bash
curl -N -X POST https://your-router-host/api/router/agent/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: hnx_sk_live_xxxxx" \
  -d '{
    "sessionId": "my-session-001",
    "message": "帮我写一个快速排序算法",
    "requestId": "req-stream-456"
  }'
```

> 参数与同步模式相同。

**SSE 响应格式：**

每个事件以 `data:` 开头，JSON 格式，通过 `eventType` 字段区分事件类型：

```
data: {"eventType":"ThinkingEvent","message":"用户需要一个快速排序实现...","isLast":false}

data: {"eventType":"ThinkingEvent","message":"让我用 Python 来写...","isLast":true,"tokenUsage":{...}}

data: {"eventType":"CallToolEvent","toolId":"tool-1","toolName":"code_execute","arguments":{"language":"python","code":"def quicksort..."},"tokenUsage":{...}}

data: {"eventType":"ToolResultEvent","toolId":"tool-1","toolName":"code_execute","message":"执行成功","success":true,"tokenUsage":{...}}

data: {"eventType":"TextEvent","message":"以下是快速排序的实现：\n```python\ndef quicksort(arr)...","isLast":false}

data: {"eventType":"TextEvent","message":"这个算法的时间复杂度为...","isLast":true,"tokenUsage":{...}}

data: {"eventType":"EndEvent"}
```

**事件类型说明：**

| eventType | 说明 | 关键字段 |
|-----------|------|---------|
| `ThinkingEvent` | Agent 的思考过程 | `message`, `isLast` |
| `TextEvent` | Agent 的文本回复 | `message`, `isLast` |
| `CallToolEvent` | Agent 调用工具 | `toolId`, `toolName`, `arguments` |
| `ToolResultEvent` | 工具执行结果 | `toolId`, `toolName`, `message`, `success` |
| `ToolConfirmEvent` | 需要用户确认的危险工具调用 | `pendingCallTools` |
| `ErrorEvent` | 错误信息 | `code`, `message` |
| `EndEvent` | 流结束标记 | 无 |

**Python 流式消费示例：**

```python
import requests

url = "https://your-router-host/api/router/agent/chat/stream"
headers = {
    "Content-Type": "application/json",
    "X-Api-Key": "hnx_sk_live_xxxxx",
}
payload = {
    "sessionId": "my-session-001",
    "message": "你好",
}

response = requests.post(url, json=payload, headers=headers, stream=True)

for line in response.iter_lines(decode_unicode=True):
    if line.startswith("data:"):
        import json
        event = json.loads(line[5:].strip())
        event_type = event["eventType"]

        if event_type == "ThinkingEvent":
            print(f"[思考] {event['message']}", end="")
        elif event_type == "TextEvent":
            print(event["message"], end="")
        elif event_type == "CallToolEvent":
            print(f"\n[调用工具] {event['toolName']}")
        elif event_type == "ToolResultEvent":
            print(f"[工具结果] {event['toolName']}: {event['message']}")
        elif event_type == "ErrorEvent":
            print(f"\n[错误] {event['code']}: {event['message']}")
        elif event_type == "EndEvent":
            print("\n[完成]")
            break
```

**JavaScript (fetch) 流式消费示例：**

```javascript
const response = await fetch("https://your-router-host/api/router/agent/chat/stream", {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    "X-Api-Key": "hnx_sk_live_xxxxx",
  },
  body: JSON.stringify({
    sessionId: "my-session-001",
    message: "你好",
  }),
});

const reader = response.body.getReader();
const decoder = new TextDecoder();
let buffer = "";

while (true) {
  const { done, value } = await reader.read();
  if (done) break;

  buffer += decoder.decode(value, { stream: true });
  const lines = buffer.split("\n");
  buffer = lines.pop() || "";

  for (const line of lines) {
    if (line.startsWith("data:")) {
      const event = JSON.parse(line.slice(5).trim());

      switch (event.eventType) {
        case "ThinkingEvent":
          console.log(`[思考] ${event.message}`);
          break;
        case "TextEvent":
          process.stdout.write(event.message);
          break;
        case "CallToolEvent":
          console.log(`\n[调用工具] ${event.toolName}`);
          break;
        case "ToolResultEvent":
          console.log(`[工具结果] ${event.toolName}: ${event.message}`);
          break;
        case "ErrorEvent":
          console.error(`[错误] ${event.code}: ${event.message}`);
          break;
        case "EndEvent":
          console.log("\n[完成]");
          break;
      }
    }
  }
}
```

---

### 4.3 发送控制命令

向 Agent 发送控制指令，如中断当前任务。

```bash
curl -X POST https://your-router-host/api/router/agent/command \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: hnx_sk_live_xxxxx" \
  -d '{
    "sessionId": "my-session-001",
    "command": "INTERRUPT"
  }'
```

**可用命令：**

| command | 说明 |
|---------|------|
| `INTERRUPT` | 中断 Agent 当前正在执行的任务 |
| `CLEAR` | 清除会话上下文 |
| `COMPACT` | 压缩对话历史（释放上下文窗口） |
| `APPROVE` | 批准待处理操作 |

**响应：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "my-session-001",
    "success": true,
    "result": null,
    "message": "Interrupted"
  }
}
```

---

### 4.4 确认工具调用

当 Agent 需要执行危险操作（如删除文件、执行代码）时，会先暂停并等待用户确认。

```bash
curl -N -X POST https://your-router-host/api/router/agent/confirm \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: hnx_sk_live_xxxxx" \
  -d '{
    "sessionId": "my-session-001",
    "isConfirmed": true,
    "toolInfoList": [
      { "toolId": "tool-1", "toolName": "code_execute" }
    ]
  }'
```

**请求参数：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | String | 会话 ID。**必填** |
| `isConfirmed` | Boolean | `true` 确认执行，`false` 拒绝 |
| `toolInfoList` | List | 要确认的工具列表（从 `ToolConfirmEvent` 中获取） |

**响应：** SSE 流，格式同 `/chat/stream`。

---

### 4.5 会话管理

**清除会话：**

```bash
curl -X DELETE https://your-router-host/api/router/agent/session/my-session-001 \
  -H "X-Api-Key: hnx_sk_live_xxxxx"
```

**获取对话历史：**

```bash
curl https://your-router-host/api/router/agent/chat/history/my-session-001 \
  -H "X-Api-Key: hnx_sk_live_xxxxx"
```

**获取计划列表：**

```bash
curl https://your-router-host/api/router/agent/session/my-session-001/plans \
  -H "X-Api-Key: hnx_sk_live_xxxxx"
```

**获取当前计划：**

```bash
curl https://your-router-host/api/router/agent/session/my-session-001/current-plan \
  -H "X-Api-Key: hnx_sk_live_xxxxx"
```

---

## 五、完整调用流程示例

以下是一个典型的多轮对话 + 工具确认的完整流程：

```
┌─────────┐                          ┌────────┐                          ┌──────────┐
│  Client │                          │ Router │                          │  Agent   │
└────┬────┘                          └───┬────┘                          └────┬─────┘
     │                                   │                                   │
     │  1. POST /chat/stream             │                                   │
     │  {"sessionId":"s1",               │                                   │
     │   "message":"删除临时文件"}        │                                   │
     │ ─────────────────────────────────>│                                   │
     │                                   │  proxy to agent                   │
     │                                   │──────────────────────────────────>│
     │                                   │                                   │
     │  2. SSE: ThinkingEvent            │                                   │
     │  3. SSE: CallToolEvent            │                                   │
     │  4. SSE: ToolConfirmEvent  ◄──────│◄──────────────────────────────────│
     │     (危险工具，需要确认)           │    (pendingCallTools)             │
     │                                   │                                   │
     │  5. POST /confirm                 │                                   │
     │  {"sessionId":"s1",               │                                   │
     │   "isConfirmed":true,             │                                   │
     │   "toolInfoList":[...]}           │                                   │
     │ ─────────────────────────────────>│                                   │
     │                                   │  proxy to agent                   │
     │                                   │──────────────────────────────────>│
     │                                   │                                   │
     │  6. SSE: ToolResultEvent          │                                   │
     │  7. SSE: TextEvent                │                                   │
     │  8. SSE: EndEvent          ◄──────│◄──────────────────────────────────│
     │                                   │                                   │
     │  9. DELETE /session/s1            │                                   │
     │ ─────────────────────────────────>│                                   │
     │                                   │                                   │
```

---

## 六、限流与错误处理

### 6.1 限流规则

- 每个 API Key 独立限流，基于滑动窗口算法
- 默认限制：**60 次/分钟**（可在创建时自定义）
- 触发限流时返回 HTTP 429，响应头包含 `Retry-After: 60`

### 6.2 常见错误码

| code | 说明 | 建议 |
|------|------|------|
| 401 | 认证失败 | 检查 API Key 是否正确、是否过期、是否被禁用 |
| 403 | 权限不足 | 检查 API Key 的 scopes 是否包含 `router:invoke` |
| 429 | 限流 | 降低请求频率，或联系管理员提高限额 |
| 500 | 服务内部错误 | 记录 requestId 联系运维排查 |
| 503 | 无可用 Agent 实例 | 稍后重试，Router 会自动故障转移 |

### 6.3 幂等性

- 发送消息时建议传入 `requestId`（如 UUID）
- 相同 `requestId` 的重复请求会被 Router 自动去重，返回之前的结果
- 如不传 `requestId`，Router 会自动生成一个，但不具备幂等保护

### 6.4 会话绑定与故障转移

- 同一 `sessionId` 的请求会被路由到同一个 Agent 实例（会话粘性）
- 当绑定实例不可用时，Router 自动将请求转移到其他健康实例
- 故障转移对调用方透明，无需额外处理

---

## 七、注意事项

1. **API Key 安全**：不要在客户端代码中硬编码 Key，使用环境变量或密钥管理服务
2. **sessionId 设计**：建议使用业务含义明确的 ID（如 `user-123-task-456`），便于排查问题
3. **流式超时**：流式连接默认超时 10 分钟，长时间无数据时建议客户端设置读取超时
4. **并发限制**：同一 sessionId 不建议并发发送消息，可能导致上下文混乱
5. **Key 轮换**：定期通过「重新生成」功能更换 Key，旧 Key 立即失效
