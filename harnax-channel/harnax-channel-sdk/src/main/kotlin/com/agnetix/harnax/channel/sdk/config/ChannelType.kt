package com.agnetix.harnax.channel.sdk.config

/**
 * Channel Type Enum
 * Supported bot platform types
 */
enum class ChannelType(val code: String, val displayName: String) {
    WECOM("wecom", "WeCom"),
    WECHAT("wechat", "WeChat"),
    FEISHU("feishu", "Feishu"),
    DINGTALK("dingtalk", "DingTalk"),
    HTTP("http", "HTTP API"),
    ;

    companion object {
        fun fromCode(code: String): ChannelType? = entries.find { it.code == code }
    }
}
