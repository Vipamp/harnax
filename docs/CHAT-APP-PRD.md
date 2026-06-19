# Harnax Chat App 产品需求文档

> 版本：v1.0.0  
> 日期：2026-06-19  
> 状态：草案

---

## 一、产品概述

### 1.1 产品定位

Harnax Chat App 是一个面向终端用户的 AI 智能体对话应用，通过 HTTP 协议连接 Harnax Session Router，提供实时流式对话体验。

与现有 harnax-webui（管理后台）不同，Chat App 专注于**对话交互**本身，不包含 Agent 管理、模型配置等运维功能，定位为轻量级前端对话客户端。

### 1.2 目标用户

| 用户类型 | 场景 | 说明 |
|---------|------|------|
| 内部用户 | 团队内部使用已配置的 Agent 进行日常工作 | 通过管理后台创建的 Session 直接对话 |
| 外部开发者 | 通过 API Key 接入 Harnax 平台 | 集成 Chat App 到自己的产品中 |
| 演示用户 | 体验 Agent 能力 | 管理员分享的对话链接 |

### 1.3 核心价值

- **即时可用**：无需管理后台账号，通过 API Key 或分享链接即可开始对话
- **流式体验**：SSE 实时流式输出，展示思考过程、工具调用、文本生成
- **多会话管理**：支持同时维护多个对话会话
- **跨平台**：Web 应用，支持桌面和移动端浏览器

---

## 二、系统架构

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Harnax Chat App                         │
│                   (独立前端 SPA 应用)                          │
│                                                              │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────────────┐  │
│  │ 会话列表  │  │  对话主界面   │  │  SSE 事件处理引擎     │  │
│  └──────────┘  └──────────────┘  └───────────────────────┘  │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP / SSE
                         │ X-Api-Key: hnx_xxx
                         ▼
┌────────────────────────────────────────────────────────────┐
│              Session Router (port 8081)                      │
│          路由 + 负载均衡 + 会话粘性 + 认证鉴权               │
└────────────────────────┬───────────────────────────────────┘
                         │ HTTP / JWT (内部)
                         ▼
┌────────────────────────────────────────────────────────────┐
│              Agent Service (port 8082)                       │
│          AI Agent 执行引擎 (ReAct 循环)                      │
└────────────────────────────────────────────────────────────┘
```

### 2.2 通信协议

| 通信方式 | 协议 | 用途 |
|---------|------|------|
| 流式对话 | HTTP POST + SSE | 发送消息，实时接收 Agent 响应 |
| 同步对话 | HTTP POST | 简单问答（备选方案） |
| 命令控制 | HTTP POST | 中断、清除会话、压缩历史 |
| 工具确认 | HTTP POST + SSE | 确认/拒绝危险工具调用 |
| 会话管理 | HTTP GET/DELETE | 获取历史、清除会话、查看计划 |

### 2.3 认证方案

Chat App 支持两种认证方式：

| 方式 | 请求头 | 适用场景 |
|------|--------|---------|
| API Key | `X-Api-Key: hnx_xxx` | 外部接入、演示链接 |
| 管理后台 Token | `Authorization: Bearer <jwt>` | 内部用户从管理后台跳转 |

---

## 三、功能需求

### 3.1 功能总览

```
Harnax Chat App
├── 连接管理
│   ├── Router 地址配置
│   ├── API Key 管理
│   └── 连接状态检测
├── 会话管理
│   ├── 创建新会话
│   ├── 会话列表
│   ├── 切换会话
│   ├── 清除会话
│   └── 会话设置（描述、参数）
├── 对话交互
│   ├── 发送文本消息
│   ├── 发送图片（多模态）
│   ├── 流式接收响应
│   ├── 中断生成
│   ├── 工具确认/拒绝
│   └── 重试发送
├── 消息展示
│   ├── 思考过程（ThinkingEvent）
│   ├── 文本回复（TextEvent）
│   ├── 工具调用（CallToolEvent）
│   ├── 工具结果（ToolResultEvent）
│   ├── 工具确认（ToolConfirmEvent）
│   ├── 错误提示（ErrorEvent）
│   ├── Markdown 渲染
│   ├── 代码高亮
│   └── Token 用量统计
├── 高级功能
│   ├── 对话历史查看
│   ├── 执行计划查看
│   ├── 对话导出
│   └── 快捷键支持
└── 设置
    ├── 主题切换（浅色/深色）
    ├── 语言切换（中/英）
    └── 流式输出偏好
