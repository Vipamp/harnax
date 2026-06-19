# Harnax Chat App 技术选型与方案设计

> 版本：v1.0.0  
> 日期：2026-06-19  
> 状态：方案评审中

---

## 一、跨平台技术选型分析

### 1.1 目标平台

| 平台 | 系统 | 应用形态 |
|------|------|---------|
| Android | Android 8.0+ | 原生 APK |
| iOS | iOS 14+ | 原生 IPA |
| 鸿蒙 NEXT | HarmonyOS NEXT | ArkTS HAP |
| 微信小程序 | 微信 8.0+ | 小程序 |

### 1.2 候选方案对比

| 维度 | **UniApp X** | **Taro 4.x** | **Flutter (鸿蒙版)** | **React Native** |
|------|-------------|-------------|---------------------|-----------------|
| **开发语言** | Vue 3 + TypeScript (UTS) | React + TypeScript | Dart | React + TypeScript |
| **Android** | ✅ 编译为原生 | ✅ React Native | ✅ 原生 | ✅ 原生 |
| **iOS** | ✅ 编译为原生 | ✅ React Native | ✅ 原生 | ✅ 原生 |
| **鸿蒙 NEXT** | ✅ 官方支持，编译为 ArkTS | ✅ 官方支持，编译为 ArkTS | ✅ 官方 flutter_harmony | ⚠️ 社区版 react-native-harmony |
| **微信小程序** | ✅ 原生编译 | ✅ 原生编译 | ❌ 不支持 | ❌ 不支持 |
| **SSE 流式通信** | ✅ fetch + ReadableStream | ⚠️ 小程序受限，需 WebSocket 降级 | ✅ 完整支持 | ✅ 完整支持 |
| **Markdown 渲染** | ✅ 生态丰富 | ✅ 丰富 | ✅ 丰富 | ✅ 丰富 |
| **UI 组件库** | uView UI / uni-ui | NutUI / Taro UI | Material / Cupertino | 丰富 |
| **性能** | 原生级别 (UTS 编译) | 原生级别 (RN 引擎) | 原生级别 (Skia) | 原生级别 |
| **生态成熟度** | ⭐⭐⭐⭐ 国内生态最好 | ⭐⭐⭐ 京东维护 | ⭐⭐⭐⭐ 全球生态 | ⭐⭐⭐⭐⭐ |
| **与 Harnax 技术栈契合度** | ⚠️ Vue 系，与现有 React 不同 | ✅ React 系，与 webui 一致 | ❌ Dart 全新语言 | ✅ React 系 |
| **鸿蒙适配工作量** | 小（官方支持） | 小（官方支持） | 中（需 Flutter Harmony 适配） | 大（社区版不稳定） |

### 1.3 关键约束分析

#### 1.3.1 SSE 流式通信

这是最大技术挑战。Router 使用 HTTP POST + SSE 流式响应：

| 平台 | SSE 支持情况 |
|------|-------------|
| Android/iOS (原生) | ✅ 完整支持 fetch + ReadableStream |
| 鸿蒙 NEXT (ArkTS) | ✅ @ohos.net.http 支持流式读取 |
| 微信小程序 | ❌ `wx.request` 不支持流式读取，需降级方案 |

**微信小程序 SSE 降级方案：**

| 方案 | 优点 | 缺点 |
|------|------|------|
| **方案 A：Router 增加 WebSocket 端点** | 体验好，双向通信 | Router 需改造 |
| 方案 B：使用同步接口 | 简单 | 体验差，无法展示流式 |
| 方案 C：Nginx 层 chunked + 轮询 | 无需改造 | 复杂，延迟高 |

**推荐方案 A**：Router 新增 `/api/router/agent/chat/ws` WebSocket 端点，小程序专用。

#### 1.3.2 鸿蒙 NEXT 适配

鸿蒙 NEXT（纯血鸿蒙）不再兼容 Android APK，必须编译为 ArkTS。目前只有三个框架官方支持：
- UniApp X — 编译为 ArkTS 原生
- Taro 4.x — 编译为 ArkTS 原生
- Flutter — flutter_harmony 插件

#### 1.3.3 技术栈一致性

Harnax 前端（harnax-webui）使用 React 18 + TypeScript + Ant Design。从团队技术栈一致性考虑，React 系更优。

### 1.4 选型结论

**推荐方案：UniApp X**

经过综合评估，UniApp X 作为 Chat App 的技术框架。

**核心理由：**

