# Harnax Channel 模块架构文档

## 一、模块总览

harnax-channel 是 Harnax 项目的多渠道消息接入层，负责对接外部 IM 平台（微信、飞书、钉钉、企微等），将用户消息统一转换后交给 AI Agent 处理，再将 AI 回复发送回对应渠道。

### Maven 模块结构

```
harnax-channel (parent)
├── harnax-channel-sdk        # SDK 抽象层 — 定义核心接口与消息模型
├── harnax-channel-feishu     # 飞书渠道实现 — WebSocket + Webhook 双模式
├── harnax-channel-wechat     # 微信渠道实现 — 长轮询模式
└── harnax-admin              # 业务集成层 — ReActAgentAdaptor 具体实现
```

### 支持的渠道类型

| 渠道 | ChannelType | 通信模式 | 流式输出 | 输入指示 |
|------|------------|---------|---------|---------|
| 微信 | `WECHAT` | 长轮询 (getUpdates) | ❌ 不支持 | ✅ typing |
| 飞书 | `FEISHU` | WebSocket / Webhook | ❌ 不支持 | ❌ 无API |
| 企微 | `WECOM` | Webhook | ❌ 不支持 | ❌ 无API |
| 钉钉 | `DINGTALK` | Webhook | ❌ 不支持 | ❌ 无API |
| HTTP | `HTTP` | SSE | ✅ 支持 | — |

---

## 二、整体架构图

```mermaid
graph TB
    subgraph 外部IM平台
        WX[微信 iLink Bot]
        FS[飞书 Open API]
        DT[钉钉 Open API]
        WC[企微 Open API]
        HTTP_CLIENT[HTTP / SSE Client]
    end

    subgraph harnax-channel-sdk["harnax-channel-sdk (抽象层)"]
        direction TB
        CA[ChannelAdaptor<br/>渠道适配器接口]
        AA[AgentAdaptor<br/>Agent处理器抽象类]
        CCM[ChannelCommunicationMode<br/>通信模式接口]
        ASE[AgentStreamEvent<br/>流式事件类型]
        CCS[ChannelChatService<br/>编排服务]
        CS[ChannelSpec<br/>渠道配置]
        CSM[ChannelSessionManager<br/>会话管理接口]
        CM[ChannelMessage<br/>统一消息模型]
    end

    subgraph harnax-channel-wechat["harnax-channel-wechat"]
        WA[WechatAdaptor]
        WLPM[WechatLongPollingMode]
        WBS[WechatBotService]
        WMC[WechatMessageConverter]
    end

    subgraph harnax-channel-feishu["harnax-channel-feishu"]
        FA[FeishuAdaptor]
        FWSM[FeishuWebSocketMode]
        FMB[FeishuMessageBuilder]
        PHC[PlatformHttpClient]
    end

    subgraph harnax-admin["harnax-admin (业务层)"]
        RAA[ReActAgentAdaptor]
        AAL[AscopeAgentLauncher]
    end

    WX -->|长轮询| WBS
    WBS -->|消息转换| WMC
    WMC -->|ChannelMessage| WA
    WA -->|implements| CA
    WA -->|delegates| WLPM
    WLPM -->|implements| CCM

    FS -->|WebSocket/回调| FWSM
    FWSM -->|消息转换| FA
    FA -->|implements| CA
    FWSM -->|implements| CCM
    FA -->|HTTP发送| PHC

    AA -->|extends| RAA
    RAA -->|调用| AAL

    CA --> CCS
    AA --> CCS
    CCM --> CCS
```

---

## 三、SDK 核心抽象层详解

### 3.1 接口关系图

