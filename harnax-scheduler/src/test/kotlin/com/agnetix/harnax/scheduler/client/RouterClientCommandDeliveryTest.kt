package com.agnetix.harnax.scheduler.client

import com.agnetix.harnax.agent.protocol.CommandType
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.net.InetSocketAddress

/**
 * The stop path branches on three answers, so the client has to produce three distinct answers against a
 * real HTTP exchange: which one a router blip turns into is precisely what a boolean used to lose.
 *
 * Driven through a loopback server rather than a mock — mocking RestClient would only restate the
 * classification this is meant to pin down.
 */
class RouterClientCommandDeliveryTest {

    private fun withRouter(
        statusCode: Int = 200,
        body: String? = null,
        block: (RouterClient) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/router/agent/command") { exchange ->
            val payload = (body ?: "").toByteArray()
            exchange.responseHeaders.set(HttpHeaders.CONTENT_TYPE, "application/json")
            exchange.sendResponseHeaders(statusCode, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            block(
                RouterClient(
                    routerUrl = "http://127.0.0.1:${server.address.port}",
                    configuredApiKey = "test-key",
                    // Never reached: an API key is configured, so no admin lookup happens.
                    adminUrl = "http://127.0.0.1:1",
                    adminSecret = "unused",
                    timeoutSeconds = 5,
                    clearSessionTimeoutSeconds = 60,
                ).apply { init() },
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an instance that interrupted a live execution reports a delivery`() {
        withRouter(
            body = """{"code":200,"message":"success","data":{"sessionId":"sess-1","success":true,"message":"Stream interrupted"}}""",
        ) { client ->
            val delivery = client.sendCommand("sess-1", CommandType.INTERRUPT)

            assertTrue(
                delivery is CommandDelivery.Delivered,
                "a success verdict must read as delivered, got: $delivery",
            )
        }
    }

    @Test
    fun `an instance that says nothing is running reports an explicit miss`() {
        withRouter(
            body = """{"code":200,"message":"success","data":{"sessionId":"sess-1","success":false,"message":"No live execution for this session on this instance"}}""",
        ) { client ->
            val delivery = client.sendCommand("sess-1", CommandType.INTERRUPT)

            assertTrue(
                delivery is CommandDelivery.Missed,
                "a normal answer saying 'no execution' is the one verdict the stop may act on, got: $delivery",
            )
            assertEquals("No live execution for this session on this instance", (delivery as CommandDelivery.Missed).message)
        }
    }

    @Test
    fun `an http error says nothing about whether the execution is alive`() {
        withRouter(statusCode = 500, body = """{"code":500,"message":"router exploded"}""") { client ->
            val delivery = client.sendCommand("sess-1", CommandType.INTERRUPT)

            assertTrue(
                delivery is CommandDelivery.Unanswered,
                "a failed call is not evidence that no execution exists, got: $delivery",
            )
        }
    }

    @Test
    fun `an error envelope with no command result says nothing either`() {
        withRouter(body = """{"code":500,"message":"no instance available","data":null}""") { client ->
            val delivery = client.sendCommand("sess-1", CommandType.INTERRUPT)

            assertTrue(
                delivery is CommandDelivery.Unanswered,
                "the router answered, but it never reached an agent, got: $delivery",
            )
        }
    }

    @Test
    fun `an empty body says nothing either`() {
        withRouter(body = "") { client ->
            val delivery = client.sendCommand("sess-1", CommandType.INTERRUPT)

            assertTrue(
                delivery is CommandDelivery.Unanswered,
                "no body means no verdict was read, got: $delivery",
            )
        }
    }
}
