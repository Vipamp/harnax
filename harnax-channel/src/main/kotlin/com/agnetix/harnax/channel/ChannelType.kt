package com.agnetix.harnax.channel

/**
 * Channel 类型枚举
 * 支持的机器人平台类型
 */
enum class ChannelType(val code: String, val displayName: String) {
    WECOM("wecom", "企业微信"),
    FEISHU("feishu", "飞书"),
    DINGTALK("dingtalk", "钉钉"),
    HTTP("http", "HTTP接口"),
    ;

    companion object {
        fun fromCode(code: String): ChannelType? = entries.find { it.code == code }
    }
}
