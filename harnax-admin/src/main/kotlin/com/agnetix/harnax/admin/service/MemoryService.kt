package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.MemoryAgentResponse
import com.agnetix.harnax.admin.dto.MemoryDetailResponse

/**
 * The logged-in user's own long-term agent memory.
 *
 * Every method answers for the caller and for nobody else: the tenant and the user id come from the
 * authenticated session, never from an argument, so no call can ask about another account's memory.
 */
interface MemoryService {
    /**
     * Which of the caller's agents have memory, with the curated layer of each read and its ledger dated.
     *
     * @return one entry per agent that has objects in the caller's memory bucket, empty when none has any
     */
    fun listMyMemory(): List<MemoryAgentResponse>

    /**
     * The caller's memory of one agent: the curated `MEMORY.md` plus every daily entry.
     *
     * @param agentId the agent's name as the runtime keys it
     * @return the memory, or null when this caller has no memory of that agent
     * @throws com.agnetix.harnax.admin.exception.BizException when [agentId] could name a path
     */
    fun readMyMemory(agentId: String): MemoryDetailResponse?

    /**
     * Deletes the caller's memory of one agent, both routes: the curated layer and every daily entry.
     *
     * @param agentId the agent's name as the runtime keys it
     * @return how many objects were removed
     * @throws com.agnetix.harnax.admin.exception.BizException when [agentId] could name a path, or when the
     *   store could not finish the deletion — a half-deleted memory is not reported as deleted
     */
    fun deleteMyMemory(agentId: String): Int
}
