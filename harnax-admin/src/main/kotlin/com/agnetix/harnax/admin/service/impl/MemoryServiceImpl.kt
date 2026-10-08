package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.MemoryAgentResponse
import com.agnetix.harnax.admin.dto.MemoryDetailResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.MemoryService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.MemoryObjectKeys
import com.agnetix.harnax.admin.util.TenantResolver
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/**
 * The caller's own memory, addressed only inside the caller's own namespace.
 *
 * Two identity facts decide everything below, and neither is a request parameter:
 * 1. the user — the `sys_user` row behind the authenticated principal, read through [SecurityUtils] the way
 *    every other logged-in admin endpoint reads it, so a token's own claims cannot invent a different owner;
 * 2. the tenant — [TenantResolver], the one chain every admin service now reads through, which means the
 *    memory shown here is bucketed by the same workspace the caller's agent list comes from. A second
 *    resolution rule would show an owner memory for agents they cannot see, or hide memory for agents they can.
 *
 * An internal (shared-secret) call has no row of its own and is refused: `internal-service` is a service
 * identity, not a person with memories, and letting it through would mean an agent runtime could read an
 * owner's bucket by guessing nothing at all.
 *
 * Everything this service shows comes out of the memory bucket alone. Which conversations of an agent are
 * still waiting to be merged is a property of the bucket, not of the agent row, so a listing needs no lookup
 * outside it — and an owner still has to be able to see and delete what was written about them.
 */
@Service
class MemoryServiceImpl(
    private val memoryStoreGateway: MemoryStoreGateway,
    private val jwtUtil: JwtUtil,
) : MemoryService {

    private val log = LoggerFactory.getLogger(MemoryServiceImpl::class.java)

    override fun listMyMemory(): List<MemoryAgentResponse> {
        val caller = currentCaller()
        val agents = memoryStoreGateway.listAgents(caller.tenantId, caller.userId)
        log.info(
            "[memory] User '{}' listed {} agent(s) with memory in tenant {}",
            caller.username,
            agents.size,
            caller.tenantId,
        )
        return agents
    }

    override fun readMyMemory(agentId: String): MemoryDetailResponse? {
        val caller = currentCaller()
        // Validated again here rather than only in the gateway: this is where a path variable stops being
        // text and becomes part of an object key.
        if (!MemoryObjectKeys.isValidAgentId(agentId)) {
            log.warn("[memory] Refused an unusable agent id from user '{}'", caller.username)
            throw BizException("Invalid agent id")
        }
        return memoryStoreGateway.readAgent(caller.tenantId, caller.userId, agentId)
    }

    override fun deleteMyMemory(agentId: String): Int {
        val caller = currentCaller()
        if (!MemoryObjectKeys.isValidAgentId(agentId)) {
            log.warn("[memory] Refused an unusable agent id from user '{}'", caller.username)
            throw BizException("Invalid agent id")
        }
        val removed = memoryStoreGateway.deleteAgent(caller.tenantId, caller.userId, agentId)
        log.info(
            "[memory] User '{}' deleted memory of agent '{}' ({} object(s)) in tenant {}",
            caller.username,
            agentId,
            removed,
            caller.tenantId,
        )
        return removed
    }

    /** The account this request is acting as, and the namespace its memory lives in. */
    private data class Caller(
        val username: String,
        val tenantId: Long,
        val userId: String,
    )

    /**
     * The logged-in owner whose memory this is, or a refusal.
     *
     * [BizException] with code 401 rather than a null: every caller of this is an endpoint that would
     * otherwise list the default tenant's user 0, which is somebody's memory.
     */
    private fun currentCaller(): Caller {
        val authentication = SecurityContextHolder.getContext()?.authentication
        if (authentication == null || !authentication.isAuthenticated || authentication.name.isNullOrBlank()) {
            throw BizException(401, "User not logged in")
        }
        if (authentication.principal == INTERNAL_SERVICE_PRINCIPAL) {
            throw BizException(401, "Memory belongs to a logged-in user, not to a service call")
        }
        val user = SecurityUtils.getCurrentUser() ?: throw BizException(401, "User not logged in")
        if (user.id <= 0) {
            throw BizException(401, "User not logged in")
        }
        return Caller(
            username = user.username,
            tenantId = TenantResolver.resolve(jwtUtil),
            userId = MemoryObjectKeys.userSegment(user.id),
        )
    }

    companion object {
        /** What `JwtAuthenticationFilter` sets for a shared-secret service call; see [UserContextUtil]. */
        private const val INTERNAL_SERVICE_PRINCIPAL = "internal-service"
    }
}