```

### 3.2 功能详细说明

#### F1: 连接管理

##### F1.1 Router 地址配置

用户首次使用时需配置 Router 地址：

- 输入 Router 服务地址（如 `https://router.example.com`）
- 支持本地存储（localStorage），下次打开自动加载
- 支持通过 URL 参数预设：`?router=https://xxx`

##### F1.2 API Key 管理

- 输入 API Key（格式 `hnx_sk_live_xxx`）
- 本地加密存储
- 支持多个 API Key 切换
- 连接测试按钮（调用 `/api/router/health`）

#### F2: 会话管理

##### F2.1 创建新会话

- 用户输入 `sessionId`（或使用自动生成的 UUID）
- 可选：输入会话描述
- 新建会话后自动进入对话界面

##### F2.2 会话列表

- 左侧边栏展示所有会话
- 显示每个会话的最后一条消息摘要
- 显示最后活跃时间
- 支持搜索过滤
- 本地存储会话列表（因为 Router 不提供列表接口）

##### F2.3 会话操作

| 操作 | API | 说明 |
|------|-----|------|
| 清除会话 | `DELETE /api/router/agent/session/{sessionId}` | 清除上下文和 Agent 缓存 |
| 压缩历史 | `POST /api/router/agent/command` + `COMPACT` | 压缩对话释放上下文窗口 |
| 查看历史 | `GET /api/router/agent/chat/history/{sessionId}` | 获取消息历史 |
| 查看计划 | `GET /api/router/agent/session/{sessionId}/plans` | 查看 Agent 执行计划 |

#### F3: 对话交互

##### F3.1 发送消息

**输入区域：**

- 多行文本输入框（支持 Shift+Enter 换行，Enter 发送）
- 图片上传按钮（支持粘贴图片）
- 发送按钮
- 停止生成按钮（发送中显示）

**发送流程：**

```
用户输入消息
    │
    ▼
POST /api/router/agent/chat/stream
Body: {
  sessionId: "sess-001",
  message: "用户消息内容",
  imageUrls: ["https://..."],    // 可选
  requestId: "uuid-xxx"          // 幂等 ID
}
Headers: {
  X-Api-Key: "hnx_xxx",
  Content-Type: "application/json"
}
    │
    ▼
建立 SSE 连接，开始接收事件流
```

##### F3.2 流式响应处理

接收 SSE 事件流，按 `eventType` 分类处理：

| eventType | 前端处理 |
|-----------|---------|
| `ThinkingEvent` | 在折叠区域显示思考过程，支持展开/收起 |
| `TextEvent` | 逐字追加到消息气泡，实时 Markdown 渲染 |
| `CallToolEvent` | 显示工具调用卡片（工具名、参数） |
| `ToolResultEvent` | 更新工具卡片状态（成功/失败、结果内容） |
| `ToolConfirmEvent` | 弹出确认对话框，等待用户操作 |
| `ErrorEvent` | 显示错误提示，标记消息为失败 |
| `EndEvent` | 结束流式接收，完成消息渲染 |

##### F3.3 工具确认交互

当收到 `ToolConfirmEvent` 时：

1. 在对话区域显示确认卡片：
   - 工具名称和参数
   - "确认执行" / "拒绝" 按钮
2. 用户点击后调用：
   ```
   POST /api/router/agent/confirm
   Body: {
     sessionId: "sess-001",
     isConfirmed: true/false,
     toolInfoList: [{ toolId: "tool-1", toolName: "code_execute" }]
   }
   ```
3. 继续接收 SSE 流

##### F3.4 中断生成

- 发送中断命令：`POST /api/router/agent/command` + `{ command: "INTERRUPT" }`
- 关闭当前 SSE 连接
- 保留已接收的部分回复

#### F4: 消息展示

##### F4.1 消息布局

