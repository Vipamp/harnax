package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.service.SchedulerClient
import com.agnetix.harnax.auth.AuthRestTemplateInterceptor
import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.AgentTaskOwner
import com.agnetix.harnax.common.dto.ResultVo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Duration

@Service
class SchedulerClientImpl(
    @Value("\${harnax.scheduler.url:http://localhost:8084}") private val schedulerUrls: String,
    private val tokenProvider: InternalTokenProvider,
) : SchedulerClient {

    private val log = LoggerFactory.getLogger(SchedulerClientImpl::class.java)

    /**
     * One address in normal operation. The comma-separated form stays parsed — an operator who configured
     * two URLs must not break on this deploy — but only the first of them is ever used.
     */
    private val urls: List<String> by lazy {
        schedulerUrls.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private val restClient: RestClient by lazy {
        val factory = org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(30))
        }
        RestClient.builder()
            .requestFactory(factory)
            // The scheduler runs with harnax.auth.enabled=false and does not install our auth filter, but
            // since release 2 it does verify this bearer: InternalCallerInterceptor refuses /api/scheduler/**
            // without a `typ=internal` token signed on the same HARNAX_AUTH_SECRET (contract C4). An old admin
            // against a new scheduler is a wall of 401s — hence the same-window upgrade in the release notes.
            .requestInterceptor(AuthRestTemplateInterceptor(tokenProvider))
            // The other half of C4, and deliberately an interceptor rather than a header per call site: every
            // path out of here is a call about a user — task 6's forwarding CRUD surface included — and a list
            // someone has to remember is how an identity-less forward gets shipped.
            .requestInterceptor { request, body, execution ->
                forwardedUser()?.let { request.headers.set(HEADER_FORWARDED_USER, it) }
                TenantContext.getTenantId()?.let { request.headers.set(HEADER_TENANT_ID, it.toString()) }
                // No X-Forwarded-Tenant, in either direction: that one is the header a browser can put on a
                // request itself, so C4 names the two above and the scheduler reads no other.
                execution.execute(request, body)
            }
            .build()
    }

    /**
     * The person this call is made for, in the form `X-Forwarded-User` wants — and null when there is no
     * person behind it, which is normal here and not a reason to fail: the [taskOwner] read runs inside a
     * task execution, on a chain that has never seen a login.
     *
     * `JwtAuthenticationFilter`'s principal for a shared-secret internal call (agent-service's spec lookup,
     * a CLI in a sandbox) is the marker string rather than a username, and `UserContextUtil` answers "SYSTEM"
     * for exactly that principal. Same answer here, so the header never spells a name that names nobody.
     */
    private fun forwardedUser(): String? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        if (!authentication.isAuthenticated || authentication is AnonymousAuthenticationToken) {
            return null
        }
        val name = authentication.name?.takeIf { it.isNotBlank() } ?: return null
        return if (name == INTERNAL_SERVICE_PRINCIPAL) INTERNAL_SERVICE_USERNAME else name
    }

    override fun triggerTask(id: Long): ResultVo<Void> = postToScheduler("/api/scheduler/tasks/$id/trigger")

    override fun startTask(id: Long): ResultVo<Void> = postToScheduler("/api/scheduler/tasks/$id/start")

    override fun pauseTask(id: Long): ResultVo<Void> = postToScheduler("/api/scheduler/tasks/$id/pause")

    override fun reloadTasks(): ResultVo<Void> = postToScheduler("/api/scheduler/reload")

    override fun stopTask(logId: Long): ResultVo<Void> = postToScheduler("/api/scheduler/tasks/logs/$logId/stop")

    /**
     * The task domain's whole path out of here since release 2, on this client's one [RestClient]: same
     * bearer, same identity headers (stamped by the interceptor above, so a forwarded call cannot forget them
     * by naming its own header list), same first instance of `harnax.scheduler.url`, same 5s connect and 30s
     * read ceilings.
     *
     * `onStatus` is registered to do nothing on an error status, which is the difference between relaying an
     * answer and inventing one. The scheduler reports a refused request the way admin's clients have always
     * been served — HTTP 400 with a `ResultVo` body for a bean-validation failure, a business code in the
     * body for everything the endpoints catch — and letting RestClient raise on those statuses would replace
     * the one message the user was meant to read with a transport error about it.
     *
     * What is left to catch is therefore genuinely "no answer": connection refused, a timeout, a body that is
     * not JSON at all. Those fold into a [ResultVo] rather than an exception for the same reason they always
     * did on this boundary — the caller's answer is a business code either way, and a stack trace out of a
     * proxy would only decide what the client sees by which path failed.
     */
    override fun forward(
        method: HttpMethod,
        path: String,
        query: Map<String, String?>,
        body: Any?,
    ): ResultVo<JsonNode> {
        val baseUrl = urls.firstOrNull() ?: return ResultVo.error("No scheduler URL configured")
        val uri = try {
            uriOf(baseUrl, path, query)
        } catch (e: Exception) {
            // A target this cannot even name is a bug in this module, not the scheduler refusing anything.
            log.error("Failed to build the forwarding target for {} {}: {}", method, path, e.message)
            return ResultVo.error("Invalid forwarding target: ${e.message}")
        }
        return try {
            val spec = restClient.method(method).uri(uri).contentType(MediaType.APPLICATION_JSON)
            val response = if (body == null) spec.retrieve() else spec.body(body).retrieve()
            response
                .onStatus({ status -> status.isError }, { _, _ -> })
                .body(FORWARDED_RESULT_TYPE)
                ?: ResultVo.error("No response from scheduler")
        } catch (e: Exception) {
            log.error("Failed to forward {} {} to the scheduler: {}", method, path, e.message, e)
            ResultVo.error("Scheduler service unavailable: ${e.message}")
        }
    }

    /**
     * The call's full target. Query values are encoded rather than concatenated: the execution-log filters
     * carry timestamps with spaces and colons in them, and a hand-built `?a=b&c=d` would either drop them or
     * hand the servlet a malformed URI — which is a forwarding bug that looks like a scheduler failure.
     */
    private fun uriOf(baseUrl: String, path: String, query: Map<String, String?>): URI {
        val builder = UriComponentsBuilder.fromUriString(baseUrl + path)
        query.forEach { (name, value) ->
            if (value != null) {
                builder.queryParam(name, value)
            }
        }
        return builder.build().encode().toUri()
    }

    /**
     * Contract C5, and the only read on this client that is typed rather than forwarded. It reuses this
     * client's one [RestClient] — same bearer from the
     * shared [InternalTokenProvider], same first instance of `harnax.scheduler.url`, same 5s connect and
     * 30s read ceilings. A shorter ceiling for this one call would mean its own request factory, its own
     * auth interceptor and its own copy of the URL list, and the path does not buy anything with it: every
     * non-answer lands in the same place, as one OAuth MCP tool missing for that execution.
     *
     * Nothing here throws. An unreachable scheduler is a [ResultVo] the resolver turns into a WARN naming
     * the task, which is the whole difference between "an OAuth tool quietly disappeared" and a task run
     * failed by the service that resolves its tools.
     */
    override fun taskOwner(id: Long): ResultVo<AgentTaskOwner?> = getFromScheduler("/api/scheduler/agent-tasks/$id/owner")

    private fun getFromScheduler(path: String): ResultVo<AgentTaskOwner?> {
        val baseUrl = urls.firstOrNull() ?: return ResultVo.error("No scheduler URL configured")
        val url = "$baseUrl$path"
        return try {
            log.debug("Reading task owner from scheduler: {}", url)
            restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<AgentTaskOwner?>>() {})
                ?: ResultVo.error("No response from scheduler")
        } catch (e: Exception) {
            // Deliberately not rethrown; see the method above. The response body of a non-2xx is not read
            // into the message, so nothing of a task's own data lands in a log line here.
            log.warn("Failed to read task owner from scheduler {}: {}", url, e.message)
            ResultVo.error("Scheduler service unavailable: ${e.message}")
        }
    }

    /**
     * One call, one instance. Start/pause/reload land in the shared Quartz store and a manual trigger is
     * a one-shot the shared execution guard dedups, so in neither case is there per-node state left to
     * notify — and no `anySuccess`-style folding to invent business codes for.
     * A single instance failing therefore means exactly one thing: the definition is saved and nothing
     * has scheduled it, which is what 40902 tells the caller.
     *
     * Folding also carried a live bug for [stopTask]: the second instance to take the same stop answered
     * "nothing live", closed the log out itself and took the real result away from the owning thread.
     */
    private fun postToScheduler(path: String): ResultVo<Void> {
        val url = urls.firstOrNull() ?: return ResultVo.error("No scheduler URL configured")
        return postToInstance(url, path)
    }

    private fun postToInstance(baseUrl: String, path: String): ResultVo<Void> = try {
        val url = "$baseUrl$path"
        log.info("Proxying request to scheduler: {}", url)

        val result = restClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(object : ParameterizedTypeReference<ResultVo<Void>>() {})

        result ?: ResultVo.error("No response from scheduler")
    } catch (e: Exception) {
        log.error("Failed to proxy request to scheduler {}: {}", baseUrl, e.message, e)
        ResultVo.error("Scheduler service unavailable: ${e.message}")
    }

    companion object {
        /**
         * The forwarding target: the shell typed, the payload still a tree. Deserialising into a task DTO
         * would need a copy of a type the scheduler owns, and the copy is where a null would go missing.
         */
        private val FORWARDED_RESULT_TYPE = object : ParameterizedTypeReference<ResultVo<JsonNode>>() {}

        /** Contract C4's two identity headers, read by `harnax-scheduler`'s `InternalCallerInterceptor`. */
        private const val HEADER_FORWARDED_USER = "X-Forwarded-User"
        private const val HEADER_TENANT_ID = "X-Tenant-Id"

        /** `JwtAuthenticationFilter`'s principal for a shared-secret internal caller. */
        private const val INTERNAL_SERVICE_PRINCIPAL = "internal-service"

        /** What `UserContextUtil` calls the same caller, so both sides name it the same way. */
        private const val INTERNAL_SERVICE_USERNAME = "SYSTEM"
    }
}
