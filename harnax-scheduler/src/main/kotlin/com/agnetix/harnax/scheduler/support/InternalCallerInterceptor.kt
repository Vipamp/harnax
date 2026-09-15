package com.agnetix.harnax.scheduler.support

import com.agnetix.harnax.auth.CallerType
import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.HandlerInterceptor
import tools.jackson.databind.ObjectMapper

/**
 * The internal-caller gate of contract C4: this service answers a request on the `/api/scheduler` surface only
 * if the bearer on it verifies as another Harnax **service**, and the end-user identity is taken from the two
 * headers that caller stamps.
 *
 * Verification is [InternalTokenProvider.verifyToken], i.e. signature plus expiry plus the `typ=internal`
 * claim, against `harnax.auth.internal.shared-secret` — the same key admin signs with. Anything else is refused
 * with `401` and a [ResultVo] body, in the shape every Harnax client already parses:
 *
 * - no bearer, or a bearer that is not a `Bearer …` value;
 * - a bearer that does not verify (wrong key, expired, not a JWT at all);
 * - a bearer that verifies but is not a service token. That last one is not hypothetical: `verifyToken`
 *   answers `EXTERNAL_API` for a login token carrying a `userId` even when it was signed with *this* secret,
 *   because an operator who points one secret at both roles is following the deploy docs, not breaking them.
 *   The caller-type check below is what refuses that, so it is load-bearing on its own and not a restatement
 *   of the exception path.
 *
 * What is *not* here: `harnax.auth.enabled` stays `false` and [com.agnetix.harnax.auth.UnifiedAuthFilter] stays
 * out of this service. Enabling it would also switch on external API-key acceptance, rate limiting and the
 * `@InternalOnly` model — a bigger design, spec §9 F1. This gate only ever accepts a service bearer.
 *
 * ## Coverage, and where it deviates from the spec
 *
 * Spec §2.3 asked for "全部写面" — the write surface — because at the time the scheduler had nothing but one.
 * Release 2 changed that: task 4 added `GET /api/scheduler/agent-tasks/{id}/owner`, which answers a creator
 * name and a tenant id for any task id handed to it. Left out of the gate, that would be one readable-without-
 * a-credential ownership lookup per port that reaches this container, and the reasoning that made writes need
 * a gate ("a caller that can reach the port is not the caller admin authenticated") applies to it word for
 * word. So [PROTECTED_PATHS] is the whole of the `/api/scheduler` surface, reads included, with no exclusion
 * list — the actuator probes stay outside it because they live under `/actuator`, not under a carve-out, which
 * is what keeps compose's healthcheck working untouched. Recorded as a deliberate deviation from §2.3's
 * wording; the `/api/scheduler/agent-tasks` CRUD surface task 6 adds therefore lands inside the gate the day
 * it exists.
 *
 * ## The identity headers
 *
 * `X-Forwarded-User` and `X-Tenant-Id` are read into [CallerContext] and are both optional: an internal call
 * with no user behind it (the C5 read during a task execution) is a normal call, not a refusal.
 * `X-Forwarded-Tenant` is **never** read: it is the header of that trio a browser controls, which is why
 * contract C4 names the other two as the ones admin stamps (spec §2.3, and C4's "必须忽略").
 */
class InternalCallerInterceptor(
    private val tokenProvider: InternalTokenProvider,
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(InternalCallerInterceptor::class.java)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // Not strictly needed for a request that is about to be refused, and cheap enough to be worth it: it
        // makes "no identity after a refusal" a property of this method rather than one of the container's
        // hygiene, which is the only other thing that would guarantee it.
        CallerContext.clear()

        val token = request.getHeader(HEADER_AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.trim()
        if (token.isNullOrEmpty()) {
            return reject(request, response, "no internal service bearer token presented")
        }

        val context = try {
            tokenProvider.verifyToken(token)
        } catch (e: Exception) {
            // Never echo the token or jjwt's message back: an expired-token reply tells a caller nothing it
            // can act on, and whatever jjwt puts in there was derived from client-supplied bytes.
            return reject(request, response, "bearer rejected (${e.javaClass.simpleName})")
        }

        if (context.callerType != CallerType.INTERNAL_SERVICE) {
            return reject(request, response, "caller '${context.callerId}' is ${context.callerType}, not an internal service")
        }

        CallerContext.set(
            CallerContext.Caller(
                callerId = context.callerId,
                username = request.getHeader(HEADER_FORWARDED_USER)?.trim()?.takeIf { it.isNotEmpty() },
                tenantId = request.getHeader(HEADER_TENANT_ID)?.trim()?.toLongOrNull(),
            ),
        )
        return true
    }

    /**
     * The one place this caller context is dropped. `postHandle` is deliberately left alone: it runs after the
     * handler and is skipped when the handler throws, so clearing there is exactly the leak this prevents —
     * `afterCompletion` is the callback the DispatcherServlet guarantees for every request whose `preHandle`
     * returned true, exception path included, and it runs before the thread goes back to the pool.
     */
    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
        CallerContext.clear()
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse, reason: String): Boolean {
        log.warn("Refusing {} from caller {}: {}", request.requestURI, request.getHeader(HEADER_CALLER_ID) ?: "unknown", reason)
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Internal service authentication is required for ${request.requestURI}.")
        return false
    }

    /** Same answer shape [com.agnetix.harnax.auth.InternalAuthorizationInterceptor] writes, so no client has to learn a second one. */
    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        if (response.isCommitted) {
            log.warn("Cannot write the refusal: response already committed for status=$status")
            return
        }
        response.status = status
        response.contentType = "application/json"
        val body = objectMapper.writeValueAsString(ResultVo.error<String>(status, message))
        try {
            response.outputStream.write(body.toByteArray(Charsets.UTF_8))
        } catch (e: IllegalStateException) {
            // The writer was already taken by something upstream; fall back the way the filter does.
            response.writer.write(body)
        }
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_FORWARDED_USER = "X-Forwarded-User"
        const val HEADER_TENANT_ID = "X-Tenant-Id"

        /** The caller's own label for itself, from [com.agnetix.harnax.auth.InternalTokenProvider.authHeaders]. */
        const val HEADER_CALLER_ID = "X-Caller-Id"

        /** The only credential shape this gate reads; anything else in `Authorization` is not a bearer token. */
        private const val BEARER_PREFIX = "Bearer "

        /**
         * Everything this gate covers. One pattern, no exclusions — see the coverage note on the class.
         * [com.agnetix.harnax.scheduler.config.SchedulerWebConfig] registers it and
         * `InternalCallerInterceptorTest` reads that registration back, so this list is the single source.
         */
        val PROTECTED_PATHS: List<String> = listOf("/api/scheduler/**")
    }
}
