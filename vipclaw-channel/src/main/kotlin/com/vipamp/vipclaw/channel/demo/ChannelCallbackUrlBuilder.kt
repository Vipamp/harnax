//package com.vipamp.vipclaw.channel.demo
//
//import com.vipamp.vipclaw.channel.ChannelType
//
///**
// * Channel 回调 URL 工具
// * 用于生成各平台的回调 URL
// */
//object ChannelCallbackUrlBuilder {
//
//    /**
//     * 基础 URL（需要根据实际部署环境配置）
//     */
//    var baseUrl: String = "http://localhost:8080/admin"
//
//    /**
//     * 构建回调 URL
//     * @param type 平台类型
//     * @param callbackKey 回调标识
//     * @return 完整的回调 URL
//     */
//    fun build(type: ChannelType, callbackKey: String): String {
//        return "$baseUrl/api/channel/callback/${type.code}/$callbackKey"
//    }
//
//    /**
//     * 解析回调 URL
//     * @param url 回调 URL
//     * @return (平台类型, 回调标识) 或 null
//     */
//    fun parse(url: String): Pair<ChannelType, String>? {
//        val regex = """.*/channel/callback/(\w+)/([^/?]+)""".toRegex()
//        val match = regex.find(url) ?: return null
//        val typeCode = match.groupValues[1]
//        val callbackKey = match.groupValues[2]
//        val type = ChannelType.fromCode(typeCode) ?: return null
//        return type to callbackKey
//    }
//
//    /**
//     * 获取各平台的回调 URL 配置说明
//     */
//    fun getConfigGuide(type: ChannelType, callbackKey: String): String {
//        val callbackUrl = build(type, callbackKey)
//        return when (type) {
//            ChannelType.WECOM -> """
//                |=== 企业微信配置说明 ===
//                |1. 登录企业微信管理后台
//                |2. 进入「应用管理」-> 选择应用 -> 「API接收消息」
//                |3. 设置以下 URL: $callbackUrl
//                |4. 设置 Token 和 EncodingAESKey
//                |5. 保存配置并验证
//            """.trimMargin()
//
//            ChannelType.FEISHU -> """
//                |=== 飞书配置说明 ===
//                |1. 登录飞书开放平台
//                |2. 进入应用 -> 「事件订阅」
//                |3. 设置请求网址: $callbackUrl
//                |4. 添加消息事件权限
//                |5. 发布版本并启用机器人
//            """.trimMargin()
//
//            ChannelType.DINGTALK -> """
//                |=== 钉钉配置说明 ===
//                |1. 登录钉钉开放平台
//                |2. 进入机器人配置
//                |3. 设置消息接收地址: $callbackUrl
//                |4. 配置机器人权限
//                |5. 发布并测试
//            """.trimMargin()
//
//            ChannelType.HTTP -> """
//                |=== HTTP 通用接口配置说明 ===
//                |回调 URL: $callbackUrl
//                |
//                |请求方式: POST
//                |Content-Type: application/json
//                |
//                |请求格式:
//                |{
//                |  "sessionId": "会话ID",
//                |  "userId": "用户ID",
//                |  "userName": "用户名",
//                |  "content": "消息内容"
//                |}
//                |
//                |响应格式:
//                |{
//                |  "code": 0,
//                |  "message": "success",
//                |  "data": {
//                |    "content": "AI回复内容",
//                |    "sessionId": "会话ID",
//                |    "timestamp": 1234567890
//                |  }
//                |}
//                |
//                |认证方式:
//                |- Header: Authorization: Bearer <token>
//                |- 或 Header: X-Channel-Token: <token>
//            """.trimMargin()
//        }
//    }
//}
//
///**
// * 回调 URL 工具 Demo
// */
//fun main() {
//    // 设置基础 URL
//    ChannelCallbackUrlBuilder.baseUrl = "https://your-domain.com/admin"
//
//    // 生成各平台的回调 URL
//    val callbackKey = "my-channel-key-123"
//
//    println("=== Channel 回调 URL 生成示例 ===\n")
//
//    ChannelType.entries.forEach { type ->
//        val url = ChannelCallbackUrlBuilder.build(type, callbackKey)
//        println("${type.displayName}: $url")
//    }
//
//    println("\n=== HTTP 接口配置说明 ===")
//    println(ChannelCallbackUrlBuilder.getConfigGuide(ChannelType.HTTP, callbackKey))
//
//    println("\n=== URL 解析示例 ===")
//    val testUrl = "https://your-domain.com/admin/api/channel/callback/http/my-channel-key-123"
//    val parsed = ChannelCallbackUrlBuilder.parse(testUrl)
//    println("解析结果: $parsed")
//}
