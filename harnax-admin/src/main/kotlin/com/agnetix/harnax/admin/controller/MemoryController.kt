package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.MemoryAgentResponse
import com.agnetix.harnax.admin.dto.MemoryDeleteResponse
import com.agnetix.harnax.admin.dto.MemoryDetailResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.MemoryService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The logged-in user's own long-term agent memory: look at it, and delete it.
 *
 * The memory files are the agent's, not the platform's — they hold what a user told an agent to remember —
 * so the owner has to be able to read them and to have them removed. Everything the runtime wrote goes
 * through `MinioBaseStore` into the shared store bucket under
 * `store/tenants/<tenantId>/users/<userId>/agents/<agentId>/…` — the tenant pair drops out when
 * `harnax.memory.tenant-scoped` is off, the switch the runtime writes with — and this is the admin-side
 * reader of that layout.
 *
 * Three things about the shape of these endpoints:
 * 1. There is no user or tenant parameter. Both come from the session, so no request can name somebody
 *    else's namespace, and the only input is the agent.
 * 2. `/api/admin/memory` is an ordinary page path, not an internal API path: [com.agnetix.harnax.admin.config.InternalApiAuthFilter]
 *    only gates the `/api/admin/internal/` prefix, and `SecurityConfig` requires an authentication for
 *    everything else. The raw shared secret therefore cannot list an owner's memory here — [MemoryService]
 *    answers a service principal with 401 rather than with a default tenant.
 * 3. Like the rest of this module, a business failure is HTTP 200 with an error `code` in the envelope, and
 *    a listing that found nothing is a success with an empty list — "no memory yet" is not an error.
 *
 * The bucket itself is never listed wider than the caller's own prefix; see [com.agnetix.harnax.admin.service.impl.MemoryStoreGateway].
 */
@RestController
@RequestMapping("/api/admin/memory")
@Tag(name = "Agent Memory", description = "The signed-in user's own long-term agent memory")
class MemoryController(
    private val memoryService: MemoryService,
) {
    private val log = LoggerFactory.getLogger(MemoryController::class.java)

    @GetMapping
    @Operation(
        summary = "List the caller's agents that have memory",
        description = "One row per agent with objects in the caller's memory bucket: the curated MEMORY.md, when it was written, and the daily ledger dates",
    )
    fun listMemory(): ResultVo<List<MemoryAgentResponse>> = try {
        ResultVo.success(memoryService.listMyMemory())
    } catch (e: BizException) {
        log.warn("Failed to list memory: code={}, message={}", e.code, e.message)
        ResultVo.error(e.code, e.message ?: "Failed to list memory")
    } catch (e: Exception) {
        log.error("Failed to list memory", e)
        ResultVo.error("Failed to list memory")
    }

    @GetMapping("/{agentId}")
    @Operation(
        summary = "Read the caller's memory of one agent",
        description = "The curated MEMORY.md text and every daily ledger entry, for the signed-in user only",
    )
    fun readMemory(
        @Parameter(description = "Agent name as the runtime keys it") @PathVariable(name = "agentId") agentId: String,
    ): ResultVo<MemoryDetailResponse> = try {
        val memory = memoryService.readMyMemory(agentId)
            ?: return ResultVo.error(404, "No memory for this agent")
        ResultVo.success(memory)
    } catch (e: BizException) {
        log.warn("Failed to read memory of agent '{}': code={}, message={}", agentId, e.code, e.message)
        ResultVo.error(e.code, e.message ?: "Failed to read memory")
    } catch (e: Exception) {
        log.error("Failed to read memory of agent '{}'", agentId, e)
        ResultVo.error("Failed to read memory")
    }

    @DeleteMapping("/{agentId}")
    @Operation(
        summary = "Delete the caller's memory of one agent",
        description = "Removes the curated MEMORY.md and every daily ledger entry of that agent for the signed-in user; nothing outside their own prefix is touched",
    )
    fun deleteMemory(
        @Parameter(description = "Agent name as the runtime keys it") @PathVariable(name = "agentId") agentId: String,
    ): ResultVo<MemoryDeleteResponse> = try {
        val removed = memoryService.deleteMyMemory(agentId)
        ResultVo.success(MemoryDeleteResponse(agentId = agentId, deletedObjects = removed))
    } catch (e: BizException) {
        log.warn("Failed to delete memory of agent '{}': code={}, message={}", agentId, e.code, e.message)
        ResultVo.error(e.code, e.message ?: "Failed to delete memory")
    } catch (e: Exception) {
        log.error("Failed to delete memory of agent '{}'", agentId, e)
        ResultVo.error("Failed to delete memory")
    }
}
