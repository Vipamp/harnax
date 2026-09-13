package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.McpOAuthAuthorizeResponse
import com.agnetix.harnax.admin.dto.McpOAuthConfig
import com.agnetix.harnax.admin.dto.McpOAuthExchangeRequest
import com.agnetix.harnax.admin.dto.McpOAuthExchangeResponse
import com.agnetix.harnax.admin.dto.McpOAuthRevokeResponse
import com.agnetix.harnax.admin.dto.McpOAuthStatusResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpOAuthUserService
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.ApiErrors
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.McpOAuthStateStore
import com.agnetix.harnax.admin.util.McpSessionOwnerResolver
import com.agnetix.harnax.admin.util.PendingAuthorization
import com.agnetix.harnax.admin.util.RemoteFetch
import com.agnetix.harnax.admin.util.RemoteJsonFetcher
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.admin.util.normalizeIssuer
import com.agnetix.harnax.admin.util.optString
import com.agnetix.harnax.entity.McpAuthTypes
import com.agnetix.harnax.entity.McpCallLog
import com.agnetix.harnax.entity.McpOauthClient
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.McpUserCredential
import com.agnetix.harnax.entity.dto.McpAccessTokenResponse
import com.agnetix.harnax.mapper.McpCallLogMapper
import com.agnetix.harnax.mapper.McpOauthClientMapper
import com.agnetix.harnax.mapper.McpServerMapper
import com.agnetix.harnax.mapper.McpUserCredentialMapper
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock

/**
 * The authorization code flow with PKCE, per user and per MCP server (design sections 6.2 and 6.4).
 *
 * Three things decide the shape of this class:
 *
 * - The authorization server redirects the browser to a **front-end** page, and that page calls
 *   [exchange] with its own credential. So the user a grant belongs to is the caller, while the
 *   pending state only names which outstanding request a code answers - presenting someone else's
 *   state is a refusal, not an identity to adopt.
 * - A code is single-use and short-lived, so `state` and `code_verifier` live in memory
 *   ([McpOAuthStateStore]) and are consumed before the exchange is attempted.
 * - What comes back is someone else's token, held for one named user. It is encrypted at rest,
 *   checked against the issuer this feature discovered, and never written to a log line or to a
 *   response DTO.
 */
