package com.agnetix.harnax.channel.sdk.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * [ReconnectBackoff] 的测试。一次断连返回的延迟是「推进之前」待生效的那一个，所以首次失败几乎
 * 立刻重连，而连续失败会逐步退避——后者正是避免被平台限流的关键。
 */
class ReconnectBackoffTest {

    private fun rapidDisconnects(
        backoff: ReconnectBackoff,
        count: Int,
        alive: Duration = Duration.ZERO,
    ): List<Duration> = (1..count).map { backoff.onDisconnected(alive) }

    @Test
    fun `starts at the initial delay`() {
        val backoff = ReconnectBackoff()

        assertEquals(Duration.ofSeconds(1), backoff.currentDelay())
    }

    @Test
    fun `each rapid failure doubles the pending delay`() {
        val backoff = ReconnectBackoff()

        assertEquals(
            listOf(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(4),
                Duration.ofSeconds(8),
            ),
            rapidDisconnects(backoff, 4),
        )
        assertEquals(Duration.ofSeconds(16), backoff.currentDelay())
    }

    @Test
    fun `the delay is capped at the configured maximum`() {
        val backoff = ReconnectBackoff()

        val delays = rapidDisconnects(backoff, 10)

        assertEquals(Duration.ofSeconds(30), delays.last())
        assertEquals(Duration.ofSeconds(30), backoff.currentDelay())
        assertEquals(5, delays.count { it == Duration.ofSeconds(30) })
    }

    @Test
    fun `a custom ceiling is respected exactly`() {
        val backoff = ReconnectBackoff(
            initial = Duration.ofMillis(100),
            max = Duration.ofMillis(400),
        )

        assertEquals(
            listOf(
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofMillis(400),
                Duration.ofMillis(400),
            ),
            rapidDisconnects(backoff, 4),
        )
    }

    @Test
    fun `a connection that survived the reset window starts over at the initial delay`() {
        val backoff = ReconnectBackoff()
        rapidDisconnects(backoff, 4)

        val delay = backoff.onDisconnected(Duration.ofSeconds(61))

        assertEquals(Duration.ofSeconds(1), delay)
        assertEquals(Duration.ofSeconds(1), backoff.currentDelay())
    }

    @Test
    fun `the reset threshold is exclusive`() {
        val backoff = ReconnectBackoff()

        val delay = backoff.onDisconnected(Duration.ofSeconds(60))

        assertEquals(Duration.ofSeconds(1), delay)
        assertEquals(Duration.ofSeconds(2), backoff.currentDelay())
    }

    @Test
    fun `a connection that died just inside the threshold keeps the backoff growing`() {
        val backoff = ReconnectBackoff(
            initial = Duration.ofMillis(100),
            max = Duration.ofSeconds(10),
            resetThreshold = Duration.ofSeconds(1),
        )

        assertEquals(
            listOf(
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofMillis(400),
            ),
            rapidDisconnects(backoff, 3, alive = Duration.ofMillis(999)),
        )
    }

    @Test
    fun `reset clears the accumulated backoff`() {
        val backoff = ReconnectBackoff()
        rapidDisconnects(backoff, 5)

        backoff.reset()

        assertEquals(Duration.ofSeconds(1), backoff.currentDelay())
        assertEquals(Duration.ofSeconds(1), backoff.onDisconnected(Duration.ZERO))
    }

    @Test
    fun `backoff survives a healthy gap and starts climbing again`() {
        val backoff = ReconnectBackoff()
        rapidDisconnects(backoff, 3)
        backoff.onDisconnected(Duration.ofMinutes(10))

        assertEquals(
            listOf(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
            ),
            rapidDisconnects(backoff, 2),
        )
    }
}
