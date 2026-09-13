package com.agnetix.harnax.channel.dingtalk

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionStatus
import com.dingtalk.open.app.api.OpenDingTalkClient
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener
import com.dingtalk.open.app.api.models.bot.ChatbotMessage
import com.dingtalk.open.app.api.models.bot.MessageContent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the listener-ownership lifecycle of [DingtalkStreamMode].
 *
 * The regression these lock down: `OpenDingTalkClient.start()` returns as soon as the SDK has
 * scheduled its own connection task, and the old code read that return as "the connection is
 * over" and retired the holder in a `finally`. Every message the platform delivered afterwards
 * hit the superseded-listener guard and was dropped in silence, while the socket stayed healthy
 * and monitoring kept reporting `CONNECTED`.
 */
class DingtalkStreamModeOwnershipTest {
    private val turnExecutor = ChannelTurnExecutor()

    private val clients = mutableListOf<FakeStreamClient>()

    private val mode = DingtalkStreamMode(
        turnExecutor = turnExecutor,
        clientFactory = { _, listener -> FakeStreamClient(listener).also { clients.add(it) } },
    )

    private val spec = ChannelSpec.builder()
        .id(101L)
        .name("dingtalk-test")
        .type(ChannelType.DINGTALK)
        .agentId(1L)
        .appId("client-id")
        .appSecret("client-secret")
        .callbackKey("cb-test")
        .communicationMode("stream")
        .build()

    @AfterTest
    fun tearDown() {
        mode.shutdown()
        turnExecutor.close()
    }

    @Test
    fun `a message delivered after start returns still reaches the handler`() {
        val received = CountDownLatch(1)
        val forwarded = mutableListOf<ChannelMessage>()
        mode.start(spec) { message ->
            synchronized(forwarded) { forwarded.add(message) }
            received.countDown()
        }

        // The connect thread has now run to completion: exactly the moment the old code
        // unregistered the holder.
        val client = clients.single()
        client.awaitConnectThreadDone()

        client.deliver(botMessage(msgId = "msg-1", conversationId = "cid-1", text = "hello"))

        assertTrue(received.await(5, TimeUnit.SECONDS), "inbound message never reached the agent handler")
        assertEquals("hello", synchronized(forwarded) { forwarded.single().content })
        assertEquals("cid-1", synchronized(forwarded) { forwarded.single().sessionId })
        // Liveness is claimed by the first message, not by start() returning.
        assertEquals(ChannelConnectionStatus.CONNECTED, mode.connectionState(spec.id).status)
    }

    @Test
    fun `stopping the channel retires the listener and marks it stopped`() {
        val received = CountDownLatch(1)
        mode.start(spec) { received.countDown() }
        val client = clients.single()
        client.awaitConnectThreadDone()

        mode.stop(spec)

        client.deliver(botMessage(msgId = "msg-2", conversationId = "cid-1", text = "late"))
        assertFalse(received.await(300, TimeUnit.MILLISECONDS), "a stopped listener must not serve messages")
        assertEquals(1, client.stopCount.get())
        assertEquals(ChannelConnectionStatus.STOPPED, mode.connectionState(spec.id).status)
    }

    @Test
    fun `a restarted channel is served only by the new listener`() {
        val staleReached = CountDownLatch(1)
        val currentReached = CountDownLatch(1)

        mode.start(spec) { staleReached.countDown() }
        val stale = clients.single()
        stale.awaitConnectThreadDone()
        mode.stop(spec)

        mode.start(spec) { currentReached.countDown() }
        val current = clients.last()
        current.awaitConnectThreadDone()

        stale.deliver(botMessage(msgId = "msg-stale", conversationId = "cid-1", text = "from the old socket"))
        assertFalse(staleReached.await(300, TimeUnit.MILLISECONDS), "a superseded listener must drop its messages")

        current.deliver(botMessage(msgId = "msg-current", conversationId = "cid-1", text = "from the new socket"))
        assertTrue(currentReached.await(5, TimeUnit.SECONDS), "the current listener must serve messages")
    }

    /** Minimal stand-in for the SDK stream client: records the callback and never opens a socket. */
    private class FakeStreamClient(
        private val listener: OpenDingTalkCallbackListener<ChatbotMessage, Any>,
    ) : OpenDingTalkClient {
        private val started = CountDownLatch(1)

        val stopCount = AtomicInteger(0)

        @Volatile
        var connectThread: Thread? = null
            private set

        override fun start() {
            connectThread = Thread.currentThread()
            started.countDown()
        }

        override fun stop() {
            stopCount.incrementAndGet()
        }

        fun deliver(message: ChatbotMessage) {
            listener.execute(message)
        }

        /**
         * Waits until the thread that called [start] has finished, so an assertion made here
         * cannot pass by racing ahead of that thread's own teardown.
         */
        fun awaitConnectThreadDone() {
            assertTrue(started.await(5, TimeUnit.SECONDS), "the stream client was never started")
            val thread = connectThread ?: return
            thread.join(5_000)
            assertFalse(thread.isAlive, "the connect thread did not finish; the test can no longer prove ownership")
        }
    }

    private fun botMessage(
        msgId: String,
        conversationId: String,
        text: String,
    ): ChatbotMessage = ChatbotMessage().apply {
        setMsgId(msgId)
        setConversationId(conversationId)
        setConversationType("1")
        setSenderStaffId("staff-1")
        setSenderNick("Tester")
        setMsgtype("text")
        setText(MessageContent().apply { setContent(text) })
        setSessionWebhook("https://example.invalid/session-webhook")
    }
}
