package com.agnetix.harnax.admin.it

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * A stand-in for the session-router's session surface, for the admin integration tests.
 *
 * Deleting a session now goes through the runtime first: admin asks the router to clear the session, the
 * router resolves it to the agent-service instance that holds the conversation, the plans and the sandbox,
 * and only a "released" answer lets the row go. That call is the half of AGENT-08 an in-process test could
 * never reach, and it leaves this JVM for an answer of its own — with nothing on the other end, every
 * deletion in every class here would be refused.
 *
 * So it serves the one path admin uses and answers the way the router does: HTTP 200 with the verdict in
 * the body's `code`, because that is what keeps the reason readable on the client side.
 *
 *  - a clear is recorded in [cleared] and answered success, which is also what an unbound session gets;
 *  - with [refusal] set, the same call answers that sentence instead, i.e. what a runtime that could not
 *    let go of the session sends back;
 *  - anything else that arrives is recorded in [otherPaths] rather than served.
 */
class FakeRouter : Dispatcher() {

    private val json: ObjectMapper = jacksonObjectMapper()

    /** Session ids admin asked the runtime to release, in order. */
    val cleared = mutableListOf<String>()

    /** Paths outside the session surface a call arrived on — nothing should reach the router for those. */
    val otherPaths = mutableListOf<String>()

    /** When set, the next clear answers a business failure carrying this sentence. */
    @Volatile
    var refusal: String? = null

    fun reset() {
        cleared.clear()
        otherPaths.clear()
        refusal = null
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val route = path.substringBefore('?')
        val method = request.method
        if (method == "DELETE" && route.startsWith(CLEAR_PREFIX)) {
            val sessionId = route.removePrefix(CLEAR_PREFIX)
            cleared += sessionId
            refusal?.let { return vo(500, it) }
            return vo(200, "success")
        }
        otherPaths += "$method $path"
        return vo(500, "Not served by the session surface: $method $path")
    }

    private fun vo(code: Int, message: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(json.writeValueAsString(linkedMapOf<String, Any?>("code" to code, "message" to message, "data" to null, "timestamp" to TIMESTAMP)))

    companion object {
        private const val CLEAR_PREFIX = "/api/router/agent/session/"
        private const val TIMESTAMP = 1704067200000L
    }
}