```
┌─────────────────────────────────────────────────────┐
│                    对话区域                           │
│                                                      │
│  ┌─────────────────────────────┐                     │
│  │ 🤖 Agent                    │                     │
│  │ ┌─ 思考过程（折叠）────────┐ │                     │
│  │ │ 用户需要一个快速排序实现..│ │                     │
│  │ └──────────────────────────┘ │                     │
│  │                              │                     │
│  │ ┌─ 工具调用 ──────────────┐ │                     │
│  │ │ 🔧 code_execute         │ │                     │
│  │ │ 参数: {"language": ...} │ │                     │
│  │ │ 结果: ✅ 执行成功        │ │                     │
│  │ └──────────────────────────┘ │                     │
│  │                              │                     │
│  │ 以下是快速排序的实现：       │                     │
│  │ ```python                    │                     │
│  │ def quicksort(arr):          │                     │
│  │   ...                        │                     │
│  │ ```                          │                     │
│  │                              │                     │
│  │ Token: 135 | 耗时: 2.5s     │                     │
│  └─────────────────────────────┘                     │
│                                                      │
│  ┌─────────────────────────────────┐                 │
│  │                         👤 用户 │                 │
│  │        帮我写一个快速排序算法    │                 │
│  └─────────────────────────────────┘                 │
│                                                      │
├─────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────┐   │
│  │ 输入消息...                          [发送]   │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

##### F4.2 Markdown 渲染

- 支持 GFM（GitHub Flavored Markdown）
- 代码块语法高亮（react-syntax-highlighter）
- 表格渲染
- 图片渲染
- 链接可点击

##### F4.3 Token 用量统计

每条 Agent 回复底部显示：
- 输入 Token 数
- 输出 Token 数
- 总 Token 数
- 响应耗时

#### F5: 高级功能

##### F5.1 对话导出

- 导出为 Markdown 文件
- 导出为 JSON 格式（含完整事件数据）

##### F5.2 快捷键

| 快捷键 | 功能 |
|--------|------|
| `Enter` | 发送消息 |
| `Shift + Enter` | 换行 |
| `Ctrl + N` | 新建会话 |
| `Ctrl + K` | 搜索会话 |
| `Escape` | 停止生成 |

---

## 四、技术方案

### 4.1 技术栈选型

| 层面 | 技术 | 说明 |
|------|------|------|
| 框架 | React 18 + TypeScript | 与 harnax-webui 保持一致 |
| 构建 | Vite 6 | 轻量快速，适合独立 SPA |
| UI 库 | Ant Design 5 | 与 harnax-webui 保持一致 |
| 状态管理 | Zustand | 轻量级，适合中小型应用 |
| SSE 处理 | EventSource / fetch + ReadableStream | 原生 SSE 或 fetch 流式读取 |
| Markdown | react-markdown + remark-gfm | 已有成熟方案 |
| 代码高亮 | react-syntax-highlighter | 已有成熟方案 |
| 国际化 | react-intl | 支持中英文 |
| 样式 | CSS Modules + CSS Variables | 支持深色模式 |

### 4.2 项目结构

```
harnax-chat/
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
├── public/
│   ├── favicon.ico
│   └── logo.svg
└── src/
    ├── main.tsx                    # 入口
    ├── App.tsx                     # 根组件
    ├── types/                      # TypeScript 类型
    │   ├── api.ts                  # API 请求/响应类型
    │   ├── chat.ts                 # ChatEvent 类型定义
    │   └── session.ts              # 会话相关类型
    ├── api/                        # API 层
    │   ├── client.ts               # HTTP 客户端封装
    │   ├── router.ts               # Router API 调用
    │   └── sse.ts                  # SSE 流式处理
    ├── store/                      # 状态管理
    │   ├── useConnectionStore.ts   # 连接配置
    │   ├── useSessionStore.ts      # 会话管理
    │   └── useChatStore.ts         # 对话状态
    ├── hooks/                      # 自定义 Hooks
    │   ├── useSSE.ts               # SSE 连接管理
    │   ├── useChat.ts              # 对话逻辑
    │   └── useKeyboard.ts          # 快捷键
    ├── components/                 # UI 组件
    │   ├── layout/
    │   │   ├── AppLayout.tsx       # 应用布局
    │   │   └── Sidebar.tsx         # 侧边栏
    │   ├── chat/
    │   │   ├── ChatView.tsx        # 对话主视图
    │   │   ├── MessageBubble.tsx   # 消息气泡
    │   │   ├── ThinkingBlock.tsx   # 思考过程块
    │   │   ├── ToolCallCard.tsx    # 工具调用卡片
    │   │   ├── ToolConfirmCard.tsx # 工具确认卡片
    │   │   ├── TokenUsage.tsx      # Token 统计
    │   │   └── InputArea.tsx       # 输入区域
    │   ├── session/
    │   │   ├── SessionList.tsx     # 会话列表
    │   │   └── SessionItem.tsx     # 会话项
    │   ├── connection/
    │   │   ├── ConnectionSetup.tsx # 连接配置
    │   │   └── ApiKeyInput.tsx     # API Key 输入
    │   └── common/
    │       ├── MarkdownRenderer.tsx # Markdown 渲染
    │       └── CodeBlock.tsx        # 代码块
    ├── pages/                      # 页面
    │   ├── ChatPage.tsx            # 对话页
    │   └── SetupPage.tsx           # 首次配置页
    ├── locales/                    # 国际化
    │   ├── zh-CN.ts
    │   └── en-US.ts
    ├── styles/                     # 全局样式
    │   ├── variables.css           # CSS 变量
    │   ├── global.css              # 全局样式
    │   └── dark.css                # 深色模式
    └── utils/                      # 工具函数
        ├── storage.ts              # 本地存储
        └── format.ts               # 格式化工具
