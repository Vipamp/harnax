package com.agnetix.harnax.channel.service.bootstrap

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * [ChannelListenerLockGuard] 的测试。两个方向都得成立：抢不到锁时这个实例不能提供服务，
 * 而与锁无关的数据库故障不能让它停下来。
 */
class ChannelListenerLockGuardTest {

    private val lockName = "harnax-channel-listeners"

    private fun guard(
        dataSource: DataSource,
        lockEnabled: Boolean = true,
    ) = ChannelListenerLockGuard(dataSource, lockEnabled, lockName)

    /**
     * 构造一个连接，它的 `SELECT GET_LOCK(...)` 按 [outcomes] 顺序作答，最后一个答案重复使用。
     * `1` 表示抢到锁，`0` 表示被别的实例持有，`null` 表示根本没查到结果行。
     *
     * @param failAfter 先正常应答这么多次，之后抛异常，模拟服务端把连接断掉。
     */
    private fun lockConnection(
        vararg outcomes: Int?,
        closed: AtomicBoolean = AtomicBoolean(false),
        failAfter: Int? = null,
    ): Connection {
        val conn = mock<Connection>()
        val polls = AtomicInteger(0)
        whenever(conn.isClosed).thenAnswer { closed.get() }
        doAnswer { closed.set(true) }.whenever(conn).close()
        whenever(conn.prepareStatement(GET_LOCK_SQL)).thenAnswer {
            val poll = polls.incrementAndGet()
            if (failAfter != null && poll > failAfter) throw SQLException("connection killed by server")
            lockStatement(outcomes[(poll - 1).coerceAtMost(outcomes.lastIndex)])
        }
        return conn
    }

    private fun lockStatement(outcome: Int?): PreparedStatement {
        val ps = mock<PreparedStatement>()
        val rs = mock<ResultSet>()
        whenever(ps.executeQuery()).thenReturn(rs)
        whenever(rs.next()).thenReturn(outcome != null)
        whenever(rs.getInt(1)).thenReturn(outcome ?: 0)
        return ps
    }

    /** 按顺序交出 [connections]，用完之后一直返回最后一个。 */
    private fun dataSourceOf(vararg connections: Connection): DataSource {
        val dataSource = mock<DataSource>()
        val taken = AtomicInteger(0)
        whenever(dataSource.connection).thenAnswer { connections[taken.getAndIncrement().coerceAtMost(connections.lastIndex)] }
        return dataSource
    }

    @Test
    fun `mutual exclusion is skipped entirely when the lock is disabled`() {
        val dataSource = mock<DataSource>()

        val held = guard(dataSource, lockEnabled = false).hold()

        assertTrue(held, "a single-instance deployment must serve with the lock switched off")
        verifyNoInteractions(dataSource)
    }

    @Test
    fun `releasing is a no-op when the lock is disabled`() {
        val dataSource = mock<DataSource>()

        guard(dataSource, lockEnabled = false).release()

        verifyNoInteractions(dataSource)
    }

    @Test
    fun `the instance that wins GET_LOCK owns the listeners`() {
        val closed = AtomicBoolean(false)
        val conn = lockConnection(1, closed = closed)
        val guard = guard(dataSourceOf(conn))

        assertTrue(guard.hold())
        assertTrue(guard.isHeld())
        assertFalse(closed.get(), "the lock lives on the connection, so it must stay open")
    }

    @Test
    fun `the instance that loses GET_LOCK stays idle`() {
        val closed = AtomicBoolean(false)
        val conn = lockConnection(0, closed = closed)
        val guard = guard(dataSourceOf(conn))

        assertFalse(guard.hold())
        assertFalse(guard.isHeld())
        assertTrue(closed.get(), "a connection for a lock we do not own must not be leaked")
    }

    @Test
    fun `an absent result row is not a win`() {
        val conn = lockConnection(null)

        assertFalse(guard(dataSourceOf(conn)).hold())
    }

    @Test
    fun `the lock connection is reused across polls`() {
        val conn = lockConnection(1)
        val dataSource = dataSourceOf(conn)
        val guard = guard(dataSource)

        assertTrue(guard.hold())
        assertTrue(guard.hold())
        assertTrue(guard.hold())

        // GET_LOCK 是会话级的：每次轮询都借还连接等于每次轮询都放锁，副本就能趁隙抢过去。
        verify(dataSource, times(1)).connection
        verify(conn, times(3)).prepareStatement(GET_LOCK_SQL)
    }

    @Test
    fun `a lock taken over our head is re-established on a fresh connection`() {
        val closed = AtomicBoolean(false)
        val stolen = lockConnection(1, 0, closed = closed)
        val renewed = lockConnection(1)
        val dataSource = dataSourceOf(stolen, renewed)
        val guard = guard(dataSource)

        assertTrue(guard.hold())
        assertFalse(guard.hold())
        assertTrue(closed.get(), "the dead connection must be dropped before another is borrowed")
        assertTrue(guard.hold())

        verify(dataSource, times(2)).connection
        assertTrue(guard.isHeld())
    }

    @Test
    fun `a broken lock connection renews itself instead of going idle`() {
        val closed = AtomicBoolean(false)
        val dying = lockConnection(1, closed = closed, failAfter = 1)
        val fresh = lockConnection(1)
        val guard = guard(dataSourceOf(dying, fresh))

        assertTrue(guard.hold(), "first poll establishes the lock")
        assertTrue(guard.hold(), "a poll that fails to read the lock must retry on a new connection")
        assertTrue(closed.get())
        assertTrue(guard.isHeld())
    }

    @Test
    fun `a database outage keeps this instance serving`() {
        val dataSource = mock<DataSource>()
        whenever(dataSource.connection).thenThrow(SQLException("connection refused"))

        val held = guard(dataSource).hold()

        assertTrue(held, "failing closed would tear down every listener over a MySQL blip")
    }

    @Test
    fun `release hands the lock back and frees the connection`() {
        val closed = AtomicBoolean(false)
        val conn = lockConnection(1, closed = closed)
        val releasePs = mock<PreparedStatement>()
        whenever(conn.prepareStatement(RELEASE_SQL)).thenReturn(releasePs)
        whenever(releasePs.executeQuery()).thenReturn(mock<ResultSet>())
        val guard = guard(dataSourceOf(conn))
        assertTrue(guard.hold())

        guard.release()

        verify(releasePs).setString(1, lockName)
        assertTrue(closed.get(), "closing is the backstop if RELEASE_LOCK never reaches the server")
        assertFalse(guard.isHeld())
    }

    @Test
    fun `release still closes the connection when RELEASE_LOCK fails`() {
        val closed = AtomicBoolean(false)
        val conn = lockConnection(1, closed = closed)
        whenever(conn.prepareStatement(RELEASE_SQL)).thenThrow(SQLException("server gone away"))
        val guard = guard(dataSourceOf(conn))
        assertTrue(guard.hold())

        guard.release()

        assertTrue(closed.get())
        assertFalse(guard.isHeld())
    }

    private companion object {
        const val GET_LOCK_SQL = "SELECT GET_LOCK(?, ?)"
        const val RELEASE_SQL = "SELECT RELEASE_LOCK(?)"
    }
}
