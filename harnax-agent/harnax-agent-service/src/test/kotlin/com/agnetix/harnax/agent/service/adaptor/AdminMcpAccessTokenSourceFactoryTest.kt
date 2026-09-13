package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.mcp.McpAuthRequiredException
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.entity.dto.McpAccessTokenResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * AdminMcpAccessTokenSourceFactory Unit Tests
 *
 * The token belongs to a person, and this is the one place on the runtime side that decides which
 * person a cached token may be shown to. So the cases worth pinning are the ones where a plausible
 * cache would leak: two sessions on one MCP server, a session that was refused, and an identity that
 * does not exist at all.
 */
class AdminMcpAccessTokenSourceFactoryTest {

    private val adminApiClient = mock<AdminApiClient>()
    private val factory = AdminMcpAccessTokenSourceFactory(adminApiClient)

    private fun issued(token: String, expiresIn: Long? = null): McpAccessTokenResponse = McpAccessTokenResponse(
        accessToken = token,
        expiresAtEpochSecond = expiresIn?.let { Instant.now().epochSecond + it },
    )

    @Test
    @DisplayName("没有用户身份的会话拿不到令牌源")
    fun `a session with no user behind it gets no source at all`() {
        // A channel conversation or a service key has no grant to spend; handing it a source would
        // only turn that into a failing tool call later.
        assertNull(factory.forUser("chn-dingtalk-1", null))
        verify(adminApiClient, never()).getMcpAccessToken(any(), any())
    }

    @Test
    @DisplayName("同一个会话同一台服务只问一次")
    fun `a cached token is reused instead of asking admin again`() {
        whenever(adminApiClient.getMcpAccessToken("web-1", 7L)).thenReturn(issued("at-1"))
        val source = factory.forUser("web-1", 42L) ?: error("a session with a user must get a source")

        assertEquals("at-1", source.accessToken(7L))
        assertEquals("at-1", source.accessToken(7L))
        verify(adminApiClient, times(1)).getMcpAccessToken("web-1", 7L)
    }

    @Test
    @DisplayName("两个会话共用一台服务时各拿各的令牌")
    fun `two sessions on one server never share a token`() {
        whenever(adminApiClient.getMcpAccessToken("web-1", 7L)).thenReturn(issued("at-owner-1"))
        whenever(adminApiClient.getMcpAccessToken("web-2", 7L)).thenReturn(issued("at-owner-2"))

        val first = factory.forUser("web-1", 42L) ?: error("no source")
        val second = factory.forUser("web-2", 43L) ?: error("no source")

        // The MCP client is cached per agent instance, so the cache key has to carry the session as
        // well as the server: keying by server alone would show user B the token of user A.
        assertEquals("at-owner-1", first.accessToken(7L))
        assertEquals("at-owner-2", second.accessToken(7L))
    }

    @Test
    @DisplayName("过了期就重新要,不把死令牌递上去")
    fun `an expired entry is fetched again rather than presented`() {
        whenever(adminApiClient.getMcpAccessToken(eq("web-1"), eq(7L)))
            .thenReturn(issued("at-old", expiresIn = -10))
            .thenReturn(issued("at-new"))
        val source = factory.forUser("web-1", 42L) ?: error("no source")

        assertEquals("at-old", source.accessToken(7L))
        assertEquals("at-new", source.accessToken(7L))
        verify(adminApiClient, times(2)).getMcpAccessToken("web-1", 7L)
    }

    @Test
    @DisplayName("被拒之后短时间内不再重复追问")
    fun `a refusal is held back so one missing grant does not hammer admin`() {
        whenever(adminApiClient.getMcpAccessToken("web-1", 7L))
            .thenThrow(McpAuthRequiredException("This MCP server is not authorized for your account yet"))
        val source = factory.forUser("web-1", 42L) ?: error("no source")

        val error = assertThrows(McpAuthRequiredException::class.java) { source.accessToken(7L) }
        val again = assertThrows(McpAuthRequiredException::class.java) { source.accessToken(7L) }

        // Each retry would be another admin round trip, another refresh attempt at the authorization
        // server, and another identical row in mcp_call_log; only a person can fix this.
        verify(adminApiClient, times(1)).getMcpAccessToken("web-1", 7L)
        assertTrue(again.message!!.contains("not authorized"), again.message)
        assertEquals(error.message, again.message, "the cooldown must not invent a different reason")
    }

    @Test
    @DisplayName("源只认自己那个会话")
    fun `a source cannot be pointed at another session`() {
        whenever(adminApiClient.getMcpAccessToken("web-owner", 7L)).thenReturn(issued("at-owner"))
        whenever(adminApiClient.getMcpAccessToken("web-owner", 8L)).thenReturn(issued("at-owner-b"))
        val source = factory.forUser("web-owner", 42L) ?: error("no source")

        assertEquals("at-owner", source.accessToken(7L))
        assertEquals("at-owner-b", source.accessToken(8L))
        // The mcpId is the only thing a caller can vary: the session is fixed when the source is made,
        // which is the whole reason the factory exists instead of one shared source.
        verify(adminApiClient, times(1)).getMcpAccessToken("web-owner", 7L)
        verify(adminApiClient, times(1)).getMcpAccessToken("web-owner", 8L)
    }
}
