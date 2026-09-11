package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.McpOAuthAuthorizeResponse
import com.agnetix.harnax.admin.dto.McpOAuthClientRequest
import com.agnetix.harnax.admin.dto.McpOAuthDiscoveryResponse
import com.agnetix.harnax.admin.dto.McpOAuthExchangeRequest
import com.agnetix.harnax.admin.dto.McpOAuthExchangeResponse
import com.agnetix.harnax.admin.dto.McpOAuthRevokeResponse
import com.agnetix.harnax.admin.dto.McpOAuthStatusResponse
import com.agnetix.harnax.admin.service.McpOAuthService
import com.agnetix.harnax.admin.service.McpOAuthUserService
import com.agnetix.harnax.admin.util.ApiErrors
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * OAuth endpoints for one MCP server (design sections 6.2, 6.4 and 9.1).
 *
 * [discover] and [saveClient] are the setup an administrator does once. The rest belong to each
 * user: they read and write that one user's own grant, so they take no user id as a parameter - the
 * identity comes from the JWT. That includes [exchange], which is why the authorization server's
 * redirect now lands on a front-end page rather than on an endpoint of this service.
 */
@RestController
@RequestMapping("/api/admin/mcp")
@Tag(name = "MCP OAuth", description = "Authorization server discovery, client registration and per-user authorization for MCP servers")
class McpOAuthController(
    private val mcpOAuthService: McpOAuthService,
    private val mcpOAuthUserService: McpOAuthUserService,
) {

    private val log = LoggerFactory.getLogger(McpOAuthController::class.java)

    @PostMapping("/{id}/oauth/discover")
    @Operation(
        summary = "Discover the authorization server",
        description = "Resolve the authorization server behind the MCP server, read its metadata and store it as this tenant's registration",
    )
    fun discover(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<McpOAuthDiscoveryResponse> = try {
        ResultVo.success(mcpOAuthService.discover(id))
    } catch (e: Exception) {
        log.error("Failed to discover OAuth metadata, mcpId: {}", id, e)
        ResultVo.error(ApiErrors.message(e, "Failed to discover OAuth metadata"))
    }

    @PostMapping("/{id}/oauth/client")
    @Operation(
        summary = "Register the OAuth client",
        description = "Store the client_id and optional client_secret this tenant presents at the discovered authorization server",
    )
    fun saveClient(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: McpOAuthClientRequest,
    ): ResultVo<McpOAuthDiscoveryResponse> = try {
        ResultVo.success(mcpOAuthService.saveClient(id, request))
    } catch (e: Exception) {
        log.error("Failed to save OAuth client, mcpId: {}", id, e)
        ResultVo.error(ApiErrors.message(e, "Failed to save OAuth client"))
    }

    @GetMapping("/{id}/oauth/authorize-url")
    @Operation(
        summary = "Ask for the URL that starts this user's authorization",
        description = "Build the authorization request with PKCE for the current user and remember the state and code verifier the exchange has to present",
    )
    fun authorizeUrl(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Scopes to ask for instead of the ones configured on the server, space or comma separated")
        @RequestParam(required = false) scope: String?,
    ): ResultVo<McpOAuthAuthorizeResponse> = try {
        ResultVo.success(mcpOAuthUserService.authorizeUrl(id, scope))
    } catch (e: Exception) {
        log.error("Failed to build the OAuth authorization URL, mcpId: {}", id, e)
        ResultVo.error(ApiErrors.message(e, "Failed to build the OAuth authorization URL"))
    }

    @PostMapping("/oauth/exchange")
    @Operation(
        summary = "Complete an authorization",
        description = "Redeem the code the front-end callback page received. The grant goes to the calling user and only if that same user started the state; the state is spent either way",
    )
    fun exchange(
        @Valid @RequestBody request: McpOAuthExchangeRequest,
    ): ResultVo<McpOAuthExchangeResponse> = try {
        // No MCP id in the path on purpose: the outstanding request named by the state already says
        // which server this consent was for, and taking an id here would let a captured state be
        // pointed at a server of the caller's choosing.
        // A refusal (unknown state, another session's state, the AS said no) is an answer with
        // authorized=false, not an HTTP error: the code is already spent and the page has to say why.
        ResultVo.success(mcpOAuthUserService.exchange(request))
    } catch (e: Exception) {
        log.error("Failed to exchange an OAuth code", e)
        ResultVo.error(ApiErrors.message(e, "Failed to complete the authorization"))
    }

    @GetMapping("/{id}/oauth/status")
    @Operation(
        summary = "Read this user's authorization",
        description = "Whether the current user has a usable grant on this MCP server, with its scopes and expiry; never with token material in the answer",
    )
    fun status(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<McpOAuthStatusResponse> = try {
        ResultVo.success(mcpOAuthUserService.status(id))
    } catch (e: Exception) {
        log.error("Failed to read the OAuth status, mcpId: {}", id, e)
        ResultVo.error(ApiErrors.message(e, "Failed to read the OAuth status"))
    }

    @PostMapping("/{id}/oauth/revoke")
    @Operation(
        summary = "Revoke this user's authorization",
        description = "Clear the stored credential and ask the authorization server to revoke it too where it offers RFC 7009 revocation",
    )
    fun revoke(
        @Parameter(description = "MCP ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<McpOAuthRevokeResponse> = try {
        ResultVo.success(mcpOAuthUserService.revoke(id))
    } catch (e: Exception) {
        log.error("Failed to revoke the OAuth authorization, mcpId: {}", id, e)
        ResultVo.error(ApiErrors.message(e, "Failed to revoke the OAuth authorization"))
    }
}