1. **唯一真正覆盖全部四端的方案** — UniApp X 对鸿蒙 NEXT 的支持最成熟，DCloud 与华为有官方合作
2. **国内生态最完善** — 小程序编译、插件市场、中文文档和社区支持远超其他方案
3. **UTS 原生编译** — 条件编译为原生代码，性能不输 React Native
4. **开发效率高** — Vue 3 + TypeScript 学习曲线平缓，组件复用率高
5. **微信生态原生支持** — 小程序编译是 UniApp 的核心能力，不是附加功能

**劣势及应对：**

| 劣势 | 应对方案 |
|------|---------|
| Vue 系与现有 React 前端不一致 | Chat App 是独立项目，不影响 webui；可安排熟悉 Vue 的开发者负责 |
| UniApp X 鸿蒙端仍在快速迭代 | 使用条件编译隔离平台代码，降低升级风险 |
| 插件生态质量参差不齐 | 核心功能（SSE、Markdown）自行实现，减少第三方依赖 |

---

## 二、系统架构

### 2.1 整体架构

```
┌────────────────────────────────────────────────────────────┐
│                    Harnax Chat App                          │
│                     (UniApp X 项目)                         │
│                                                             │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────────┐    │
│  │ 会话列表  │  │  对话主界面   │  │  流式通信引擎      │    │
│  └──────────┘  └──────────────┘  └────────────────────┘    │
└──────────┬───────────┬────────────────┬─────────────────────┘
           │           │                │
    ┌──────▼──────┐  ┌─▼──────────┐  ┌─▼──────────────┐
    │  Android    │  │   iOS      │  │  鸿蒙 NEXT     │
    │  (原生 APK) │  │ (原生 IPA) │  │  (ArkTS HAP)   │
    └──────┬──────┘  └──────┬─────┘  └──────┬─────────┘
           │                │                │
           │    HTTP POST + SSE              │
           └────────────────┼────────────────┘
                            │
                     ┌──────▼──────┐
                     │   微信小程序  │
                     │  (wx.request │
                     │   → HTTP)    │
                     └──────┬──────┘
                            │
                    WebSocket 降级
                     ┌──────▼──────┐
                     │  WebSocket  │
                     │  Endpoint   │
                     └──────┬──────┘
                            │
                ┌───────────▼──────────────┐
                │   Session Router :8081    │
                │  /api/router/agent/chat/stream (SSE)  │
                │  /api/router/agent/chat/ws   (WS, 新增)│
                └──────────────────────────┘
```

### 2.2 通信协议

| 通信方式 | 协议 | 适用平台 | 用途 |
|---------|------|---------|------|
| 流式对话 | HTTP POST + SSE | Android/iOS/鸿蒙 | 发送消息，实时接收 Agent 响应 |
| WebSocket | WebSocket | 微信小程序 | 发送消息，接收 Agent 响应（降级方案） |
| 同步对话 | HTTP POST | 全平台 | 简单问答（备选方案） |
| 命令控制 | HTTP POST | 全平台 | 中断、清除会话、压缩历史 |
| 工具确认 | HTTP POST / WebSocket | 全平台 | 确认/拒绝危险工具调用 |
| 会话管理 | HTTP GET/DELETE | 全平台 | 获取历史、清除会话、查看计划 |

### 2.3 认证方案

Chat App 支持两种认证方式：

| 方式 | 请求头 | 适用场景 |
|------|--------|---------|
| API Key | `X-Api-Key: hnx_xxx` | 外部接入、演示链接 |
| 管理后台 Token | `Authorization: Bearer <jwt>` | 内部用户从管理后台跳转 |

---

## 三、项目结构

### 3.1 目录结构

