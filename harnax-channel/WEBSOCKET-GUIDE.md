# 飞书 WebSocket 模式使用指南

## 概述

飞书 WebSocket 模式基于飞书官方 Java SDK (oapi-sdk 2.4.0) 实现，支持 WebSocket 长连接通信。

### 特点

- ✅ **无需公网 IP** - 只需能访问公网即可
- ✅ **无需域名** - 不需要配置回调 URL
- ✅ **内置加密** - SDK 自动处理加密和鉴权
- ✅ **自动重连** - 支持断线自动重连
- ✅ **适用于内网** - 适合本地开发和企业私有化部署

### 注意事项

- ⚠️ WebSocket 仅用于**接收消息**
- ⚠️ **发送消息**仍需调用飞书 Open API
- ⚠️ 同一应用多个客户端只有一个会收到消息（集群模式）

## 快速开始

### 1. 创建 Channel 配置

```kotlin
val channel = ChannelSpec.builder()
    .id(1L)
    .name("飞书机器人")
    .type(ChannelType.FEISHU)
    .agentId(1L)
    .appId("your_app_id")           // 飞书应用的 App ID
    .appSecret("your_app_secret")   // 飞书应用的 App Secret
    .communicationMode("websocket") // ← 关键配置：使用 WebSocket 模式
    .callbackKey("feishu-bot")
    .build()
```

### 2. 启动 WebSocket 连接

```kotlin
val adaptor = FeishuAdaptor()

// 启动 WebSocket 连接
adaptor.startChannel(channel) { message ->
    // 处理接收到的消息
    println("收到消息: ${message.content}")
    
    // 调用 AI 模型生成回复
    val reply = callAIModel(message.content)
    
    // 发送回复
    runBlocking {
        adaptor.sendMessage(channel, message.sessionId, reply)
    }
}

println("✅ WebSocket 连接已启动")
```

### 3. Spring Boot 集成示例

```kotlin
@Service
class FeishuBotService(
    private val channelService: ChannelService
) {
    
    @PostConstruct
    fun startWebSocket() {
        // 启动所有 WebSocket 模式的 Channel
        val channels = channelRepository.findAll()
            .filter { it.communicationMode == "websocket" }
        
        channels.forEach { channel ->
            channelService.startChannel(channel.id) { message ->
                processMessage(channel, message)
            }
        }
    }
    
    private suspend fun processMessage(
        channel: ChannelSpec,
        message: ChannelMessage
    ) {
        // 业务逻辑：调用 AI 生成回复
        val reply = aiService.generateReply(message.content)
        
        // 发送回复
        channelService.sendTextMessage(
            channel.id,
            message.sessionId,
            reply
        )
    }
}
```

## 完整工作流程

```
1️⃣ 应用启动
   └─> 读取数据库中的 Channel 配置
   └─> 筛选 communicationMode="websocket" 的通道

2️⃣ 建立连接
   └─> 调用 FeishuAdaptor.startChannel()
   └─> 创建飞书 SDK 的 WebSocket 客户端
   └─> 建立与飞书服务器的长连接

3️⃣ 接收消息
   └─> 飞书推送消息到 WebSocket 连接
   └─> SDK 触发事件回调
   └─> 转换为 ChannelMessage
   └─> 调用消息处理函数

4️⃣ 处理消息
   └─> 保存消息到会话历史
   └─> 调用 AI 模型生成回复
   └─> 保存回复到会话历史

5️⃣ 发送回复
   └─> 获取 tenant_access_token
   └─> 调用飞书 Open API 发送消息
   └─> POST /open-apis/im/v1/messages

6️⃣ 应用关闭
   └─> 调用 FeishuAdaptor.stopChannel()
   └─> 关闭 WebSocket 连接
   └─> 清理资源
```

## Webhook vs WebSocket 对比