```mermaid
classDiagram
    class ChannelAdaptor {
        <<interface>>
        +getType() ChannelType
        +verifySignature(request, channel) Boolean
        +parseMessage(request) ChannelMessage
        +buildResponse(reply, originalMessage) Any
        +sendMessage(channel, sessionId, message)
        +sendRichMessage(channel, sessionId, richMessage)
        +handleUrlVerification(request, channel) Any?
        +supportsStreamingOutput() Boolean
        +sendStreamingFragment(channel, sessionId, fragment, isLast)
        +sendTypingIndicator(channel, sessionId)
    }

    class AgentAdaptor {
        <<abstract>>
        +getName() String
        +process(context) AgentResponse
        +streamProcess(context) Flow~AgentStreamEvent~
        +supportsStreaming() Boolean
        +supports(message) Boolean
        +onBeforeProcess(context)
        +onAfterProcess(context, response)
        +onError(context, error) AgentResponse
    }

    class ChannelCommunicationMode {
        <<interface>>
        +getModeName() String
        +isCallbackMode() Boolean
        +start(channel, messageHandler)
        +stop(channel)
        +sendMessage(channel, sessionId, message)
        +sendRichMessage(channel, sessionId, richMessage)
        +supportsStreamingOutput() Boolean
        +sendStreamingFragment(channel, sessionId, fragment, isLast)
        +sendTypingIndicator(channel, sessionId)
    }

    class ChannelChatService {
        +chat(message, channel, agentAdaptor, channelAdaptor)
        #streamAndSend(...)
        #batchSend(...)
        #handleChatError(...)
        #saveAssistantMessage(...)
    }

    class ChannelSessionManager {
        <<interface>>
        +getHistory(channelId, sessionId, limit) List~ChannelMessage~
        +addMessage(channelId, message)
        +clearHistory(channelId, sessionId)
        +toAgentMessages(messages) List~AgentMessage~
    }

    class AgentStreamEvent {
        <<sealed>>
        +TextStreamEvent(content, isLast)
        +ThinkingStreamEvent(content, isLast)
        +EndStreamEvent(fullContent?)
        +ErrorStreamEvent(error, cause?)
    }

    class ChannelSpec {
        +id Long
        +name String
        +type ChannelType
        +agentId Long
        +webhookUrl String?
        +appId String?
        +appSecret String?
        +communicationMode String
    }

    class ChannelMessage {
        +sessionId String
        +content String
        +role MessageRole
        +channelType ChannelType
        +senderId String?
        +messageType MessageType
    }

    ChannelChatService --> ChannelSessionManager : uses
    ChannelChatService --> AgentAdaptor : calls
    ChannelChatService --> ChannelAdaptor : calls
    AgentAdaptor --> AgentStreamEvent : produces
    WechatAdaptor --|> ChannelAdaptor : implements
    FeishuAdaptor --|> ChannelAdaptor : implements
    WechatLongPollingMode --|> ChannelCommunicationMode : implements
    FeishuWebSocketMode --|> ChannelCommunicationMode : implements
```

### 3.2 核心数据模型

```mermaid
classDiagram
    class ChannelType {
        <<enum>>
        WECOM
        WECHAT
        FEISHU
        DINGTALK
        HTTP
    }

    class ChannelSpec {
        +id: Long
        +name: String
        +type: ChannelType
        +agentId: Long
        +webhookUrl: String?
        +token: String?
        +appId: String?
        +appSecret: String?
        +callbackKey: String
        +communicationMode: String
    }

    class ChannelMessage {
        +messageId: String?
        +sessionId: String
        +content: String
        +role: MessageRole
        +channelType: ChannelType
        +senderId: String?
        +senderName: String?
        +messageType: MessageType
        +isGroupMessage: Boolean
        +groupId: String?
        +rawContent: Any?
        +timestamp: Long
    }

    class MessageRole {
        <<enum>>
        USER
        ASSISTANT
        SYSTEM
    }

    class MessageType {
        <<enum>>
        TEXT
        IMAGE
        FILE
        EVENT
    }

    class RichMessage {
        <<sealed>>
        TextRichMessage
        MarkdownRichMessage
        ImageRichMessage
        FileRichMessage
        CardRichMessage
    }

    class AgentContext {
        +message: ChannelMessage
        +history: List~AgentMessage~
        +channelSpec: ChannelSpec
        +metadata: Map
    }

    class AgentResponse {
        +content: String
        +richMessage: RichMessage?
        +shouldReply: Boolean
        +metadata: Map
    }

    class AgentMessage {
        +role: String
        +content: String
    }

    ChannelSpec --> ChannelType
    ChannelMessage --> MessageRole
    ChannelMessage --> MessageType
    ChannelMessage --> ChannelType
    AgentContext --> ChannelMessage
    AgentContext --> AgentMessage
    AgentContext --> ChannelSpec
    AgentResponse --> RichMessage
```

