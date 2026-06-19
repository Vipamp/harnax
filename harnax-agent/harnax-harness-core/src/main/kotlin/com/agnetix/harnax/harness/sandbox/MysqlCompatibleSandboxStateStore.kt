package com.agnetix.harnax.harness.sandbox

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.agentscope.core.session.Session
import io.agentscope.core.state.SimpleSessionKey
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey
import io.agentscope.harness.agent.sandbox.SandboxStateStore
import java.io.IOException
import java.util.Optional

/**
 * A [SandboxStateStore] that wraps the default [io.agentscope.harness.agent.sandbox.SessionSandboxStateStore]
 * to make session keys compatible with [io.agentscope.core.session.mysql.MysqlSession].
 *
 * The default store generates keys like `"sandbox/session/<sessionId>"` which contain path separators (`/`).
 * MysqlSession rejects such IDs with `IllegalArgumentException: Session ID cannot contain path separators`.
 *
 * This wrapper replaces `/` with `-` in the session key to avoid the validation error while preserving
 * uniqueness.
 */
class MysqlCompatibleSandboxStateStore(
    private val session: Session,
    private val agentId: String,
) : SandboxStateStore {

    private companion object {
        const val STATE_KEY = "_sandbox_state"
    }

    @Throws(IOException::class)
    override fun load(key: SandboxIsolationKey): Optional<String> = try {
        val state = session.get(slotKey(key), STATE_KEY, SandboxStateSlot::class.java)
        if (state.isEmpty || state.get().deleted || state.get().json == null) {
            Optional.empty()
        } else {
            Optional.of(state.get().json)
        }
    } catch (e: Exception) {
        throw IOException("Failed to load sandbox state for $key", e)
    }

    @Throws(IOException::class)
    override fun save(key: SandboxIsolationKey, json: String) {
        try {
            session.save(slotKey(key), STATE_KEY, SandboxStateSlot(json, false))
        } catch (e: Exception) {
            throw IOException("Failed to save sandbox state for $key", e)
        }
    }

    @Throws(IOException::class)
    override fun delete(key: SandboxIsolationKey) {
        try {
            session.save(slotKey(key), STATE_KEY, SandboxStateSlot("", true))
        } catch (e: Exception) {
            throw IOException("Failed to delete sandbox state for $key", e)
        }
    }

    /**
     * Builds a MysqlSession-compatible [SimpleSessionKey] by replacing `/` with `-`.
     *
     * Original keys from [io.agentscope.harness.agent.sandbox.SessionSandboxStateStore]:
     * - SESSION: `sandbox/session/<value>` → `sandbox-session-<value>`
     * - USER: `sandbox/user/<agentId>/<value>` → `sandbox-user-<agentId>-<value>`
     * - AGENT: `sandbox/agent/<agentId>` → `sandbox-agent-<agentId>`
     * - GLOBAL: `sandbox/global` → `sandbox-global`
     */
    private fun slotKey(key: SandboxIsolationKey): SimpleSessionKey {
        val raw = when (key.scope) {
            io.agentscope.harness.agent.IsolationScope.SESSION -> "sandbox-session-${key.value}"
            io.agentscope.harness.agent.IsolationScope.USER -> "sandbox-user-$agentId-${key.value}"
            io.agentscope.harness.agent.IsolationScope.AGENT -> "sandbox-agent-$agentId"
            io.agentscope.harness.agent.IsolationScope.GLOBAL -> "sandbox-global"
        }
        return SimpleSessionKey.of(raw)
    }

    /**
     * State record matching the JSON structure used by
     * [io.agentscope.harness.agent.sandbox.SessionSandboxStateStore.SandboxStateSlot].
     */
    private data class SandboxStateSlot @JsonCreator constructor(
        @JsonProperty("json") val json: String,
        @JsonProperty("deleted") val deleted: Boolean,
    ) : io.agentscope.core.state.State
}
