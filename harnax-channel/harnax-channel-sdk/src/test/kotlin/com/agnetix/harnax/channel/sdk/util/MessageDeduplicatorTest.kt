package com.agnetix.harnax.channel.sdk.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [MessageDeduplicator] 的测试。register/commit/rollback 的拆分，是「至少一次投递」的平台不丢
 * 处理失败消息的关键，因此两个方向都必须成立：同一条消息不重复处理，处理失败后的重投必须能被
 * 重新接受。
 */
class MessageDeduplicatorTest {

    @Nested
    inner class InFlight {
        @Test
        fun `first delivery is accepted`() {
            val dedup = MessageDeduplicator()

            assertTrue(dedup.tryBegin("msg-1"))
        }

        @Test
        fun `redelivery arriving while the first is still in flight is rejected`() {
            val dedup = MessageDeduplicator()

            assertTrue(dedup.tryBegin("msg-1"))
            assertFalse(dedup.tryBegin("msg-1"))
        }

        @Test
        fun `in flight message is not counted as committed`() {
            val dedup = MessageDeduplicator()

            dedup.tryBegin("msg-1")

            assertEquals(0, dedup.size())
        }

        @Test
        fun `rollback makes the next redelivery acceptable again`() {
            val dedup = MessageDeduplicator()

            dedup.tryBegin("msg-1")
            assertFalse(dedup.tryBegin("msg-1"))
            dedup.rollback("msg-1")

            assertTrue(dedup.tryBegin("msg-1"))
        }

        @Test
        fun `rollback of an unknown message is harmless`() {
            val dedup = MessageDeduplicator()

            dedup.rollback("never-seen")

            assertTrue(dedup.tryBegin("never-seen"))
        }
    }

    @Nested
    inner class Committed {
        @Test
        fun `committed message stays filtered for every later delivery`() {
            val dedup = MessageDeduplicator()

            dedup.tryBegin("msg-1")
            dedup.commit("msg-1")

            assertFalse(dedup.tryBegin("msg-1"))
            assertEquals(1, dedup.size())
        }

        @Test
        fun `committing one message leaves other conversations untouched`() {
            val dedup = MessageDeduplicator()

            dedup.tryBegin("msg-1")
            dedup.commit("msg-1")

            assertTrue(dedup.tryBegin("msg-2"))
            assertFalse(dedup.tryBegin("msg-1"))
        }

        @Test
        fun `committing twice does not add a second entry`() {
            val dedup = MessageDeduplicator()

            dedup.commit("msg-1")
            dedup.commit("msg-1")

            assertEquals(1, dedup.size())
        }

        @Test
        fun `a failed turn never enters the committed window`() {
            val dedup = MessageDeduplicator()

            dedup.tryBegin("msg-1")
            dedup.rollback("msg-1")

            assertEquals(0, dedup.size())
        }
    }

    @Nested
    inner class Window {
        @Test
        fun `oldest committed id is evicted once the window is full`() {
            val dedup = MessageDeduplicator(maxCommitted = 2)

            dedup.commit("msg-1")
            dedup.commit("msg-2")
            dedup.commit("msg-3")

            assertEquals(2, dedup.size())
            assertTrue(dedup.tryBegin("msg-1"), "msg-1 was the oldest entry and must be forgotten")
            assertFalse(dedup.tryBegin("msg-3"))
        }

        @Test
        fun `window size never exceeds the configured bound`() {
            val dedup = MessageDeduplicator(maxCommitted = 3)

            (1L..50L).forEach { dedup.commit("msg-$it") }

            assertEquals(3, dedup.size())
        }

        @Test
        fun `evicted id can be processed again and re-enters the window`() {
            val dedup = MessageDeduplicator(maxCommitted = 1)

            dedup.commit("msg-1")
            dedup.commit("msg-2")

            // msg-1 已经被挤出窗口，重新投递时要能再处理一次。
            assertTrue(dedup.tryBegin("msg-1"))
            dedup.commit("msg-1")

            // 窗口只有一个格子，msg-1 进来就把 msg-2 挤了出去：此刻只有 msg-1 还会被判成重复。
            assertFalse(dedup.tryBegin("msg-1"))
            assertTrue(dedup.tryBegin("msg-2"))
            assertEquals(1, dedup.size())
        }

        @Test
        fun `default window is the documented size`() {
            assertEquals(1000, MessageDeduplicator.DEFAULT_MAX_COMMITTED)
            assertEquals(0, MessageDeduplicator().size())
        }
    }

    @Nested
    inner class Concurrency {
        @Test
        fun `only one thread wins a racing delivery of the same id`() {
            val threads = 8
            val dedup = MessageDeduplicator()
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val accepted = AtomicInteger(0)
            try {
                val done = (1..threads).map {
                    pool.submit {
                        start.await()
                        if (dedup.tryBegin("msg-1")) accepted.incrementAndGet()
                    }
                }
                start.countDown()
                done.forEach { it.get(5, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            assertEquals(1, accepted.get())
        }

        @Test
        fun `distinct ids are accepted concurrently`() {
            val threads = 8
            val dedup = MessageDeduplicator()
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val accepted = AtomicInteger(0)
            try {
                val done = (1..threads).map { index ->
                    pool.submit {
                        start.await()
                        if (dedup.tryBegin("msg-$index")) accepted.incrementAndGet()
                    }
                }
                start.countDown()
                done.forEach { it.get(5, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            assertEquals(threads, accepted.get())
            assertEquals(0, dedup.size())
        }
    }
}
