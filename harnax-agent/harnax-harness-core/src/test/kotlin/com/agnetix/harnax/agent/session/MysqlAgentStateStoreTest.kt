package com.agnetix.harnax.agent.session

import io.agentscope.core.state.State
import io.agentscope.core.util.JsonUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

class MysqlAgentStateStoreTest {

    private lateinit var dataSource: DataSource
    private lateinit var connection: Connection
    private lateinit var preparedStatement: PreparedStatement
    private lateinit var resultSet: ResultSet
    private lateinit var store: MysqlAgentStateStore

    @BeforeEach
    fun setUp() {
        dataSource = mock(DataSource::class.java)
        connection = mock(Connection::class.java)
        preparedStatement = mock(PreparedStatement::class.java)
        resultSet = mock(ResultSet::class.java)

        `when`(dataSource.connection).thenReturn(connection)
        `when`(connection.prepareStatement(anyString())).thenReturn(preparedStatement)
        `when`(preparedStatement.executeQuery()).thenReturn(resultSet)
        `when`(preparedStatement.executeUpdate()).thenReturn(1)

        store = MysqlAgentStateStore(dataSource, "agent_state", createIfNotExist = false)
    }

    // ==================== save (single) ====================

    @Nested
    inner class SaveSingle {
        @Test
        fun `save single state with valid userId`() {
            val testState = TestState("hello")
            store.save("user1", "session1", "key1", testState)

            verify(preparedStatement).setString(1, "user1")
            verify(preparedStatement).setString(2, "session1")
            verify(preparedStatement).setString(3, "key1")
            verify(preparedStatement).executeUpdate()
        }

        @Test
        fun `save single state with null userId uses anon`() {
            val testState = TestState("hello")
            store.save(null, "session1", "key1", testState)

            verify(preparedStatement).setString(1, "__anon__")
        }

        @Test
        fun `save single state with blank userId uses anon`() {
            val testState = TestState("hello")
            store.save("", "session1", "key1", testState)

            verify(preparedStatement).setString(1, "__anon__")
        }

        @Test
        fun `save single state sets state_type to SINGLE`() {
            val testState = TestState("hello")
            store.save("user1", "session1", "key1", testState)

            val sqlCaptor = org.mockito.ArgumentCaptor.forClass(String::class.java)
            verify(connection).prepareStatement(sqlCaptor.capture())
            val capturedSql = sqlCaptor.value
            assertTrue(capturedSql.contains("'SINGLE'"))
        }
    }

    // ==================== save (list) ====================

    @Nested
    inner class SaveList {
        @Test
        fun `save list state with valid userId`() {
            val states = listOf(TestState("a"), TestState("b"))
            store.save("user1", "session1", "key1", states)

            verify(preparedStatement).setString(1, "user1")
            verify(preparedStatement).setString(2, "session1")
            verify(preparedStatement).setString(3, "key1")
            verify(preparedStatement).executeUpdate()
        }

        @Test
        fun `save list state sets state_type to LIST`() {
            val states = listOf(TestState("a"))
            store.save("user1", "session1", "key1", states)

            val sqlCaptor = org.mockito.ArgumentCaptor.forClass(String::class.java)
            verify(connection).prepareStatement(sqlCaptor.capture())
            val capturedSql = sqlCaptor.value
            assertTrue(capturedSql.contains("'LIST'"))
        }

        @Test
        fun `save empty list state`() {
            val states = emptyList<TestState>()
            store.save("user1", "session1", "key1", states)

            verify(preparedStatement).executeUpdate()
        }
    }

    // ==================== get ====================

    @Nested
    inner class Get {
        @Test
        fun `get returns state when record exists`() {
            val testState = TestState("hello")
            val json = JsonUtils.getJsonCodec().toJson(testState)

            `when`(resultSet.next()).thenReturn(true)
            `when`(resultSet.getString("json_value")).thenReturn(json)

            val result = store.get("user1", "session1", "key1", TestState::class.java)

            assertTrue(result.isPresent)
            assertEquals("hello", result.get().value)
        }

        @Test
        fun `get returns empty when no record found`() {
            `when`(resultSet.next()).thenReturn(false)

            val result = store.get("user1", "session1", "key1", TestState::class.java)

            assertFalse(result.isPresent)
        }

        @Test
        fun `get with null userId uses anon`() {
            `when`(resultSet.next()).thenReturn(false)

            store.get(null, "session1", "key1", TestState::class.java)

            verify(preparedStatement).setString(1, "__anon__")
        }
    }

    // ==================== getList ====================

