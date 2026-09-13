package com.agnetix.harnax.agent.adaptor.mcp

/**
 * Where an OAuth MCP client gets the bearer token it presents.
 *
 * Deliberately a callback rather than a config field: the token belongs to a *person*, and the only
 * party that knows which person a client was built for is the code that built it. `McpHelper` binds
 * one of these per MCP client at assembly time (design section 7.3), so the token it asks for is
 * always the one belonging to whoever owns the session this client serves — never a tenant-wide
 * credential that outlives the person who granted it.
 *
 * Implementations are expected to cache and to renew before expiry, because [accessToken] is called
 * on every outbound HTTP request of the client, including the handshake.
 */
fun interface McpAccessTokenSource {

    /**
     * @throws McpAuthRequiredException when the user behind this client has no usable grant; the call
     * fails and the reason reaches the log, rather than a request going out unauthenticated
     */
    fun accessToken(mcpId: Long): String
}

/** No token to present: the user has to authorize this MCP server (again). */
class McpAuthRequiredException(message: String) : RuntimeException(message)