@Service
class McpOAuthUserServiceImpl(
    private val jwtUtil: JwtUtil,
    private val mcpServerService: McpServerService,
    private val mcpOauthClientMapper: McpOauthClientMapper,
    private val mcpUserCredentialMapper: McpUserCredentialMapper,
    private val aesUtil: AesUtil,
    private val remoteJsonFetcher: RemoteJsonFetcher,
    private val stateStore: McpOAuthStateStore,
    private val objectMapper: ObjectMapper,
    private val mcpServerMapper: McpServerMapper,
    private val mcpCallLogMapper: McpCallLogMapper,
    private val sessionOwnerResolver: McpSessionOwnerResolver,
) : McpOAuthUserService {

    private val log = LoggerFactory.getLogger(McpOAuthUserServiceImpl::class.java)

    private val random = SecureRandom()

    /**
     * One lock per stripe rather than one per credential: refresh tokens rotate, so two simultaneous
     * refreshes of the same grant make the loser's answer dead and look like a user who needs to
     * consent again. A fixed set of stripes serialises that without a map growing per (user, server).
     */
    private val refreshLocks = Array(LOCK_STRIPES) { ReentrantLock() }

    override fun authorizeUrl(
        mcpId: Long,
        scope: String?,
    ): McpOAuthAuthorizeResponse {
        val userId = currentUserId()
        val server = requireOAuthServer(mcpId)
        val config = readConfig(server)
        val client = requireClientRegistration(server, config)
        val authorizationEndpoint = client.authorizationEndpoint?.trim().orEmpty()
        if (authorizationEndpoint.isEmpty()) {
            throw BizException(
                "This authorization server has no recorded authorization endpoint: run discovery again " +
                    "(POST /api/admin/mcp/$mcpId/oauth/discover)",
            )
        }
        val redirectUri = client.callbackUrl.trim().takeIf { it.isNotEmpty() }
            ?: throw BizException(
                "This client registration has no redirect_uri on file: save the OAuth client again " +
                    "(POST /api/admin/mcp/$mcpId/oauth/client)",
            )
        val scopes = (scope?.takeIf { it.isNotBlank() }?.splitScopeList() ?: config.scopes).distinct()
        // What is requested here is what gets stored in mcp_user_credential.scopes, a VARCHAR(512).
        // Refused rather than truncated: a cut-off list would record a grant nobody asked for, and the
        // caller can narrow the parameter.
        if (scopes.joinToString(",").length > SCOPES_MAX_LEN) {
            throw BizException(
                "The requested scopes are too wide to record (over $SCOPES_MAX_LEN characters), " +
                    "please narrow the scope parameter",
            )
        }
        warnOnUnknownScopes(scopes, client.scopesSupported, mcpId)

        // RFC 8707 binds the token to this MCP server, which is what stops a token minted for one
        // server from being replayed at another. Off only for authorization servers that reject the
        // parameter, per oauthConfig.resourceIndicator.
        val resource = server.url.trim().takeIf { config.resourceIndicator && it.isNotEmpty() }
        // Checked before any material is minted: the shared store has a global cap, and without a
        // per-user one in front of it a single caller could fill that cap and leave everyone else
        // unable to start an authorization until the entries age out.
        if (stateStore.countFor(userId) >= McpOAuthStateStore.MAX_PER_USER) {
            throw BizException(
                "You already have ${McpOAuthStateStore.MAX_PER_USER} authorizations in flight; finish one " +
                    "or wait for them to expire (up to ${McpOAuthStateStore.TTL.toMinutes()} minutes)",
            )
        }
        val verifier = urlToken(VERIFIER_BYTES)
        val state = urlToken(STATE_BYTES)
        val pending = PendingAuthorization(
            tenantId = server.tenantId,
            userId = userId,
            mcpId = server.id,
            issuer = client.issuer,
            codeVerifier = verifier,
            redirectUri = redirectUri,
            resource = resource,
            requestedScopes = scopes,
            expiresAtNanos = McpOAuthStateStore.newExpiresAtNanos(),
        )
        if (!stateStore.put(state, pending)) {
            throw BizException("Too many authorizations are in flight right now, please try again in a moment")
        }
        val params = mutableListOf(
            "response_type" to "code",
            "client_id" to client.clientId,
            "redirect_uri" to redirectUri,
            "state" to state,
            "code_challenge" to challengeOf(verifier),
            "code_challenge_method" to "S256",
        )
        scopes.takeIf { it.isNotEmpty() }?.let { params.add("scope" to it.joinToString(" ")) }
        resource?.let { params.add("resource" to it) }
        config.audience?.trim()?.takeIf { it.isNotEmpty() }?.let { params.add("audience" to it) }

        log.info("MCP {} built an authorization URL for user {} at {}", mcpId, userId, client.issuer)
        return McpOAuthAuthorizeResponse(
            authorizeUrl = appendQuery(authorizationEndpoint, params),
            issuer = client.issuer,
            scopes = scopes,
            expiresIn = McpOAuthStateStore.TTL.seconds,
        )
    }

    override fun exchange(request: McpOAuthExchangeRequest): McpOAuthExchangeResponse {
        val userId = currentUserId()
        // The state is burned either way: a flow the authorization server refused is over, and a
        // state that survived one refusal is a token-minting request anyone can retry.
        val pending = stateStore.consume(request.state.orEmpty())
        // Checked before anything the request body says. Otherwise a caller who has learned someone
        // else's state could cancel that person's in-flight authorization by posting an `error` of
        // their own, and the refusal below - the detector for consent phishing - would stay silent
        // about it. The state no longer decides who this is: the caller's own credential does, so a
        // state belonging to someone else is an event to report rather than an identity to adopt.
        // Handing out an authorize-url for someone else to consent into this account has exactly this
        // shape.
        if (pending != null && pending.userId != userId) {
            log.warn(
                "MCP {} authorization state started by user {} was presented by user {}; refused and burned",
                pending.mcpId,
                pending.userId,
                userId,
            )
            return McpOAuthExchangeResponse(
                false,
                "This authorization was started in another session, so it is not applied to your account. Please start the authorization again",
            )
        }
        // Answered even when the state has aged out: nothing is stored and nothing is left to burn,
        // and the authorization server's own reason is worth more to the page than "unknown state".
        request.error?.trim()?.takeIf { it.isNotEmpty() }?.let { refused ->
            val described = listOf(refused, request.errorDescription?.trim().orEmpty())
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .take(MAX_ANSWER_CHARS)
            return McpOAuthExchangeResponse(false, "The authorization server refused: $described. Please start the authorization again")
        }
        if (pending == null) {
            return McpOAuthExchangeResponse(false, "This authorization request is unknown or has expired. Please start the authorization again")
        }
        val code = request.code?.trim().orEmpty()
        if (code.isEmpty()) {
            return McpOAuthExchangeResponse(false, "The authorization server returned no code. Please start the authorization again")
        }
        return try {
            val stored = redeem(pending, code)
            McpOAuthExchangeResponse(
                authorized = true,
                message = "This MCP server is authorized for your account",
                scopes = stored.scopes,
                accessExpiresAt = stored.accessExpiresAt,
            )
        } catch (e: BizException) {
            McpOAuthExchangeResponse(false, "${e.message?.trimEnd('.')} - please start the authorization again")
        } catch (e: Exception) {
            log.error("Failed to exchange the OAuth code for MCP {}", pending.mcpId, e)
            // ApiErrors is the policy the JSON endpoints already use: a database error never reaches
            // the browser in its own words, and an over-long upstream answer is trimmed.
            McpOAuthExchangeResponse(false, "${ApiErrors.message(e, "Authorization failed")} - please start the authorization again")
        }
    }

    override fun status(mcpId: Long): McpOAuthStatusResponse {
        val userId = currentUserId()
        val server = requireOAuthServer(mcpId)
        val credential = mcpUserCredentialMapper.selectByUserAndMcp(server.tenantId, userId, server.id)
            ?: return McpOAuthStatusResponse(authorized = false)
        val expiresAt = credential.accessExpiresAt
        // Until refresh exists (design P2-4) an expired access token is not usable, so the page has
        // to say so rather than show a grant the runtime cannot spend.
        val usable = credential.status == McpUserCredential.STATUS_ACTIVE &&
            (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()))
        return McpOAuthStatusResponse(
            authorized = usable,
            status = credential.status,
            scopes = credential.scopes.splitScopes(),
            accessExpiresAt = expiresAt,
            lastRefreshedAt = credential.lastRefreshedAt,
            lastError = credential.lastError,
        )
    }

    override fun revoke(mcpId: Long): McpOAuthRevokeResponse {
        val userId = currentUserId()
        val server = requireOAuthServer(mcpId)
        val credential = mcpUserCredentialMapper.selectByUserAndMcp(server.tenantId, userId, server.id)
            ?: return McpOAuthRevokeResponse(
                revoked = true,
                upstreamRevoked = false,
                message = "You have no authorization on this MCP server, so there was nothing to revoke",
            )
        val client = registrationOf(server)
        val endpoint = client?.revocationEndpoint?.trim().orEmpty()
        // RFC 7009 lets either token be presented; the refresh token is the one worth killing, because
        // an access token dies of age within minutes while a refresh token keeps minting new ones, and
        // a compliant server revoking the refresh token kills the grant as a whole.
        // A ciphertext that will not open - the AES key was rotated, or the row was written by hand -
        // must not strand the grant: this call is the user's only way out, and the local row is the
        // half this deployment controls. Such a token is treated as absent, which is exactly what it
        // is here.
        val storedNothing = credential.accessTokenEnc == null && credential.refreshTokenEnc == null
        val toRevoke: Pair<String, String>? =
            credential.refreshTokenEnc?.let { openToken(it, mcpId) }?.let { it to "refresh_token" }
                ?: credential.accessTokenEnc?.let { openToken(it, mcpId) }?.let { it to "access_token" }
        val upstreamRevoked = client != null &&
            endpoint.isNotEmpty() &&
            toRevoke != null &&
            revokeUpstream(endpoint, client, toRevoke.first, toRevoke.second)
        clearLocally(credential)
        log.info("MCP {} authorization of user {} revoked (upstream revoked: {})", mcpId, userId, upstreamRevoked)
        return McpOAuthRevokeResponse(
            revoked = true,
            upstreamRevoked = upstreamRevoked,
            message = when {
                client == null ->
                    "The local copy is cleared. Which authorization server this grant came from can no longer be " +
                        "resolved from the server's settings, so nothing could be presented upstream - run discovery " +
                        "again (POST /api/admin/mcp/$mcpId/oauth/discover) to restore that link"

                endpoint.isEmpty() ->
                    "The authorization here is cleared. This authorization server publishes no revocation endpoint " +
                        "(RFC 7009), so its own copy stays valid until it expires"

                toRevoke == null && !storedNothing ->
                    "The local copy is cleared. The stored token could not be decrypted - the encryption key has " +
                        "changed since it was written - so it could not be presented upstream"

                toRevoke == null ->
                    "The local copy is cleared; there was no stored token left to present upstream"

                upstreamRevoked ->
                    "The authorization server accepted the revocation and the local copy is cleared"

                else ->
                    "The local copy is cleared, but the authorization server did not accept the revocation - " +
                        "its own copy stays usable until it expires"
            },
        )
    }

    /**
     * The runtime's view of a grant: hand out what is still usable, refresh what is not.
     *
     * Reached only through the internal API, and the session id is the whole request - which is the
     * point. agent-service cannot ask for somebody else's token by naming a user, because it does not
     * know any user ids: this resolves the session back to its owner the same way the agent-spec
     * endpoint does, and answers for that person only.
     */
    override fun accessToken(
        sessionId: String,
        mcpId: Long,
    ): McpAccessTokenResponse {
        val started = System.nanoTime()
        // Read by id through the mapper rather than through McpServerService: that guard compares the
        // row against the *request's* tenant header, and an internal call from agent-service has none.
        // What scopes this answer is the server's own tenant, which is also the key the grant was
        // stored under by [redeem].
        val server = mcpServerMapper.selectById(mcpId) ?: throw BizException("MCP server not found")
        if (server.authType != McpAuthTypes.OAUTH2) {
            throw BizException(
                "MCP server '${server.name}' has auth type ${server.authType}, so there is no per-user token to issue",
            )
        }
        val owner = sessionOwnerResolver.resolve(sessionId)
            ?: throw needsConsent(server, null, null, sessionId, started, "This session has no user identity to authorize as")
        // Delivery only ever sends an agent servers from its own tenant, so this should be unreachable.
        // It stays checked here because the credential below is keyed by the *server's* tenant: without
        // the guard, a session in tenant B could spend tenant A's grant for a server it was never given.
        if (owner.tenantId != server.tenantId) {
            throw BizException(403, "MCP server '${server.name}' belongs to another tenant than session $sessionId")
        }
        val credential = mcpUserCredentialMapper.selectByUserAndMcp(server.tenantId, owner.userId, server.id)
            ?: throw needsConsent(server, null, owner.userId, sessionId, started, "This MCP server is not authorized for your account yet")
        if (credential.status == McpUserCredential.STATUS_REVOKED) {
            // Its tokens were cleared by `revoke`, so there is nothing to renew - but the status stays
            // REVOKED. Overwriting it with NEEDS_CONSENT would erase the one thing this row still
            // records: that this person revoked this grant on purpose.
            audit(server, owner.userId, sessionId, McpCallLog.ACTION_ISSUE, McpCallLog.OUTCOME_NEEDS_CONSENT, started)
            throw BizException(401, "You revoked this MCP server's authorization for your account - please authorize it again")
        }
        usableToken(server, credential)?.let {
            audit(server, owner.userId, sessionId, McpCallLog.ACTION_ISSUE, McpCallLog.OUTCOME_OK, started)
            return it
        }
        return refreshGrant(server, credential, owner.userId, sessionId, started)
    }

    /** The stored access token while it still has life in it, or null when a refresh is due. */
    private fun usableToken(
        server: McpServer,
        credential: McpUserCredential,
    ): McpAccessTokenResponse? {
        if (credential.status != McpUserCredential.STATUS_ACTIVE) {
            return null
        }
        val ciphertext = credential.accessTokenEnc ?: return null
        val expiresAt = credential.accessExpiresAt
        // Refresh before the expiry rather than after: a tool call that fails because a token aged out
        // one second into a long run reads as a broken MCP server, and the user has no way to tell
        // that from an outage. A row with no expiry keeps its token until something refuses it.
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now().plusSeconds(REFRESH_SKEW_SECONDS))) {
            return null
        }
        // Undecryptable (the AES key moved since this was written) is the same as absent here; the
        // refresh path reports it as a lost grant rather than sending garbage upstream.
        return openToken(ciphertext, server.id)?.let {
            McpAccessTokenResponse(accessToken = it, expiresAtEpochSecond = toEpochSecond(expiresAt))
        }
    }

    /**
     * The expiry as the wire carries it: epoch seconds, not a date-time. Every comparison inside admin
     * is in local terms, but the caller is another process that reads this against its own clock and
     * is not guaranteed to share this one's time zone.
     */
    private fun toEpochSecond(expiresAt: LocalDateTime?): Long? = expiresAt?.atZone(ZoneId.systemDefault())?.toEpochSecond()

    /**
     * Spend the refresh token, store what comes back, and hand the new access token over.
     *
     * What the answer can say about the grant falls into two groups, and telling them apart is the
     * whole design of this method: `invalid_grant` means the authorization is gone and only the user
     * can get it back, while a 5xx or an unreachable server means nothing about the grant changed and
     * retrying is enough. Marking the second kind as consent-needed would push users through an
     * authorization page because of a network blip, and clearing a working grant to do it would be
     * worse than the outage.
     */
    private fun refreshGrant(
        server: McpServer,
        credential: McpUserCredential,
        userId: Long,
        sessionId: String,
        started: Long,
    ): McpAccessTokenResponse {
        val lock = refreshLocks[(credential.id % LOCK_STRIPES).toInt().let { if (it < 0) it + LOCK_STRIPES else it }]
        lock.lock()
        try {
            // Re-read under the lock: whoever held it a moment ago may have refreshed this very grant,
            // and doing it twice would burn the rotation that call just stored.
            val current = mcpUserCredentialMapper.selectByUserAndMcp(server.tenantId, userId, server.id) ?: credential
            usableToken(server, current)?.let {
                audit(server, userId, sessionId, McpCallLog.ACTION_ISSUE, McpCallLog.OUTCOME_OK, started)
                return it
            }
            val refreshToken = current.refreshTokenEnc?.let { openToken(it, server.id) }
                ?: throw needsConsent(
                    server,
                    current,
                    userId,
                    sessionId,
                    started,
                    if (current.refreshTokenEnc == null) {
                        "This grant has no refresh token, so it cannot be renewed silently"
                    } else {
                        "The stored refresh token cannot be decrypted with the current key"
                    },
                )
            val config = readConfig(server)
            val issuer = normalizeIssuer(config.authorizationServer)
            val client = mcpOauthClientMapper.selectByTenantAndIssuer(server.tenantId, issuer)
                ?: throw BizException("No client registration exists for $issuer: run discovery again")
            if (client.clientId.isBlank()) {
                throw BizException("No client_id is registered for $issuer: save the OAuth client again")
            }
            val tokenEndpoint = client.tokenEndpoint?.trim().orEmpty()
            if (tokenEndpoint.isEmpty()) {
                throw BizException("The token endpoint of this authorization server is unknown; run discovery again")
            }
            val resource = server.url.trim().takeIf { config.resourceIndicator && it.isNotEmpty() }
            val form = mutableMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to client.clientId,
            )
            client.clientSecretEnc?.let { enc ->
                // Undecryptable means this deployment's key moved, not that the user's consent lapsed:
                // said here rather than reaching the token endpoint without a secret and coming back as
                // `invalid_client`, which reads like a broken registration.
                form["client_secret"] = openToken(enc, client.id, "OAuth client")
                    ?: throw transientFailure(
                        server,
                        current,
                        userId,
                        sessionId,
                        started,
                        "the stored client secret cannot be decrypted with the current key; re-save the OAuth client",
                        null,
                    )
            }
            // RFC 8707 §2.3: a refreshed token has to be re-bound to the resource, or the AS may hand
            // back one whose audience is whatever it defaults to - which the MCP server then refuses.
            resource?.let { form["resource"] = it }

            val response = try {
                remoteJsonFetcher.postForm(tokenEndpoint, form)
            } catch (e: Exception) {
                throw transientFailure(server, current, userId, sessionId, started, "the authorization server could not be reached (${e.javaClass.simpleName})", e)
            }
            if (!response.ok) {
                val error = response.json?.optString("error")?.takeIf { it.isNotBlank() }
                if (error == "invalid_grant" || (error == null && response.status in 400..499)) {
                    throw needsConsent(
                        server,
                        current,
                        userId,
                        sessionId,
                        started,
                        "The authorization server refused to renew this grant${error?.let { " ($it)" } ?: ""} (HTTP ${response.status})",
                    )
                }
                // invalid_client/unauthorized_client is this deployment's registration, not the user's
                // consent; a 5xx is the server's problem. Neither may touch the stored grant.
                throw transientFailure(
                    server,
                    current,
                    userId,
                    sessionId,
                    started,
                    "the token endpoint refused the refresh with HTTP ${response.status}${error?.let { ": $it" } ?: ""}",
                    null,
                )
            }
            val json = try {
                response.answerOrThrow("The token refresh")
            } catch (e: BizException) {
                // A 200 that is not a token answer says nothing about the grant, so it is a failure to
                // retry rather than a reason to drop the user's consent.
                throw transientFailure(server, current, userId, sessionId, started, e.message ?: "the token refresh failed", e)
            }
            val accessToken = json.optString(ACCESS_TOKEN_FIELD)
                ?: throw transientFailure(server, current, userId, sessionId, started, "the authorization server answered without an access_token", null)
            val rotated = json.optString(REFRESH_TOKEN_FIELD)
            val grantedScopes = json.optString("scope")?.splitScopeList().orEmpty()
            try {
                validateAgainstIssuer(json, accessToken, issuer, resource, server.id)
            } catch (e: BizException) {
                // Refused here rather than stored: a token for another issuer or another resource would
                // fail at the MCP server with an error nobody can trace back to this row.
                throw transientFailure(server, current, userId, sessionId, started, e.message ?: "the refreshed token does not match this server", e)
            }

            val now = LocalDateTime.now()
            // An answer without `expires_in` (RFC 6749 §5.1 makes it optional) keeps the lifetime the
            // previous token had, as long as that is still ahead: nothing here learns that the MCP
            // server refused a token, so a row with no expiry at all reads as "valid forever" and the
            // runtime would keep presenting a dead one.
            val expiresAt = json.path(EXPIRES_IN_FIELD).takeIf { it.isNumber }?.asInt()?.let { now.plusSeconds(it.toLong()) }
                ?: current.accessExpiresAt?.takeIf { it.isAfter(now) }
            current.accessTokenEnc = aesUtil.encrypt(accessToken)
            // Only replaced when the answer carries one: RFC 6749 §6 lets an AS issue no new refresh
            // token, and dropping the stored one then would end a grant that is still good.
            rotated?.let { current.refreshTokenEnc = aesUtil.encrypt(it) }
            current.accessExpiresAt = expiresAt
            val refreshedScopes = grantedScopes.ifEmpty { null }?.joinToString(",")
            if (refreshedScopes != null && refreshedScopes.length > SCOPES_MAX_LEN) {
                // Same rule as the exchange: a truncated list would read as "these are what you were
                // granted" with a fragment at the end, so the previous value is kept and said so.
                log.warn(
                    "MCP {} refresh granted {} characters of scope, over {}, so the recorded scopes are unchanged",
                    server.id,
                    refreshedScopes.length,
                    SCOPES_MAX_LEN,
                )
            }
            current.scopes = refreshedScopes?.takeIf { it.length <= SCOPES_MAX_LEN } ?: current.scopes
            current.status = McpUserCredential.STATUS_ACTIVE
            current.lastError = null
            current.lastRefreshedAt = now
            current.updateTime = now
            mcpUserCredentialMapper.updateById(current)
            log.info(
                "MCP {} refreshed the OAuth grant of user {} (expiresIn={}, rotatedRefreshToken={})",
                server.id,
                userId,
                json.path(EXPIRES_IN_FIELD).takeIf { it.isNumber }?.asInt() ?: -1,
                rotated != null,
            )
            audit(server, userId, sessionId, McpCallLog.ACTION_REFRESH, McpCallLog.OUTCOME_OK, started)
            return McpAccessTokenResponse(accessToken = accessToken, expiresAtEpochSecond = toEpochSecond(expiresAt))
        } finally {
            lock.unlock()
        }
    }

    /** The grant is gone and only the user can bring it back: record that, then refuse. */
    private fun needsConsent(
        server: McpServer,
        credential: McpUserCredential?,
        userId: Long?,
        sessionId: String,
        started: Long,
        reason: String,
    ): BizException {
        // The ciphertexts stay: `revoke` is the user's way of also telling the authorization server,
        // and it needs what is stored to do that. Status is what makes the grant unusable.
        credential?.let { markStatus(it, McpUserCredential.STATUS_NEEDS_CONSENT, reason) }
        audit(server, userId, sessionId, McpCallLog.ACTION_ISSUE, McpCallLog.OUTCOME_NEEDS_CONSENT, started)
        return BizException(401, "$reason - please authorize this MCP server again")
    }

    /** Nothing about the grant changed; this is a failure to retry, not one to re-authorize. */
    private fun transientFailure(
        server: McpServer,
        credential: McpUserCredential,
        userId: Long,
        sessionId: String,
        started: Long,
        reason: String,
        cause: Throwable?,
    ): BizException {
        markStatus(credential, credential.status, reason)
        audit(server, userId, sessionId, McpCallLog.ACTION_REFRESH, McpCallLog.OUTCOME_ERROR, started)
        // The cause is logged, not chained: BizException has no (code, message, cause) constructor, and
        // the stack behind a 503 is admin's own detail rather than something the caller can act on.
        log.warn("MCP {} grant of user {} could not be renewed: {}", server.id, userId, reason, cause)
        return BizException(503, "MCP server '${server.name}' is unavailable right now: $reason")
    }

    private fun markStatus(
        credential: McpUserCredential,
        status: String,
        reason: String,
    ) {
        try {
            credential.status = status
            credential.lastError = reason.take(MAX_ERROR_CHARS)
            credential.updateTime = LocalDateTime.now()
            mcpUserCredentialMapper.updateById(credential)
        } catch (e: Exception) {
            // A row that cannot be written must not hide the answer the caller needs; the next attempt
            // reads whatever status is still stored and reaches the same conclusion.
            log.warn("The status of MCP credential {} could not be updated: {}", credential.id, e.javaClass.simpleName)
        }
    }

    /**
     * One audit row per token decision (design section 7.2). Outcome and latency only - never a token,
     * a scope list or a response body, because admins who are not the grant's owner read this table.
     * Failing to write it must not fail the call.
     */
    private fun audit(
        server: McpServer,
        userId: Long?,
        sessionId: String,
        action: String,
        outcome: String,
        started: Long,
    ) {
        try {
            mcpCallLogMapper.insert(
                McpCallLog().apply {
                    tenantId = server.tenantId
                    this.userId = userId
                    mcpId = server.id
                    this.sessionId = sessionId
                    toolName = null
                    this.action = action
                    this.outcome = outcome
                    latencyMs = (System.nanoTime() - started) / 1_000_000
                    createTime = LocalDateTime.now()
                },
            )
        } catch (e: Exception) {
            log.warn("The MCP call audit row for server {} could not be written: {}", server.id, e.javaClass.simpleName)
        }
    }

    /** The plaintext of a stored token, or null when it cannot be opened. Never logs material. */
    private fun openToken(
        ciphertext: String,
        /** `mcp_server.id` for a grant, `mcp_oauth_client.id` for a registration. */
        rowId: Long,
        what: String = "MCP",
    ): String? = try {
        aesUtil.decrypt(ciphertext)
    } catch (e: Exception) {
        log.warn("The stored credential of {} {} could not be decrypted with the current key: {}", what, rowId, e.javaClass.simpleName)
        null
    }

    /**
     * Redeem the code and store what comes back.
     */
    private fun redeem(
        pending: PendingAuthorization,
        code: String,
    ): StoredGrant {
        // Read through `McpServerService.getMcpServer`, which applies the request's tenant guard:
        // this call is authenticated now, so a server the caller cannot see is simply not found. The
        // state still pins the tenant the flow started in, and a row that has since moved out of it
        // must not receive a grant.
        val server = requireOAuthServer(pending.mcpId)
        if (server.tenantId != pending.tenantId) {
            throw BizException("The MCP server this authorization was started for no longer exists in this tenant")
        }
        // The pending state pinned the RFC 8707 resource built from this row's url. Consent is given
        // against that address, so a row edited mid-flow means the code was minted for a server the
        // token will not be accepted by; storing it would park a credential that cannot work.
        if (pending.resource != null && server.url.trim() != pending.resource) {
            throw BizException(
                "This MCP server's URL changed while the authorization was in progress, so the code was " +
                    "minted for the old address; please authorize again",
            )
        }
        // Looked up by the issuer the flow was started against, not by whatever `oauth_config` says
        // now: the issuer is the identity the pending state was built on.
        val client = mcpOauthClientMapper.selectByTenantAndIssuer(pending.tenantId, pending.issuer)
            ?: throw BizException("The client registration for ${pending.issuer} has disappeared; run discovery again")
        if (client.clientId.isBlank()) {
            throw BizException("No client_id is registered for ${pending.issuer}; save the OAuth client again")
        }
        val tokenEndpoint = client.tokenEndpoint?.trim().orEmpty()
        if (tokenEndpoint.isEmpty()) {
            throw BizException("The token endpoint of this authorization server is unknown; run discovery again")
        }

        val form = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to pending.redirectUri,
            "client_id" to client.clientId,
            // RFC 6749 §4.1.3 allows the credentials in the body or as basic auth. In the body,
            // because every implementation takes it and it needs no second code path.
            "code_verifier" to pending.codeVerifier,
        )
        client.clientSecretEnc?.let { enc ->
            // Same rule as the refresh: a secret that will not open means the key moved, and the page
            // has to hear that rather than "Authorization failed - please start again".
            form["client_secret"] = openToken(enc, pending.mcpId)
                ?: throw BizException("The stored client secret cannot be decrypted with the current key; re-save the OAuth client")
        }
        pending.resource?.let { form["resource"] = it }

        val json = remoteJsonFetcher.postForm(tokenEndpoint, form).answerOrThrow("The token exchange")
        val accessToken = json.optString(ACCESS_TOKEN_FIELD)
            ?: throw BizException("The authorization server answered without an access_token")
        val refreshToken = json.optString(REFRESH_TOKEN_FIELD)
        val grantedScopes = json.optString("scope")?.splitScopeList().orEmpty()
        validateAgainstIssuer(json, accessToken, pending.issuer, pending.resource, pending.mcpId)

        val now = LocalDateTime.now()
        val expiresInSeconds = json.path(EXPIRES_IN_FIELD).takeIf { it.isNumber }?.asInt()
        val granted = grantedScopes.ifEmpty { pending.requestedScopes }
        // A list wider than the column is not recorded at all rather than cut to fit: a truncated
        // string reads as "these are the granted scopes" while its last entry is a fragment nobody
        // granted, and the caller cannot narrow an answer the authorization server chose. The row
        // still says when it was cut, because `status` shows last_error.
        val joinedScopes = granted.joinToString(",")
        val scopeText = joinedScopes.takeIf { it.length <= SCOPES_MAX_LEN }
        val cutNote = if (scopeText == null) {
            "The granted scope list is ${joinedScopes.length} characters, over the $SCOPES_MAX_LEN this " +
                "column holds, so it is not recorded here"
        } else {
            null
        }
        val accessExpiresAt = expiresInSeconds?.let { now.plusSeconds(it.toLong()) }
        val existing = mcpUserCredentialMapper.selectByUserAndMcp(pending.tenantId, pending.userId, pending.mcpId)
        val credential = existing ?: McpUserCredential().apply {
            tenantId = pending.tenantId
            userId = pending.userId
            mcpId = pending.mcpId
            createTime = now
        }
        credential.accessTokenEnc = aesUtil.encrypt(accessToken)
        // A fresh exchange replaces the grant wholesale. Keeping a refresh token from the previous one
        // would leave a way to mint tokens whose scopes nobody just agreed to.
        credential.refreshTokenEnc = refreshToken?.let { aesUtil.encrypt(it) }
        credential.accessExpiresAt = accessExpiresAt
        credential.scopes = scopeText
        credential.status = McpUserCredential.STATUS_ACTIVE
        credential.lastError = cutNote
        credential.lastRefreshedAt = now
        credential.updateTime = now
        if (existing == null) {
            try {
                mcpUserCredentialMapper.insert(credential)
            } catch (e: DuplicateKeyException) {
                // Two authorizations of the same user for the same server raced, and the unique key
                // let one of them create the row. The token this exchange holds is just as good, so it
                // is written onto the row that won rather than reporting a database failure for a
                // grant that did happen. `updateById` sets every column and keys on id alone.
                val raced = mcpUserCredentialMapper.selectByUserAndMcp(pending.tenantId, pending.userId, pending.mcpId)
                    ?: throw e
                credential.id = raced.id
                mcpUserCredentialMapper.updateById(credential)
            }
        } else {
            mcpUserCredentialMapper.updateById(credential)
        }
        if (grantedScopes.isNotEmpty()) {
            (pending.requestedScopes - grantedScopes).takeIf { it.isNotEmpty() }?.let {
                log.warn("MCP {} user {} was granted fewer scopes than requested: missing {}", pending.mcpId, pending.userId, it)
            }
        }
        log.info(
            "MCP {} stored an OAuth grant for user {} (scopes={}, expiresIn={}, refreshToken={})",
            pending.mcpId,
            pending.userId,
            granted,
            expiresInSeconds ?: -1,
            refreshToken != null,
        )
        // What the row holds, not what the token answer said: RFC 6749 §5.1 lets the authorization
        // server omit `scope`, and answering with that empty list would show the landing page no
        // scopes while `status` - reading this same column - shows the requested ones a moment later.
        // A list too wide to record comes back empty for the same reason: that is what was stored.
        return StoredGrant(scopeText.splitScopes(), accessExpiresAt)
    }

    /**
     * A token from somewhere else is worthless here unless it is for this issuer and this resource,
     * so both are checked before anything is stored.
     *
     * The claims of a JWT access token are read for these two comparisons without verifying any
     * signature: nothing here consumes the token's contents, it is only ever forwarded to the MCP
     * server, which is the party that validates it. An opaque token cannot be inspected at all, which
     * is logged rather than treated as a pass - the resource binding then rests on the `resource`
     * parameter having been honoured by the authorization server.
     */
    private fun validateAgainstIssuer(
        json: JsonNode,
        accessToken: String,
        issuer: String,
        resource: String?,
        mcpId: Long,
    ) {
        json.optString("iss")?.let { assertIssuer(it, issuer) }
        val claims = jwtClaims(accessToken)
        if (claims == null) {
            log.info("MCP {} got an opaque access token; its iss and aud cannot be inspected here", mcpId)
            return
        }
        claims.optString("iss")?.let { assertIssuer(it, issuer) }
        val audience = claims.audienceValues()
        if (resource != null && audience.isEmpty()) {
            // A JWT carrying no `aud` is as uninspectable as an opaque one, and gets the same
            // treatment: said out loud rather than counted as a pass. The resource binding then rests
            // on the authorization server having honoured the `resource` parameter.
            log.info("MCP {} got a token carrying no aud claim; its audience cannot be inspected here", mcpId)
        } else if (resource != null && audience.none { it == resource }) {
            throw BizException(
                "The token audience (${audience.joinToString(", ")}) does not include this MCP server, " +
                    "so presenting it there would be a token used for the wrong thing",
            )
        }
    }

    /**
     * Byte-exact, the same rule the registration lookup uses: a prefix or case-folded match would
     * accept a token minted by a different authorization server that happens to share a hostname.
     */
    private fun assertIssuer(
        found: String,
        expected: String,
    ) {
        if (found != expected) {
            throw BizException("The tokens carry iss '$found', not the discovered authorization server '$expected'")
        }
    }

    private fun revokeUpstream(
        endpoint: String,
        client: McpOauthClient,
        token: String,
        hint: String,
    ): Boolean {
        val form = mutableMapOf("token" to token, "token_type_hint" to hint, "client_id" to client.clientId)
        // A secret that will not open (the AES key moved) is a secret that cannot be presented, not a
        // reason to abandon the revocation: this call is the user's only way out, and the local row is
        // the half this deployment controls. Without the secret a confidential client's revocation is
        // refused upstream, and `upstreamRevoked` says so.
        client.clientSecretEnc?.let { enc -> runCatching { aesUtil.decrypt(enc) }.getOrNull()?.let { form["client_secret"] = it } }
        return try {
            val response = remoteJsonFetcher.postForm(endpoint, form)
            if (response.ok) {
                true
            } else {
                log.warn("The revocation endpoint answered HTTP {} ({}), revoking locally only", response.status, response.json?.optString("error"))
                false
            }
        } catch (e: Exception) {
            log.warn("The revocation endpoint could not be reached ({}), revoking locally only", e.message)
            false
        }
    }

    private fun clearLocally(credential: McpUserCredential) {
        credential.accessTokenEnc = null
        credential.refreshTokenEnc = null
        credential.accessExpiresAt = null
        credential.scopes = null
        credential.status = McpUserCredential.STATUS_REVOKED
        credential.lastError = null
        credential.updateTime = LocalDateTime.now()
        mcpUserCredentialMapper.updateById(credential)
    }

    /** What a completed exchange stored, in the shape the answer to the browser needs. */
    private data class StoredGrant(
        val scopes: List<String>,
        val accessExpiresAt: LocalDateTime?,
    )

    private fun currentUserId(): Long = UserContextUtil.getCurrentUserId(jwtUtil)
        ?: throw BizException("Authorization is per user and this request carries no user identity")

    /**
     * Reads through [McpServerService] so the tenant guard applies, which the discovery service does
     * for the same reason: these endpoints build URLs from a row the caller may not own.
     */
    private fun requireOAuthServer(mcpId: Long): McpServer {
        val server = mcpServerService.getMcpServer(mcpId) ?: throw BizException("MCP server not found")
        if (server.authType != McpAuthTypes.OAUTH2) {
            throw BizException("MCP server '${server.name}' has auth type ${server.authType}, OAuth authorization does not apply")
        }
        return server
    }

    private fun requireClientRegistration(
        server: McpServer,
        config: McpOAuthConfig,
    ): McpOauthClient {
        val issuer = normalizeIssuer(config.authorizationServer)
        if (issuer.isEmpty()) {
            throw BizException(
                "The authorization server of this MCP server is not known yet: run discovery first " +
                    "(POST /api/admin/mcp/${server.id}/oauth/discover)",
            )
        }
        val registration = mcpOauthClientMapper.selectByTenantAndIssuer(server.tenantId, issuer)
            ?: throw BizException(
                "No client registration exists for $issuer: run discovery first " +
                    "(POST /api/admin/mcp/${server.id}/oauth/discover)",
            )
        // Discovery stores a row with an empty client_id on purpose, so "configured" is not yet
        // "usable": sending a user to the AS with client_id= yields an error page there.
        if (registration.clientId.isBlank()) {
            throw BizException(
                "No client_id is registered for $issuer: save the OAuth client " +
                    "(POST /api/admin/mcp/${server.id}/oauth/client)",
            )
        }
        return registration
    }

    /** The registration row of the configured issuer, or null when the setup no longer resolves. */
    private fun registrationOf(server: McpServer): McpOauthClient? {
        val issuer = normalizeIssuer(readConfig(server).authorizationServer)
        return issuer.takeIf { it.isNotEmpty() }?.let { mcpOauthClientMapper.selectByTenantAndIssuer(server.tenantId, it) }
    }

    private fun warnOnUnknownScopes(
        scopes: List<String>,
        scopesSupported: String?,
        mcpId: Long,
    ) {
        val supported = scopesSupported.splitScopes()
        if (supported.isEmpty()) {
            return
        }
        (scopes - supported).takeIf { it.isNotEmpty() }?.let {
            log.warn("MCP {} requests scopes {} the authorization server did not list", mcpId, it)
        }
    }

    private fun readConfig(server: McpServer): McpOAuthConfig {
        val json = server.oauthConfig?.trim().orEmpty()
        if (json.isEmpty()) {
            return McpOAuthConfig()
        }
        return try {
            objectMapper.readValue(json, McpOAuthConfig::class.java)
        } catch (e: Exception) {
            throw BizException("The oauth_config of MCP server ${server.id} cannot be read, re-save its OAuth settings")
        }
    }

    private fun appendQuery(
        endpoint: String,
        params: List<Pair<String, String>>,
    ): String {
        val query = params.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        return if (endpoint.contains('?')) "$endpoint&$query" else "$endpoint?$query"
    }

    private fun urlToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun challengeOf(verifier: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    private fun jwtClaims(accessToken: String): JsonNode? {
        val parts = accessToken.split('.')
        if (parts.size != 3) {
            return null
        }
        val payload = try {
            Base64.getUrlDecoder().decode(parts[1])
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            objectMapper.readTree(payload).takeIf { it.isObject }
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonNode.audienceValues(): List<String> = path("aud").let { node ->
        when {
            node.isArray -> node.filter { it.isString }.map { it.asText() }
            node.isString -> listOf(node.asText())
            else -> emptyList()
        }
    }

    /**
     * A failed token or revocation answer in the words the authorization server chose (`error` and
     * `error_description`, RFC 6749 §5.2), capped, and never with token material in it.
     */
    private fun RemoteFetch.answerOrThrow(what: String): JsonNode {
        if (!ok) {
            val described = (json?.optString("error_description") ?: json?.optString("error"))?.take(MAX_ANSWER_CHARS)
            throw BizException("$what failed with HTTP $status${described?.let { ": $it" } ?: ""}")
        }
        return json ?: throw BizException("$what answered with no JSON object (HTTP $status)")
    }

    private fun String?.splitScopes(): List<String> = this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun String.splitScopeList(): List<String> = split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        /** 256 bits of verifier: the width RFC 7636 §4.1 caps out at, and base64url-safe. */
        private const val VERIFIER_BYTES = 32

        /** 192 bits is beyond guessing and still short enough for a query string. */
        private const val STATE_BYTES = 24

        private const val ACCESS_TOKEN_FIELD = "access_token"
        private const val REFRESH_TOKEN_FIELD = "refresh_token"
        private const val EXPIRES_IN_FIELD = "expires_in"

        /** `mcp_user_credential.scopes` is VARCHAR(512). */
        private const val SCOPES_MAX_LEN = 512

        /** `mcp_user_credential.last_error` is VARCHAR(512); leave room for nothing else to be lost. */
        private const val MAX_ERROR_CHARS = 500

        /** Refresh this many seconds before the stored expiry, so no call runs on a dying token. */
        private const val REFRESH_SKEW_SECONDS = 60L

        /** Locks guarding concurrent refreshes; see [refreshLocks]. */
        private const val LOCK_STRIPES = 64

        /** Anything longer from upstream is a dump, not a message. */
        private const val MAX_ANSWER_CHARS = 200
    }
}
