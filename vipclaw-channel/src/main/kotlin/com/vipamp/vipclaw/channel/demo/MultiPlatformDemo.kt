package com.vipamp.vipclaw.channel.demo

import com.vipamp.vipclaw.channel.ChannelSpec
import com.vipamp.vipclaw.channel.ChannelType
import com.vipamp.vipclaw.channel.adaptor.ChannelAdaptor
import com.vipamp.vipclaw.channel.adaptor.ChannelAdaptorFactory
import com.vipamp.vipclaw.channel.message.ChannelMessage

/**
 * 多平台适配器使用示例
 * 演示如何使用企业微信、飞书、钉钉适配器
 */
object MultiPlatformDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(60))
        println("多平台适配器使用示例")
        println("=".repeat(60))
        
        // ========== 1. 查看支持的平台 ==========
        println("\n【1】支持的平台列表:")
        ChannelAdaptorFactory.getSupportedTypes().forEach { type ->
            println("  - ${type.displayName} (${type.code})")
        }
        
        // ========== 2. 企业微信配置示例 ==========
        println("\n【2】企业微信 Channel 配置:")
        val wecomChannel = ChannelSpec.builder()
            .id(1L)
            .name("企业微信客服机器人")
            .type(ChannelType.WECOM)
            .agentId(1L)
            .callbackKey("wecom-abc123")
            .token("your-wecom-token")           // 企业微信后台配置的 Token
            .encodingAesKey("your-aes-key-32chars")  // 企业微信后台配置的 EncodingAESKey
            .webhookUrl("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx")  // 可选
            .build()
        
        printlnChannelConfig(wecomChannel)
        printWecomGuide(wecomChannel)
        
        // ========== 3. 飞书配置示例 ==========
        println("\n【3】飞书 Channel 配置:")
        val feishuChannel = ChannelSpec.builder()
            .id(2L)
            .name("飞书助手机器人")
            .type(ChannelType.FEISHU)
            .agentId(2L)
            .callbackKey("feishu-xyz789")
            .appId("cli_xxx")           // 飞书应用 ID
            .appSecret("your-app-secret")  // 飞书应用密钥
            .webhookUrl("https://open.feishu.cn/open-apis/bot/v2/hook/xxx")  // 可选
            .build()
        
        printlnChannelConfig(feishuChannel)
        printFeishuGuide(feishuChannel)
        
        // ========== 4. 钉钉配置示例 ==========
        println("\n【4】钉钉 Channel 配置:")
        val dingtalkChannel = ChannelSpec.builder()
            .id(3L)
            .name("钉钉办公机器人")
            .type(ChannelType.DINGTALK)
            .agentId(3L)
            .callbackKey("dingtalk-def456")
            .appId("dingxxx")            // 钉钉应用 ID
            .appSecret("your-ding-secret")  // 钉钉应用密钥
            .webhookUrl("https://oapi.dingtalk.com/robot/send?access_token=xxx")  // 可选
            .build()
        
        printlnChannelConfig(dingtalkChannel)
        printDingtalkGuide(dingtalkChannel)
        
        // ========== 5. 消息处理流程 ==========
        println("\n【5】统一消息处理流程:")
        println("""
            |所有平台的消息处理流程一致：
            |
            |┌─────────────────────────────────────────────────────────┐
            |│  1. 接收回调请求                                        │
            |│     ↓                                                   │
            |│  2. 验证签名 (verifySignature)                          │
            |│     ↓                                                   │
            |│  3. 解析消息 (parseMessage)                             │
            |│     ↓                                                   │
            |│  4. 查询 Channel 配置和关联的 Agent                     │
            |│     ↓                                                   │
            |│  5. 加载会话历史                                        │
            |│     ↓                                                   │
            |│  6. 调用 Agent 处理消息                                 │
            |│     ↓                                                   │
            |│  7. 保存 AI 回复到会话历史                              │
            |│     ↓                                                   │
            |│  8. 构建响应 (buildResponse)                            │
            |│     ↓                                                   │
            |│  9. 返回响应给平台                                      │
            |└─────────────────────────────────────────────────────────┘
        """.trimMargin())
        
        // ========== 6. 适配器核心方法 ==========
        println("\n【6】适配器核心方法说明:")
        println("""
            |interface ChannelAdaptor {
            |    // 获取平台类型
            |    fun getType(): ChannelType
            |    
            |    // 验证回调签名（确保消息来自官方平台）
            |    fun verifySignature(request: HttpServletRequest, channel: ChannelSpec): Boolean
            |    
            |    // 解析消息（将平台格式转换为统一格式）
            |    fun parseMessage(request: HttpServletRequest): ChannelMessage
            |    
            |    // 构建响应（将AI回复转换为平台格式）
            |    fun buildResponse(reply: String, originalMessage: ChannelMessage): Any
            |    
            |    // 主动推送消息（可选）
            |    suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String)
            |    
            |    // URL验证（首次配置时）
            |    fun handleUrlVerification(request: HttpServletRequest, channel: ChannelSpec): Any?
            |}
        """.trimMargin())
        
        println("\n" + "=".repeat(60))
        println("多平台示例完成！")
        println("=".repeat(60))
    }
    
    private fun printlnChannelConfig(channel: ChannelSpec) {
        println("  ID: ${channel.id}")
        println("  名称: ${channel.name}")
        println("  类型: ${channel.type.displayName}")
        println("  回调Key: ${channel.callbackKey}")
    }
    
    private fun printWecomGuide(channel: ChannelSpec) {
        println("""
            |  配置步骤:
            |  1. 登录企业微信管理后台 (https://work.weixin.qq.com)
            |  2. 应用管理 -> 选择应用 -> 设置API接收消息
            |  3. 配置回调URL: http://your-server/admin/api/channel/callback/wecom/${channel.callbackKey}
            |  4. 设置 Token 和 EncodingAESKey（与配置一致）
            |  5. 保存并验证
            |
            |  回调URL: http://your-server/admin/api/channel/callback/wecom/${channel.callbackKey}
        """.trimMargin())
    }
    
    private fun printFeishuGuide(channel: ChannelSpec) {
        println("""
            |  配置步骤:
            |  1. 登录飞书开放平台 (https://open.feishu.cn)
            |  2. 创建企业自建应用
            |  3. 事件订阅 -> 配置请求网址
            |  4. 回调URL: http://your-server/admin/api/channel/callback/feishu/${channel.callbackKey}
            |  5. 添加消息接收权限
            |  6. 发布版本并启用机器人
            |
            |  回调URL: http://your-server/admin/api/channel/callback/feishu/${channel.callbackKey}
        """.trimMargin())
    }
    
    private fun printDingtalkGuide(channel: ChannelSpec) {
        println("""
            |  配置步骤:
            |  1. 登录钉钉开放平台 (https://open.dingtalk.com)
            |  2. 创建企业内部机器人
            |  3. 配置消息接收地址
            |  4. 回调URL: http://your-server/admin/api/channel/callback/dingtalk/${channel.callbackKey}
            |  5. 配置机器人权限
            |  6. 发布并测试
            |
            |  回调URL: http://your-server/admin/api/channel/callback/dingtalk/${channel.callbackKey}
        """.trimMargin())
    }
}
