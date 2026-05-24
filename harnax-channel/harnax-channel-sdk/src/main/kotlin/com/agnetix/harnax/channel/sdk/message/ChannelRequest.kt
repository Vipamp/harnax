package com.agnetix.harnax.channel.sdk.message

/**
 * Platform-agnostic Request Abstraction
 * Extracts core HTTP request information into a platform-agnostic data class
 *
 * Each channel module is responsible for converting Servlet Request or other framework Requests
 * into this ChannelRequest, decoupling the SDK from specific frameworks.
 */
data class ChannelRequest(
    /**
     * Request headers
     */
    val headers: Map<String, String> = emptyMap(),

    /**
     * Raw request body content
     */
    val body: String = "",

    /**
     * Query parameters
     */
    val parameters: Map<String, String> = emptyMap(),

    /**
     * Request method (GET, POST, etc.)
     */
    val method: String = "GET",

    /**
     * Request path
     */
    val path: String = "",

    /**
     * Client IP address
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