```

### 4.3 核心类型定义

```typescript
// SSE 事件类型
type ChatEvent =
  | ThinkingEvent
  | TextEvent
  | CallToolEvent
  | ToolResultEvent
  | ToolConfirmEvent
  | ErrorEvent
  | EndEvent;

interface ThinkingEvent {
  eventType: 'ThinkingEvent';
  message: string;
  isLast: boolean;
  tokenUsage?: TokenUsage;
}

interface TextEvent {
  eventType: 'TextEvent';
  message: string;
  isLast: boolean;
  tokenUsage?: TokenUsage;
}

interface CallToolEvent {
  eventType: 'CallToolEvent';
  toolId: string;
  toolName: string;
  arguments: Record<string, unknown>;
  tokenUsage?: TokenUsage;
}

interface ToolResultEvent {
  eventType: 'ToolResultEvent';
  toolId: string;
  toolName: string;
  message: string;
  success: boolean;
  tokenUsage?: TokenUsage;
}

interface ToolConfirmEvent {
  eventType: 'ToolConfirmEvent';
  pendingCallTools: PendingCallTool[];
}

interface ErrorEvent {
  eventType: 'ErrorEvent';
  code: number;
  message: string;
}

interface EndEvent {
  eventType: 'EndEvent';
}

interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  costTime: number;
  timestamp: number;
}