```
harnax-app/
├── package.json
├── index.html
├── vite.config.ts
├── manifest.json                    # UniApp 配置
├── uni.scss                         # 全局样式
├── App.vue                          # 根组件
├── main.ts                          # 入口
├── pages.json                       # 页面路由配置
├── types/                           # TypeScript 类型
│   ├── api.ts                       # API 请求/响应类型
│   ├── chat.ts                      # ChatEvent 类型定义
│   └── session.ts                   # 会话相关类型
├── api/                             # API 层
│   ├── client.ts                    # HTTP 客户端封装
│   ├── router.ts                    # Router API 调用
│   ├── sse.ts                       # SSE 流式处理 (App/鸿蒙)
│   └── websocket.ts                 # WebSocket 处理 (小程序)
├── store/                           # 状态管理
│   ├── index.ts                     # Pinia 初始化
│   ├── useConnectionStore.ts        # 连接配置
│   ├── useSessionStore.ts           # 会话管理
│   └── useChatStore.ts              # 对话状态
├── composables/                     # 组合式函数
│   ├── useSSE.ts                    # SSE 连接管理
│   ├── useWebSocket.ts              # WebSocket 连接管理
│   ├── useChat.ts                   # 对话逻辑
│   └── useKeyboard.ts               # 快捷键
├── components/                      # UI 组件
│   ├── layout/
│   │   ├── AppLayout.vue            # 应用布局
│   │   └── Sidebar.vue              # 侧边栏
│   ├── chat/
│   │   ├── ChatView.vue             # 对话主视图
│   │   ├── MessageBubble.vue        # 消息气泡
│   │   ├── ThinkingBlock.vue        # 思考过程块
│   │   ├── ToolCallCard.vue         # 工具调用卡片
│   │   ├── ToolConfirmCard.vue      # 工具确认卡片
│   │   ├── TokenUsage.vue           # Token 统计
│   │   └── InputArea.vue            # 输入区域
│   ├── session/
│   │   ├── SessionList.vue          # 会话列表
│   │   └── SessionItem.vue          # 会话项
│   ├── connection/
│   │   ├── ConnectionSetup.vue      # 连接配置
│   │   └── ApiKeyInput.vue          # API Key 输入
│   └── common/
│       ├── MarkdownRenderer.vue     # Markdown 渲染
│       └── CodeBlock.vue            # 代码块
├── pages/                           # 页面
│   ├── chat/
│   │   └── index.vue                # 对话页
│   └── setup/
│       └── index.vue                # 首次配置页
├── locales/                         # 国际化
│   ├── zh-CN.ts
│   └── en-US.ts
├── styles/                          # 全局样式
│   ├── variables.scss               # CSS 变量
│   ├── global.scss                  # 全局样式
│   └── dark.scss                    # 深色模式
├── utils/                           # 工具函数
│   ├── storage.ts                   # 本地存储
│   ├── format.ts                    # 格式化工具
│   └── platform.ts                  # 平台检测
├── static/                          # 静态资源
│   ├── favicon.ico
│   └── logo.svg
└── platform/                        # 平台特定代码
    ├── app/                         # App 平台特定
    │   └── sse.ts
    ├── mp-weixin/                   # 微信小程序特定
    │   └── websocket.ts
    └── harmony/                     # 鸿蒙特定
        └── sse.ts
```

### 3.2 平台条件编译

UniApp X 支持条件编译，用于处理平台差异：

```typescript
// #ifdef APP-PLUS
// App 平台代码（Android/iOS）
import { streamChatSSE } from '@/platform/app/sse'
// #endif

// #ifdef MP-WEIXIN
// 微信小程序平台代码
import { streamChatWebSocket } from '@/platform/mp-weixin/websocket'
// #endif

// #ifdef APP-HARMONY
// 鸿蒙平台代码
import { streamChatHarmony } from '@/platform/harmony/sse'
// #endif
```

---

## 四、核心类型定义

### 4.1 ChatEvent 类型

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

### 4.2 API 请求/响应类型

```typescript
interface ChatRequest {
  sessionId: string;
  message: string;
  imageUrls?: string[];
  requestId?: string;
}

interface CommandRequest {
  sessionId: string;
  command: 'INTERRUPT' | 'CLEAR' | 'COMPACT' | 'APPROVE';
}

interface ConfirmRequest {
  sessionId: string;
  isConfirmed: boolean;
  toolInfoList: Array<{
    toolId: string;
    toolName: string;
  }>;
}

interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data?: T;
  timestamp: number;
}
```

---

## 五、流式通信方案

### 5.1 SSE 方案（App/鸿蒙）

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

### 5.2 WebSocket 方案（微信小程序）

```typescript
class ChatWebSocket {
  private ws: UniApp.SocketTask | null = null;
  private routerUrl: string;
  private apiKey: string;

  constructor(routerUrl: string, apiKey: string) {
    this.routerUrl = routerUrl;
    this.apiKey = apiKey;
  }

  connect() {
    const wsUrl = this.routerUrl.replace(/^http/, 'ws');
    this.ws = uni.connectSocket({
      url: `${wsUrl}/api/router/agent/chat/ws?apiKey=${this.apiKey}`,
      success: () => {
        console.log('WebSocket 连接成功');
      },
    });

    this.ws.onMessage((res) => {
      const event = JSON.parse(res.data) as ChatEvent;
      this.onEvent?.(event);
    });
  }

  send(params: ChatRequest) {
    this.ws?.send({
      data: JSON.stringify({
        type: 'chat',
        ...params,
      }),
    });
  }

  onEvent?: (event: ChatEvent) => void;

  close() {
    this.ws?.close({});
  }
}
```

---

## 六、Router 侧改造需求

### 6.1 新增 WebSocket 端点