| 特性 | Webhook 模式 | WebSocket 模式 |
|------|-------------|---------------|
| 公网 IP | ✅ 需要 | ❌ 不需要 |
| 域名 | ✅ 需要 | ❌ 不需要 |
| 内网穿透 | ✅ 需要 (开发) | ❌ 不需要 |
| 签名验证 | ✅ 需要处理 | ❌ SDK 自动处理 |
| 消息延迟 | 低 | 低 |
| 适用场景 | 生产环境 | 开发/内网环境 |
| 配置复杂度 | 中等 | 简单 |
| 网络要求 | 可接收外部请求 | 可访问公网 |

### 推荐方案

- **本地开发**：使用 WebSocket 模式
- **内网部署**：使用 WebSocket 模式
- **公网生产**：使用 Webhook 模式（更稳定）

## 飞书开放平台配置

### 1. 创建企业自建应用

1. 登录 [飞书开放平台](https://open.feishu.cn)
2. 创建企业自建应用
3. 获取 App ID 和 App Secret

### 2. 启用机器人功能

```
应用详情 -> 机器人 -> 启用机器人
```

### 3. 添加事件订阅

```
应用详情 -> 事件订阅 -> 添加事件
事件类型：im.message.receive_v1（接收消息 v2.0）
```

### 4. 发布应用

```
应用版本管理与发布 -> 创建版本 -> 申请发布
```

### 5. 添加机器人到群聊

在群聊设置中添加应用机器人

## API 权限要求

发送消息需要以下权限：

- `im:message` - 获取与发送单聊、群组消息
- `im:message:send_as_bot` - 以应用的身份发消息

## 常见问题

### Q1: NoSuchMethodError: makeExtensionsImmutable()

**错误信息：**
```
Exception in thread "pool-1-thread-4" java.lang.NoSuchMethodError: 
'void com.lark.oapi.ws.pb.Pbbp2$Frame.makeExtensionsImmutable()'
```

**原因：**
protobuf 版本冲突。飞书 SDK 需要 `protobuf-java 3.22.2`，但项目可能使用了更高版本（如 4.x）。

**解决方法：**
在 `harnax-channel/pom.xml` 中固定 protobuf 版本：

```xml
<!-- 固定 protobuf 版本以兼容飞书 SDK -->
<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
    <version>3.22.2</version>
</dependency>
```

### Q2: WebSocket 连接失败

**可能原因：**
- App ID 或 App Secret 错误
- 应用未启用机器人功能
- 未添加事件订阅
- 网络连接问题

**解决方法：**
1. 检查 App ID 和 App Secret 是否正确
2. 确认应用已启用机器人功能
3. 确认已添加事件订阅
4. 检查网络连接

### Q3: 收不到消息

**可能原因：**
- 事件订阅未配置
- 应用未发布
- 机器人未添加到群聊

**解决方法：**
1. 确认已订阅 `im.message.receive_v1` 事件
2. 确认应用已发布且处于可用状态
3. 将机器人添加到需要接收消息的群聊

### Q4: 发送消息失败

**可能原因：**
- tenant_access_token 获取失败
- 缺少 API 权限
- 消息格式错误

**解决方法：**
1. 检查 appId 和 appSecret 是否正确
2. 确认应用具有发送消息的权限
3. 检查消息格式是否符合飞书 API 要求

## 技术细节

### WebSocket 客户端

```kotlin
val wsClient = WsClient.Builder(appId, appSecret)
    .eventHandler(eventDispatcher)
    .autoReconnect(true)
    .build()
```

### 事件处理器

```kotlin
val eventDispatcher = EventDispatcher.newBuilder("", "")
    .onP2MessageReceiveV1(handler)
    .build()
```

### 发送消息

```kotlin
// 获取 token
val token = getTenantAccessToken(appId, appSecret)

// 构建消息
val messageBody = FeishuMessageBuilder.buildText(message)

// 发送消息
val response = httpClient.postJson(
    "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id",
    messageBody,
    mapOf("Authorization" to "Bearer $token")
)
```

## 相关文档

- [飞书开放平台](https://open.feishu.cn)
- [WebSocket 长连接文档](https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription-configure-/request-url-configuration-case)
- [事件订阅列表](https://open.feishu.cn/document/ukTMukTMukTM/uYDNxYjL2QTM24iN0EjN/event-list)
- [飞书 Java SDK](https://github.com/larksuite/oapi-sdk-java)
