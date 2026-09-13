package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionStatus
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.EventMessage
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the listener-ownership lifecycle of [FeishuWebSocketMode].
 *
 * The regression these lock down: `WsClient.start()` only schedules the handshake and returns,
 * and the old code read that return as "the connection is over" and retired the holder in a
 * `finally`. Every event the platform delivered afterwards hit the superseded-listener guard
 * and was dropped in silence, while the SDK kept the socket alive and reconnected it happily.
 */
class FeishuWebSocketModeOwnershipTest {
    private val turnExecutor = ChannelTurnExecutor()

    private val transports = mutableListOf<FakeFeishuTransport>()

    private val mode = FeishuWebSocketMode(
        turnExecutor = turnExecutor,
        transportFactory = { _, handler -> FakeFeishuTransport(handler).also { transports.add(it) } },
    )

    private val spec = ChannelSpec.builder()
        .id(202L)
        .name("feishu-test")
        .type(ChannelType.FEISHU)
        .agentId(1L)
        .appId("app-id")
        .appSecret("app-secret")
        .callbackKey("cb-test")
        .communicationMode("websocket")
        .build()

    @AfterTest
    fun tearDown() {
        mode.shutdown()
        turnExecutor.close()
    }

    @Test
    fun `an event delivered after start returns still reaches the handler`() {
        val received = CountDownLatch(1)
        val forwarded = mutableListOf<ChannelMessage>()
        mode.start(spec) { message ->
            synchronized(forwarded) { forwarded.add(message) }
            received.countDown()
        }

        // The connect thread has now run to completion: exactly the moment the old code
        // unregistered the holder.
        val transport = transports.single()
        transport.awaitConnectThreadDone()

        transport.deliver(messageId = "msg-1", chatId = "oc-1", text = "hello")

        assertTrue(received.await(5, TimeUnit.SECONDS), "inbound event never reached the agent handler")
        assertEquals("hello", synchronized(forwarded) { forwarded.single().content })
        assertEquals("oc-1", synchronized(forwarded) { forwarded.single().sessionId })
        // Liveness is claimed by the first event, not by start() returning.
        assertEquals(ChannelConnectionStatus.CONNECTED, mode.connectionState(spec.id).status)
    }

    @Test
    fun `stopping the channel retires the listener and closes its socket`() {
        val received = CountDownLatch(1)
        mode.start(spec) { received.countDown() }
        val transport = transports.single()
        transport.awaitConnectThreadDone()

        mode.stop(spec)

        transport.deliver(messageId = "msg-2", chatId = "oc-1", text = "late")
        assertFalse(received.await(300, TimeUnit.MILLISECONDS), "a stopped listener must not serve events")
        assertEquals(1, transport.closeCount.get())
        assertEquals(ChannelConnectionStatus.STOPPED, mode.connectionState(spec.id).status)
    }

    @Test
    fun `a restarted channel is served only by the new listener`() {
        val staleReached = CountDownLatch(1)
        val currentReached = CountDownLatch(1)

        mode.start(spec) { staleReached.countDown() }
        val stale = transports.single()
        stale.awaitConnectThreadDone()
        mode.stop(spec)

        mode.start(spec) { currentReached.countDown() }
        val current = transports.last()
        current.awaitConnectThreadDone()

        stale.deliver(messageId = "msg-stale", chatId = "oc-1", text = "from the old socket")
        assertFalse(staleReached.await(300, TimeUnit.MILLISECONDS), "a superseded listener must drop its events")

        current.deliver(messageId = "msg-current", chatId = "oc-1", text = "from the new socket")
        assertTrue(currentReached.await(5, TimeUnit.SECONDS), "the current listener must serve events")
    }

    /** Minimal stand-in for the SDK WebSocket client: records the handler, never opens a socket. */
    private class FakeFeishuTransport(
        private val handler: ImService.P2MessageReceiveV1Handler,
    ) : FeishuWsTransport {
        private val started = CountDownLatch(1)

        val closeCount = AtomicInteger(0)

        @Volatile
        var connectThread: Thread? = null
            private set

        override fun start() {
            connectThread = Thread.currentThread()
            started.countDown()
        }

        override fun close() {
            closeCount.incrementAndGet()
        }

        fun deliver(
            messageId: String,
            chatId: String,
            text: String,
        ) {
            val event = P2MessageReceiveV1()
            val data = P2MessageReceiveV1Data()
            data.message = EventMessage().apply {
                setMessageId(messageId)
                setChatId(chatId)
                setMessageType("text")
                setContent("""{"text":"$text"}""")
            }
            event.setEvent(data)
            handler.handle(event)
        }

        /**
         * Waits until the thread that called [start] has finished, so an assertion made here
         * cannot pass by racing ahead of that thread's own teardown.
         */
        fun awaitConnectThreadDone() {
            assertTrue(started.await(5, TimeUnit.SECONDS), "the websocket transport was never started")
            val thread = connectThread ?: return
            thread.join(5_000)
            assertFalse(thread.isAlive, "the connect thread did not finish; the test can no longer prove ownership")
        }
    }
}
