package com.agnetix.harnax.channel.service.endpoint

import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCallbackResult
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.service.manager.ChannelAdaptorRegistry
import com.agnetix.harnax.channel.service.monitor.FakeChannelAdaptor
import com.agnetix.harnax.channel.service.session.InMemoryChannelSessionManager
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.mapper.ChannelMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration

/**
 * The routing decisions [ChannelCallbackController] makes before it touches a platform contract.
 *
 * The interesting part is not the happy path — that is the adaptor's job, and Feishu's own
 * signature/decrypt round trip is covered in the Feishu module. What is easy to get wrong here is
 * which failure answers with what: a webhook channel that silently 200s an event nobody can handle
 * is the exact bug this endpoint exists to fix.
 */
class ChannelCallbackControllerTest {
    private val channelMapper = mock<ChannelMapper>()

    private val sessionManager = InMemoryChannelSessionManager(
        maxSessions = 10,
        maxMessagesPerSession = 10,
        idleTtl = Duration.ofMinutes(1),
    )

    private val chatService = ChannelChatService(sessionManager)

    private val agentAdaptor: AgentAdaptor = object : AgentAdaptor() {
        override fun getName(): String = "test-agent"

        override suspend fun process(context: AgentContext): AgentResponse = throw UnsupportedOperationException("not reached")
    }

    private fun controllerFor(adaptor: FakeChannelAdaptor) = ChannelCallbackController(
        channelMapper = channelMapper,
        adaptorRegistry = ChannelAdaptorRegistry(listOf(adaptor)),
        agentAdaptor = agentAdaptor,
        sessionManager = sessionManager,
        chatService = chatService,
    )

    private fun channel(mode: String = "webhook") = Channel().apply {
        id = 7L
        name = "feishu-callback"
        type = ChannelType.FEISHU.code
        agentId = 1L
        callbackKey = "cb-7"
        sessionId = "chn-7"
        communicationMode = mode
        enabled = 1
        status = 1
    }

    private fun stubLookup(channel: Channel?) {
        whenever(channelMapper.selectByCallbackKey(any())).thenReturn(channel)
    }

    private fun request() = MockHttpServletRequest("POST", "/api/channel/callback/cb-7").apply {
        contentType = "application/json"
        setContent("""{"schema":"2.0","header":{"event_id":"evt-1"}}""".toByteArray())
        addHeader("X-Lark-Request-Timestamp", "1737000000")
    }

    @Test
    fun `an unknown callback key is not found`() {
        stubLookup(null)

        val response = controllerFor(FakeChannelAdaptor(callbackResult = ChannelCallbackResult())).callback("cb-unknown", request())

        assertEquals(HttpStatus.NOT_FOUND.value(), response.statusCode.value())
    }

    @Test
    fun `a disabled channel is refused`() {
        stubLookup(channel().apply { status = 0 })

        val response = controllerFor(FakeChannelAdaptor(callbackResult = ChannelCallbackResult())).callback("cb-7", request())

        assertEquals(HttpStatus.FORBIDDEN.value(), response.statusCode.value())
    }

    @Test
    fun `a long-connection channel does not answer on the callback path`() {
        // Both entry points handling one channel would run every message twice.
        stubLookup(channel(mode = "websocket"))

        val response = controllerFor(FakeChannelAdaptor(callbackResult = ChannelCallbackResult())).callback("cb-7", request())

        assertEquals(HttpStatus.NOT_FOUND.value(), response.statusCode.value())
    }

    @Test
    fun `a platform without a callback contract answers not implemented rather than accepting`() {
        stubLookup(channel())

        val response = controllerFor(FakeChannelAdaptor(callbackResult = null)).callback("cb-7", request())

        assertEquals(HttpStatus.NOT_IMPLEMENTED.value(), response.statusCode.value())
    }

    @Test
    fun `the adaptor reply is played back verbatim and the pipeline is handed over`() {
        stubLookup(channel())
        val adaptor = FakeChannelAdaptor(callbackResult = ChannelCallbackResult(status = 200, body = """{"challenge":"abc"}"""))

        val response = controllerFor(adaptor).callback("cb-7", request())

        assertEquals(200, response.statusCode.value())
        assertEquals("""{"challenge":"abc"}""", response.body)
        assertNotNull(adaptor.lastPipeline)
    }

    @Test
    fun `an oversized body is refused before it reaches the adaptor`() {
        stubLookup(channel())
        val adaptor = FakeChannelAdaptor(callbackResult = ChannelCallbackResult(status = 200))
        val huge = MockHttpServletRequest("POST", "/api/channel/callback/cb-7").apply {
            contentType = "application/json"
            setContent(ByteArray(2 * 1024 * 1024))
        }

        val response = controllerFor(adaptor).callback("cb-7", huge)

        assertEquals(413, response.statusCode.value())
        assertNull(adaptor.lastPipeline, "an over-limit body must not be handed to any handler")
    }

    @Test
    fun `a server-side failure is reported as a client error so the platform stops retrying`() {
        stubLookup(channel())
        // The Feishu SDK answers an undecodable or mis-signed event with 500. Passed through, that
        // tells the platform "not delivered", so it retries the same bad event forever.
        val adaptor = FakeChannelAdaptor(callbackResult = ChannelCallbackResult(status = 500, body = """{"msg":"decrypt failed"}"""))

        val response = controllerFor(adaptor).callback("cb-7", request())

        assertEquals(400, response.statusCode.value())
    }
}
