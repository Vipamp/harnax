package com.agnetix.harnax.channel.service.health

import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionStatus
import com.agnetix.harnax.channel.service.manager.ChannelAdaptorRegistry
import com.agnetix.harnax.channel.service.monitor.ChannelRuntimeMonitor
import com.agnetix.harnax.channel.service.monitor.FakeChannelAdaptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * [ChannelConnectionHealthIndicator] 的测试。整体状态是接告警的，所以全部设计都在避免狼来了：
 * 正在重连的频道、运维主动停掉的频道、确实故障超过了宽限期的频道，不能看起来是同一回事。
 */
class ChannelConnectionHealthIndicatorTest {

    private val adaptor = FakeChannelAdaptor()

    private val monitor = ChannelRuntimeMonitor(ChannelAdaptorRegistry(listOf(adaptor)))

    private fun indicator(
        enabled: Boolean = true,
        failureGraceMs: Long = 60_000,
    ) = ChannelConnectionHealthIndicator(monitor, enabled, failureGraceMs)

    private fun state(
        channelId: Long,
        status: ChannelConnectionStatus,
        ageMs: Long = 0,
        idleMs: Long? = null,
        lastError: String? = null,
    ): ChannelConnectionState {
        val now = System.currentTimeMillis()
        return ChannelConnectionState(
            channelId = channelId,
            status = status,
            sinceMillis = now - ageMs,
            lastConnectedAt = if (status == ChannelConnectionStatus.UNKNOWN) 0 else now - ageMs,
            lastActivityAt = idleMs?.let { now - it } ?: 0,
            lastError = lastError,
        )
    }

    @Test
    fun `a serving fleet is UP`() {
        monitor.trackExpected(1L, "feishu prod", "feishu", "websocket")
        adaptor.report(1L, state(1L, ChannelConnectionStatus.CONNECTED, ageMs = 1_000, idleMs = 500))

        val health = indicator().health()

        assertEquals("UP", health.status.code)
        assertEquals(1, health.details["total"])
        assertEquals(1, health.details["serving"])
    }

    @Test
    fun `a channel that is still connecting does not look broken`() {
        monitor.trackExpected(1L, "feishu prod", "feishu", "websocket")
        adaptor.report(1L, state(1L, ChannelConnectionStatus.CONNECTING))

        assertEquals("UP", indicator().health().status.code)
    }

    @Test
    fun `a webhook-only deployment with no listeners is UP`() {
        val health = indicator().health()

        assertEquals("UP", health.status.code)
        assertEquals("none configured", health.details["channels"])
    }

    @Test
    fun `a fresh failure waits out the grace window`() {
        monitor.trackExpected(7L, "feishu prod", "feishu", "websocket")
        adaptor.report(7L, state(7L, ChannelConnectionStatus.FAILED, lastError = "invalid app secret"))

        val health = indicator(failureGraceMs = 60_000).health()

        // 重连每隔几秒就会来一次，头一次失败就发出告警纯属噪音。
        assertEquals("UP", health.status.code)
        assertTrue((health.details["failed"] as List<*>).isEmpty())
    }

    @Test
    fun `a failure at the grace boundary counts as failed`() {
        monitor.trackExpected(7L, "feishu prod", "feishu", "websocket")
        adaptor.report(7L, state(7L, ChannelConnectionStatus.FAILED, ageMs = 60_000, lastError = "invalid app secret"))

        val health = indicator(failureGraceMs = 60_000).health()

        assertEquals("DOWN", health.status.code)
        val failed = health.details["failed"] as List<*>
        assertEquals(1, failed.size)
        assertTrue(failed.single().toString().contains("7=feishu prod"))
        assertTrue(failed.single().toString().contains("invalid app secret"), "the detail has to name the reason")
    }

    @Test
    fun `a channel whose listener vanished is OUT_OF_SERVICE rather than DOWN`() {
        monitor.trackExpected(3L, "wecom webhook", "wecom", "webhook")

        val health = indicator().health()

        assertEquals("OUT_OF_SERVICE", health.status.code)
        assertTrue((health.details["failed"] as List<*>).isEmpty())
        assertTrue(health.details["degraded"].toString().contains("3=wecom webhook"))
    }

    @Test
    fun `a deliberately stopped channel is degraded not down`() {
        monitor.trackExpected(3L, "wecom ops", "wecom", "stream")
        adaptor.report(3L, state(3L, ChannelConnectionStatus.STOPPED))

        val degraded = indicator().health().details["degraded"].toString()

        assertTrue(degraded.contains("3=wecom ops [STOPPED"))
        // 传输层从没上报过活动时间时 lastActivityAt 就是 0，也就没有静默时长可展示。
        assertFalse(degraded.contains("idle"))
    }

    @Test
    fun `a degraded channel shows how long it has been quiet`() {
        monitor.trackExpected(3L, "wecom ops", "wecom", "stream")
        adaptor.report(3L, state(3L, ChannelConnectionStatus.STOPPED, ageMs = 5_000, idleMs = 5_000))

        val degraded = indicator().health().details["degraded"].toString()

        assertTrue(degraded.contains(", idle "), "a socket that is up but silent is the case operators ask about")
    }

    @Test
    fun `the indicator can be switched off without hiding the metrics`() {
        monitor.trackExpected(7L, "feishu prod", "feishu", "websocket")
        adaptor.report(7L, state(7L, ChannelConnectionStatus.FAILED, ageMs = Duration.ofMinutes(10).toMillis(), lastError = "token revoked"))

        val health = indicator(enabled = false).health()

        assertEquals("UP", health.status.code)
        assertEquals(false, health.details["enabled"])
    }
}