---

## 四、多渠道交互流程图

### 4.1 通用消息处理流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant Platform as IM平台
    participant Channel as ChannelAdaptor
    participant ChatService as ChannelChatService
    participant SessionMgr as ChannelSessionManager
    participant Agent as AgentAdaptor
    participant AgentImpl as ReActAgentAdaptor

    User->>Platform: 发送消息
    Platform->>Channel: 推送/轮询获取消息
    Channel->>Channel: parseMessage() → ChannelMessage

    Channel->>ChatService: chat(message, channel, agent, adaptor)

    ChatService->>SessionMgr: addMessage() 保存用户消息
    ChatService->>SessionMgr: getHistory() 获取会话历史
    ChatService->>SessionMgr: toAgentMessages() 转换格式
    ChatService->>ChatService: 构建 AgentContext

    ChatService->>Agent: onBeforeProcess(context) 前置钩子

    alt 流式输出 (supportsStreamingOutput && supportsStreaming)
        ChatService->>Channel: sendTypingIndicator() [Thinking时]
        ChatService->>Agent: streamProcess(context) → Flow<AgentStreamEvent>
        loop 每个流式事件
            AgentImpl-->>ChatService: TextStreamEvent
            ChatService->>Channel: sendStreamingFragment(fragment, isLast)
            AgentImpl-->>ChatService: ThinkingStreamEvent
            ChatService->>Channel: sendTypingIndicator()
        end
        AgentImpl-->>ChatService: EndStreamEvent
    else 批量输出 (默认模式)
        ChatService->>Channel: sendTypingIndicator() 显示输入中
        ChatService->>Agent: streamProcess(context) → Flow<AgentStreamEvent>
        loop 缓冲所有文本片段
            AgentImpl-->>ChatService: TextStreamEvent
            ChatService->>ChatService: fullContent.append(content)
        end
        AgentImpl-->>ChatService: EndStreamEvent
        ChatService->>Channel: sendMessage(sessionId, fullContent)
    end

    ChatService->>SessionMgr: addMessage() 保存AI回复
    ChatService->>Agent: onAfterProcess(context, response) 后置钩子
    Channel->>Platform: 发送回复
    Platform->>User: 收到AI回复
```

### 4.2 微信渠道（长轮询模式）交互流程

```mermaid
sequenceDiagram
    participant User as 微信用户
    participant ILink as 微信 iLink Bot
    participant BotService as WechatBotService
    participant LPMode as WechatLongPollingMode
    participant Adaptor as WechatAdaptor
    participant ChatSvc as ChannelChatService
    participant Agent as AgentAdaptor

    Note over LPMode: 启动阶段
    LPMode->>BotService: getOrCreateClient() 创建ILinkClient
    LPMode->>BotService: executeLogin() 获取二维码
    LPMode-->>ILink: 显示二维码内容
    User->>ILink: 扫码登录
    ILink-->>BotService: 登录成功

    Note over LPMode: 轮询阶段
    loop 消息轮询循环
        LPMode->>BotService: startPolling() → getUpdates()
        ILink-->>BotService: 返回 WeixinMessage 列表
        BotService-->>LPMode: 消息回调
        LPMode->>LPMode: WechatMessageConverter.toChannelMessage()
        LPMode->>ChatSvc: chat(message, channel, agent, adaptor)

        ChatSvc->>Adaptor: sendTypingIndicator()
        Adaptor->>LPMode: sendTypingIndicator()
        LPMode->>BotService: sendTextWithTyping(empty, 2000ms)

        ChatSvc->>Agent: streamProcess(context)
        Agent-->>ChatSvc: TextStreamEvent 片段
        Agent-->>ChatSvc: EndStreamEvent 完成

        ChatSvc->>Adaptor: sendMessage(sessionId, fullText)
        Adaptor->>LPMode: sendMessage()
        LPMode->>BotService: sendTextWithTyping(text, typingMillis)
        BotService->>ILink: 发送消息
        ILink->>User: 收到AI回复
    end
