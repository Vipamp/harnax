# 飞书机器人验证演示

## 快速开始

### 1. 准备工作

1. 登录 [飞书开放平台](https://open.feishu.cn)
2. 创建企业自建应用（或选择已有应用）
3. 进入应用详情，获取：
   - **App ID**（格式：`cli_xxxxxxxxxxxxx`）
   - **App Secret**（应用密钥）
4. 在应用中启用机器人功能
5. 获取 Webhook URL（格式：`https://open.feishu.cn/open-apis/bot/v2/hook/xxx`）

### 2. 运行演示

```bash
# 在项目根目录执行
cd /Users/heqingsong/code/my_project/vipclaw
mvn compile -pl vipclaw-channel -am

# 运行演示（需要在 IDE 中运行）
# 打开文件: vipclaw-channel/src/main/kotlin/com/vipamp/vipclaw/channel/demo/FeishuBotDemo.kt
# 右键点击 main 方法 -> Run 'FeishuBotDemoKt'
```

### 3. 按提示输入

运行后会提示输入：

```
【步骤 1】请输入飞书应用配置
----------------------------------------------------------------------
请输入 App ID (例如: cli_xxxxxxxxxxxxx): cli_xxxxxxxxxxxxx
请输入 App Secret: your_app_secret_here
请输入 Webhook URL (可选，直接回车跳过): https://open.feishu.cn/open-apis/bot/v2/hook/xxx
```

### 4. 演示内容

程序会依次演示：

1. **创建 Channel 配置** - 验证配置格式
2. **签名验证示例** - 展示 HMAC-SHA256 签名机制
3. **获取 Token** - 获取 tenant_access_token 的方法
4. **发送消息测试** - 生成 curl 命令用于测试
5. **富消息示例** - 展示文本、Markdown、卡片消息的 JSON 格式

## 演示功能说明

### 功能 1: 配置验证

输入 App ID 和 App Secret 后，程序会：
- 验证格式是否正确
- 创建 ChannelSpec 配置对象
- 显示配置信息（敏感信息会隐藏）

### 功能 2: 签名验证

展示飞书的签名验证机制：

```kotlin
// 签名内容 = timestamp + nonce + appSecret + body
val contentToSign = timestamp + nonce + appSecret + body
val signature = hmacSha256(appSecret, contentToSign)
```

### 功能 3: Token 获取

提供获取 `tenant_access_token` 的 curl 命令：

```bash
curl -X POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal \
  -H "Content-Type: application/json" \
  -d '{"app_id":"cli_xxx","app_secret":"xxx"}'
```

### 功能 4: 消息发送测试

生成三种消息类型的 curl 测试命令：

#### 纯文本消息
```bash
curl -X POST 'YOUR_WEBHOOK_URL' \
  -H "Content-Type: application/json" \
  -d '{"msg_type":"text","content":{"text":"🤖 这是来自 VIPClaw Channel 的测试消息！"}}'
```

#### 富文本消息 (Post)
支持标题、链接、@用户等

#### 交互式卡片消息
支持按钮、Markdown 内容等

### 功能 5: 富消息构建

使用 VIPClaw Channel 的富消息抽象：

```kotlin
// 纯文本
val textMessage = TextRichMessage("这是一条纯文本消息")

// Markdown
val markdownMessage = MarkdownRichMessage("""
    # 标题
    **加粗文本** 和 *斜体文本*
    - 列表项 1
""")

// 自动转换为飞书格式
val json = FeishuMessageBuilder.buildFromRichMessage(textMessage)
```

## 常见问题

### Q1: 在哪里获取 App ID 和 App Secret？

1. 登录 [飞书开放平台](https://open.feishu.cn)
2. 点击你的应用
3. 在"凭证与基础信息"页面查看

### Q2: 如何获取 Webhook URL？

1. 进入应用 -> 机器人
2. 启用机器人
3. 复制显示的 Webhook 地址

### Q3: Token 获取失败怎么办？

- 确认 App ID 和 App Secret 正确
- 确认应用已发布
- 检查应用权限

### Q4: 消息发送失败？

- 确认机器人已添加到群聊
- 确认 Webhook URL 正确
- 检查网络连接

## 下一步

验证成功后，你可以：

1. 在 Spring Boot 项目中集成 ChannelService
2. 配置事件订阅 URL 接收消息
3. 使用 `ChannelService.sendTextMessage()` 发送消息
4. 使用 `ChannelService.sendRichMessage()` 发送富消息

## 相关文档

- [飞书开放平台文档](https://open.feishu.cn/document)
- [获取 tenant_access_token](https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal)
- [发送消息 API](https://open.feishu.cn/document/server-docs/im-v1/message/create)
- [事件订阅指南](https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription-configure-)
