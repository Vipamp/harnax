package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.McpOAuthAuthorizeResponse
import com.agnetix.harnax.admin.dto.McpOAuthExchangeRequest
import com.agnetix.harnax.admin.dto.McpOAuthExchangeResponse
import com.agnetix.harnax.admin.dto.McpOAuthRevokeResponse
import com.agnetix.harnax.admin.dto.McpOAuthStatusResponse
import com.agnetix.harnax.entity.dto.McpAccessTokenResponse

/**
 * The per-user authorization code flow of one MCP server (design section 6.2 and 6.4).
 *
 * [McpOAuthService] is what an administrator sets up once; this is what each user goes through, and
 * what is stored is a grant that belongs to one person - never to the tenant as a whole.
 */
interface McpOAuthUserService {

    /**
     * Build the URL to send the current user's browser to, remembering the `state` and the
     * `code_verifier` that [exchange] has to present.
     *
     * @param mcpId MCP server to authorize against; must be an OAUTH2 server of this tenant
     * @param scope space- or comma-separated override of the scopes configured on the server
     * @throws com.agnetix.harnax.admin.exception.BizException when the setup is incomplete (no
     * issuer, no client, no authorization endpoint), when the caller has no user identity, or when
     * too many authorizations are already in flight
     */
    fun authorizeUrl(
        mcpId: Long,
        scope: String?,
    ): McpOAuthAuthorizeResponse

    /**
     * Redeem the code the authorization server sent back and store what it answers.
     *
     * Authenticated, and that is the point: the user the grant belongs to is the caller, not
     * something the [McpOAuthExchangeRequest.state] gets to decide. The state stays in the flow as a
     * one-time anti-forgery token - it names which pending request this code answers, and one that
     * belongs to another user is refused. Whatever the outcome, the state is used once.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException when the request carries no user
     * identity, or when the setup went away mid-flow (no server, no registration, no token endpoint)
     */
    fun exchange(request: McpOAuthExchangeRequest): McpOAuthExchangeResponse

    /**
     * The current user's grant on [mcpId], with no token material in the answer.
     */
    fun status(mcpId: Long): McpOAuthStatusResponse

    /**
     * Drop the current user's grant: locally always, upstream when the authorization server offers
     * RFC 7009 revocation.
     */
    fun revoke(mcpId: Long): McpOAuthRevokeResponse

    /**
     * The token the runtime presents when [sessionId] calls [mcpId] (design section 7.2).
     *
     * This is the internal-API counterpart of [status]: the caller is agent-service rather than a
     * browser, there is no authenticated user to ask, and so the grant is found by resolving the
     * session back to the person who owns it. A stored token that is still usable is handed out
     * unchanged; one that has passed its expiry - or is about to - is refreshed with the stored
     * refresh token first, which is what keeps a long conversation from dying mid-tool-call.
     *
     * @throws com.agnetix.harnax.admin.exception.BizException with code 401 when the session has no
     * user identity, the user never authorized this server, or the grant is gone (revoked upstream,
     * refresh token rotated away) - in each case the answer is the same for the user: authorize
     * again; and with code 503 when the authorization server could not be reached, which asks for a
     * retry rather than a re-authorization
     */
    fun accessToken(
        sessionId: String,
        mcpId: Long,
    ): McpAccessTokenResponse
}