```

### 4.3 飞书渠道（WebSocket 模式）交互流程

```mermaid
sequenceDiagram
    participant User as 飞书用户
    participant FeishuAPI as 飞书 Open API
    participant WSMode as FeishuWebSocketMode
    participant Adaptor as FeishuAdaptor
    participant ChatSvc as ChannelChatService
    participant Agent as AgentAdaptor
    participant PHC as PlatformHttpClient

    Note over WSMode: 启动阶段
    WSMode->>FeishuAPI: 建立 WebSocket 连接 (appId + appSecret)
    FeishuAPI-->>WSMode: 连接成功，开始监听事件

    Note over WSMode: 消息接收阶段
    User->>FeishuAPI: 发送消息给机器人
    FeishuAPI-->>WSMode: im.message.receive_v1 事件
    WSMode->>WSMode: 解析事件 → ChannelMessage

    WSMode->>ChatSvc: chat(message, channel, agent, adaptor)

    Note over ChatSvc: 飞书不支持流式输出，使用批量模式
    ChatSvc->>Agent: streamProcess(context)
    Agent-->>ChatSvc: TextStreamEvent 片段 (缓冲)
    Agent-->>ChatSvc: EndStreamEvent 完成

    ChatSvc->>Adaptor: sendMessage(sessionId, fullText)
    Adaptor->>WSMode: sendMessage()

    WSMode->>WSMode: getTenantAccessToken(appId, appSecret)
    WSMode->>PHC: POST /open-apis/im/v1/messages
    PHC->>FeishuAPI: 发送消息 (receive_id + content)
    FeishuAPI->>User: 收到AI回复
```

### 4.4 飞书渠道（Webhook 模式）交互流程

```mermaid
sequenceDiagram
    participant User as 飞书用户
    participant FeishuAPI as 飞书 Open API
    participant Controller as HTTP Controller
    participant Adaptor as FeishuAdaptor
    participant ChatSvc as ChannelChatService
    participant Agent as AgentAdaptor
    participant PHC as PlatformHttpClient

    User->>FeishuAPI: 发送消息给机器人
    FeishuAPI->>Controller: HTTP POST 回调 (事件推送)

    alt URL验证请求 (首次配置)
        Controller->>Adaptor: handleUrlVerification(request, channel)
        Adaptor-->>Controller: {challenge: xxx}
        Controller-->>FeishuAPI: 返回验证响应
    else 消息回调
        Controller->>Adaptor: verifySignature(request, channel) 验证签名
        Controller->>Adaptor: parseMessage(request) → ChannelMessage
        Controller->>ChatSvc: chat(message, channel, agent, adaptor)

        ChatSvc->>Agent: streamProcess(context)
        Agent-->>ChatSvc: 文本片段 (缓冲)

        ChatSvc->>Adaptor: sendMessage(sessionId, fullText)
        Adaptor->>PHC: POST webhookUrl (消息JSON)
        PHC->>FeishuAPI: 发送消息
        FeishuAPI->>User: 收到AI回复
    end
```

---

## 五、流式输出策略决策图

```mermaid
flowchart TD
    A[ChannelChatService.chat 收到消息] --> B{判断输出策略}

    B -->|supportsStreamingOutput=true<br/>AND supportsStreaming=true| C[流式输出模式 streamAndSend]
    B -->|supportsStreamingOutput=false<br/>OR supportsStreaming=false| D[批量输出模式 batchSend]

    C --> C1[调用 AgentAdaptor.streamProcess]
    C1 --> C2[收到 TextStreamEvent]
    C2 --> C3[立即调用 sendStreamingFragment<br/>逐片段发送给用户]
    C3 --> C4[收到 ThinkingStreamEvent]
    C4 --> C5[调用 sendTypingIndicator<br/>显示思考中]
    C5 --> C6[收到 EndStreamEvent]
    C6 --> C7[保存完整回复到会话<br/>调用 onAfterProcess]

    D --> D1[调用 sendTypingIndicator<br/>显示输入中]
    D1 --> D2[调用 AgentAdaptor.streamProcess]
    D2 --> D3[收到 TextStreamEvent]
    D3 --> D4[缓冲到 StringBuilder<br/>不立即发送]
    D4 --> D5[收到 EndStreamEvent]
    D5 --> D6[合并所有文本<br/>调用 sendMessage 一次性发送]
    D6 --> D7[保存完整回复到会话<br/>调用 onAfterProcess]
