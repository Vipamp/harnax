package com.agnetix.harnax.agent.session

import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * H2-backed tests for [MysqlSessionMessageStore]: the idempotent upsert, the read-back order and the
 * summary exclusion are what keeps the chat page showing the full original conversation after the model
 * context has been compacted, so they are checked against a real database rather than a mock.
 */
class MysqlSessionMessageStoreH2Test {

    private lateinit var dataSource: JdbcDataSource
    private lateinit var store: MysqlSessionMessageStore

    @BeforeEach
    fun setUp() {
        dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:msgdb_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        store = MysqlSessionMessageStore(dataSource, "session_message", createIfNotExist = true)
    }

    @AfterEach
    fun tearDown() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DROP TABLE IF EXISTS session_message").use { it.executeUpdate() }
        }
    }

    private fun msg(
        id: String,
        role: MsgRole,
        name: String,
        text: String,
        timestamp: String = "2026-10-05T00:00:00Z",
    ): Msg = Msg.builder()
        .id(id)
        .role(role)
        .name(name)
        .textContent(text)
        .timestamp(timestamp)
        .build()

    @Nested
    inner class ArchiveAndLoad {
        @Test
        fun `archived messages read back in the order they were first recorded`() {
            store.archive(
                "",
                "s1",
                listOf(
                    msg("m1", MsgRole.USER, "user", "first"),
                    msg("m2", MsgRole.ASSISTANT, "assistant", "second"),
                ),
            )

            val loaded = store.load("", "s1")

            assertEquals(listOf("m1", "m2"), loaded.map { it.id })
            assertEquals(listOf("first", "second"), loaded.map { it.textContent })
        }

        @Test
        fun `auto-increment order wins over the message timestamp`() {
            // A session database reached over serverTimezone=UTC and a primary one on Asia/Shanghai do not
            // share a clock reading, so the bubble order must come from this table's own id.
            store.archive(
                "",
                "s1",
                listOf(
                    msg("m1", MsgRole.USER, "user", "older stamp", timestamp = "2026-10-05T10:00:00Z"),
                    msg("m2", MsgRole.ASSISTANT, "assistant", "newer stamp", timestamp = "2026-10-05T09:00:00Z"),
                ),
            )

            assertEquals(listOf("m1", "m2"), store.load("", "s1").map { it.id })
        }

        @Test
        fun `a blank user id and an explicit one land in the same bucket`() {
            store.archive("", "s1", listOf(msg("m1", MsgRole.USER, "user", "hello")))

            assertEquals(1, store.load(null, "s1").size)
            assertEquals(1, store.load("__anon__", "s1").size)
        }
    }

    @Nested
    inner class Idempotency {
        @Test
        fun `re-archiving the same id keeps one row and takes the newer body`() {
            store.archive("", "s1", listOf(msg("m1", MsgRole.USER, "user", "short")))
            store.archive("", "s1", listOf(msg("m1", MsgRole.USER, "user", "fuller rebuild")))

            val loaded = store.load("", "s1")

            assertEquals(1, loaded.size)
            assertEquals("fuller rebuild", loaded[0].textContent)
        }

        @Test
        fun `a rebuild that trims the body keeps the archived original`() {
            // Upstream's prune step rewrites the SAME Msg.id with a short preview of a long tool result, so
            // the newer version is not automatically the fuller one. The page reads this table, so a preview
            // taking over would cut a bubble down to what only the model is meant to still see.
            store.archive("", "s1", listOf(msg("m1", MsgRole.ASSISTANT, "assistant", "x".repeat(5000))))
            store.archive("", "s1", listOf(msg("m1", MsgRole.ASSISTANT, "assistant", "x".repeat(200))))

            val loaded = store.load("", "s1")

            assertEquals(1, loaded.size)
            assertEquals(5000, loaded[0].textContent.length, "the fullest version is the one the page keeps")
        }

        @Test
        fun `writing the whole context every turn never duplicates a row`() {
            val first = listOf(
                msg("m1", MsgRole.USER, "user", "one"),
                msg("m2", MsgRole.ASSISTANT, "assistant", "two"),
            )
            store.archive("", "s1", first)
            store.archive("", "s1", first)
            store.archive("", "s1", first + msg("m3", MsgRole.USER, "user", "three"))

            assertEquals(listOf("m1", "m2", "m3"), store.load("", "s1").map { it.id })
        }
    }

    @Nested
    inner class Exclusions {
        @Test
        fun `a compaction summary is never archived`() {
            // The summary is built as a USER message; archiving it would put a bubble of summary text on a
            // page that must show only the original conversation.
            val summary = Msg.builder()
                .id("s-1")
                .role(MsgRole.USER)
                .name(ConversationCompactor.SUMMARY_MSG_NAME)
                .textContent("Earlier the user asked about the weather.")
                .build()

            val recorded = store.archive("", "s1", listOf(msg("m1", MsgRole.USER, "user", "hi"), summary))

            assertEquals(1, recorded)
            assertEquals(listOf("m1"), store.load("", "s1").map { it.id })
        }

        @Test
        fun `a message with a blank id is skipped instead of failing the turn`() {
            // Msg.Builder stamps a random UUID when no id is given, so this only happens if a caller
            // clears it explicitly; msg_id is NOT NULL, so the alternative is losing the whole archive
            // write for the turn.
            val blankId = Msg.builder()
                .id("")
                .role(MsgRole.USER)
                .name("user")
                .textContent("orphan")
                .build()

            val recorded = store.archive("", "s1", listOf(blankId, msg("m1", MsgRole.USER, "user", "kept")))

            assertEquals(1, recorded)
            assertEquals(listOf("m1"), store.load("", "s1").map { it.id })
        }

        @Test
        fun `a message built without an explicit id still archives under its own id`() {
            val autoId = Msg.builder().role(MsgRole.USER).name("user").textContent("auto").build()

            store.archive("", "s1", listOf(autoId))

            assertEquals(listOf(autoId.id), store.load("", "s1").map { it.id })
        }
    }

    @Nested
    inner class Delete {
        @Test
        fun `deleting a session removes its archive and leaves the others alone`() {
            store.archive("", "s1", listOf(msg("m1", MsgRole.USER, "user", "one")))
            store.archive("", "s2", listOf(msg("m2", MsgRole.USER, "user", "two")))

            val deleted = store.delete("", "s1")

            assertEquals(1, deleted)
            assertTrue(store.load("", "s1").isEmpty())
            assertEquals(listOf("m2"), store.load("", "s2").map { it.id })
        }

        @Test
        fun `an unknown session has nothing to read and nothing to delete`() {
            assertTrue(store.load("", "nope").isEmpty())
            assertEquals(0, store.delete("", "nope"))
        }
    }
}