| 改造项 | 优先级 | 说明 |
|--------|--------|------|
| 新增 WebSocket 端点 | P0 | `/api/router/agent/chat/ws`，小程序专用 |
| WebSocket 消息协议 | P0 | 复用 ChatEvent 类型，JSON over WS |
| WebSocket 认证 | P0 | 连接时通过 URL 参数或首条消息传递 API Key |

### 6.2 WebSocket 端点设计

```
WebSocket: wss://router/api/router/agent/chat/ws?apiKey=hnx_xxx

客户端发送:
{
  "type": "chat",
  "sessionId": "sess-001",
  "message": "你好",
  "requestId": "uuid-xxx"
}

客户端发送（命令）:
{
  "type": "command",
  "sessionId": "sess-001",
  "command": "INTERRUPT"
}

客户端发送（确认）:
{
  "type": "confirm",
  "sessionId": "sess-001",
  "isConfirmed": true,
  "toolInfoList": [{"toolId": "tool-1", "toolName": "code_execute"}]
}

服务端推送（与 SSE 事件类型一致）:
{"eventType": "ThinkingEvent", "message": "...", "isLast": false}
{"eventType": "TextEvent", "message": "...", "isLast": false}
{"eventType": "CallToolEvent", "toolId": "...", "toolName": "...", "arguments": {...}}
{"eventType": "ToolResultEvent", "toolId": "...", "toolName": "...", "message": "...", "success": true}
{"eventType": "EndEvent"}
```

### 6.3 实现要点

1. **连接管理**：使用 `ConcurrentHashMap<sessionId, WebSocketSession>` 管理连接
2. **消息路由**：WebSocket 消息转换为内部 `AgentRequest`，复用现有代理逻辑
3. **事件推送**：Agent 产出的 `ChatEvent` 序列化为 JSON 推送给客户端
4. **认证**：连接建立时校验 API Key，失败则关闭连接
5. **心跳**：每 30s 发送 ping，客户端响应 pong，超时断开

---

## 七、页面设计

### 7.1 页面清单

| 页面 | 路由 | 说明 |
|------|------|------|
| 初始配置页 | `/pages/setup/index` | 首次使用时配置 Router 和 API Key |
| 对话页 | `/pages/chat/index` | 主界面，包含会话列表和对话区域 |
| 对话页（指定会话） | `/pages/chat/index?sessionId=xxx` | 直接进入指定会话 |

### 7.2 界面布局

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

### 7.3 响应式设计

| 断点 | 布局 | 说明 |
|------|------|------|
| ≥ 768px | 左侧会话列表 + 右侧对话区 | 桌面端/平板 |
| < 768px | 会话列表抽屉 + 全屏对话区 | 手机 |

### 7.4 消息展示

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

---

## 八、技术栈

| 层面 | 技术 | 说明 |
|------|------|------|
| 框架 | UniApp X | 跨平台编译框架 |
| 语言 | Vue 3 + TypeScript (UTS) | 条件编译支持 |
| UI 库 | uni-ui + 自定义组件 | 兼容全端 |
| 状态管理 | Pinia | Vue 3 官方推荐 |
| Markdown | 自研（基于 rich-text） | 小程序不支持 DOM，需自定义 |
| 代码高亮 | highlight.js + rich-text | 转 HTML 字符串渲染 |
| 流式通信 | SSE (App/鸿蒙) + WebSocket (小程序) | 平台适配层 |
| 网络请求 | uni.request / UTS 原生 | 条件编译 |
| 国际化 | vue-i18n | 中英文 |
| 样式 | SCSS + CSS Variables | 支持深色模式 |

---

## 九、非功能性需求

### 9.1 性能

| 指标 | 目标 |
|------|------|
| 首屏加载 | < 2s（gzip 后 < 500KB） |
| 消息渲染延迟 | < 100ms（单条事件） |
| SSE 连接恢复 | < 3s（断线重连） |
| 内存占用 | < 200MB（10 个会话、1000 条消息） |
| 小程序包体积 | < 2MB（主包） |

### 9.2 安全

- API Key 不提交到任何第三方服务
- API Key 本地存储使用加密
- 支持 Content-Security-Policy
- XSS 防护：Markdown 渲染时过滤危险 HTML
- 不记录对话内容到服务端日志

### 9.3 可用性

- 支持深色/浅色主题
- 支持中英文切换
- 键盘无障碍访问
- 消息发送失败自动重试（最多 2 次）
- SSE 断线自动重连（最多 3 次，指数退避）

---

## 十、部署方案

### 10.1 构建命令