```

---

## 六、各渠道通信模式对比

```mermaid
flowchart LR
    subgraph 微信["微信 iLink (长轮询)"]
        direction TB
        WX1[ILinkClient 创建] --> WX2[QR码登录]
        WX2 --> WX3[getUpdates 长轮询]
        WX3 --> WX4[sendTextWithTyping 发送]
    end

    subgraph 飞书WS["飞书 WebSocket"]
        direction TB
        FS1[WebSocket 连接<br/>appId+appSecret] --> FS2[监听事件推送]
        FS2 --> FS3[im.message.receive_v1]
        FS3 --> FS4[POST /im/v1/messages<br/>发送回复]
    end

    subgraph 飞书WH["飞书 Webhook"]
        direction TB
        FW1[HTTP回调接收] --> FW2[签名验证]
        FW2 --> FW3[消息解析]
        FW3 --> FW4[POST webhookUrl<br/>发送回复]
    end

    subgraph HTTP_SSE["HTTP SSE (流式)"]
        direction TB
        HS1[HTTP请求接收] --> HS2[SSE事件流输出]
        HS2 --> HS3[逐片段推送]
    end
```

### 通信模式特性对比表

| 特性 | 微信长轮询 | 飞书 WebSocket | 飞书 Webhook | HTTP SSE |
|------|-----------|--------------|-------------|----------|
| 是否需要公网IP | ❌ 不需要 | ❌ 不需要 | ✅ 需要 | ✅ 需要 |
| 是否需要域名 | ❌ 不需要 | ❌ 不需要 | ✅ 需要 | ✅ 需要 |
| 是否需要内网穿透 | ❌ 不需要 | ❌ 不需要 | ✅ 开发时需要 | ✅ 开发时需要 |
| 签名验证方式 | ❌ 无需 | ❌ SDK自动 | ✅ HmacSHA256 | 自定义 |
| 消息获取方式 | 主动轮询 | 被动推送 | 被动回调 | 主动请求 |
| 消息发送方式 | ILinkClient | Open API | Webhook URL | SSE响应 |
| 流式输出支持 | ❌ | ❌ | ❌ | ✅ |
| 输入指示支持 | ✅ typing | ❌ | ❌ | — |
| 适用场景 | 本地开发/内网 | 本地开发/内网 | 生产环境 | Web前端 |

---

## 七、startChannelWithAgent 便捷接入流程

```mermaid
flowchart TD
    A[开发者调用<br/>adaptor.startChannelWithAgent<br/>channel, agent, sessionManager] --> B[内部创建 ChannelChatService]
    B --> C[调用 startChannel 启动通信模式]
    C --> D[通信模式开始接收消息]

    D --> E{收到用户消息}
    E --> F[ChatService.chat 自动编排]
    F --> F1[保存用户消息到会话]
    F1 --> F2[获取会话历史]
    F2 --> F3[构建 AgentContext]
    F3 --> F4[调用 AgentAdaptor 处理]
    F4 --> F5{判断输出策略}
    F5 -->|流式| F6[逐片段发送]
    F5 -->|批量| F7[缓冲+typing+合并发送]
    F6 --> F8[保存AI回复到会话]
    F7 --> F8
    F8 --> G[等待下一条消息]

    style A fill:#e1f5fe
    style F fill:#fff3e0
    style F5 fill:#fce4ec
