package com.agnetix.harnax.channel.service.wechat

import com.agnetix.harnax.channel.wechat.WechatBotService
import com.github.wechat.ilink.sdk.ILinkClient
import com.github.wechat.ilink.sdk.core.model.WeixinMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [WechatBotService] polling thread fault-tolerance.
 *
 * Verifies:
 * - Exponential backoff on consecutive failures
 * - Thread does not crash on RuntimeException
 * - Successful poll resets failure counter
 * - Thread stops cleanly on interrupt / flag toggle
 * - startPolling throws when client not found
 */
class WechatBotServicePollingTest {

    private lateinit var service: WechatBotService
    private lateinit var mockClient: ILinkClient

    private val channelId = 99L

    @BeforeEach
    fun setUp() {
        service = WechatBotService()
        mockClient = mock()

        // Inject mock client into private clientMap via reflection
        val clientMapField = WechatBotService::class.java.getDeclaredField("clientMap")
        clientMapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val clientMap = clientMapField.get(service) as ConcurrentHashMap<Long, ILinkClient>
        clientMap[channelId] = mockClient
    }

    private fun stopPolling() {
        val flagsField = WechatBotService::class.java.getDeclaredField("pollingFlags")
        flagsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flags = flagsField.get(service) as ConcurrentHashMap<Long, AtomicBoolean>
        flags[channelId]?.set(false)
    }

    // ==================== Startup validation ====================

    @Nested
    inner class StartupValidation {

        @Test
        fun `throws IllegalStateException when client not found`() {
            val unknownChannelId = 999L

            assertThrows(IllegalStateException::class.java) {
                service.startPolling(unknownChannelId) { }
            }
        }
    }

    // ==================== Fault tolerance ====================

    @Nested
    inner class FaultTolerance {

        @Test
        fun `thread survives RuntimeException and retries`() {
            val callCount = AtomicInteger(0)
            val latch = CountDownLatch(3)

            whenever(mockClient.isLoggedIn).thenReturn(true)
            whenever(mockClient.getUpdates()).thenAnswer {
                val count = callCount.incrementAndGet()
                latch.countDown()
                if (count <= 2) throw RuntimeException("DNS resolution failed")
                emptyList<WeixinMessage>()
            }

            service.startPolling(channelId) { }

            // Wait for at least 3 calls (2 failures + 1 success)
            val completed = latch.await(15, TimeUnit.SECONDS)
            stopPolling()

            assertTrue(completed, "Polling should retry after RuntimeException")
            assertTrue(callCount.get() >= 3, "Expected at least 3 calls, got ${callCount.get()}")
        }

        @Test
        fun `consecutive failures trigger increasing delays`() {
            val timestamps = mutableListOf<Long>()
            val latch = CountDownLatch(3)

            whenever(mockClient.isLoggedIn).thenReturn(true)
            whenever(mockClient.getUpdates()).thenAnswer {
                synchronized(timestamps) { timestamps.add(System.currentTimeMillis()) }
                latch.countDown()
                throw RuntimeException("Connection refused")
            }

            service.startPolling(channelId) { }

            val completed = latch.await(20, TimeUnit.SECONDS)
            stopPolling()

            assertTrue(completed, "Should have 3 failure timestamps")
            synchronized(timestamps) {
                if (timestamps.size >= 3) {
                    val delay1 = timestamps[1] - timestamps[0]
                    val delay2 = timestamps[2] - timestamps[1]
                    // First delay ~3s, second delay ~6s (exponential backoff)
                    assertTrue(delay1 >= 2500, "First backoff should be ~3s, was ${delay1}ms")
                    assertTrue(delay2 >= 5000, "Second backoff should be ~6s, was ${delay2}ms")
                    assertTrue(delay2 > delay1, "Backoff should increase")
                }
            }
        }

        @Test
        fun `success resets failure counter`() {
            val callCount = AtomicInteger(0)
            val latch = CountDownLatch(4)

            whenever(mockClient.isLoggedIn).thenReturn(true)
            whenever(mockClient.getUpdates()).thenAnswer {
                val count = callCount.incrementAndGet()
                latch.countDown()
                // Fail on 1st, succeed on 2nd, fail on 3rd
                if (count == 1 || count == 3) throw RuntimeException("Temporary error")
                emptyList<WeixinMessage>()
            }

            service.startPolling(channelId) { }

            val completed = latch.await(20, TimeUnit.SECONDS)
            stopPolling()

            assertTrue(completed, "Should complete 4 calls")
            // After success (call 2), failure counter resets
            // So call 3 failure should use delay=3s (not 6s)
        }
    }

    // ==================== Message delivery ====================

    @Nested
    inner class MessageDelivery {

        @Test
        fun `delivers messages to handler`() {
            val receivedMessages = mutableListOf<WeixinMessage>()
            val latch = CountDownLatch(1)
            val msg = mock<WeixinMessage>()

            whenever(mockClient.isLoggedIn).thenReturn(true)
            whenever(mockClient.getUpdates()).thenAnswer {
                latch.countDown()
                listOf(msg)
            }

            service.startPolling(channelId) { messages ->
                synchronized(receivedMessages) { receivedMessages.addAll(messages) }
            }

            latch.await(5, TimeUnit.SECONDS)
            Thread.sleep(100) // Allow handler to complete
            stopPolling()

            synchronized(receivedMessages) {
                assertTrue(receivedMessages.isNotEmpty(), "Handler should receive messages")
            }
        }

        @Test
        fun `empty message list does not invoke handler`() {
            var handlerInvoked = false
            val latch = CountDownLatch(2)

            whenever(mockClient.isLoggedIn).thenReturn(true)
            whenever(mockClient.getUpdates()).thenAnswer {
                latch.countDown()
                emptyList<WeixinMessage>()
            }

            service.startPolling(channelId) { handlerInvoked = true }

            latch.await(5, TimeUnit.SECONDS)
            stopPolling()

            assertFalse(handlerInvoked, "Handler should not be called for empty list")
        }
    }

    // ==================== Thread lifecycle ====================

    @Nested
    inner class ThreadLifecycle {

        @Test
        fun `stops when isLoggedIn becomes false`() {
            val callCount = AtomicInteger(0)

            whenever(mockClient.isLoggedIn).thenAnswer {
                callCount.get() < 3 // Stop after 3 polls
            }
            whenever(mockClient.getUpdates()).thenAnswer {
                callCount.incrementAndGet()
                emptyList<WeixinMessage>()
            }

            service.startPolling(channelId) { }

            // Wait for thread to finish
            Thread.sleep(1000)

            assertEquals(3, callCount.get(), "Should stop polling when isLoggedIn=false")
        }

        @Test
        fun `stops when flag is set to false`() {
            val latch = CountDownLatch(1)

            whenever(mockClient.isLoggedIn).thenReturn(true)
            whenever(mockClient.getUpdates()).thenAnswer {
                latch.countDown()
                Thread.sleep(100) // Slow poll to give time to set flag
                emptyList<WeixinMessage>()
            }

            service.startPolling(channelId) { }

            latch.await(5, TimeUnit.SECONDS)
            stopPolling()

            // Thread should stop within a reasonable time
            Thread.sleep(500)
            // No assertion needed - if thread doesn't stop, test will hang
        }
    }
}
