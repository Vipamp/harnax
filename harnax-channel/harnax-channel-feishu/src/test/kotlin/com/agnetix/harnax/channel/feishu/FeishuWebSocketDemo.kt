package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import kotlinx.coroutines.runBlocking
import java.util.Scanner

/**
 * 飞书 WebSocket 长连接模式演示
 *
 * 功能：
 * 1. 演示如何配置飞书 WebSocket 模式
 * 2. 说明 WebSocket 模式的使用场景和优势
 * 3. 提供完整的配置示例和使用指南
 *
 * 使用方法：
 * 1. 在飞书开放平台创建企业自建应用
 * 2. 获取 App ID 和 App Secret
 * 3. 运行此 Demo，输入凭证即可查看配置示例
 *
 * 优势：
 * - 无需公网 IP 或域名
 * - 无需内网穿透工具
 * - 内置加密和鉴权
 * - 适用于本地开发和内网部署
 */
object FeishuWebSocketDemo {

    private val scanner = Scanner(System.`in`)

    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(70))
        println("飞书 WebSocket 长连接模式演示")
        println("=".repeat(70))

        println(
            """
            
            【使用说明】
            1. 登录飞书开放平台: https://open.feishu.cn
            2. 创建企业自建应用
            3. 获取应用的 App ID 和 App Secret
            4. 在应用中启用机器人功能
            5. 添加事件订阅：im.message.receive_v1
            6. 运行此演示进行验证测试
            
            【WebSocket 模式优势】
            - 无需公网 IP 或域名
            - 无需内网穿透（如 ngrok）
            - 内置加密传输，无需处理签名
            - 适用于本地开发环境和内网部署
            
            """.trimIndent(),
        )

        // 获取用户输入
        println("\n【步骤 1】请输入飞书应用配置")
        println("-".repeat(70))

        print("请输入 App ID (例如: cli_xxxxxxxxxxxxx): ")
        val appId = scanner.nextLine().trim()

        print("请输入 App Secret: ")
        val appSecret = scanner.nextLine().trim()

        if (appId.isBlank() || appSecret.isBlank()) {
            println("\n❌ 错误: App ID 和 App Secret 不能为空")
            return
        }

        println("\n✅ 配置信息已接收")
        println("  - App ID: ${maskSecret(appId, 4, 4)}")
        println("  - App Secret: ${maskSecret(appSecret)}")

        // 创建 Channel 配置
        println("\n【步骤 2】创建飞书 Channel 配置 (WebSocket 模式)")
        println("-".repeat(70))

        val channel = ChannelSpec.builder()
            .id(1L)
            .name("飞书 WebSocket 演示")
            .type(ChannelType.FEISHU)
            .agentId(1L)
            .callbackKey("feishu-ws-demo")
            .appId(appId)
            .appSecret(appSecret)
            .communicationMode("websocket")
            .build()

        println("✅ Channel 配置创建成功:")
        println("  - 名称: ${channel.name}")
        println("  - 类型: ${channel.type.displayName}")
        println("  - 通信模式: ${channel.communicationMode}")
        println("  - App ID: ${maskSecret(channel.appId ?: "", 4, 4)}")
        println("  - Agent ID: ${channel.agentId}")

        // 启动 WebSocket 连接
        println("\n【步骤 3】启动 WebSocket 连接")
        println("-".repeat(70))

        val adaptor = FeishuAdaptor()
        adaptor.startChannel(channel) { message ->
            // 处理接收到的消息
            println("收到消息: ${message.content}")

            // 调用 AI 模型生成回复
            val reply = "Hello: ${message.content}"
            // 发送回复
            runBlocking {
                adaptor.sendMessage(channel, message.sessionId, reply)
            }
        }

        println("✅ WebSocket 连接已启动")

        // 阻塞主线程保持运行
        println("\n输入 'quit' 退出...")
        while (true) {
            val input = scanner.nextLine()
            if (input.lowercase() == "quit") {
                adaptor.stopChannel(channel)
                println("✅ WebSocket 连接已关闭")
                break
            }
        }

        // 显示配置说明
        println("\n【步骤 4】WebSocket 模式配置说明")
        println("-".repeat(70))

        println(
            """
            📋 WebSocket 模式配置清单：
            
            ✅ 已完成：
            1. 创建 ChannelSpec 并设置 communicationMode="websocket"
            2. 配置 appId 和 appSecret（必需）
            3. 设置 callbackKey 用于标识通道
            
            📝 后续需要在飞书开放平台完成：
            1. 启用机器人功能
               路径：应用详情 -> 机器人 -> 启用机器人
            
            2. 添加事件订阅
               路径：应用详情 -> 事件订阅 -> 添加事件
               事件类型：im.message.receive_v1（接收消息 v2.0）
            
            3. 发布应用版本
               路径：应用版本管理与发布 -> 创建版本 -> 申请发布
            
            4. 将机器人添加到群聊
               在群聊设置中添加应用机器人
            
            """.trimIndent(),
        )

        // 显示代码示例
        println("\n【步骤 5】代码使用示例")
        println("-".repeat(70))

        println(
            """
            💻 Kotlin 代码示例：
            
            // 1. 创建飞书适配器
            val adaptor = FeishuAdaptor()
            
            // 2. 创建 Channel 配置
            val channel = ChannelSpec.builder()
                .id(1L)
                .name("飞书机器人")
                .type(ChannelType.FEISHU)
                .agentId(1L)
                .appId("your_app_id")
                .appSecret("your_app_secret")
                .communicationMode("websocket")  // ← 关键配置
                .callbackKey("feishu-bot")
                .build()
            
            // 3. 启动 WebSocket 连接
            adaptor.startChannel(channel) { message ->
                // 4. 处理接收到的消息
                println("收到消息: ${'$'}{message.content}")
                
                // 5. 调用 AI 模型生成回复
                val reply = callAIModel(message.content)
                
                // 6. 发送回复
                runBlocking {
                    adaptor.sendMessage(channel, message.sessionId, reply)
                }
            }
            
            """.trimIndent(),
        )

        // 显示 Webhook vs WebSocket 对比
        println("\n【步骤 6】Webhook vs WebSocket 对比")
        println("-".repeat(70))

        println(
            """
            📊 两种通信模式对比：
            
            ┌──────────────────┬──────────────────┬──────────────────┐
            │ 特性             │ Webhook 模式     │ WebSocket 模式   │
            ├──────────────────┼──────────────────┼──────────────────┤
            │ 公网 IP          │ ✅ 需要          │ ❌ 不需要        │
            │ 域名             │ ✅ 需要          │ ❌ 不需要        │
            │ 内网穿透         │ ✅ 需要 (开发)   │ ❌ 不需要        │
            │ 签名验证         │ ✅ 需要处理      │ ❌ SDK 自动处理  │
            │ 消息延迟         │ 低               │ 低               │
            │ 适用场景         │ 生产环境         │ 开发/内网环境    │
            │ 配置复杂度       │ 中等             │ 简单             │
            │ 网络要求         │ 可接收外部请求   │ 可访问公网       │
            └──────────────────┴──────────────────┴──────────────────┘
            
            💡 推荐：
            - 本地开发：使用 WebSocket 模式
            - 内网部署：使用 WebSocket 模式
            - 公网生产：使用 Webhook 模式（更稳定）
            
            """.trimIndent(),
        )

        // 完成
        println("\n" + "=".repeat(70))
        println("飞书 WebSocket 演示完成！")
        println("=".repeat(70))
    }

    /**
     * 隐藏敏感信息
     */
    private fun maskSecret(
        secret: String,
        showStart: Int = 4,
        showEnd: Int = 0,
    ): String {
        if (secret.length <= showStart + showEnd) {
            return "*".repeat(secret.length)
        }
        val start = secret.take(showStart)
        val end = if (showEnd > 0) secret.takeLast(showEnd) else ""
        val middle = "*".repeat(secret.length - showStart - showEnd)
        return "$start$middle$end"
    }
}
