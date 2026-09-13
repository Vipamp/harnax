package com.agnetix.harnax.agent.adaptor

import com.agnetix.harnax.agent.adaptor.mcp.McpAccessTokenSource

/**
 * Binds an MCP token source to the person one agent instance is being built for.
 *
 * Why a factory and not one application-wide source: an MCP client is created per agent instance and
 * then reused for every call that instance serves, while the bearer token it presents belongs to one
 * named user. Reading "the current user" from a thread local at request time would therefore hand
 * user A's token to user B the moment a cached agent is shared (design section 7.3) - so the identity
 * is captured here, at build time, and the returned source can only ever ask for that person's
 * token.
 *
 * Implemented by agent-service, which is where the admin client lives; harness-core only calls it.
 */
fun interface McpAccessTokenSourceFactory {

    /**
     * @param userId the user this agent instance serves, null when the caller is a service key or a
     * channel conversation - in which case there is no grant to spend and null is the right answer
     * @return the source to consult per request, or null when this session cannot have one
     */
    fun forUser(
        sessionId: String,
        userId: Long?,
    ): McpAccessTokenSource?
}
