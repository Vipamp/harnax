package com.agnetix.harnax.channel.sdk.dispatch

import com.agnetix.harnax.channel.sdk.monitor.ChannelMetricsSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ChannelTurnExecutor] 的测试。它是唯一挡在「一个慢下游」和「整个 JVM 卡死」之间的东西，
 * 所以它的三个上限（线程池、单频道并发、单会话串行）都必须能被观测到。
 */
class ChannelTurnExecutorTest {

    private class RecordingSink : ChannelMetricsSink {
        val turns = ConcurrentLinkedQueue<Triple<Long, Throwable?, Long>>()

        override fun onTurnCompleted(
            channelId: Long,
            elapsedMs: Long,
            error: Throwable?,
        ) {
            turns.add(Triple(channelId, error, elapsedMs))
        }
    }

    private lateinit var sink: RecordingSink
    private lateinit var executor: ChannelTurnExecutor

    @BeforeEach
    fun setUp() {
        sink = RecordingSink()
        executor = ChannelTurnExecutor(threadPoolSize = 8, perChannelConcurrency = 2, sink = sink)
    }

    @AfterEach
    fun tearDown() {
        executor.close()
    }

    /** 等到并发数达到 [expected]；没达到就直接断言失败，而不是静默通过。 */
    private fun awaitQueueDepth(
        queue: AtomicInteger,
        expected: Int,
        timeoutSeconds: Long = 10,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (queue.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(queue.get() >= expected, "expected $expected concurrent turns, saw ${queue.get()}")
    }

    private fun awaitTurns(count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (sink.turns.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(count, sink.turns.size)
    }

    @Test
    fun `turns of one conversation never overlap`() {
        val active = AtomicInteger(0)
        val highest = AtomicInteger(0)

        runBlocking {
            repeat(6) {
                executor.launchTurn(channelId = 1L, sessionId = "same-session") {
                    val now = active.incrementAndGet()
                    highest.updateAndGet { maxOf(it, now) }
                    delay(30)
                    active.decrementAndGet()
                }
            }
        }
        awaitTurns(6)

        assertEquals(1, highest.get())
    }

    @Test
    fun `different conversations of one channel run side by side but stay capped`() {
        val active = AtomicInteger(0)
        val highest = AtomicInteger(0)
        val twoInFlight = CountDownLatch(2)
        val release = CountDownLatch(1)

        repeat(6) { index ->
            executor.launchTurn(channelId = 1L, sessionId = "session-$index") {
                val now = active.incrementAndGet()
                highest.updateAndGet { current -> maxOf(current, now) }
                twoInFlight.countDown()
                release.await(10, TimeUnit.SECONDS)
                active.decrementAndGet()
            }
        }

        // 两个许可被卡在闸门上的回合占着，另四个回合只能在信号量外面等；上限一旦失守，
        // 第三个回合也会进到闸门前，active 就会变成 3。闸门只由测试线程打开。
        awaitQueueDepth(active, 2)
        Thread.sleep(200)
        assertTrue(highest.get() <= 2, "per-channel concurrency was exceeded: ${highest.get()}")
        release.countDown()
        awaitTurns(6)
    }

    @Test
    fun `one saturated channel does not block another channel`() {
        val blocked = CountDownLatch(1)
        val otherChannelRan = CountDownLatch(1)

        repeat(2) { index ->
            executor.launchTurn(channelId = 1L, sessionId = "busy-$index") {
                blocked.await(10, TimeUnit.SECONDS)
            }
        }
        executor.launchTurn(channelId = 2L, sessionId = "healthy") {
            otherChannelRan.countDown()
        }

        assertTrue(otherChannelRan.await(10, TimeUnit.SECONDS), "the second channel queued behind the first")
        blocked.countDown()
        awaitTurns(3)
    }

    @Test
    fun `a failing turn is reported to the sink and does not escape`() {
        runBlocking {
            executor.launchTurn(channelId = 7L, sessionId = "boom") {
                throw IllegalStateException("router unreachable")
            }
        }
        awaitTurns(1)

        val (channelId, error, _) = sink.turns.first()
        assertEquals(7L, channelId)
        assertNotNull(error)
        assertEquals("router unreachable", error?.message)
    }

    @Test
    fun `a successful turn reports no error`() {
        runBlocking {
            executor.launchTurn(channelId = 3L, sessionId = "fine") {}
        }
        awaitTurns(1)

        val (channelId, error, elapsed) = sink.turns.first()
        assertEquals(3L, channelId)
        assertNull(error)
        assertTrue(elapsed >= 0)
    }

    @Test
    fun `elapsed time is measured per turn`() {
        runBlocking {
            executor.launchTurn(channelId = 4L, sessionId = "slow") {
                delay(120)
            }
        }
        awaitTurns(1)

        val elapsed = sink.turns.first().third
        assertTrue(elapsed >= 100, "turn reported ${elapsed}ms, expected the sleep to be measured")
    }
}
