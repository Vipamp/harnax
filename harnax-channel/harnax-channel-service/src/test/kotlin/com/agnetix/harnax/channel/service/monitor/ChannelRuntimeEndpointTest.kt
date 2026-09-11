package com.agnetix.harnax.channel.service.monitor

import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionStatus
import com.agnetix.harnax.channel.service.bootstrap.ChannelListenerLockGuard
import com.agnetix.harnax.channel.service.client.RouterCircuitBreaker
import com.agnetix.harnax.channel.service.manager.ChannelAdaptorRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * [ChannelRuntimeEndpoint] 的测试。排障时运维读的就是这份数据，所以能区分「一切正常」「监听器
 * 由另一个副本持有」「连接在一个小时前就断了」的那几个字段，必须在转 map 之后还在。
 */
class ChannelRuntimeEndpointTest {

    private val adaptor = FakeChannelAdaptor()

    private val monitor = ChannelRuntimeMonitor(ChannelAdaptorRegistry(listOf(adaptor)))

    private val breaker = RouterCircuitBreaker(failureThreshold = 2)

    private fun endpoint(guard: ChannelListenerLockGuard) = ChannelRuntimeEndpoint(monitor, breaker, guard)

    /**
     * 这里用真的 [ChannelListenerLockGuard] 而不是 mock：isHeld 只在类内部翻转，mock 出来的话
     * 断言的就只是自己塞进去的值，跟 endpoint 实际上报什么无关。
     */
    private fun lockGuard(
        outcome: Int?,
        lockEnabled: Boolean = true,
    ): ChannelListenerLockGuard {
        val dataSource = mock<DataSource>()
        if (lockEnabled) {
            val conn = mock<Connection>()
            val ps = mock<PreparedStatement>()
            val rs = mock<ResultSet>()
            whenever(dataSource.connection).thenReturn(conn)
            whenever(conn.isClosed).thenReturn(false)
            whenever(conn.prepareStatement(any())).thenReturn(ps)
            whenever(ps.executeQuery()).thenReturn(rs)
            whenever(rs.next()).thenReturn(outcome != null)
            whenever(rs.getInt(1)).thenReturn(outcome ?: 0)
        }
        return ChannelListenerLockGuard(dataSource, lockEnabled, LOCK_NAME).also { it.hold() }
    }

    private fun serving(channelId: Long): ChannelConnectionState = ChannelConnectionState(
        channelId = channelId,
        status = ChannelConnectionStatus.CONNECTED,
        sinceMillis = System.currentTimeMillis() - 1_000,
        lastConnectedAt = System.currentTimeMillis() - 1_000,
        lastActivityAt = System.currentTimeMillis() - 500,
    )

    @Suppress("UNCHECKED_CAST")
    private fun section(
        payload: Map<String, Any?>,
        key: String,
    ): Map<String, Any?> = payload[key] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun channels(payload: Map<String, Any?>): List<Map<String, Any?>> = payload["channels"] as List<Map<String, Any?>>

    private fun channel(payload: Map<String, Any?>): Map<String, Any?> = channels(payload).single()

    @Test
    fun `the instance that owns the listener lock says so`() {
        val payload = endpoint(lockGuard(1)).channels()

        assertEquals(true, section(payload, "listenerLock")["held"])
        assertEquals(LOCK_NAME, section(payload, "listenerLock")["name"])
        assertEquals(true, section(payload, "listenerLock")["enabled"])
    }

    @Test
    fun `a replica that lost the lock reports itself as idle`() {
        val payload = endpoint(lockGuard(0)).channels()

        assertEquals(false, section(payload, "listenerLock")["held"])
        // enabled 就是给运维看的：这个副本是没抢到锁才待命，不是出了故障。
        assertEquals(true, section(payload, "listenerLock")["enabled"])
    }

    @Test
    fun `a single-instance deployment with the lock switched off is distinguishable`() {
        val payload = endpoint(lockGuard(null, lockEnabled = false)).channels()

        assertEquals(false, section(payload, "listenerLock")["held"])
        assertEquals(false, section(payload, "listenerLock")["enabled"])
    }

    @Test
    fun `the summary counts every declared channel by status`() {
        monitor.trackExpected(1L, "feishu prod", "feishu", "websocket")
        monitor.trackExpected(2L, "dingtalk ops", "dingtalk", "stream")
        monitor.trackExpected(3L, "wecom webhook", "wecom", "webhook")
        adaptor.report(1L, serving(1L))
        adaptor.report(
            2L,
            ChannelConnectionState(
                channelId = 2L,
                status = ChannelConnectionStatus.RECONNECTING,
                sinceMillis = System.currentTimeMillis(),
            ),
        )

        val summary = section(endpoint(lockGuard(1)).channels(), "summary")

        assertEquals(3, summary["total"])
        assertEquals(2, summary["serving"], "a reconnecting listener still counts as serving")
        assertEquals(
            mapOf("CONNECTED" to 1, "RECONNECTING" to 1, "UNKNOWN" to 1),
            summary["byStatus"],
        )
    }

    @Test
    fun `a declared channel with no listener is flagged rather than left out`() {
        monitor.trackExpected(7L, "feishu prod", "feishu", "websocket")

        val view = channel(endpoint(lockGuard(1)).channels())

        assertEquals(7L, view["channelId"])
        assertEquals("feishu prod", view["name"])
        assertEquals("websocket", view["communicationMode"])
        assertEquals("UNKNOWN", view["status"])
        assertEquals(false, view["serving"])
        assertEquals(true, view["missingListener"])
        assertNull(view["lastConnectedAt"], "a transport that never connected has no timestamp to invent")
        assertNull(view["lastActivityAt"])
    }

    @Test
    fun `a live channel reports its counters and last activity`() {
        monitor.trackExpected(7L, "feishu prod", "feishu", "websocket")
        adaptor.report(
            7L,
            serving(7L).copy(
                reconnectCount = 1L,
                receivedCount = 12L,
                lastError = "socket closed",
            ),
        )

        val view = channel(endpoint(lockGuard(1)).channels())

        assertEquals(false, view["missingListener"])
        assertNotNull(view["lastConnectedAt"])
        assertNotNull(view["lastActivityAt"])
        assertEquals(1L, view["reconnectCount"])
        assertEquals(12L, view["receivedCount"])
        assertEquals("socket closed", view["lastError"])
    }

    @Test
    fun `the router circuit is visible without a second endpoint call`() {
        breaker.onFailure("connect timeout")
        breaker.onFailure("connect timeout")

        val circuit = section(endpoint(lockGuard(1)).channels(), "routerCircuit")

        assertEquals("OPEN", circuit["state"])
        assertEquals(2, circuit["consecutiveFailures"])
    }

    @Test
    fun `an empty deployment is still a readable payload`() {
        val payload = endpoint(lockGuard(1)).channels()

        assertNotNull(payload["generatedAt"])
        assertEquals(0, section(payload, "summary")["total"])
        assertTrue(channels(payload).isEmpty())
    }

    private companion object {
        const val LOCK_NAME = "harnax-channel-listeners"
    }
}
