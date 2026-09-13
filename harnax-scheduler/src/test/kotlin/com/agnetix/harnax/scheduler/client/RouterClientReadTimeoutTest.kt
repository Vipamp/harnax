package com.agnetix.harnax.scheduler.client

import com.agnetix.harnax.agent.protocol.CommandType
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Three calls leave this process, and they ask three different questions — so they need three clocks:
 *
 *  - `chat` asks "did this execution finish?". Its budget *is* the execution's: `scheduler.timeout-seconds`.
 *  - `clearSession` asks "is the session cleaned up?". Best-effort tail of an execution; it got its own
 *    template (and the container's `stop_grace_period` its real number) in G1, which measured the worst
 *    case at 300 + 300 = 600s against the 360s the compose comment then derived from "300 + 40 + 20" —
 *    SIGKILL landing inside the DELETE, leaving a settled log row whose lock row still reads 0 and the
 *    session unclosed.
 *  - `sendCommand` asks "did the stop signal get through?". A user is waiting on that answer, and admin
 *    only forwards for 30s (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt`):
 *    on the shared chat clock one stalled router made the user see a failure while this thread kept
 *    parking for the rest of the execution's budget. Same shape as `clearSession`, missed by G1.
 *
 * A read timeout can only be proven by waiting for one, so all three run against a loopback server that
 * accepts and never answers: a mocked RestClient would just restate whichever timeout the code hands it.
 * The bands this file compares those measurements against are derived from the constants and cross-checked
 * by [the three clocks stay far enough apart that a shared one cannot pass for its own] — see the note there
 * for why the two short clocks are allowed to overlap and the long one is not.
 */
class RouterClientReadTimeoutTest {

    private fun withUnresponsiveRouter(
        timeoutSeconds: Int,
        clearSessionTimeoutSeconds: Int,
        commandTimeoutSeconds: Int,
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
                    commandTimeoutSeconds = commandTimeoutSeconds,
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
            commandTimeoutSeconds = COMMAND_TIMEOUT_SECONDS,
        ) { client ->
            val clearElapsed = measure { assertDoesNotThrow { client.clearSession("sess-1") } }

            var chatThrew = false
            val chatElapsed = measure { chatThrew = runCatching { client.chat("sess-1", "prompt") }.isFailure }

            assertTrue(
                gaveUpWithin(clearElapsed, CLEAR_TIMEOUT_SECONDS),
                "clearSession waited ${clearElapsed}ms — it is still riding the ${CHAT_TIMEOUT_SECONDS}s chat timeout",
            )
            assertTrue(
                waitedItsOwnClock(chatElapsed, CHAT_TIMEOUT_SECONDS),
                "chat came back after only ${chatElapsed}ms, so it no longer waits its own " +
                    "${CHAT_TIMEOUT_SECONDS}s either — this test would then prove nothing about which timeout is which",
            )
            assertTrue(chatThrew, "a router that never answers must not read as a finished execution")
        }
    }

    /**
     * The stop command on its own clock, and — the part that decides whether this fix is allowed to
     * exist — a timeout there reported as [CommandDelivery.Unanswered]. Folding it into `Missed` would
     * take a router blip and let `stopTask` close a live execution out as stopped (status 4 -> 5, "No live
     * execution to interrupt"), throwing away the result that execution still reports back.
     */
    @Test
    fun `the stop command gives up inside the forwarding budget and reports that it never got an answer`() {
        withUnresponsiveRouter(
            timeoutSeconds = CHAT_TIMEOUT_SECONDS,
            clearSessionTimeoutSeconds = CLEAR_TIMEOUT_SECONDS,
            commandTimeoutSeconds = COMMAND_TIMEOUT_SECONDS,
        ) { client ->
            var delivery: CommandDelivery? = null
            val elapsed = measure { delivery = client.sendCommand("sess-1", CommandType.INTERRUPT) }

            assertTrue(
                gaveUpWithin(elapsed, COMMAND_TIMEOUT_SECONDS),
                "sendCommand waited ${elapsed}ms — it is riding the ${CHAT_TIMEOUT_SECONDS}s chat timeout again, " +
                    "so admin (which stops listening at ${ADMIN_FORWARD_TIMEOUT_SECONDS}s) reports a failed stop " +
                    "while this thread is still parked on it",
            )
            assertTrue(
                waitedItsOwnClock(elapsed, COMMAND_TIMEOUT_SECONDS),
                "sendCommand gave up after ${elapsed}ms, short of its own ${COMMAND_TIMEOUT_SECONDS}s — " +
                    "the band it lands in then says nothing about which template it came from",
            )
            assertTrue(
                delivery is CommandDelivery.Unanswered,
                "a router that never answered proves nothing about the execution; got: $delivery",
            )
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

    /**
     * The measurements above are wall clock, so each comparison is a band rather than a value, and a band
     * that overlaps the execution clock's band would prove nothing: putting every template back on one
     * shared read timeout would still satisfy every assertion. This test pins the spacing that keeps the
     * two short clocks distinguishable from the long one, so editing a constant here fails loudly instead
     * of quietly turning the timing tests into decoration.
     *
     * The two short clocks may overlap each other — a command that gave up on the *cleanup* budget would
     * still answer within admin's forwarding timeout, and that is not the regression under test here. What
     * must stay impossible is any short clock reaching into the execution clock's band.
     */
    @Test
    fun `the three clocks stay far enough apart that a shared one cannot pass for its own`() {
        val chatBandBottom = ownClockBottomMillis(CHAT_TIMEOUT_SECONDS)
        assertTrue(
            bandTopMillis(CLEAR_TIMEOUT_SECONDS) < chatBandBottom,
            "a clearSession timeout of ${CLEAR_TIMEOUT_SECONDS}s plus jitter reaches ${bandTopMillis(CLEAR_TIMEOUT_SECONDS)}ms, " +
                "which the ${CHAT_TIMEOUT_SECONDS}s execution clock can also produce — the timing tests could not tell the two apart",
        )
        assertTrue(
            bandTopMillis(COMMAND_TIMEOUT_SECONDS) < chatBandBottom,
            "a sendCommand timeout of ${COMMAND_TIMEOUT_SECONDS}s plus jitter reaches ${bandTopMillis(COMMAND_TIMEOUT_SECONDS)}ms, " +
                "which the ${CHAT_TIMEOUT_SECONDS}s execution clock can also produce — the timing tests could not tell the two apart",
        )
    }

    /** A call that rode its own short clock has given up by this point; one that rode the chat clock has not. */
    private fun gaveUpWithin(elapsedMs: Long, clockSeconds: Int): Boolean = elapsedMs < bandTopMillis(clockSeconds)

    /** A call that returned before this point did not wait for its own read timeout at all. */
    private fun waitedItsOwnClock(elapsedMs: Long, clockSeconds: Int): Boolean = elapsedMs >= ownClockBottomMillis(clockSeconds)

    private fun ownClockBottomMillis(clockSeconds: Int): Long = clockSeconds * 1_000L - UNDERSHOT_TOLERANCE_MS

    private fun bandTopMillis(clockSeconds: Int): Long = clockSeconds * 1_000L + JITTER_ALLOWANCE_MS

    private fun measure(block: () -> Unit): Long {
        val startedAt = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - startedAt
    }

    private companion object {
        const val CHAT_TIMEOUT_SECONDS = 6
        const val CLEAR_TIMEOUT_SECONDS = 1
        const val COMMAND_TIMEOUT_SECONDS = 2

        /**
         * Slack on the *upper* side of a band only. Jitter can make a client give up later than its
         * timeout, never earlier, so the lower bounds carry [UNDERSHOT_TOLERANCE_MS] instead.
         */
        const val JITTER_ALLOWANCE_MS = 2_000L

        /** A read timeout fires at its second boundary; half a second of clock-skew is the most a band bottom gives. */
        const val UNDERSHOT_TOLERANCE_MS = 500L

        /** Admin's read timeout on the /stop forward — the budget the command clock has to fit inside. */
        const val ADMIN_FORWARD_TIMEOUT_SECONDS = 30

        /** Longer than any timeout above, so nothing answers before every client has given up. */
        const val HANG_SECONDS = 30L
    }
}
