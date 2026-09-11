package com.agnetix.harnax.router.service

import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * The two stream bounds are the difference between a client that learns an answer died and one that
 * watches a spinner forever, so they are tested on the operators rather than through HTTP.
 */
class AgentServiceClientTest {

    private fun client(
        idleSeconds: Long,
        maxMinutes: Long,
    ): AgentServiceClient = AgentServiceClient(
        WebClient.builder().build(),
        WebClient.builder().build(),
        ObjectMapper(),
        idleSeconds,
        maxMinutes,
    )

    private fun bound(
        client: AgentServiceClient,
        source: Flux<ChatEvent>,
    ): Flux<ChatEvent> = with(client) { source.withinStreamLimits() }

    private fun event(text: String): ChatEvent = StreamTextChatEvent(message = text, isLast = false, tokenUsage = null)

    @Test
    fun `a stream that stops talking is ended`() {
        val stalled: Flux<ChatEvent> = Flux.just(event("half an answer")).concatWith(Flux.never())

        StepVerifier.create(bound(client(idleSeconds = 1, maxMinutes = 30), stalled))
            .expectNextCount(1)
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(10))
    }

    @Test
    fun `a stream that never stops is ended too`() {
        // It keeps emitting, so the idle bound cannot catch it; only the wall clock can. Zero minutes
        // is not a setting an operator would write — it is how the deadline gets reached in a test.
        val endless: Flux<ChatEvent> = Flux.interval(Duration.ofMillis(50)).map { event("token $it") }

        StepVerifier.create(bound(client(idleSeconds = 3600, maxMinutes = 0), endless))
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(10))
    }

    @Test
    fun `a stream that answers in time is left alone`() {
        val answered: Flux<ChatEvent> = Flux.just(event("first"), EndEventChatEvent())

        // A deadline outliving the stream must not hold it open. StepVerifier's own default would wait
        // forever, so the budget here is the assertion.
        StepVerifier.create(bound(client(idleSeconds = 3600, maxMinutes = 30), answered))
            .expectNextCount(2)
            .expectComplete()
            .verify(Duration.ofSeconds(5))
    }
}
