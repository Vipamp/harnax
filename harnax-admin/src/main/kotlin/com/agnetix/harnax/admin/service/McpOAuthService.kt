package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.McpOAuthClientRequest
import com.agnetix.harnax.admin.dto.McpOAuthDiscoveryResponse

/**
 * Authorization server discovery and client registration for an MCP server (design section 6.1).
 *
 * The per-user authorization code flow is not here yet; this covers the one-time setup an
 * administrator does before any user can authorize.
 */
interface McpOAuthService {

    /**
     * Locate the authorization server behind [mcpId], read its metadata and store the result as the
     * tenant's registration for that issuer.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException when nothing answers, or when what
     * answers is not a usable authorization server
     */
    fun discover(mcpId: Long): McpOAuthDiscoveryResponse

    /**
     * Store the client credentials this tenant presents at the discovered authorization server.
     *
     * @param mcpId MCP server whose configured authorization server the client belongs to
     * @param request client_id, optional secret and optional redirect_uri
     * @return the registration as stored, with the secret masked
     */
    fun saveClient(
        mcpId: Long,
        request: McpOAuthClientRequest,
    ): McpOAuthDiscoveryResponse
}
