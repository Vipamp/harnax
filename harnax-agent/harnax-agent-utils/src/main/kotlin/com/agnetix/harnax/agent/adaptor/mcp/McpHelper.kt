package com.agnetix.harnax.agent.adaptor.mcp

import com.agnetix.harnax.common.error.HarnaxException
import com.agnetix.harnax.entity.McpAuthTypes
import com.agnetix.harnax.entity.McpServer
import io.agentscope.core.tool.mcp.McpClientBuilder
import io.agentscope.core.tool.mcp.McpClientWrapper
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * 配置解析器函数类型：将数据库中存储的 JSON 字符串解析为明文的 Key-Value Map
 * 实现方负责反序列化 + 解密 secret 条目
 */
typealias McpConfigResolver = (String?) -> Map<String, String>

/**
 * MCP 客户端辅助工具类
 * 提供基于 McpConfig 创建 McpClientBuilder 的方法
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: harnax
 */
object McpHelper {

    private val log = LoggerFactory.getLogger(McpHelper::class.java)

    /**
     * How long a handshake call may block the caller. Both users of this run on a request thread,
     * so "no answer" has to become an error instead of a parked thread.
     */
    private val CLIENT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

    /**
     * How long building a client may take. Generous compared to [CLIENT_REQUEST_TIMEOUT] because a
     * stdio client starts an external process, which may have to be fetched first (`npx -y ...`).
     * Matches the registration wait in `HarnessAgentBuilder.addMcp`.
     */
    private val CLIENT_BUILD_TIMEOUT: Duration = Duration.ofSeconds(60)

    /**
     * Handshake budget for an OAuth server, wider than for the others: answering the token callback
     * the first time goes to the admin and, through it, to the authorization server, which is two
     * network hops on the path to `initialize`. Ten seconds would make a cold first call look broken.
     */
    private val OAUTH_INITIALIZATION_TIMEOUT: Duration = Duration.ofSeconds(30)

    private const val AUTHORIZATION_HEADER = "Authorization"

    fun listTools(mcpServer: McpServer, configResolver: McpConfigResolver? = null, envResolver: McpConfigResolver? = null): List<McpSchema.Tool> {
        val mcpClient = createMcpClient(mcpServer, false, configResolver, envResolver)
        // Closed on the way out, whatever the outcome: this runs once per connectivity test click and
        // nobody else holds the client — a stdio one is an OS process.
        try {
            try {
                mcpClient.initialize()?.block(CLIENT_REQUEST_TIMEOUT)
            } catch (t: Throwable) {
                log.error("Failed to initialize McpClient `${mcpServer.name}`", t)
                throw McpErrorCode.MCP_CONNECTION_FAILED.format(t, mcpServer.name)
            }
            try {
                // Bounded like initialize(): an un-timed block() here would hang the admin request
                // on a server that answers initialize but not tools/list.
                return mcpClient.listTools()?.block(CLIENT_REQUEST_TIMEOUT) ?: emptyList()
            } catch (t: Throwable) {
                log.error("Failed to list tools from McpClient `${mcpServer.name}`", t)
                throw McpErrorCode.MCP_CONNECTION_FAILED.format(t, mcpServer.name)
            }
        } finally {
            closeQuietly(mcpClient, mcpServer.name)
        }
    }

    /**
     * Close a client without letting a second failure replace the first one.
     */
    fun closeQuietly(mcpClient: McpClientWrapper, name: String) {
        try {
            mcpClient.close()
        } catch (t: Throwable) {
            log.warn("Failed to close McpClient `$name`", t)
        }
    }

    /**
     * Build McpConfig based on MCP type
     *
     * @param mcpServer MCP server entity
     * @param configResolver optional resolver to deserialize + decrypt headers/envParams JSON to plain Map
     */
    fun buildMcpConfig(mcpServer: McpServer, configResolver: McpConfigResolver? = null, envResolver: McpConfigResolver? = null): McpConfig? {
        val resolve: McpConfigResolver = configResolver ?: { emptyMap() }
        val resolveEnv: McpConfigResolver = envResolver ?: resolve
        return when (val type = mcpServer.type.lowercase()) {
            "stdio" -> StdioMcpConfig(
                mcpServer.name,
                mcpServer.command,
                emptyList(),
                resolveEnv(mcpServer.envParams),
            )
            "sse" -> SseHttpMcpConfig(
                mcpServer.name,
                mcpServer.url,
                networkHeaders(mcpServer, resolve),
                emptyMap(),
            )
            "streamablehttp" -> StreamableHttpMcpConfig(
                mcpServer.name,
                mcpServer.url,
                networkHeaders(mcpServer, resolve),
                emptyMap(),
            )
            else -> {
                log.warn("Unsupported MCP type: $type")
                null
            }
        }
    }

