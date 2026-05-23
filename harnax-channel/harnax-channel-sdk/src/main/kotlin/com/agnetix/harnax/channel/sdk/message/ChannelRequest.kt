package com.agnetix.harnax.channel.sdk.message

/**
 * 平台无关的请求抽象
 * 将 HTTP 请求的核心信息提取为平台无关的数据类
 *
 * 各渠道模块负责将 Servlet Request 或其他框架的 Request
 * 转换为此 ChannelRequest，实现 SDK 与特定框架的解耦。
 */
data class ChannelRequest(
    /**
     * 请求头
     */
    val headers: Map<String, String> = emptyMap(),

    /**
     * 请求体原始内容
     */
    val body: String = "",

    /**
     * 查询参数
     */
    val parameters: Map<String, String> = emptyMap(),

    /**
     * 请求方法 (GET, POST, etc.)
     */
    val method: String = "GET",

    /**
     * 请求路径
     */
    val path: String = "",

    /**
     * 客户端 IP 地址
     */
    val remoteAddr: String? = null,

    /**
     * Content-Type
     */
    val contentType: String? = null,
) {
    companion object {
        @JvmStatic
        fun builder() = ChannelRequestBuilder()
    }
}

class ChannelRequestBuilder {
    private val headers = mutableMapOf<String, String>()
    private var body: String = ""
    private val parameters = mutableMapOf<String, String>()
    private var method: String = "GET"
    private var path: String = ""
    private var remoteAddr: String? = null
    private var contentType: String? = null

    fun header(key: String, value: String) = apply { headers[key] = value }
    fun headers(headers: Map<String, String>) = apply { this.headers.putAll(headers) }
    fun body(body: String) = apply { this.body = body }
    fun parameter(key: String, value: String) = apply { parameters[key] = value }
    fun parameters(parameters: Map<String, String>) = apply { this.parameters.putAll(parameters) }
    fun method(method: String) = apply { this.method = method }
    fun path(path: String) = apply { this.path = path }
    fun remoteAddr(remoteAddr: String?) = apply { this.remoteAddr = remoteAddr }
    fun contentType(contentType: String?) = apply { this.contentType = contentType }

    fun build() = ChannelRequest(
        headers = headers.toMap(),
        body = body,
        parameters = parameters.toMap(),
        method = method,
        path = path,
        remoteAddr = remoteAddr,
        contentType = contentType,
    )
}
