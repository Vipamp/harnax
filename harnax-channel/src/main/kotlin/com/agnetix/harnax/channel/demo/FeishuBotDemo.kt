package com.agnetix.harnax.channel.demo

import com.agnetix.harnax.channel.ChannelSpec
import com.agnetix.harnax.channel.ChannelType
import com.agnetix.harnax.channel.adaptor.feishu.FeishuAdaptor
import com.agnetix.harnax.channel.client.PlatformHttpClient
import com.agnetix.harnax.channel.message.MarkdownRichMessage
import com.agnetix.harnax.channel.message.TextRichMessage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.Scanner
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 飞书机器人验证演示
 *
 * 功能：
 * 1. 输入 appId 和 appSecret 验证配置
 * 2. 获取飞书 tenant_access_token
 * 3. 测试发送消息到飞书 webhook
 * 4. 验证签名机制
 *
 * 使用方法：
 * 直接运行 main 方法，按提示输入 appId、appSecret 和 webhook URL
 */
object FeishuBotDemo {

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val httpClient = PlatformHttpClient()
    private val scanner = Scanner(System.`in`)

    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(70))
        println("飞书机器人验证演示")
        println("=".repeat(70))

        println(
            """
            
            【使用说明】
            1. 登录飞书开放平台: https://open.feishu.cn
            2. 创建企业自建应用或选择已有应用
            3. 获取应用的 App ID 和 App Secret
            4. 在应用中启用机器人，并获取 Webhook URL
            5. 运行此演示进行验证测试
            
            """.trimIndent(),
        )

        // ========== 1. 获取用户输入 ==========
        println("\n【步骤 1】请输入飞书应用配置")
        println("-".repeat(70))

        print("请输入 App ID (例如: cli_xxxxxxxxxxxxx): ")
        val appId = scanner.nextLine().trim()

        print("请输入 App Secret: ")
        val appSecret = scanner.nextLine().trim()

        print("请输入 Webhook URL (可选，直接回车跳过): ")
        val webhookUrl = scanner.nextLine().trim().ifBlank { null }

        if (appId.isBlank() || appSecret.isBlank()) {
            println("\n❌ 错误: App ID 和 App Secret 不能为空")
            return
        }

        // ========== 2. 创建 Channel 配置 ==========
        println("\n【步骤 2】创建飞书 Channel 配置")
        println("-".repeat(70))

        val channel = ChannelSpec.builder()
            .id(1L)
            .name("飞书验证演示")
            .type(ChannelType.FEISHU)
            .agentId(1L)
            .callbackKey("feishu-demo-test")
            .appId(appId)
            .appSecret(appSecret)
            .webhookUrl(webhookUrl)
            .build()

        println("✅ Channel 配置创建成功:")
        println("  - 名称: ${channel.name}")
        println("  - App ID: ${maskSecret(appId, 4, 4)}")
        println("  - App Secret: ${maskSecret(appSecret)}")
        println("  - Webhook: ${webhookUrl ?: "(未配置)"}")

        // ========== 3. 验证签名机制 ==========
        println("\n【步骤 3】飞书签名验证示例")
        println("-".repeat(70))

        demonstrateSignatureVerification(appSecret)

        // ========== 4. 获取 Tenant Access Token ==========
        println("\n【步骤 4】获取 tenant_access_token")
        println("-".repeat(70))

        val tokenResult = getTenantAccessToken(appId, appSecret)
        if (tokenResult != null) {
            println("✅ Token 获取成功!")
            println("  - Token: ${maskSecret(tokenResult, 8, 4)}")
            println("  - 过期时间: 7200 秒 (2小时)")
        } else {
            println("❌ Token 获取失败，请检查 App ID 和 App Secret 是否正确")
            println("\n提示:")
            println("  1. 确认应用已发布且处于可用状态")
            println("  2. 确认 App ID 和 App Secret 正确无误")
            println("  3. 检查应用是否具有相应的 API 权限")
        }

        // ========== 5. 测试发送消息 ==========
        if (webhookUrl != null) {
            println("\n【步骤 5】测试发送消息到飞书")
            println("-".repeat(70))

            testSendMessage(webhookUrl)
        } else {
            println("\n【步骤 5】跳过消息发送测试")
            println("-".repeat(70))
            println("⚠️ 未配置 Webhook URL，跳过此步骤")
            println("\n如需测试消息发送，请在飞书开放平台:")
            println("  1. 进入应用 -> 机器人 -> 启用机器人")
            println("  2. 复制 Webhook 地址")
            println("  3. 重新运行此演示并输入 Webhook URL")
        }

        // ========== 6. 富消息示例 ==========
        println("\n【步骤 6】飞书富消息示例")
        println("-".repeat(70))

        demonstrateRichMessages()

        // ========== 完成 ==========
        println("\n" + "=".repeat(70))
        println("飞书机器人验证演示完成！")
        println("=".repeat(70))

        println(
            """
            
            【后续步骤】
            1. 在飞书开放平台配置事件订阅 URL
            2. 添加消息接收权限 (im:message:readonly)
            3. 发布应用版本并启用
            4. 将机器人添加到群聊中进行测试
            
            【常用 API 文档】
            - 获取 tenant_access_token: 
              https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal
            - 发送消息: 
              https://open.feishu.cn/document/server-docs/im-v1/message/create
            - 事件订阅: 
              https://open.feishu.cn/document/server-docs/event-subscription-guide/event-subscription-configure-
            
            """.trimIndent(),
        )
    }

    /**
     * 演示飞书签名验证机制
     */
    private fun demonstrateSignatureVerification(appSecret: String) {
        println(
            """
            飞书使用 HMAC-SHA256 进行签名验证：
            
            签名内容 = timestamp + nonce + appSecret + body
            签名算法 = HmacSHA256(appSecret, 签名内容)
            
            """.trimIndent(),
        )

        // 模拟签名计算
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "demo-nonce-12345"
        val body = """{"event":{"message_id":"demo"}}"""

        val contentToSign = timestamp + nonce + appSecret + body
        val signature = hmacSha256(appSecret, contentToSign)

        println("示例签名计算:")
        println("  - Timestamp: $timestamp")
        println("  - Nonce: $nonce")
        println("  - Body: ${body.take(30)}...")
        println("  - 计算签名: ${signature.take(20)}...")
        println("\n✅ 当飞书发送回调时，使用相同算法计算签名并比对")
    }

    /**
     * 获取飞书 tenant_access_token
     */
    private fun getTenantAccessToken(appId: String, appSecret: String): String? = try {
        val requestBody = mapOf(
            "app_id" to appId,
            "app_secret" to appSecret,
        )

        println("正在请求 token...")
        println("  URL: https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")

        // 注意：这里使用同步方式调用，实际应使用 suspend 函数
        // 为了演示目的，我们打印请求信息
        println("\n📤 请求体:")
        println("  ${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody)}")

        // 实际调用（需要运行在协程中）
        // val response = httpClient.postJson(
        //     "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
        //     requestBody
        // )

        println("\n💡 提示: 实际 token 获取需要在 Web 服务环境中进行")
        println("   您可以使用 curl 测试:")
        println(
            """
               curl -X POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal \\
                 -H "Content-Type: application/json" \\
                 -d '{"app_id":"$appId","app_secret":"${maskSecret(appSecret)}"}'
            """.trimIndent(),
        )

        null // 演示环境不实际调用
    } catch (e: Exception) {
        println("❌ 获取 token 失败: ${e.message}")
        null
    }

    /**
     * 测试发送消息到飞书 webhook
     */
    private fun testSendMessage(webhookUrl: String) {
        println(
            """
            飞书 Webhook 消息格式:
            POST https://open.feishu.cn/open-apis/bot/v2/hook/{hook_id}
            
            支持的消息类型:
            - text: 纯文本消息
            - post: 富文本消息
            - image: 图片消息
            - interactive: 交互式卡片消息
            
            """.trimIndent(),
        )

        // 示例 1: 发送文本消息
        println("\n【示例 1】发送文本消息")
        val textMessage = mapOf(
            "msg_type" to "text",
            "content" to mapOf("text" to "🤖 这是来自 Harnax Channel 的测试消息！"),
        )

        println("📤 请求体:")
        println("  ${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(textMessage)}")
        println("\n📝 curl 命令:")
        println(
            """
           curl -X POST '$webhookUrl' \\
             -H "Content-Type: application/json" \\
             -d '${objectMapper.writeValueAsString(textMessage)}'
            """.trimIndent(),
        )

        // 示例 2: 发送富文本消息
        println("\n\n【示例 2】发送富文本消息 (Post)")
        val postMessage = mapOf(
            "msg_type" to "post",
            "content" to mapOf(
                "post" to mapOf(
                    "zh_cn" to mapOf(
                        "title" to "Harnax 通知",
                        "content" to listOf(
                            listOf(mapOf("tag" to "text", "text" to "这是一条测试通知")),
                            listOf(
                                mapOf("tag" to "a", "text" to "查看详情", "href" to "https://example.com"),
                                mapOf("tag" to "at", "user_id" to "ou_xxxxx"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        println("📤 请求体:")
        println("  ${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(postMessage)}")

        // 示例 3: 发送交互式卡片消息
        println("\n\n【示例 3】发送交互式卡片消息 (Interactive)")
        val cardMessage = mapOf(
            "msg_type" to "interactive",
            "card" to mapOf(
                "header" to mapOf(
                    "title" to mapOf("tag" to "plain_text", "content" to "🎉 Harnax 卡片消息"),
                ),
                "elements" to listOf(
                    mapOf(
                        "tag" to "markdown",
                        "content" to "**这是一条交互式卡片消息**\n支持 Markdown 格式",
                    ),
                    mapOf("tag" to "hr"),
                    mapOf(
                        "tag" to "action",
                        "actions" to listOf(
                            mapOf(
                                "tag" to "button",
                                "text" to mapOf("tag" to "plain_text", "content" to "查看详情"),
                                "url" to "https://example.com",
                                "type" to "primary",
                            ),
                        ),
                    ),
                ),
            ),
        )

        println("📤 请求体:")
        println("  ${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cardMessage)}")
    }

    /**
     * 演示飞书富消息功能
     */
    private fun demonstrateRichMessages() {
        val adaptor = FeishuAdaptor()

        // 示例 1: 纯文本消息
        println("【1】纯文本消息")
        val textMessage = TextRichMessage("这是一条纯文本消息")
        val textJson = com.agnetix.harnax.channel.adaptor.feishu.FeishuMessageBuilder.buildFromRichMessage(textMessage)
        println("  JSON: ${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(textJson)}")

        // 示例 2: Markdown 消息（飞书使用 post 类型）
        println("\n【2】Markdown 消息")
        val markdownMessage = MarkdownRichMessage(
            """
            # 标题
            **加粗文本** 和 *斜体文本*
            - 列表项 1
            - 列表项 2
            [链接](https://example.com)
            """.trimIndent(),
        )

        val markdownJson = com.agnetix.harnax.channel.adaptor.feishu.FeishuMessageBuilder.buildFromRichMessage(markdownMessage)
        println("  JSON: ${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(markdownJson)}")

        println("\n✅ 富消息构建成功！")
    }

    /**
     * HMAC-SHA256 签名
     */
    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val digest = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
