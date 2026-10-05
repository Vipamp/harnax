package com.agnetix.harnax.agent.session

import io.agentscope.core.message.Msg
import io.agentscope.core.util.JsonUtils
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Append-only archive of every message a session has ever shown, one row per owner bucket, session and
 * [Msg.getId].
 *
 * The model context (`AgentState.context`) is a living buffer that compaction rewrites in place, while
 * the chat page has to keep showing the full original conversation. Those two readers cannot share one
 * store, so this table is the read side: written with the whole live context at the end of each turn,
 * never trimmed, never rewritten by compaction.
 *
 * Table schema (auto-created):
 * ```sql
 * CREATE TABLE IF NOT EXISTS session_message (
 *     id BIGINT AUTO_INCREMENT PRIMARY KEY,
 *     user_id VARCHAR(255) NOT NULL,
 *     session_id VARCHAR(255) NOT NULL,
 *     msg_id VARCHAR(64) NOT NULL,
 *     role VARCHAR(16) NOT NULL,
 *     msg_name VARCHAR(128) NULL,
 *     json_value LONGTEXT NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     UNIQUE KEY uk_session_msg (user_id, session_id, msg_id),
 *     KEY idx_session_order (session_id, id)
 * );
 * ```
 *
 * @param dataSource JDBC DataSource of the session database, the same one that holds `agent_state`
 * @param tableName table name (default: "session_message")
 * @param createIfNotExist whether to auto-create the table
 */
class MysqlSessionMessageStore(
    private val dataSource: DataSource,
    private val tableName: String = "session_message",
    private val createIfNotExist: Boolean = true,
) {

    private val log = LoggerFactory.getLogger(MysqlSessionMessageStore::class.java)

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
                msg_id VARCHAR(64) NOT NULL,
                role VARCHAR(16) NOT NULL,
                msg_name VARCHAR(128) NULL,
                json_value LONGTEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY uk_session_msg (user_id, session_id, msg_id),
                KEY idx_session_order (session_id, id)
            )
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { it.executeUpdate() }
        }
    }

    private fun normalizeUserId(userId: String?): String = userId?.ifBlank { ANON_USER } ?: ANON_USER

    /**
     * Idempotently records every message of one turn.
     *
     * Called with the whole live context each turn rather than with a per-turn delta, so a write that
     * fails is healed by the next turn and neither compaction path needs a hook of its own. A message
     * whose id already exists in this session and owner bucket is compared, not replaced. Upstream
     * rebuilds the same `Msg.id` in both directions — `withContent` / `withMetadata` /
     * `withGenerateReason` append, while the compactor's prune step replaces a long tool result with a
     * preview — and the page must keep the fullest body.
     *
     * Compaction summaries are skipped on purpose — they are built as `USER` messages, so archiving one
     * would put a bubble of summary text on a page that must show only the original conversation.
     *
     * @return number of messages written or refreshed
     */
    fun archive(
        userId: String?,
        sessionId: String,
        messages: List<Msg>,
    ): Int {
        if (messages.isEmpty()) return 0
        val uid = normalizeUserId(userId)
        // "Newer" is not the same as "fuller": upstream's prune step rewrites the SAME Msg.id with a
        // 2000-char preview of a long tool result, so an unconditional overwrite would take the trimmed
        // body into the table the page reads. The row keeps the longest version ever written, and
        // role/msg_name follow that same version rather than the newest one.
        val sql = """
            INSERT INTO $tableName (user_id, session_id, msg_id, role, msg_name, json_value)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                json_value = CASE WHEN CHAR_LENGTH(VALUES(json_value)) > CHAR_LENGTH(json_value) THEN VALUES(json_value) ELSE json_value END,
                role = CASE WHEN CHAR_LENGTH(VALUES(json_value)) > CHAR_LENGTH(json_value) THEN VALUES(role) ELSE role END,
                msg_name = CASE WHEN CHAR_LENGTH(VALUES(json_value)) > CHAR_LENGTH(json_value) THEN VALUES(msg_name) ELSE msg_name END
        """.trimIndent()
        var recorded = 0
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                for (msg in messages) {
                    val msgId = msg.id
                    if (msgId.isNullOrBlank()) {
                        log.warn("Skipping archive of a message without an id: session={}, role={}", sessionId, msg.role)
                        continue
                    }
                    if (msg.name == ConversationCompactor.SUMMARY_MSG_NAME) continue
                    ps.setString(1, uid)
                    ps.setString(2, sessionId)
                    ps.setString(3, msgId)
                    ps.setString(4, msg.role?.name ?: "")
                    ps.setString(5, msg.name)
                    ps.setString(6, JsonUtils.getJsonCodec().toJson(msg))
                    ps.executeUpdate()
                    // Counted rather than summed from executeUpdate: MySQL answers 1 for an insert and
                    // 2 for an upsert that changed the row, so the driver's number is not a row count.
                    recorded++
                }
            }
        }
        return recorded
    }

    /**
     * Reads a session's full history in the order it was first recorded.
     *
     * Ordered by this table's own auto-increment id rather than by any timestamp: the session database
     * is reached over a `serverTimezone=UTC` connection while the primary one is `Asia/Shanghai`, so
     * time columns across the two are not comparable. The instant a bubble shows comes from `Msg.timestamp`.
     *
     * @return the archived messages, empty when this session has no archive yet
     */
    fun load(
        userId: String?,
        sessionId: String,
    ): List<Msg> {
        val uid = normalizeUserId(userId)
        val sql = "SELECT json_value FROM $tableName WHERE user_id = ? AND session_id = ? ORDER BY id ASC"
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.setString(2, sessionId)
                ps.executeQuery().use { rs ->
                    val messages = mutableListOf<Msg>()
                    while (rs.next()) {
                        runCatching { JsonUtils.getJsonCodec().fromJson(rs.getString("json_value"), Msg::class.java) }
                            .onFailure { log.warn("Unreadable archived message dropped for session={}: {}", sessionId, it.message) }
                            .getOrNull()
                            ?.let { messages.add(it) }
                    }
                    messages
                }
            }
        }
    }

    /**
     * Removes a session's archive, called when the session itself is deleted.
     *
     * @return number of rows deleted
     */
    fun delete(
        userId: String?,
        sessionId: String,
    ): Int {
        val uid = normalizeUserId(userId)
        val sql = "DELETE FROM $tableName WHERE user_id = ? AND session_id = ?"
        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, uid)
                ps.setString(2, sessionId)
                ps.executeUpdate()
            }
        }
    }

    private companion object {
        const val ANON_USER = "__anon__"
    }
}
