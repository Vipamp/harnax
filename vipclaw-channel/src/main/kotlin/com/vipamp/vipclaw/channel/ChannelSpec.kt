package com.vipamp.vipclaw.channel

/**
 * Channel 配置规格
 * 用于定义一个对话通道的完整配置
 */
data class ChannelSpec(
    val id: Long,
    val name: String,
    val type: ChannelType,
    val agentId: Long,
    val webhookUrl: String? = null,
    val token: String? = null,
    val encodingAesKey: String? = null,
    val appId: String? = null,
    val appSecret: String? = null,
    val callbackKey: String,
    val status: Int = 1
) {
    companion object {
        @JvmStatic
        fun builder() = ChannelSpecBuilder()
    }
}

class ChannelSpecBuilder {
    private var id: Long = 0
    private var name: String = ""
    private var type: ChannelType = ChannelType.HTTP
    private var agentId: Long = 0
    private var webhookUrl: String? = null
    private var token: String? = null
    private var encodingAesKey: String? = null
    private var appId: String? = null
    private var appSecret: String? = null
    private var callbackKey: String = ""
    private var status: Int = 1

    fun id(id: Long) = apply { this.id = id }
    fun name(name: String) = apply { this.name = name }
    fun type(type: ChannelType) = apply { this.type = type }
    fun agentId(agentId: Long) = apply { this.agentId = agentId }
    fun webhookUrl(webhookUrl: String?) = apply { this.webhookUrl = webhookUrl }
    fun token(token: String?) = apply { this.token = token }
    fun encodingAesKey(encodingAesKey: String?) = apply { this.encodingAesKey = encodingAesKey }
    fun appId(appId: String?) = apply { this.appId = appId }
    fun appSecret(appSecret: String?) = apply { this.appSecret = appSecret }
    fun callbackKey(callbackKey: String) = apply { this.callbackKey = callbackKey }
    fun status(status: Int) = apply { this.status = status }

    fun build() = ChannelSpec(
        id = id,
        name = name,
        type = type,
        agentId = agentId,
        webhookUrl = webhookUrl,
        token = token,
        encodingAesKey = encodingAesKey,
        appId = appId,
        appSecret = appSecret,
        callbackKey = callbackKey,
        status = status
    )
}
