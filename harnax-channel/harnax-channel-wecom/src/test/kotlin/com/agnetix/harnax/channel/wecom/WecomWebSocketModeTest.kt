package com.agnetix.harnax.channel.wecom

import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WecomFramesTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `subscribe frame carries cmd headers and credentials`() {
        val frame = WecomFrames.subscribe("aibot_subscribe_1", "bot-1", "secret-1")
        val json = mapper.readTree(mapper.writeValueAsString(frame))

        assertEquals("aibot_subscribe", json.path("cmd").asText())
        assertEquals("aibot_subscribe_1", json.path("headers").path("req_id").asText())
        assertEquals("bot-1", json.path("body").path("bot_id").asText())
        assertEquals("secret-1", json.path("body").path("secret").asText())
    }

    @Test
    fun `ping frame carries cmd and req_id`() {
        val json = mapper.readTree(mapper.writeValueAsString(WecomFrames.ping("ping_5")))
        assertEquals("ping", json.path("cmd").asText())
        assertEquals("ping_5", json.path("headers").path("req_id").asText())
    }

    @Test
    fun `respond frame uses stream format with finish true and original req_id`() {
        val json = mapper.readTree(mapper.writeValueAsString(WecomFrames.respondMsg("orig_1", "stream_2", "hello")))
        assertEquals("aibot_respond_msg", json.path("cmd").asText())
        assertEquals("orig_1", json.path("headers").path("req_id").asText())
        assertEquals("stream", json.path("body").path("msgtype").asText())
        assertEquals("stream_2", json.path("body").path("stream").path("id").asText())
        assertTrue(json.path("body").path("stream").path("finish").asBoolean())
        assertEquals("hello", json.path("body").path("stream").path("content").asText())
    }

    @Test
    fun `send frame uses markdown and chatid`() {
        val json = mapper.readTree(mapper.writeValueAsString(WecomFrames.sendMsg("send_1", "chat-9", "**md**")))
        assertEquals("aibot_send_msg", json.path("cmd").asText())
        assertEquals("chat-9", json.path("body").path("chatid").asText())
        assertEquals("markdown", json.path("body").path("msgtype").asText())
        assertEquals("**md**", json.path("body").path("markdown").path("content").asText())
    }
}

class ReconnectBackoffTest {

    @Test
    fun `backoff doubles on rapid failures up to max`() {
        val backoff = ReconnectBackoff(
            initial = Duration.ofSeconds(1),
            max = Duration.ofSeconds(30),
            resetThreshold = Duration.ofSeconds(60),
        )
        // rapid failures (alive shorter than reset threshold)
        val short = Duration.ofSeconds(1)
        assertEquals(1, backoff.onDisconnected(short).seconds) // returns current (1s), next=2
        assertEquals(2, backoff.onDisconnected(short).seconds)
        assertEquals(4, backoff.onDisconnected(short).seconds)
        assertEquals(8, backoff.onDisconnected(short).seconds)
        assertEquals(16, backoff.onDisconnected(short).seconds)
        assertEquals(30, backoff.onDisconnected(short).seconds) // capped at 30 (32→30)
        assertEquals(30, backoff.onDisconnected(short).seconds)
    }

    @Test
    fun `backoff resets when connection was alive long enough`() {
        val backoff = ReconnectBackoff(
            initial = Duration.ofSeconds(1),
            max = Duration.ofSeconds(30),
            resetThreshold = Duration.ofSeconds(60),
        )
        backoff.onDisconnected(Duration.ofSeconds(1)) // 1 → next 2
        backoff.onDisconnected(Duration.ofSeconds(1)) // 2 → next 4
        // long-lived connection resets backoff to initial
        assertEquals(1, backoff.onDisconnected(Duration.ofSeconds(120)).seconds)
        // subsequent rapid failure starts from initial again
        assertEquals(1, backoff.onDisconnected(Duration.ofSeconds(1)).seconds)
        assertEquals(2, backoff.onDisconnected(Duration.ofSeconds(1)).seconds)
    }
}
