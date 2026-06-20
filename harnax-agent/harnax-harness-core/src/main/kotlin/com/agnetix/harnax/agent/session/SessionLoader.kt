package com.agnetix.harnax.agent.session

import io.agentscope.core.state.AgentStateStore
import io.agentscope.core.state.InMemoryAgentStateStore
import io.agentscope.core.state.JsonFileAgentStateStore
import kotlin.io.path.Path

/**
 * Loads an [AgentStateStore] from [SessionConfig].
 *
 * In agentscope 2.0.0, `Session` was replaced by `AgentStateStore`.
 * `MysqlSession` → `MysqlAgentStateStore` (see harness/sandbox package).
 * `JsonSession` → `JsonFileAgentStateStore`.
 * `InMemorySession` → `InMemoryAgentStateStore`.
 */
object SessionLoader {

    fun load(sessionConfig: SessionConfig?): AgentStateStore = if (sessionConfig == null) {
        InMemoryAgentStateStore()
    } else {
        when (sessionConfig) {
            is JsonSessionConfig -> JsonFileAgentStateStore(Path(sessionConfig.path))
            is MysqlSessionConfig -> MysqlAgentStateStore(sessionConfig.toDataSource())
        }
    }
}
