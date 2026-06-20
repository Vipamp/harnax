package com.agnetix.harnax.agent.session

import io.agentscope.core.state.AgentStateStore
import io.agentscope.core.state.State
import io.agentscope.core.util.JsonUtils
import org.slf4j.LoggerFactory
import java.util.Optional
import javax.sql.DataSource

/**
 * MySQL-backed [AgentStateStore] implementation.
 *
 * In agentscope 2.0.0, `Session` / `MysqlSession` was replaced by `AgentStateStore`.
 * This class provides equivalent MySQL persistence using the new `(userId, sessionId, key)` model.
 *
 * Table schema (auto-created):
 * ```sql
 * CREATE TABLE IF NOT EXISTS agent_state (
 *     id BIGINT AUTO_INCREMENT PRIMARY KEY,
 *     user_id VARCHAR(255) NOT NULL,
 *     session_id VARCHAR(255) NOT NULL,
 *     state_key VARCHAR(255) NOT NULL,
 *     state_type VARCHAR(32) NOT NULL,       -- 'SINGLE' or 'LIST'
 *     json_value LONGTEXT NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     UNIQUE KEY uk_slot (user_id, session_id, state_key)
 * );
 * ```
 *
 * @param dataSource JDBC DataSource for MySQL
 * @param tableName table name (default: "agent_state")
 * @param createIfNotExist whether to auto-create the table
 */
class MysqlAgentStateStore(
    private val dataSource: DataSource,
    private val tableName: String = "agent_state",
    private val createIfNotExist: Boolean = true,
) : AgentStateStore {

    private val log = LoggerFactory.getLogger(MysqlAgentStateStore::class.java)

    init {
        if (createIfNotExist) {
            createTableIfNeeded()
        }
    }

    private fun createTableIfNeeded() {
        val sql = """
            CREATE TABLE IF NOT EXISTS $tableName (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id VARCHAR(255) NOT NULL,
                session_id VARCHAR(255) NOT NULL,
                state_key VARCHAR(255) NOT NULL,
                state_type VARCHAR(32) NOT NULL,
                json_value LONGTEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_slot (user_id, session_id, state_key)
            )
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { it.executeUpdate() }
        }
    }

    override fun save(userId: String, sessionId: String, key: String, value: State) {
        val uid = userId.ifBlank { ANON_USER }
        val json = JsonUtils.getJsonCodec().toJson(value)
        val sql = """
            INSERT INTO $tableName (user_id, session_id, state_key, state_type, json_value)
            VALUES (?, ?, ?, 'SINGLE', ?)
            ON DUPLICATE KEY UPDATE state_type = 'SINGLE', json_value = VALUES(json_value)
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.setString(2, sessionId)
                ps.setString(3, key)
                ps.setString(4, json)
                ps.executeUpdate()
            }
        }
    }

    override fun save(userId: String, sessionId: String, key: String, values: List<out State>) {
        val uid = userId.ifBlank { ANON_USER }
        // Serialize as a wrapper object to distinguish from single state
        val json = JsonUtils.getJsonCodec().toJson(ListStateWrapper(values.map { JsonUtils.getJsonCodec().toJson(it) }))
        val sql = """
            INSERT INTO $tableName (user_id, session_id, state_key, state_type, json_value)
            VALUES (?, ?, ?, 'LIST', ?)
            ON DUPLICATE KEY UPDATE state_type = 'LIST', json_value = VALUES(json_value)
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.setString(2, sessionId)
                ps.setString(3, key)
                ps.setString(4, json)
                ps.executeUpdate()
            }
        }
    }

    override fun <T : State> get(
        userId: String,
        sessionId: String,
        key: String,
        type: Class<T>,
    ): Optional<T> {
        val uid = userId.ifBlank { ANON_USER }
        val sql = "SELECT json_value FROM $tableName WHERE user_id = ? AND session_id = ? AND state_key = ?"
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.setString(2, sessionId)
                ps.setString(3, key)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val json = rs.getString("json_value")
                        Optional.of(JsonUtils.getJsonCodec().fromJson(json, type))
                    } else {
                        Optional.empty()
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : State> getList(
        userId: String,
        sessionId: String,
        key: String,
        itemType: Class<T>,
    ): List<T> {
        val uid = userId.ifBlank { ANON_USER }
        val sql = "SELECT json_value, state_type FROM $tableName WHERE user_id = ? AND session_id = ? AND state_key = ?"
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.setString(2, sessionId)
                ps.setString(3, key)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val json = rs.getString("json_value")
                        val stateType = rs.getString("state_type")
                        if (stateType == "LIST") {
                            val wrapper = JsonUtils.getJsonCodec().fromJson(json, ListStateWrapper::class.java)
                            wrapper.items.map { JsonUtils.getJsonCodec().fromJson(it, itemType) }
                        } else {
                            // SINGLE value stored, wrap in list
                            val item = JsonUtils.getJsonCodec().fromJson(json, itemType)
                            listOf(item)
                        }
                    } else {
                        emptyList()
                    }
                }
            }
        }
    }

    override fun exists(userId: String, sessionId: String): Boolean {
        val uid = userId.ifBlank { ANON_USER }
        val sql = "SELECT COUNT(*) FROM $tableName WHERE user_id = ? AND session_id = ? LIMIT 1"
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.setString(2, sessionId)
                ps.executeQuery().use { rs ->
                    rs.next() && rs.getInt(1) > 0
                }
            }
        }
    }

    override fun delete(userId: String, sessionId: String) {
        val uid = userId.ifBlank { ANON_USER }
        val sql = "DELETE FROM $tableName WHERE user_id = ? AND session_id = ?"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.setString(2, sessionId)
                ps.executeUpdate()
            }
        }
    }

    override fun delete(userId: String, sessionId: String, key: String) {
        val uid = userId.ifBlank { ANON_USER }
        val sql = "DELETE FROM $tableName WHERE user_id = ? AND session_id = ? AND state_key = ?"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.setString(2, sessionId)
                ps.setString(3, key)
                ps.executeUpdate()
            }
        }
    }

    override fun listSessionIds(userId: String): Set<String> {
        val uid = userId.ifBlank { ANON_USER }
        val sql = "SELECT DISTINCT session_id FROM $tableName WHERE user_id = ?"
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.executeQuery().use { rs ->
                    val ids = mutableSetOf<String>()
                    while (rs.next()) {
                        ids.add(rs.getString("session_id"))
                    }
                    ids
                }
            }
        }
    }

    /**
     * Internal wrapper for serializing a list of State items as JSON.
     * Each item is pre-serialized to JSON string to allow heterogeneous types.
     */
    data class ListStateWrapper(val items: List<String>) : State

    private companion object {
        const val ANON_USER = "__anon__"
    }
}
