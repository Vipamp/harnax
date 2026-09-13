package com.agnetix.harnax.scheduler.client

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One execution owns the Quartz worker twice: the router call and the session cleanup that follows it.
 * Until this test's subject existed both shared `scheduler.timeout-seconds`, so the worst case was
 * 300 + 300 = 600s against a container grace period derived from 300 + 60 + 20 — SIGKILL landed inside
 * `clearSession` and left a settled log row with a lock row still at 0 and the session unclosed.
 *
 * The cleanup is best-effort (it already swallows every exception), so a short read timeout costs
 * nothing and puts the real budget back on paper.
 *
 * A loopback server that accepts and never answers, because a read timeout can only be proven by
 * waiting for one: a mocked RestClient would just restate whichever timeout the code hands it.
 */
class RouterClientReadTimeoutTest {

    private fun withUnresponsiveRouter(
        timeoutSeconds: Int,
        clearSessionTimeoutSeconds: Int,
        block: (RouterClient) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Every handler parks: the response that never comes is the whole point. The executor is shut
        // down before the server stops, so a parked thread cannot outlive the test.
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "unresponsive-router").apply { isDaemon = true }
        }
        server.executor = executor
        server.createContext("/") { _ -> TimeUnit.SECONDS.sleep(HANG_SECONDS) }
        server.start()
        try {
            block(
                RouterClient(
                    routerUrl = "http://127.0.0.1:${server.address.port}",
                    configuredApiKey = "test-key",
                    // Never reached: an API key is configured, so no admin lookup happens.
                    adminUrl = "http://127.0.0.1:1",
                    adminSecret = "unused",
                    timeoutSeconds = timeoutSeconds,
                    clearSessionTimeoutSeconds = clearSessionTimeoutSeconds,
                ).apply { init() },
            )
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    /**
     * The two calls on one client, timed against the same non-answer. Only one property can produce
     * these two numbers: separate request templates with separate read timeouts. A shared timeout puts
     * `clearSession` in the same band as `chat`, and the first assertion fails.
     */
    @Test
    fun `the session cleanup gives up on its own clock while the router call keeps the execution budget`() {
        withUnresponsiveRouter(
            timeoutSeconds = CHAT_TIMEOUT_SECONDS,
            clearSessionTimeoutSeconds = CLEAR_TIMEOUT_SECONDS,
        ) { client ->
            val clearElapsed = measure { assertDoesNotThrow { client.clearSession("sess-1") } }

            var chatThrew = false
            val chatElapsed = measure { chatThrew = runCatching { client.chat("sess-1", "prompt") }.isFailure }

            assertTrue(
                clearElapsed < CLEAR_TIMEOUT_SECONDS * 2_000L,
                "clearSession waited ${clearElapsed}ms — it is still riding the ${CHAT_TIMEOUT_SECONDS}s chat timeout",
            )
            assertTrue(
                chatElapsed >= (CHAT_TIMEOUT_SECONDS - 1) * 1_000L,
                "chat came back after only ${chatElapsed}ms, so it no longer waits its own " +
                    "${CHAT_TIMEOUT_SECONDS}s either — this test would then prove nothing about which timeout is which",
            )
            assertTrue(chatThrew, "a router that never answers must not read as a finished execution")
        }
    }

    /**
     * The ceiling only ever shortens: a cleanup may not outlive the execution whose session it closes,
     * and it must not start costing a full minute on a deployment that runs a 30s task timeout.
     */
    @Test
    fun `the cleanup budget is the ceiling or the execution timeout, whichever is shorter`() {
        assertEquals(60L, RouterClient.clearSessionReadTimeoutSeconds(timeoutSeconds = 300, capSeconds = 60))
        assertEquals(30L, RouterClient.clearSessionReadTimeoutSeconds(timeoutSeconds = 30, capSeconds = 60))
        assertEquals(300L, RouterClient.clearSessionReadTimeoutSeconds(timeoutSeconds = 300, capSeconds = 600))
    }

    private fun measure(block: () -> Unit): Long {
        val startedAt = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - startedAt
    }

    private companion object {
        const val CHAT_TIMEOUT_SECONDS = 4
        const val CLEAR_TIMEOUT_SECONDS = 1

        /** Longer than either timeout above, so nothing answers before both clients have given up. */
        const val HANG_SECONDS = 30L
    }
}
