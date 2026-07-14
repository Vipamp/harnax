package com.agnetix.harnax.agent.service.client

import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import org.springframework.stereotype.Component

/**
 * Thread-local holder for the current agent-creation session's spec data.
 *
 * **Flow:**
 * 1. [AgentSpecResolver] calls [set] after fetching the spec from admin.
 * 2. The launcher creates the agent synchronously; adaptor impls read via [get].
 * 3. After creation, [clear] is called to prevent leaks.
 *
 * This is safe because Caffeine's `cache.get(key, mappingFunction)` runs the
 * lambda synchronously in the calling thread, and all adaptor calls happen
 * within that same synchronous agent-creation flow.
 */
@Component
class AgentSpecContextHolder {

    private val holder = ThreadLocal<AgentSpecInfoResponse?>()

    fun set(spec: AgentSpecInfoResponse) {
        holder.set(spec)
    }

    fun get(): AgentSpecInfoResponse? = holder.get()

    fun clear() {
        holder.remove()
    }
}