```bash
# 安装依赖
npm install

# 开发模式
npm run dev:app            # App 平台
npm run dev:mp-weixin      # 微信小程序
npm run dev:harmony        # 鸿蒙

# 生产构建
npm run build:app          # App 平台（生成 APK/IPA）
npm run build:mp-weixin    # 微信小程序（生成小程序代码）
npm run build:harmony      # 鸿蒙（生成 HAP）
```

### 10.2 发布渠道

| 平台 | 发布方式 | 审核 |
|------|---------|------|
| Android | APK 下载 / Google Play / 应用宝 | 需要 |
| iOS | App Store | 需要 |
| 鸿蒙 NEXT | 华为应用市场 | 需要 |
| 微信小程序 | 微信开放平台 | 需要 |

---

## 十一、里程碑计划

| 阶段 | 内容 | 预计工时 |
|------|------|---------|
| P0 - 核心对话 | 连接配置 + 单会话流式对话 + 消息展示 | 7 天 |
| P1 - 会话管理 | 多会话 + 会话列表 + 清除/压缩 | 3 天 |
| P2 - 高级交互 | 工具确认 + 中断生成 + 图片发送 | 3 天 |
| P3 - Router 改造 | WebSocket 端点开发 + 联调 | 3 天 |
| P4 - 多端适配 | 各平台调试 + 性能优化 + UI 适配 | 5 天 |
| P5 - 体验优化 | 深色模式 + 国际化 + 快捷键 + 导出 | 3 天 |
| P6 - 发布上线 | 打包 + 审核 + 上线 | 5 天 |

**总计：约 29 个工作日**

---

## 十二、风险与应对

| 风险 | 概率 | 影响 | 应对方案 |
|------|------|------|---------|
| UniApp X 鸿蒙端 API 不稳定 | 中 | 高 | 使用条件编译隔离，及时跟进版本更新 |
| 微信小程序审核被拒 | 中 | 中 | 提前准备审核材料，准备备用方案 |
| SSE 在部分机型兼容性问题 | 低 | 中 | 充分测试，准备 WebSocket 降级方案 |
| Markdown 渲染在小程序性能差 | 中 | 中 | 虚拟列表 + 懒加载 + 简化渲染 |

---

## 十三、开放问题

| 编号 | 问题 | 备选方案 | 待决策 |
|------|------|---------|--------|
| Q1 | Chat App 是独立项目还是嵌入 harnax-webui？ | 独立 SPA / 子路由嵌入 | 建议先独立，后续可选嵌入 |
| Q2 | 会话列表数据从哪里获取？ | 本地存储 / Router 新增列表接口 | Router 当前无列表接口，先用本地存储 |
| Q3 | 是否需要用户登录？ | 纯 API Key / 管理后台 SSO | 建议先用 API Key，后续可对接 SSO |
| Q4 | 是否需要支持多 Agent 切换？ | 固定 Agent / 可切换 | 建议先绑定单个 Agent（通过 Session） |
| Q5 | 移动端是否需要独立 App？ | Web 响应式 / PWA / 原生 App | 建议先做原生 App（UniApp X） |
| Q6 | 是否需要推送通知？ | 不需要 / 需要 | 建议一期不做，二期考虑 |

---

## 十四、与现有系统的关系

```
harnax-webui (管理后台)          harnax-app (对话应用)
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

两个前端共享同一个后端基础设施，但通过不同的 API 入口和认证方式访问。Chat App 使用 Router 的外部 API Key 认证（`X-Api-Key`），管理后台使用 Spring Security JWT。

---

## 附录 A：API 端点汇总

Chat App 使用的 Router API 端点清单：

| 方法 | 端点 | 功能 | 认证 |
|------|------|------|------|
| POST | `/api/router/agent/chat/stream` | 流式对话 | X-Api-Key |
| WebSocket | `/api/router/agent/chat/ws` | 流式对话（小程序） | URL 参数 |
| POST | `/api/router/agent/chat` | 同步对话（备选） | X-Api-Key |
| POST | `/api/router/agent/command` | 控制命令 | X-Api-Key |
| POST | `/api/router/agent/confirm` | 工具确认 | X-Api-Key |
| DELETE | `/api/router/agent/session/{sessionId}` | 清除会话 | X-Api-Key |
| GET | `/api/router/agent/chat/history/{sessionId}` | 获取历史 | X-Api-Key |
| GET | `/api/router/agent/session/{sessionId}/plans` | 获取计划列表 | X-Api-Key |
| GET | `/api/router/agent/session/{sessionId}/current-plan` | 获取当前计划 | X-Api-Key |
| GET | `/api/router/health` | 健康检查 | 无 |
