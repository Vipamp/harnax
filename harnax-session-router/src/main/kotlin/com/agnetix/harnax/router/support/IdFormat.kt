package com.agnetix.harnax.router.support

/**
 * Format rules for the two identifiers the router lets callers supply.
 *
 * Both end up somewhere a free-form string must not go:
 *
 * - `sessionId` is concatenated into Redis key names. The Lua that maintains the reverse index
 *   derives a key from a JSON-encoded member by stripping its quotes, so an id containing a quote or
 *   a backslash produces a key that never matches the real one: the binding is written under one name
 *   and refreshed under another, and the session silently loses its agent mid-conversation.
 * - `sessionId` is also pasted into URLs the router itself calls: admin's
 *   `/api/admin/internal/sessions/{id}/info` and agent-service's `/api/agent/session/{id}/...`.
 *   A value carrying `../`, `?` or `#` turns a lookup of one session into a request for some other
 *   internal endpoint, presented with the router's own service credentials.
 * - Both land in MDC and in log lines, where a newline forges a record that looks like it came from
 *   somewhere else.
 *
 * `web-<uuid>` and `mp-<uuid>` are the shapes this system actually generates; the class deliberately
 * allows `.`, `_` and `:` so an operator who invents a scheme is not cut off, while everything with a
 * structural meaning in a URL, a Redis key or a log line is not. A single `.` is part of that
 * allowance; `..` is not, because as a path segment it re-points the URL at a different endpoint.
 */
object IdFormat {

    const val MAX_ID_LENGTH = 128
    const val MAX_INSTANCE_ID_LENGTH = 64

    private val sessionIdPattern = Regex("^[A-Za-z0-9._:-]{1,$MAX_ID_LENGTH}$")
    private val instanceIdPattern = Regex("^[A-Za-z0-9._-]{1,$MAX_INSTANCE_ID_LENGTH}$")

    fun isSessionId(value: String): Boolean = !value.contains("..") && sessionIdPattern.matches(value)

    fun isInstanceId(value: String): Boolean = !value.contains("..") && instanceIdPattern.matches(value)

    /**
     * @throws IllegalArgumentException for the caller to turn into a 400. The rejected value is never
     *   echoed: it is exactly the string that would be forging something downstream.
     */
    fun requireSessionId(value: String) {
        if (!isSessionId(value)) {
            throw IllegalArgumentException("Invalid sessionId format")
        }
    }

    fun requireInstanceId(value: String) {
        if (!isInstanceId(value)) {
            throw IllegalArgumentException("Invalid instanceId format")
        }
    }

    /** Splits a comma-separated list of session ids and validates every element. */
    fun parseSessionIds(value: String): List<String> {
        val ids = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(ids.isNotEmpty()) { "No sessionId supplied" }
        ids.forEach { requireSessionId(it) }
        return ids
    }
}
