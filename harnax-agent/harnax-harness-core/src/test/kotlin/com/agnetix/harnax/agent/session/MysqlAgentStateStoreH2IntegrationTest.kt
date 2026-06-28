package com.agnetix.harnax.agent.session

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.agentscope.core.state.State
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Integration test for MysqlAgentStateStore using H2 in-memory database.
 *
 * Tests real JDBC operations against an actual database to verify SQL correctness,
 * upsert behavior, and edge cases that unit tests with mocks cannot cover.
 */
class MysqlAgentStateStoreH2IntegrationTest {

    private lateinit var dataSource: JdbcDataSource
    private lateinit var store: MysqlAgentStateStore

    @BeforeEach
    fun setUp() {
        dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:testdb_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        store = MysqlAgentStateStore(dataSource, "agent_state", createIfNotExist = true)
    }

    @AfterEach
    fun tearDown() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DROP TABLE IF EXISTS agent_state").use { it.executeUpdate() }
        }
    }

    // ==================== save + get roundtrip ====================

    @Nested
    inner class SaveAndGet {
        @Test
        fun `save and retrieve single state`() {
            val state = TestState("hello world")
            store.save("user1", "session1", "key1", state)

            val result = store.get("user1", "session1", "key1", TestState::class.java)

            assertTrue(result.isPresent)
            assertEquals("hello world", result.get().value)
        }

        @Test
        fun `save overwrites existing state via upsert`() {
            store.save("user1", "session1", "key1", TestState("v1"))
            store.save("user1", "session1", "key1", TestState("v2"))

            val result = store.get("user1", "session1", "key1", TestState::class.java)

            assertTrue(result.isPresent)
            assertEquals("v2", result.get().value)
        }

        @Test
        fun `save with null userId stored under anon key`() {
            store.save(null, "session1", "key1", TestState("anon data"))

            val result = store.get(null, "session1", "key1", TestState::class.java)
            assertTrue(result.isPresent)
            assertEquals("anon data", result.get().value)

            val result2 = store.get("", "session1", "key1", TestState::class.java)
            assertTrue(result2.isPresent)
        }

        @Test
        fun `get returns empty for non-existent key`() {
            val result = store.get("user1", "session1", "nonexistent", TestState::class.java)
            assertFalse(result.isPresent)
        }

        @Test
        fun `different users have isolated state`() {
            store.save("user1", "session1", "key1", TestState("user1 data"))
            store.save("user2", "session1", "key1", TestState("user2 data"))

            val r1 = store.get("user1", "session1", "key1", TestState::class.java)
            val r2 = store.get("user2", "session1", "key1", TestState::class.java)

            assertEquals("user1 data", r1.get().value)
            assertEquals("user2 data", r2.get().value)
        }

        @Test
        fun `different sessions have isolated state`() {
            store.save("user1", "session1", "key1", TestState("s1"))
            store.save("user1", "session2", "key1", TestState("s2"))

            assertEquals("s1", store.get("user1", "session1", "key1", TestState::class.java).get().value)
            assertEquals("s2", store.get("user1", "session2", "key1", TestState::class.java).get().value)
        }
    }

    // ==================== save list + getList roundtrip ====================

    @Nested
    inner class SaveListAndGetList {
        @Test
        fun `save list and retrieve all items`() {
            val states = listOf(TestState("a"), TestState("b"), TestState("c"))
            store.save("user1", "session1", "msgs", states)

            val result = store.getList("user1", "session1", "msgs", TestState::class.java)

            assertEquals(3, result.size)
            assertEquals("a", result[0].value)
            assertEquals("b", result[1].value)
            assertEquals("c", result[2].value)
        }

        @Test
        fun `save list overwrites existing list`() {
            store.save("user1", "session1", "msgs", listOf(TestState("old1"), TestState("old2")))
            store.save("user1", "session1", "msgs", listOf(TestState("new1")))

            val result = store.getList("user1", "session1", "msgs", TestState::class.java)

            assertEquals(1, result.size)
            assertEquals("new1", result[0].value)
        }

        @Test
        fun `getList returns empty list for non-existent key`() {
            val result = store.getList("user1", "session1", "nonexistent", TestState::class.java)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `getList wraps SINGLE state in a single-element list`() {
            store.save("user1", "session1", "key1", TestState("single"))

            val result = store.getList("user1", "session1", "key1", TestState::class.java)

            assertEquals(1, result.size)
            assertEquals("single", result[0].value)
        }

        @Test
        fun `save empty list and retrieve it`() {
            store.save("user1", "session1", "msgs", emptyList<TestState>())

            val result = store.getList("user1", "session1", "msgs", TestState::class.java)
            assertTrue(result.isEmpty())
        }
    }

    // ==================== exists ====================

    @Nested
    inner class Exists {
        @Test
        fun `exists returns true for existing session`() {
            store.save("user1", "session1", "key1", TestState("data"))
            assertTrue(store.exists("user1", "session1"))
        }

        @Test
        fun `exists returns false for non-existent session`() {
            assertFalse(store.exists("user1", "nonexistent"))
        }

        @Test
        fun `exists returns false for wrong userId`() {
            store.save("user1", "session1", "key1", TestState("data"))
            assertFalse(store.exists("user2", "session1"))
        }
    }

    // ==================== delete ====================

    @Nested
    inner class Delete {
        @Test
        fun `delete by sessionId removes all keys for that session`() {
            store.save("user1", "session1", "key1", TestState("a"))
            store.save("user1", "session1", "key2", TestState("b"))

            store.delete("user1", "session1")

            assertFalse(store.get("user1", "session1", "key1", TestState::class.java).isPresent)
            assertFalse(store.get("user1", "session1", "key2", TestState::class.java).isPresent)
            assertFalse(store.exists("user1", "session1"))
        }

        @Test
        fun `delete by key removes only that key`() {
            store.save("user1", "session1", "key1", TestState("a"))
            store.save("user1", "session1", "key2", TestState("b"))

            store.delete("user1", "session1", "key1")

            assertFalse(store.get("user1", "session1", "key1", TestState::class.java).isPresent)
            assertTrue(store.get("user1", "session1", "key2", TestState::class.java).isPresent)
        }

        @Test
        fun `delete non-existent session is safe`() {
            assertDoesNotThrow { store.delete("user1", "nonexistent") }
        }

        @Test
        fun `delete does not affect other sessions`() {
            store.save("user1", "session1", "key1", TestState("keep"))
            store.save("user1", "session2", "key1", TestState("delete-me"))

            store.delete("user1", "session2")

            assertTrue(store.get("user1", "session1", "key1", TestState::class.java).isPresent)
            assertFalse(store.exists("user1", "session2"))
        }
    }

    // ==================== listSessionIds ====================

    @Nested
    inner class ListSessionIds {
        @Test
        fun `listSessionIds returns all distinct session ids`() {
            store.save("user1", "session-a", "key1", TestState("1"))
            store.save("user1", "session-b", "key1", TestState("2"))
            store.save("user1", "session-a", "key2", TestState("3"))

            val ids = store.listSessionIds("user1")

            assertEquals(setOf("session-a", "session-b"), ids)
        }

        @Test
        fun `listSessionIds returns empty set for unknown user`() {
            val ids = store.listSessionIds("unknown-user")
            assertTrue(ids.isEmpty())
        }

        @Test
        fun `listSessionIds does not leak across users`() {
            store.save("user1", "session-1", "key1", TestState("a"))
            store.save("user2", "session-2", "key1", TestState("b"))

            assertEquals(setOf("session-1"), store.listSessionIds("user1"))
            assertEquals(setOf("session-2"), store.listSessionIds("user2"))
        }
    }

    // ==================== Concurrent operations ====================

    @Nested
    inner class ConcurrentOperations {
        @Test
        fun `concurrent saves to same key do not corrupt`() {
            val threads = (1..10).map { i ->
                Thread {
                    store.save("user1", "session1", "counter", TestState("value-$i"))
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            val result = store.get("user1", "session1", "counter", TestState::class.java)
            assertTrue(result.isPresent)
            assertTrue(result.get().value.startsWith("value-"))
        }

        @Test
        fun `concurrent saves to different keys all succeed`() {
            val threads = (1..10).map { i ->
                Thread {
                    store.save("user1", "session1", "key-$i", TestState("value-$i"))
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            for (i in 1..10) {
                val result = store.get("user1", "session1", "key-$i", TestState::class.java)
                assertTrue(result.isPresent, "key-$i should exist")
                assertEquals("value-$i", result.get().value)
            }
        }
    }

    // ==================== Table auto-creation ====================

    @Nested
    inner class TableCreation {
        @Test
        fun `constructor auto-creates table when enabled`() {
            val ds = JdbcDataSource().apply {
                setURL("jdbc:h2:mem:autocreate_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1")
                user = "sa"
                password = ""
            }

            val s = MysqlAgentStateStore(ds, "my_states", createIfNotExist = true)
            s.save("u", "s", "k", TestState("ok"))

            val result = s.get("u", "s", "k", TestState::class.java)
            assertTrue(result.isPresent)

            ds.connection.use { conn ->
                conn.prepareStatement("DROP TABLE my_states").use { it.executeUpdate() }
            }
        }
    }

    // ==================== Special characters and edge values ====================

    @Nested
    inner class SpecialValues {
        @Test
        fun `save and retrieve state with unicode characters`() {
            val state = TestState("你好世界 🌍 hello")
            store.save("user1", "session1", "key1", state)

            val result = store.get("user1", "session1", "key1", TestState::class.java)
            assertTrue(result.isPresent)
            assertEquals("你好世界 🌍 hello", result.get().value)
        }

        @Test
        fun `save and retrieve state with JSON special characters`() {
            val state = TestState(
                """value with "quotes" and \backslash and newlines
multiline""",
            )
            store.save("user1", "session1", "key1", state)

            val result = store.get("user1", "session1", "key1", TestState::class.java)
            assertTrue(result.isPresent)
            assertTrue(result.get().value.contains("\"quotes\""))
            assertTrue(result.get().value.contains("multiline"))
        }

        @Test
        fun `save and retrieve state with empty string value`() {
            val state = TestState("")
            store.save("user1", "session1", "key1", state)

            val result = store.get("user1", "session1", "key1", TestState::class.java)
            assertTrue(result.isPresent)
            assertEquals("", result.get().value)
        }

        @Test
        fun `save with null sessionId does not throw`() {
            assertDoesNotThrow {
                store.save("user1", null, "key1", TestState("data"))
            }
        }

        @Test
        fun `save and retrieve list with many items`() {
            val states = (1..50).map { TestState("item-$it") }
            store.save("user1", "session1", "big-list", states)

            val result = store.getList("user1", "session1", "big-list", TestState::class.java)
            assertEquals(50, result.size)
            assertEquals("item-1", result[0].value)
            assertEquals("item-50", result[49].value)
        }

        @Test
        fun `switching between single and list on same key works`() {
            store.save("user1", "session1", "key1", TestState("single"))
            store.save("user1", "session1", "key1", listOf(TestState("a"), TestState("b")))

            val listResult = store.getList("user1", "session1", "key1", TestState::class.java)
            assertEquals(2, listResult.size)

            store.save("user1", "session1", "key1", TestState("back-to-single"))
            val singleResult = store.get("user1", "session1", "key1", TestState::class.java)
            assertTrue(singleResult.isPresent)
            assertEquals("back-to-single", singleResult.get().value)
        }
    }

    data class TestState @JsonCreator constructor(
        @JsonProperty("value") val value: String,
    ) : State
}