    /**
     * 根据 MCP 配置创建 MCP 客户端
     *
     * @param mcpServer MCP 服务实体
     * @param isAsync 是否异步创建客户端
     * @param configResolver optional resolver to deserialize + decrypt headers/envParams JSON to plain Map
     * @param tokenSource per-user bearer token source; required when the server is OAUTH2, ignored otherwise
     * @return McpClientWrapper 实例
     * @throws McpErrorCode.MCP_CLIENT_CREATE_FAILED 当客户端创建失败时抛出
     */
    fun createMcpClient(
        mcpServer: McpServer,
        isAsync: Boolean,
        configResolver: McpConfigResolver? = null,
        envResolver: McpConfigResolver? = null,
        tokenSource: McpAccessTokenSource? = null,
    ): McpClientWrapper {
        val oauth = mcpServer.authType == McpAuthTypes.OAUTH2
        if (oauth && tokenSource == null) {
            // Building one anyway would connect without a token, fail on the first tool call, and
            // leave the error pointing at the MCP server instead of at the missing grant.
            throw McpErrorCode.MCP_CLIENT_CREATE_FAILED.format(
                "MCP server `${mcpServer.name}` authorizes per user (${McpAuthTypes.OAUTH2}) and no token " +
                    "source was given, so it cannot be connected",
            )
        }
        val mcpConfig = buildMcpConfig(mcpServer, configResolver, envResolver) ?: throw McpErrorCode.MCP_CLIENT_CREATE_FAILED.format("MCP config is null")
        val builder = when (mcpConfig) {
            is StdioMcpConfig -> buildStdioMcpClient(mcpConfig)
            is SseHttpMcpConfig -> buildSseMcpClient(mcpConfig)
            is StreamableHttpMcpConfig -> buildStreamableMcpClient(mcpConfig)
        }
        return try {
            // Inside the try, not before it: a transport that refuses the customizer should say which
            // server failed rather than throw bare.
            if (oauth) {
                builder.applyUserToken(mcpServer.id, tokenSource!!)
            }
            if (isAsync) {
                // Bounded, and null-checked rather than asserted: an un-timed block() parks the
                // caller forever on a server that never answers, and `!!` on that null would surface
                // as a bare NPE naming nothing.
                builder.buildAsync().block(CLIENT_BUILD_TIMEOUT)
                    ?: run {
                        log.error("MCP client build returned null: name={}", mcpConfig.name)
                        throw McpErrorCode.MCP_CLIENT_CREATE_FAILED.format()
                    }
            } else {
                builder.buildSync()
            }
        } catch (e: HarnaxException) {
            throw e
        } catch (e: Exception) {
            throw McpErrorCode.MCP_CLIENT_CREATE_FAILED.format(e, mcpConfig.name)
        }
    }

    /**
     * 构建 STDIO 类型的 MCP 客户端 Builder
     *
     * @param mcpConfig STDIO 类型的 MCP 配置
     * @return McpClientBuilder 实例
     */
    fun buildStdioMcpClient(mcpConfig: StdioMcpConfig): McpClientBuilder = McpClientBuilder.create(mcpConfig.name)
        .stdioTransport(mcpConfig.command, mcpConfig.args, mcpConfig.env)

    /**
     * 构建 SSE HTTP 类型的 MCP 客户端 Builder
     *
     * @param mcpConfig SSE HTTP 类型的 MCP 配置
     * @return McpClientBuilder 实例
     */
    fun buildSseMcpClient(mcpConfig: SseHttpMcpConfig): McpClientBuilder = McpClientBuilder.create(mcpConfig.name)
        .sseTransport(mcpConfig.url)
        .applyHttpTransport(mcpConfig.headers, mcpConfig.queryParam)

    /**
     * 构建 Streamable HTTP 类型的 MCP 客户端 Builder
     *
     * @param mcpConfig Streamable HTTP 类型的 MCP 配置
     * @return McpClientBuilder 实例
     */
    fun buildStreamableMcpClient(mcpConfig: StreamableHttpMcpConfig): McpClientBuilder = McpClientBuilder.create(mcpConfig.name)
        .streamableHttpTransport(mcpConfig.url)
        .applyHttpTransport(mcpConfig.headers, mcpConfig.queryParam)

    /**
     * 扩展函数：应用 HTTP 传输配置
     *
     * @param headers HTTP 请求头
     * @param queryParam URL 查询参数
     * @return McpClientBuilder 实例
     */
    fun McpClientBuilder.applyHttpTransport(
        headers: Map<String, String>,
        queryParam: Map<String, String>,
    ): McpClientBuilder = apply {
        if (headers.isNotEmpty()) headers(headers)
        if (queryParam.isNotEmpty()) queryParams(queryParam)
    }

    /**
     * The headers of a network server, minus an `Authorization` someone saved next to an OAuth setup.
     *
     * Both would write the same header, and which one wins is decided by the transport's own ordering -
     * not by anything the user can see. The per-user token is the one that carries an identity, so it
     * is the one kept, and the dropped static value is said out loud.
     */
    private fun networkHeaders(
        mcpServer: McpServer,
        resolve: McpConfigResolver,
    ): Map<String, String> {
        val headers = resolve(mcpServer.headers)
        if (mcpServer.authType != McpAuthTypes.OAUTH2 || headers.isEmpty()) {
            return headers
        }
        val kept = headers.filterKeys { !it.equals(AUTHORIZATION_HEADER, ignoreCase = true) }
        if (kept.size != headers.size) {
            log.warn(
                "MCP server `${mcpServer.name}` is {}, its configured static Authorization header is ignored in " +
                    "favour of the per-user token",
                mcpServer.authType,
            )
        }
        return kept
    }

    /**
     * Attach the per-user bearer token to every request this client makes.
     *
     * A customizer rather than `headers(...)` because the token expires and rotates: a header set at
     * build time is frozen into the connection, so a long conversation would die at the access token's
     * expiry - which for a 5-minute token is minutes into one answer. The callback is consulted per
     * request, and its implementation caches, so the common path costs a map lookup.
     *
     * It runs on whichever thread the MCP client sends from, so a cold call (first request, or a
     * renewal) blocks there for the duration of one admin round trip. Accepted knowingly: the cache
     * makes it once per session per server, not once per call.
     */
    private fun McpClientBuilder.applyUserToken(
        mcpId: Long,
        source: McpAccessTokenSource,
    ): McpClientBuilder = httpRequestCustomizer(
        McpSyncHttpClientRequestCustomizer { request, _, _, _, _ ->
            request.setHeader(AUTHORIZATION_HEADER, "Bearer ${source.accessToken(mcpId)}")
        },
    ).initializationTimeout(OAUTH_INITIALIZATION_TIMEOUT)
}
