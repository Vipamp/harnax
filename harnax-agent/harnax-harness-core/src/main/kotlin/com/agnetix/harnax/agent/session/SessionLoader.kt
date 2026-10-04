package com.agnetix.harnax.agent.session

import io.agentscope.core.state.AgentStateStore
import io.agentscope.core.state.InMemoryAgentStateStore
import io.agentscope.core.state.JsonFileAgentStateStore
import kotlin.io.path.Path

/**
 * The stores over one session database.
 *
 * @param stateStore the agent state the model reads and writes
 * @param messageStore the append-only history the chat page reads; null when the session database is not
 * MySQL, which leaves history on the state store's own two fallbacks
 */
class SessionStores(
    val stateStore: AgentStateStore,
    val messageStore: MysqlSessionMessageStore?,
)

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

    /**
     * Loads both stores for [sessionConfig] over a single connection pool.
     *
     * One pool is the reason this exists: [MysqlSessionConfig.toDataSource] builds a fresh Hikari pool on
     * every call, so a caller that asked for a DataSource per store would run the session database with two
     * pools of ten connections each.
     */
    fun loadStores(sessionConfig: SessionConfig?): SessionStores = if (sessionConfig is MysqlSessionConfig) {
        val dataSource = sessionConfig.toDataSource()
        SessionStores(
            stateStore = MysqlAgentStateStore(dataSource),
            messageStore = MysqlSessionMessageStore(dataSource),
        )
    } else {
        SessionStores(load(sessionConfig), messageStore = null)
    }
}