interface PendingCallTool {
  toolId: string;
  toolName: string;
  arguments?: Record<string, unknown>;
}
```

### 4.4 SSE 流式处理方案

使用 `fetch` + `ReadableStream` 方案（相比 `EventSource` 支持 POST 请求和自定义 Header）：

```typescript
async function streamChat(
  params: ChatRequest,
  apiKey: string,
  routerUrl: string,
  onEvent: (event: ChatEvent) => void,
  signal?: AbortSignal,
) {
  const response = await fetch(
    `${routerUrl}/api/router/agent/chat/stream`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Api-Key': apiKey,
      },
      body: JSON.stringify(params),
      signal,
    },
  );

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      if (line.startsWith('data:')) {
        const event = JSON.parse(line.slice(5).trim()) as ChatEvent;
        onEvent(event);
        if (event.eventType === 'EndEvent') return;
      }
    }
  }
}
```

### 4.5 部署方案

| 方案 | 说明 | 适用场景 |
|------|------|---------|
| 独立部署 | 独立 Nginx 容器，反向代理 Router | 生产环境 |
| 嵌入管理后台 | 作为 harnax-webui 的子路由 | 管理后台内置对话功能 |
| CDN 静态部署 | 纯前端 SPA，指向 Router 地址 | 快速分发 |

---

## 五、页面设计

### 5.1 页面清单

| 页面 | 路由 | 说明 |
|------|------|------|
| 初始配置页 | `/setup` | 首次使用时配置 Router 和 API Key |
| 对话页 | `/` | 主界面，包含会话列表和对话区域 |
| 对话页（指定会话） | `/session/:sessionId` | 直接进入指定会话 |

### 5.2 界面布局

```
┌──────────┬──────────────────────────────────────────────┐
│          │  会话标题                    [设置] [清除]     │
│ 会话列表  ├──────────────────────────────────────────────┤
│          │                                              │
│ [+新建]  │   Agent 消息气泡                              │
│ [搜索]   │   - 思考过程（可折叠）                         │
│          │   - 工具调用卡片                               │
│ 会话 1   │   - 文本回复（Markdown）                       │
│ 会话 2 ● │   - Token 统计                                │
│ 会话 3   │                                              │
│          │            用户消息气泡                        │
│          │                                              │
│          │   Agent 正在输入... ●●●                        │
│          │                                              │
│          ├──────────────────────────────────────────────┤
│          │  [📎] 输入消息...                    [发送]    │
│          │                                      [停止]  │
└──────────┴──────────────────────────────────────────────┘
```

### 5.3 响应式设计

| 断点 | 布局 | 说明 |
|------|------|------|
| ≥ 768px | 左侧会话列表 + 右侧对话区 | 桌面端 |
| < 768px | 会话列表抽屉 + 全屏对话区 | 移动端 |

---

## 六、非功能性需求

### 6.1 性能

| 指标 | 目标 |
|------|------|
| 首屏加载 | < 2s（gzip 后 < 500KB） |
| 消息渲染延迟 | < 100ms（单条事件） |
| SSE 连接恢复 | < 3s（断线重连） |
| 内存占用 | < 200MB（10 个会话、1000 条消息） |

### 6.2 安全

- API Key 不提交到任何第三方服务
- API Key 本地存储使用加密（AES）
- 支持 Content-Security-Policy
- XSS 防护：Markdown 渲染时过滤危险 HTML
- 不记录对话内容到服务端日志

### 6.3 可用性

- 支持深色/浅色主题
- 支持中英文切换
- 键盘无障碍访问
- 消息发送失败自动重试（最多 2 次）
- SSE 断线自动重连（最多 3 次，指数退避）

---

## 七、API 端点汇总

Chat App 使用的 Router API 端点清单：

| 方法 | 端点 | 功能 | 认证 |
|------|------|------|------|
| POST | `/api/router/agent/chat/stream` | 流式对话 | X-Api-Key |
| POST | `/api/router/agent/chat` | 同步对话（备选） | X-Api-Key |
| POST | `/api/router/agent/command` | 控制命令 | X-Api-Key |
| POST | `/api/router/agent/confirm` | 工具确认 | X-Api-Key |
| DELETE | `/api/router/agent/session/{sessionId}` | 清除会话 | X-Api-Key |
| GET | `/api/router/agent/chat/history/{sessionId}` | 获取历史 | X-Api-Key |
| GET | `/api/router/agent/session/{sessionId}/plans` | 获取计划列表 | X-Api-Key |
| GET | `/api/router/agent/session/{sessionId}/current-plan` | 获取当前计划 | X-Api-Key |
| GET | `/api/router/health` | 健康检查 | 无 |

---

## 八、里程碑计划

| 阶段 | 内容 | 预计工时 |
|------|------|---------|
| P0 - 核心对话 | 连接配置 + 单会话流式对话 + 消息展示 | 5 天 |
| P1 - 会话管理 | 多会话 + 会话列表 + 清除/压缩 | 3 天 |
| P2 - 高级交互 | 工具确认 + 中断生成 + 图片发送 | 3 天 |
| P3 - 体验优化 | 深色模式 + 国际化 + 快捷键 + 导出 | 3 天 |
| P4 - 部署上线 | Nginx 部署 + 嵌入管理后台选项 | 2 天 |

---

## 九、开放问题

| 编号 | 问题 | 备选方案 | 待决策 |
|------|------|---------|--------|
| Q1 | Chat App 是独立项目还是嵌入 harnax-webui？ | 独立 SPA / 子路由嵌入 | 建议先独立，后续可选嵌入 |
| Q2 | 会话列表数据从哪里获取？ | 本地存储 / Router 新增列表接口 | Router 当前无列表接口，先用本地存储 |
| Q3 | 是否需要用户登录？ | 纯 API Key / 管理后台 SSO | 建议先用 API Key，后续可对接 SSO |
| Q4 | 是否需要支持多 Agent 切换？ | 固定 Agent / 可切换 | 建议先绑定单个 Agent（通过 Session） |
| Q5 | 移动端是否需要独立 App？ | Web 响应式 / PWA / 原生 App | 建议先做 Web 响应式 |

---

## 附录 A：与现有系统的关系

```
harnax-webui (管理后台)          harnax-chat (对话应用)
┌──────────────────────┐     ┌──────────────────────┐
│ Agent 管理            │     │ 对话交互              │
│ 模型配置              │     │ 会话管理              │
│ MCP 管理              │     │ 消息展示              │
│ Skill 管理            │     │                      │
│ Channel 管理          │     │                      │
│ 用户/租户管理         │     │                      │
│ 任务管理              │     │                      │
│ Token 监控            │     │                      │
│ API Key 管理          │     │                      │
└──────────┬───────────┘     └──────────┬───────────┘
           │                            │
           │  /api/ (管理 API)           │  /api/router/agent/ (对话 API)
           ▼                            ▼
    harnax-admin :8080          Session Router :8081
                                        │
                                        ▼
                                Agent Service :8082
```

两个前端共享同一个后端基础设施，但通过不同的 API 入口和认证方式访问。Chat App 使用 Router 的 `router:invoke` scope，管理后台使用 Spring Security JWT。