```

---

## 八、模块依赖关系

```mermaid
graph TB
    subgraph Maven依赖
        SDK[harnax-channel-sdk<br/>纯Kotlin, 无框架依赖]
        WECHAT[harnax-channel-wechat<br/>依赖: SDK + wechat-ilink-sdk + OkHttp 4.12]
        FEISHU[harnax-channel-feishu<br/>依赖: SDK + oapi-sdk + Spring WebFlux]
        ADMIN[harnax-admin<br/>依赖: SDK + agent-core + kotlinx-coroutines-reactor]
    end

    WECHAT --> SDK
    FEISHU --> SDK
    ADMIN --> SDK
    ADMIN --> AGENT[harnax-agent-core]

    subgraph 外部依赖
        ILINK[wechat-ilink-sdk 2.3.3]
        OKHTTP[OkHttp 4.12.0]
        OAPI[oapi-sdk 2.4.0]
        WEBFLUX[spring-boot-starter-webflux]
        COROUTINES[kotlinx-coroutines-reactor]
    end

    WECHAT --> ILINK
    WECHAT --> OKHTTP
    FEISHU --> OAPI
    FEISHU --> WEBFLUX
    ADMIN --> COROUTINES
```

---

## 九、文件清单

### harnax-channel-sdk（抽象层）

| 包路径 | 文件 | 说明 |
|--------|------|------|
| `sdk/adaptor` | `ChannelAdaptor.kt` | 渠道适配器接口，定义统一行为契约 |
| `sdk/adaptor` | `AgentAdaptor.kt` | Agent处理器抽象类，含 process/streamProcess |
| `sdk/adaptor` | `AgentStreamEvent.kt` | 流式事件密封类（Text/Thinking/End/Error） |
| `sdk/adaptor` | `ChannelCommunicationMode.kt` | 通信模式接口（Webhook/WebSocket/长轮询） |
| `sdk/config` | `ChannelSpec.kt` | 渠道配置规格，Builder模式 |
| `sdk/config` | `ChannelType.kt` | 渠道类型枚举（WECOM/WECHAT/FEISHU/DINGTALK/HTTP） |
| `sdk/message` | `ChannelMessage.kt` | 统一消息模型，Builder模式 |
| `sdk/message` | `ChannelRequest.kt` | 平台无关请求对象（替代HttpServletRequest） |
| `sdk/message` | `RichMessage.kt` | 富消息密封类（Text/Markdown/Image/File/Card） |
| `sdk/message` | `AgentMessage.kt` | Agent消息模型（role + content） |
| `sdk/message` | `SendResult.kt` | 发送结果（Success/Failure） |
| `sdk/error` | `ChannelException.kt` | 渠道异常（SendException/NotFoundException/SignatureException） |
| `sdk/session` | `ChannelSessionManager.kt` | 会话管理接口 |
| `sdk/service` | `ChannelChatService.kt` | 核心编排服务（流式/批量策略） |

### harnax-channel-wechat（微信实现）

| 文件 | 说明 |
|------|------|
| `WechatAdaptor.kt` | ChannelAdaptor实现，含 startChannelWithAgent 便捷方法 |
| `WechatBotService.kt` | ILinkClient生命周期管理（创建/登录/轮询/发送/关闭） |
| `WechatLongPollingMode.kt` | 长轮询通信模式，含 sendTypingIndicator |
| `WechatMessageConverter.kt` | WeixinMessage → ChannelMessage 转换器 |

### harnax-channel-feishu（飞书实现）

| 文件 | 说明 |
|------|------|
| `FeishuAdaptor.kt` | ChannelAdaptor实现，含 WebSocket/Webhook 双模式 + startChannelWithAgent |
| `FeishuWebSocketMode.kt` | WebSocket长连接模式，含消息发送与事件处理 |
| `FeishuMessageBuilder.kt` | 飞书消息JSON构建器 |
| `client/PlatformHttpClient.kt` | 基于WebFlux的HTTP客户端 |
| `client/PlatformResponse.kt` | HTTP响应封装 |

### harnax-admin（业务层）

| 文件 | 说明 |
|------|------|
| `adaptor/ReActAgentAdaptor.kt` | AgentAdaptor具体实现，桥接ChatEvent → AgentStreamEvent |