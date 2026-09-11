package com.agnetix.harnax.entity

/**
 * Values of `mcp_server.auth_type` (migration V25).
 *
 * Shared because two processes read it: admin decides what to store and deliver, the agent runtime
 * branches on it to choose between a static header and a per-user bearer token.
 */
object McpAuthTypes {

    /** No upstream credential. Behaviourally the same as STATIC_HEADER with empty headers. */
    const val NONE = "NONE"

    /** Today's `headers` column, labelled: one static credential shared by every user. */
    const val STATIC_HEADER = "STATIC_HEADER"

    /** HTTP Basic against a shared credential. Stored but not yet wired into the runtime. */
    const val BASIC = "BASIC"

    /** Per-user OAuth 2.1 grant, resolved at runtime by (mcp, user). */
    const val OAUTH2 = "OAUTH2"

    /** Auth types the runtime can actually honour today. */
    val SUPPORTED = listOf(NONE, STATIC_HEADER, OAUTH2)
}