    @Nested
    inner class GetList {
        @Test
        fun `getList returns items when state_type is LIST`() {
            val states = listOf(TestState("a"), TestState("b"))
            val wrapper = MysqlAgentStateStore.ListStateWrapper(
                states.map { JsonUtils.getJsonCodec().toJson(it) },
            )
            val wrapperJson = JsonUtils.getJsonCodec().toJson(wrapper)

            `when`(resultSet.next()).thenReturn(true)
            `when`(resultSet.getString("json_value")).thenReturn(wrapperJson)
            `when`(resultSet.getString("state_type")).thenReturn("LIST")

            val result = store.getList("user1", "session1", "key1", TestState::class.java)

            assertEquals(2, result.size)
            assertEquals("a", result[0].value)
            assertEquals("b", result[1].value)
        }

        @Test
        fun `getList wraps SINGLE item in list`() {
            val testState = TestState("single")
            val json = JsonUtils.getJsonCodec().toJson(testState)

            `when`(resultSet.next()).thenReturn(true)
            `when`(resultSet.getString("json_value")).thenReturn(json)
            `when`(resultSet.getString("state_type")).thenReturn("SINGLE")

            val result = store.getList("user1", "session1", "key1", TestState::class.java)

            assertEquals(1, result.size)
            assertEquals("single", result[0].value)
        }

        @Test
        fun `getList returns empty list when no record`() {
            `when`(resultSet.next()).thenReturn(false)

            val result = store.getList("user1", "session1", "key1", TestState::class.java)

            assertTrue(result.isEmpty())
        }
    }

    // ==================== exists ====================

    @Nested
    inner class Exists {
        @Test
        fun `exists returns true when count is greater than zero`() {
            `when`(resultSet.next()).thenReturn(true)
            `when`(resultSet.getInt(1)).thenReturn(5)

            val result = store.exists("user1", "session1")

            assertTrue(result)
        }

        @Test
        fun `exists returns false when count is zero`() {
            `when`(resultSet.next()).thenReturn(true)
            `when`(resultSet.getInt(1)).thenReturn(0)

            val result = store.exists("user1", "session1")

            assertFalse(result)
        }

        @Test
        fun `exists returns false when no row returned`() {
            `when`(resultSet.next()).thenReturn(false)

            val result = store.exists("user1", "session1")

            assertFalse(result)
        }

        @Test
        fun `exists with blank userId uses anon`() {
            `when`(resultSet.next()).thenReturn(true)
            `when`(resultSet.getInt(1)).thenReturn(0)

            store.exists("  ", "session1")

            verify(preparedStatement).setString(1, "__anon__")
        }
    }

    // ==================== delete ====================

    @Nested
    inner class Delete {
        @Test
        fun `delete by sessionId uses correct userId`() {
            store.delete("user1", "session1")

            verify(preparedStatement).setString(1, "user1")
            verify(preparedStatement).setString(2, "session1")
            verify(preparedStatement).executeUpdate()
        }

        @Test
        fun `delete by sessionId with null userId uses anon`() {
            store.delete(null, "session1")

            verify(preparedStatement).setString(1, "__anon__")
        }

        @Test
        fun `delete by key uses correct parameters`() {
            store.delete("user1", "session1", "key1")

            verify(preparedStatement).setString(1, "user1")
            verify(preparedStatement).setString(2, "session1")
            verify(preparedStatement).setString(3, "key1")
            verify(preparedStatement).executeUpdate()
        }
    }

    // ==================== listSessionIds ====================

    @Nested
    inner class ListSessionIds {
        @Test
        fun `listSessionIds returns distinct session ids`() {
            `when`(resultSet.next())
                .thenReturn(true)
                .thenReturn(true)
                .thenReturn(false)
            `when`(resultSet.getString("session_id"))
                .thenReturn("session1")
                .thenReturn("session2")

            val result = store.listSessionIds("user1")

            assertEquals(setOf("session1", "session2"), result)
        }

        @Test
        fun `listSessionIds returns empty set when no records`() {
            `when`(resultSet.next()).thenReturn(false)

            val result = store.listSessionIds("user1")

            assertTrue(result.isEmpty())
        }

        @Test
        fun `listSessionIds with null userId uses anon`() {
            `when`(resultSet.next()).thenReturn(false)

            store.listSessionIds(null)

            verify(preparedStatement).setString(1, "__anon__")
        }
    }

    // ==================== createTableIfNeeded ====================

    @Nested
    inner class CreateTable {
        @Test
        fun `constructor creates table when createIfNotExist is true`() {
            val ds = mock(DataSource::class.java)
            val conn = mock(Connection::class.java)
            val ps = mock(PreparedStatement::class.java)
            `when`(ds.connection).thenReturn(conn)
            `when`(conn.prepareStatement(anyString())).thenReturn(ps)
            `when`(ps.executeUpdate()).thenReturn(0)

            MysqlAgentStateStore(ds, "test_table", createIfNotExist = true)

            val sqlCaptor = org.mockito.ArgumentCaptor.forClass(String::class.java)
            verify(conn).prepareStatement(sqlCaptor.capture())
            assertTrue(sqlCaptor.value.contains("CREATE TABLE IF NOT EXISTS test_table"))
        }

        @Test
        fun `constructor skips table creation when createIfNotExist is false`() {
            val ds = mock(DataSource::class.java)
            val conn = mock(Connection::class.java)
            `when`(ds.connection).thenReturn(conn)

            MysqlAgentStateStore(ds, "test_table", createIfNotExist = false)

            verify(conn, never()).prepareStatement(anyString())
        }
    }

    // ==================== Test State class ====================

    data class TestState(val value: String) : State
}
