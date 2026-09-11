package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.McpOAuthConfig
import com.agnetix.harnax.admin.dto.McpServerCreateRequest
import com.agnetix.harnax.admin.dto.McpServerResponse
import com.agnetix.harnax.admin.dto.McpServerUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.agent.adaptor.mcp.McpHelper
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.McpAuthTypes
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.McpServerMapper
import com.agnetix.harnax.mapper.McpUserCredentialMapper
import com.github.pagehelper.PageHelper
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils.hasText
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.LocalDateTime

/**
 * MCP server service implementation
 */
@Service
class McpServerServiceImpl(
    private val jwtUtil: JwtUtil,
    private val mcpServerMapper: McpServerMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
    private val objectMapper: ObjectMapper,
    private val agentMcpBindingMapper: AgentMcpBindingMapper,
    private val mcpUserCredentialMapper: McpUserCredentialMapper,
) : McpServerService {

    private val log = LoggerFactory.getLogger(McpServerServiceImpl::class.java)

    override fun page(
        keyword: String?,
        status: Int?,
        type: String?,
        pageNum: Int,
        pageSize: Int,
    ): Page<McpServer> {
        log.info(
            "Paginated query for MCP server list, pageNum: {}, pageSize: {}, keyword: {}, status: {}, type: {}",
            pageNum,
            pageSize,
            keyword,
            status,
            type,
        )

        // Get current user
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val tenantId = currentTenantId()

        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<Agent>(safePageNum, safePageSize)
        return Page.fromPageInfo(mcpServerMapper.selectMcpServerList(keyword, status, type, currentUsername, tenantId))
    }

    /**
     * Single-row access to `mcp_server`.
     *
     * The list query filters by tenant, so a by-id read that does not would leave that filter
     * cosmetic: any row could be opened, edited or deleted by guessing its id. A row outside the
     * current tenant answers as a missing one, which is also how the callers of this method already
     * treat a dangling binding row.
     */
    override fun getMcpServer(id: Long): McpServer? = mcpServerMapper.selectById(id)?.takeIf { it.tenantId == currentTenantId() }

    private fun currentTenantId(): Long = TenantContext.getTenantId() ?: 1

    @Transactional(rollbackFor = [Exception::class])
    override fun createMcpServer(request: McpServerCreateRequest): Boolean {
        log.info("Creating MCP server, name: {}", request.name)

        // Validate name uniqueness within the tenant the row is about to belong to
        val tenantId = currentTenantId()
        val existing = mcpServerMapper.selectByName(request.name!!, tenantId)
        if (existing != null) {
            throw BizException("MCP name already exists")
        }

        // Validate type and field linkage logic
        validateTypeAndFields(request.type, request.command, request.url)
        val authType = resolveAuthType(request.authType)
        validateAuthType(request.type, authType)

        val mcpServer = McpServer()
        mcpServer.name = request.name
        mcpServer.description = request.description ?: ""
        mcpServer.type = request.type
        mcpServer.command = request.command ?: ""
        mcpServer.url = request.url ?: ""
        mcpServer.authType = authType
        mcpServer.oauthConfig = request.oauthConfig?.let { writeOAuthConfig(it, authType) }
        mcpServer.status = request.status ?: 1
        mcpServer.isPublic = request.isPublic ?: 1
        mcpServer.active = 1

        // Serialize headers with encryption (McpConfigEntry) and envParams with ToolEnvParamEntry format
        mcpServer.headers = secretFieldEncryptor.serializeWithEncryption(request.headers)
        mcpServer.envParams = secretFieldEncryptor.serializeToolEnvParams(request.envParams)

        // Set tenant ID
        mcpServer.tenantId = tenantId

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        mcpServer.creator = currentUsername

        val success = this.mcpServerMapper.insert(mcpServer) > 0
        log.info("MCP server creation {}, id: {}", if (success) "successful" else "failed", mcpServer.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateMcpServer(id: Long, request: McpServerUpdateRequest): Boolean {
        log.info("Updating MCP server, id: {}", id)

        val mcpServer = getMcpServer(id)
            ?: throw BizException("MCP server not found")
        // Read before any field is applied: the end of this method has to know whether the request
        // turned OAuth off, which it can no longer tell from the entity once auth_type is overwritten.
        val wasOAuth = mcpServer.authType == McpAuthTypes.OAUTH2

        // Selectively update fields: omitted means keep what is stored
        request.name?.let { name ->
            if (!hasText(name)) {
                throw BizException("MCP name cannot be empty")
            }
            if (name != mcpServer.name) {
                val existing = mcpServerMapper.selectByName(name, mcpServer.tenantId)
                if (existing != null) {
                    throw BizException("MCP name already exists")
                }
                mcpServer.name = name
            }
        }
        request.description?.let { mcpServer.description = it }
        request.type?.let { newType ->
            if (!newType.equals(mcpServer.type, ignoreCase = true)) {
                // `validateTypeAndFields` only requires the field the new type uses, so without this a
                // row keeps the previous transport's parameters: an sse server that still stores
                // `command`, which nothing reads but the detail page shows as if it did.
                when (newType.lowercase()) {
                    "stdio" -> {
                        // Empty, not null: `url`/`command` are non-null columns on the entity.
                        // `updateById` writes every column, so this does reach the row.
                        mcpServer.url = ""
                        mcpServer.headers = null
                    }

                    else -> {
                        mcpServer.command = ""
                        mcpServer.envParams = null
                    }
                }
            }
            mcpServer.type = newType
        }
        request.command?.let { mcpServer.command = it }
        // A grant is bound to the resource it was issued for (RFC 8707), so moving the server to
        // another URL invalidates every user's token while the status column still reads authorized.
        val oauthResourceMoved = wasOAuth && request.url != null && request.url != mcpServer.url
        request.url?.let { mcpServer.url = it }
        request.authType?.let { mcpServer.authType = resolveAuthType(it) }
        request.isPublic?.let { mcpServer.isPublic = it }
        request.status?.let { mcpServer.status = it }

        request.oauthConfig?.let { incoming ->
            // Discovery puts the issuer it found into this column. A request that carries the rest of
            // the config without it means "I did not touch that field", not "forget which
            // authorization server this is": erasing it would orphan a registration the tenant still
            // has, and force a re-discovery to undo a scope edit.
            val storedIssuer = McpServerResponse.parseOAuthConfig(mcpServer.oauthConfig, objectMapper)?.authorizationServer
            val config = if (incoming.authorizationServer.isNullOrBlank() && !storedIssuer.isNullOrBlank()) {
                incoming.copy(authorizationServer = storedIssuer)
            } else {
                incoming
            }
            mcpServer.oauthConfig = writeOAuthConfig(config, mcpServer.authType)
        }
        if (mcpServer.authType != McpAuthTypes.OAUTH2) {
            // Leaving a config behind for a server that no longer does OAuth would have the UI keep
            // showing scopes it never uses.
            mcpServer.oauthConfig = null
        }

        // Update headers (McpConfigEntry) and envParams (ToolEnvParamEntry) with encryption
        if (request.headers != null) {
            mcpServer.headers = secretFieldEncryptor.serializeWithEncryption(request.headers, mcpServer.headers)
        }
        if (request.envParams != null) {
            mcpServer.envParams = secretFieldEncryptor.serializeToolEnvParams(request.envParams, mcpServer.envParams)
        }

        // Validate type and field linkage logic after update
        validateTypeAndFields(mcpServer.type, mcpServer.command, mcpServer.url)
        validateAuthType(mcpServer.type, mcpServer.authType)

        // updateById writes the entity's update_time, so leaving it at the loaded value would freeze
        // the column and with it the list's update_time ordering.
        mcpServer.updateTime = LocalDateTime.now()

        val success = this.mcpServerMapper.updateById(mcpServer) > 0

        // Cleared after the write and only when it hit: a row this transaction no longer owns - it was
        // deleted a moment ago, so updateById matched nothing - still says OAUTH2 in the database, and
        // deleting grants for it here would destroy them for a server that is still an OAuth one. The
        // same reason deleteMcpServer cascades only on a hit.
        if (success && wasOAuth && oauthResourceMoved) {
            // Still OAUTH2, so the branch above does not fire — but each grant was issued for the
            // previous resource and the token exchange checks `aud` against the configured url, so
            // every one of them now fails upstream while `status` keeps reading "authorized".
            // Clearing forces a re-authorization instead of a permanent, unexplained breakage.
            val stale = mcpUserCredentialMapper.deleteByMcpId(id)
            if (stale > 0) {
                log.warn(
                    "MCP server {} moved to another url, so {} stored user grant(s) were cleared; users must authorize again",
                    id,
                    stale,
                )
            }
        } else if (success && wasOAuth && mcpServer.authType != McpAuthTypes.OAUTH2) {
            // Per-user grants are read and revoked through the OAuth endpoints, and those refuse a
            // server whose auth type is no longer OAUTH2 - so leaving the rows behind would strand
            // ciphertext their owners can neither see nor revoke. Cleared locally like the delete path
            // does; the authorization server's own copies are not presented, so they stay valid until
            // they expire on their own.
            val orphaned = mcpUserCredentialMapper.deleteByMcpId(id)
            if (orphaned > 0) {
                log.warn(
                    "MCP server {} left {} for {}, so {} stored user grant(s) were cleared; their upstream copies expire on their own",
                    id,
                    McpAuthTypes.OAUTH2,
                    mcpServer.authType,
                    orphaned,
                )
            }
        }

        log.info("MCP server update {}, id: {}", if (success) "successful" else "failed", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun toggleMcpServerStatus(id: Long, status: Int): Boolean {
        log.info("Toggling MCP server status, id: {}, status: {}", id, status)

        val mcpServer = getMcpServer(id)
            ?: throw BizException("MCP server not found")

        val success = mcpServerMapper.updateStatus(id, status) > 0
        log.info("MCP server status toggle {}, id: {}, status: {}", if (success) "successful" else "failed", id, status)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteMcpServer(id: Long): Boolean {
        log.info("Deleting MCP server, id: {}", id)

        val mcpServer = getMcpServer(id)
            ?: throw BizException("MCP server not found")

        if (mcpServerMapper.deleteById(id) == 0) {
            log.info("MCP server deletion failed, id: {}", id)
            return false
        }
        // Bindings are not filtered by active, so a leftover row would turn every later delivery
        // into a "MCP not found" warning for a server that no longer exists.
        val removed = agentMcpBindingMapper.deleteByMcpId(id)
        // Grants are per (user, mcp): with the server gone there is nothing to present them to, and
        // leaving ciphertexts behind for a deleted row is pure blast radius. mcp_oauth_client is not
        // touched here on purpose - another server of this tenant may share the registration.
        val credentials = mcpUserCredentialMapper.deleteByMcpId(id)
        log.info(
            "MCP server deleted, id: {}, {} binding(s) and {} credential(s) removed",
            id,
            removed,
            credentials,
        )
        return true
    }

    override fun connectivityTest(id: Long): Boolean {
        log.info("MCP server connectivity test, id: {}", id)
        listTools(id)
        return true
    }

    /**
     * Validate type and command/url field linkage logic
     *
     * @param type MCP type
     * @param command Execution command
     * @param url Server URL
     */
    private fun validateTypeAndFields(type: String?, command: String?, url: String?) {
        when (type) {
            "stdio" -> {
                if (!hasText(command)) {
                    throw BizException("MCP server of stdio type, command cannot be empty")
                }
            }

            "sse", "streamablehttp" -> {
                if (!hasText(url)) {
                    throw BizException("MCP server of $type type, url cannot be empty")
                }
            }

            else -> {
                throw BizException("Unsupported MCP type: $type, only supports stdio/sse/streamablehttp")
            }
        }
    }

    /**
     * An omitted auth type is NONE, i.e. exactly the behaviour every row had before V25.
     */
    private fun resolveAuthType(raw: String?): String {
        val authType = raw?.takeIf { hasText(it) } ?: McpAuthTypes.NONE
        if (authType !in McpAuthTypes.SUPPORTED) {
            val reason = if (authType == McpAuthTypes.BASIC) {
                "$authType is stored by V25 but not wired into the runtime yet"
            } else {
                "only ${McpAuthTypes.SUPPORTED.joinToString("/")} are accepted"
            }
            throw BizException("Unsupported auth type: $reason")
        }
        return authType
    }

    /**
     * OAuth delivers a bearer token as an HTTP header; a stdio server has no request to attach it to
     * and would silently end up unauthenticated.
     */
    private fun validateAuthType(type: String?, authType: String?) {
        if (authType == McpAuthTypes.OAUTH2 && type == "stdio") {
            throw BizException("MCP server of stdio type cannot use ${McpAuthTypes.OAUTH2}, there is no HTTP request to attach a token to")
        }
    }

    private fun writeOAuthConfig(config: McpOAuthConfig, authType: String?): String {
        if (authType != McpAuthTypes.OAUTH2) {
            throw BizException("OAuth configuration only applies to an auth type of ${McpAuthTypes.OAUTH2}")
        }
        // The admin fetches this issuer during discovery, so anything but an http(s) URL is a
        // read-your-own-filesystem request waiting to happen. Parsed rather than prefix-matched:
        // "https://" has the right prefix and no host, and a value that cannot be requested has no
        // business being stored as an identity.
        config.authorizationServer?.takeIf { it.isNotBlank() }?.let { issuer ->
            val uri = try {
                URI.create(issuer.trim())
            } catch (e: Exception) {
                null
            }
            val scheme = uri?.scheme?.lowercase()
            if (uri == null || (scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
                throw BizException("Authorization server must be an http(s) URL: $issuer")
            }
        }
        return objectMapper.writeValueAsString(config)
    }

    override fun convertToResponse(mcpServer: McpServer): McpServerResponse = McpServerResponse.fromEntity(mcpServer, objectMapper, secretFieldEncryptor)

    override fun listTools(mcpId: Long): List<McpSchema.Tool> {
        val mcpServer = getMcpServer(mcpId) ?: throw BizException("MCP server not found")
        return McpHelper.listTools(mcpServer, secretFieldEncryptor::decryptToMap, secretFieldEncryptor::decryptToolEnvParamsToMap)
    }
}
