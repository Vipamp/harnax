package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.McpOAuthClientRequest
import com.agnetix.harnax.admin.dto.McpOAuthConfig
import com.agnetix.harnax.admin.dto.McpOAuthDiscoveryResponse
import com.agnetix.harnax.admin.dto.McpServerResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpOAuthService
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.RemoteFetchException
import com.agnetix.harnax.admin.util.RemoteJsonFetcher
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.admin.util.normalizeIssuer
import com.agnetix.harnax.admin.util.optString
import com.agnetix.harnax.admin.util.optStringList
import com.agnetix.harnax.admin.util.redactUrl
import com.agnetix.harnax.entity.McpAuthTypes
import com.agnetix.harnax.entity.McpOauthClient
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.mapper.McpOauthClientMapper
import com.agnetix.harnax.mapper.McpServerMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.LocalDateTime

/**
 * OAuth authorization server discovery and client registration (design section 6.1).
 *
 * Everything here is setup an administrator does once per (tenant, authorization server), and all of
 * it fails loudly: a half-discovered server that still reads as "configured" is how a runtime later
 * sends an unauthenticated request on someone's behalf.
 */
@Service
class McpOAuthServiceImpl(
    private val jwtUtil: JwtUtil,
    private val mcpServerService: McpServerService,
    private val mcpServerMapper: McpServerMapper,
    private val mcpOauthClientMapper: McpOauthClientMapper,
    private val secretFieldEncryptor: SecretFieldEncryptor,
    private val remoteJsonFetcher: RemoteJsonFetcher,
    private val objectMapper: ObjectMapper,
    @Value("\${app.base-url:http://localhost:8080}")
    private val baseUrl: String,
    /**
     * Where the browser is. The OAuth `redirect_uri` has to be built from this and not from
     * [baseUrl], because the callback lands on a front-end route: behind the shipped nginx the two
     * are the same address, but in development the SPA is on :8000 while admin is on :8080, and a
     * `redirect_uri` pointing at admin would land on a port with no page to receive it.
     */
    @Value("\${app.frontend-base-url:}")
    private val frontendBaseUrl: String,
) : McpOAuthService {

    private val log = LoggerFactory.getLogger(McpOAuthServiceImpl::class.java)

    override fun discover(mcpId: Long): McpOAuthDiscoveryResponse {
        val server = requireOAuthServer(mcpId)
        val config = currentConfig(server)
        val issuer = resolveIssuer(server, config)
        val metadata = fetchMetadata(issuer.value, issuer.source != ISSUER_FROM_CONFIG)
        val client = storeEndpoints(server, issuer.value, metadata)
        if (issuer.source != ISSUER_FROM_CONFIG) {
            // Discovery found an issuer the row did not carry. Writing it back is what makes this
            // endpoint and the later authorize-url deterministic: they read one column instead of
            // repeating a network walk that could land somewhere else next time.
            rememberIssuer(server, config, issuer.value)
        }
        log.info("MCP {} discovered authorization server {} (from {})", mcpId, issuer.value, issuer.source)
        return toResponse(client, metadata.optStringList(SCOPES_SUPPORTED), config, issuer.source)
    }

    override fun saveClient(
        mcpId: Long,
        request: McpOAuthClientRequest,
    ): McpOAuthDiscoveryResponse {
        val server = requireOAuthServer(mcpId)
        val config = currentConfig(server)
        val issuer = normalizeIssuer(config.authorizationServer)
        if (issuer.isEmpty()) {
            throw BizException(
                "The authorization server of this MCP server is not known yet: run discovery first " +
                    "(POST /api/admin/mcp/$mcpId/oauth/discover), or set oauthConfig.authorizationServer",
            )
        }
        validateIssuerUrl(issuer)
        // Checked before anything is written: a request that cannot be completed should not leave a
        // half-populated registration behind.
        val clientId = request.clientId?.trim()
        if (clientId.isNullOrBlank()) {
            throw BizException("client_id cannot be empty")
        }
        val stored = mcpOauthClientMapper.selectByTenantAndIssuer(server.tenantId, issuer)
        // Endpoints are what the flow actually redirects to, so a client saved without a prior
        // discovery still needs them; once they are on the row they are reused without a new request.
        val needsMetadata = stored == null || stored.authorizationEndpoint.isNullOrBlank() || stored.tokenEndpoint.isNullOrBlank()
        val metadata = if (needsMetadata) fetchMetadata(issuer, false) else null
        val client = metadata?.let { storeEndpoints(server, issuer, it) } ?: stored!!

        client.clientId = clientId
        client.clientSecretEnc = secretFieldEncryptor.resolveSecret(request.clientSecret, client.clientSecretEnc)
        request.callbackUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { callback ->
            validateHttpUrl(callback, "Callback url", URL_MAX_LEN)
            client.callbackUrl = callback
        }
        client.registrationSource = REGISTRATION_SOURCE_MANUAL
        client.updateTime = LocalDateTime.now()
        mcpOauthClientMapper.updateById(client)
        log.info("MCP {} registered OAuth client {} for issuer {}", mcpId, clientId, issuer)
        return toResponse(client, client.scopesSupported.orEmpty().splitScopes(), config, ISSUER_FROM_CONFIG)
    }

    /**
     * Single-row access goes through [McpServerService.getMcpServer] on purpose: that is where the
     * tenant guard lives, and discovery hands out endpoints for a row the caller may not own.
     */
    private fun requireOAuthServer(mcpId: Long): McpServer {
        val server = mcpServerService.getMcpServer(mcpId)
            ?: throw BizException("MCP server not found")
        if (server.authType != McpAuthTypes.OAUTH2) {
            throw BizException("MCP server '${server.name}' has auth type ${server.authType}, OAuth discovery does not apply")
        }
        val url = server.url.trim()
        if (url.isEmpty()) {
            throw BizException("OAuth discovery needs the MCP server's url, and this one has none")
        }
        validateHttpUrl(url, "MCP server url", URL_MAX_LEN)
        return server
    }

    /**
     * An empty column is "no config yet". A column that will not parse is drift in data this service
     * serialized itself, and discovery rewrites that column: answering with defaults here would
     * overwrite scopes the administrator typed with an empty list, on their side of a
     * "discovery succeeded" response.
     */
    private fun currentConfig(server: McpServer): McpOAuthConfig {
        val stored = server.oauthConfig
        if (stored.isNullOrBlank()) return McpOAuthConfig()
        return McpServerResponse.parseOAuthConfig(stored, objectMapper)
            ?: throw BizException(
                "The oauth_config stored on MCP server '${server.name}' cannot be read, and discovery writes that " +
                    "column: fix it on the MCP form before running discovery",
            )
    }

    /**
     * The order section 6.1 lists: an issuer the admin typed wins; then the RFC 9728 protected
     * resource document; then the `resource_metadata` pointer inside the upstream's 401 challenge.
     */
    private fun resolveIssuer(
        server: McpServer,
        config: McpOAuthConfig,
    ): Found {
        normalizeIssuer(config.authorizationServer).takeIf { it.isNotEmpty() }?.let { issuer ->
            validateIssuerUrl(issuer)
            return Found(issuer, ISSUER_FROM_CONFIG)
        }
        val failures = mutableListOf<String>()
        val url = server.url
        protectedResourceUrls(url).forEach { candidate ->
            readAuthorizationServer(candidate, failures)?.let { return Found(it, ISSUER_FROM_PROTECTED_RESOURCE) }
        }
        challengeMetadataUrl(url, failures)?.let { pointer ->
            readAuthorizationServer(pointer, failures)?.let { return Found(it, ISSUER_FROM_CHALLENGE) }
        }
        throw BizException("Cannot locate the authorization server for ${redactUrl(url)}${detail(failures)}")
    }

    private fun readAuthorizationServer(
        metadataUrl: String,
        failures: MutableList<String>,
    ): String? {
        val shown = redactUrl(metadataUrl)
        val fetch = try {
            remoteJsonFetcher.fetch(metadataUrl)
        } catch (e: RemoteFetchException) {
            failures += e.message.orEmpty()
            return null
        }
        if (!fetch.ok) {
            failures += "$shown answered ${fetch.status}"
            return null
        }
        val servers = fetch.array("authorization_servers")
        if (servers.isEmpty()) {
            failures += "$shown lists no authorization_servers"
            return null
        }
        if (servers.size > 1) {
            log.info("Resource metadata {} lists {} authorization servers, using the first", shown, servers.size)
        }
        val issuer = normalizeIssuer(servers.first())
        // The document is untrusted input about where harnax should send users, so it has to clear the
        // same bar as a hand-typed value: no file://, no scheme-less host, and no credentials hidden
        // in a URL that will be fetched and then stored as an identity.
        return try {
            validateIssuerUrl(issuer, "Authorization server advertised by $shown")
            issuer
        } catch (e: BizException) {
            failures += e.message.orEmpty()
            null
        }
    }

    /**
     * Some servers advertise their authorization server only in the challenge they answer an
     * unauthenticated request with, so one GET on the MCP endpoint is worth sending before giving up.
     */
    private fun challengeMetadataUrl(
        url: String,
        failures: MutableList<String>,
    ): String? {
        val shown = redactUrl(url)
        val fetch = try {
            remoteJsonFetcher.fetch(url)
        } catch (e: RemoteFetchException) {
            failures += e.message.orEmpty()
            return null
        }
        val header = fetch.wwwAuthenticate
        if (header.isNullOrBlank()) {
            failures += "$shown answered ${fetch.status} with no WWW-Authenticate challenge"
            return null
        }
        val pointer = RESOURCE_METADATA_PATTERN.find(header)
            ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .orEmpty()
        if (pointer.isEmpty()) {
            failures += "the challenge of $shown does not carry resource_metadata"
            return null
        }
        return try {
            validateHttpUrl(pointer, "resource_metadata", URL_MAX_LEN)
            pointer
        } catch (e: BizException) {
            failures += e.message.orEmpty()
            null
        }
    }

    /**
     * RFC 8414 builds the metadata URL by inserting the well-known segment before the issuer's path,
     * so both orderings are tried; the appended one last because it is what servers that predate the
     * RFC, and most OIDC libraries, actually serve.
     */
    private fun fetchMetadata(
        issuer: String,
        issuerWasAdvertised: Boolean,
    ): JsonNode {
        val failures = mutableListOf<String>()
        val candidates = wellKnownUrls(issuer, OAUTH_METADATA_SEGMENT) + wellKnownUrls(issuer, OIDC_METADATA_SEGMENT)
        candidates.forEach { candidate ->
            readMetadata(candidate, issuer, issuerWasAdvertised, failures)?.let { return it }
        }
        throw BizException("No usable authorization server metadata for ${redactUrl(issuer)}${detail(failures)}")
    }

    /**
     * One metadata candidate, or null with the reason appended to [failures].
     */
    private fun readMetadata(
        candidate: String,
        issuer: String,
        issuerWasAdvertised: Boolean,
        failures: MutableList<String>,
    ): JsonNode? {
        val shown = redactUrl(candidate)
        val fetch = try {
            remoteJsonFetcher.fetch(candidate)
        } catch (e: RemoteFetchException) {
            failures += e.message.orEmpty()
            return null
        }
        if (!fetch.ok) {
            failures += "$shown answered ${fetch.status}"
            return null
        }
        val json = fetch.json
        if (json == null) {
            failures += "$shown is not a JSON object"
            return null
        }
        for (field in REQUIRED_ENDPOINT_FIELDS) {
            val endpoint = json.optString(field)
            if (endpoint == null) {
                failures += "$shown has no ${REQUIRED_ENDPOINT_FIELDS.joinToString("/")}"
                return null
            }
            // These two strings are what the flow redirects a browser to and what it posts a
            // client_secret to. A document that fills them with `javascript:` or with a value longer
            // than the column is not an answer, and storing it would make the snapshot the attack.
            try {
                validateHttpUrl(endpoint, "Authorization server metadata $field", URL_MAX_LEN)
            } catch (e: BizException) {
                failures += "${e.message}, advertised by $shown"
                return null
            }
        }
        val declared = json.optString("issuer")
        if (declared == null) {
            if (issuerWasAdvertised) {
                // Somebody else's document about somebody else's server. This is the case where the
                // issuer itself came from an untrusted document, so there is no admin who can vouch
                // for it. Naming the issuer by hand is the way out, and it says so.
                failures += "$shown declares no issuer, so nothing ties it to ${redactUrl(issuer)}: set oauthConfig.authorizationServer if this is the right server"
                return null
            }
        } else if (declared != issuer) {
            // RFC 8414 requires exact equality. A document naming another issuer is either a proxy
            // answering for someone else or drift, and storing it would make every token fail its own
            // issuer check in a way nobody traces back to this row.
            failures += "$shown declares issuer $declared, expected $issuer"
            return null
        }
        return json
    }

    /**
     * Store what discovery found, reusing the tenant's registration for this issuer. `client_id`,
     * secret and redirect_uri come from the loaded row: `updateById` writes every column, so not
     * carrying them over would unregister the client on the next discovery.
     */
    private fun storeEndpoints(
        server: McpServer,
        issuer: String,
        metadata: JsonNode,
    ): McpOauthClient {
        val now = LocalDateTime.now()
        val existing = mcpOauthClientMapper.selectByTenantAndIssuer(server.tenantId, issuer)
        if (existing != null) {
            metadata.applyTo(existing, issuer)
            existing.updateTime = now
            mcpOauthClientMapper.updateById(existing)
            return existing
        }
        // client_id stays empty until an admin registers one: this row's job is the endpoint
        // snapshot, and authorize-url has to refuse an unregistered client anyway.
        return McpOauthClient().apply {
            tenantId = server.tenantId
            this.issuer = issuer
            clientId = ""
            registrationSource = REGISTRATION_SOURCE_MANUAL
            callbackUrl = defaultCallbackUrl()
            creator = UserContextUtil.getCurrentUsername(jwtUtil)
            active = 1
            createTime = now
            updateTime = now
            metadata.applyTo(this, issuer)
        }.also { mcpOauthClientMapper.insert(it) }
    }

    private fun JsonNode.applyTo(
        client: McpOauthClient,
        issuer: String,
    ) {
        // Trimmed because validateHttpUrl measures the trimmed length against the column width: a value
        // padded with whitespace would clear validation and then fail the write.
        client.authorizationEndpoint = optString(AUTHORIZATION_ENDPOINT_FIELD)?.trim()
        client.tokenEndpoint = optString(TOKEN_ENDPOINT_FIELD)?.trim()
        // A null here means the authorization server stopped offering that endpoint, which is exactly
        // what has to be recorded; see the mapper's note on its unconditional SET list.
        client.registrationEndpoint = optionalEndpoint(REGISTRATION_ENDPOINT_FIELD, issuer)
        client.revocationEndpoint = optionalEndpoint(REVOCATION_ENDPOINT_FIELD, issuer)
        client.scopesSupported = optStringList(SCOPES_SUPPORTED).joinToString(",").takeIf { it.isNotEmpty() }
    }

    /**
     * An optional endpoint the AS does not offer is simply absent. One it offers at a value that is
     * not a usable URL is dropped rather than stored: dynamic registration (P4) and revocation (P2-3)
     * both post credentials to whatever is on this row, and a malformed value there is either a
     * request that cannot be made or a request to the wrong place.
     */
    private fun JsonNode.optionalEndpoint(
        field: String,
        issuer: String,
    ): String? {
        val value = optString(field) ?: return null
        return try {
            validateHttpUrl(value, "Authorization server metadata $field", URL_MAX_LEN)
            value.trim()
        } catch (e: BizException) {
            log.warn("Authorization server {} advertises an unusable {}: {}", issuer, field, e.message)
            null
        }
    }

    private fun rememberIssuer(
        server: McpServer,
        config: McpOAuthConfig,
        issuer: String,
    ) {
        mcpServerMapper.updateOAuthConfig(server.id, objectMapper.writeValueAsString(config.copy(authorizationServer = issuer)))
    }

    private fun toResponse(
        client: McpOauthClient,
        scopesSupported: List<String>,
        config: McpOAuthConfig,
        issuerSource: String,
    ): McpOAuthDiscoveryResponse = McpOAuthDiscoveryResponse(
        issuer = client.issuer,
        issuerSource = issuerSource,
        authorizationEndpoint = client.authorizationEndpoint,
        tokenEndpoint = client.tokenEndpoint,
        registrationEndpoint = client.registrationEndpoint,
        revocationEndpoint = client.revocationEndpoint,
        scopesSupported = scopesSupported,
        // A typo in the requested scopes otherwise surfaces as a 400 after a user has already been
        // sent to log in, so report it here where fixing it is free.
        unknownScopes = if (scopesSupported.isEmpty()) emptyList() else config.scopes.filter { it !in scopesSupported },
        clientId = client.clientId.takeIf { it.isNotBlank() },
        clientSecretPresent = !client.clientSecretEnc.isNullOrBlank(),
        callbackUrl = client.callbackUrl,
        defaultCallbackUrl = defaultCallbackUrl(),
    )

    private fun defaultCallbackUrl(): String = "${frontendBaseUrl.ifBlank { baseUrl }.trimEnd('/')}$CALLBACK_PATH"

    /**
     * The bar every URL this feature stores or redirects to has to clear.
     *
     * [maxLen] is the width of the column the value is going into. MySQL answers an over-long value
     * with a 1406 the administrator cannot act on, and with strict mode off it truncates - of an
     * identity column, which would leave a registration no token ever matches again.
     */
    private fun validateHttpUrl(
        value: String,
        what: String,
        maxLen: Int,
    ) {
        val uri = try {
            URI.create(value.trim())
        } catch (e: Exception) {
            throw BizException("$what is not a valid URL: ${redactUrl(value)}")
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            throw BizException("$what must be an http(s) URL: ${redactUrl(value)}")
        }
        if (value.trim().length > maxLen) {
            throw BizException("$what is longer than the $maxLen characters it can be stored in")
        }
    }

    /**
     * The issuer is not just a place to send a request, it is the identity of a stored registration:
     * it is compared byte for byte against a token's `iss` and against a metadata document. So the URL
     * parts that are legal but have no place in an issuer identifier are refused instead of being
     * quietly dropped: userinfo would be fetched with and then sit in a column, and a query or
     * fragment is not carried into the metadata URL, which would leave the stored value unlike
     * anything the server ever confirmed.
     */
    private fun validateIssuerUrl(
        value: String,
        what: String = "Authorization server",
    ) {
        validateHttpUrl(value, what, ISSUER_MAX_LEN)
        val uri = URI.create(value.trim())
        if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            throw BizException("$what must be an http(s) URL without userinfo, query or fragment: ${redactUrl(value)}")
        }
    }

    /**
     * The reasons collected on the way, appended to an error that goes out in an API response. Bounded,
     * because each one can carry an upstream message of its own.
     */
    private fun detail(failures: List<String>): String = if (failures.isEmpty()) "" else ": ${failures.joinToString("; ").take(MAX_DETAIL_CHARS)}"

    /** Path-scoped RFC 9728 document first, then the appended form, then the host root. */
    private fun protectedResourceUrls(url: String): List<String> = (wellKnownUrls(url, PROTECTED_RESOURCE_SEGMENT) + listOf(originOf(url) + "/" + PROTECTED_RESOURCE_SEGMENT)).distinct()

    /**
     * `<origin>/<segment><path>` per RFC 8414 / RFC 9728, then `<origin><path>/<segment>`.
     */
    private fun wellKnownUrls(
        base: String,
        segment: String,
    ): List<String> {
        val uri = URI.create(base.trim())
        val origin = originOf(uri)
        val path = (uri.rawPath ?: "").trimEnd('/')
        return listOf("$origin/$segment$path", "$origin$path/$segment").distinct()
    }

    private fun originOf(url: String): String = originOf(URI.create(url.trim()))

    private fun originOf(uri: URI): String {
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://${uri.host}$port"
    }

    private fun String.splitScopes(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private data class Found(
        val value: String,
        val source: String,
    )

    companion object {
        private const val CALLBACK_PATH = "/mcp/oauth/callback"
        private const val REGISTRATION_SOURCE_MANUAL = "MANUAL"
        private const val ISSUER_FROM_CONFIG = "CONFIG"
        private const val ISSUER_FROM_PROTECTED_RESOURCE = "PROTECTED_RESOURCE"
        private const val ISSUER_FROM_CHALLENGE = "RESOURCE_METADATA"
        private const val SCOPES_SUPPORTED = "scopes_supported"
        private const val OAUTH_METADATA_SEGMENT = ".well-known/oauth-authorization-server"
        private const val OIDC_METADATA_SEGMENT = ".well-known/openid-configuration"
        private const val PROTECTED_RESOURCE_SEGMENT = ".well-known/oauth-protected-resource"
        private const val AUTHORIZATION_ENDPOINT_FIELD = "authorization_endpoint"
        private const val TOKEN_ENDPOINT_FIELD = "token_endpoint"
        private const val REGISTRATION_ENDPOINT_FIELD = "registration_endpoint"
        private const val REVOCATION_ENDPOINT_FIELD = "revocation_endpoint"
        private val REQUIRED_ENDPOINT_FIELDS = listOf(AUTHORIZATION_ENDPOINT_FIELD, TOKEN_ENDPOINT_FIELD)

        /** Widths from V26: `mcp_oauth_client.issuer` is 255, every URL column on either table is 500. */
        private const val ISSUER_MAX_LEN = 255
        private const val URL_MAX_LEN = 500
        private const val MAX_DETAIL_CHARS = 600

        /**
         * RFC 9728 / RFC 6750 parameter inside `WWW-Authenticate: Bearer resource_metadata="..."`. The
         * quotes are the convention, not a requirement, so both forms are read.
         */
        private val RESOURCE_METADATA_PATTERN = """resource_metadata="([^"]*)"|resource_metadata=([^\s,]+)""".toRegex()
    }
}
