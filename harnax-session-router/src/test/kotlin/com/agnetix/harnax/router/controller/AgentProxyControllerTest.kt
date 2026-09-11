package com.agnetix.harnax.router.controller

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.auth.AuthContext
import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.auth.CallerType
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.proxy.SessionRouterService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import reactor.core.publisher.Flux

/**
 * The router is the first place that knows both "who authenticated" and "what the body claims".
 * These tests pin the rule that the authenticated identity wins, so an API-key caller cannot
 * name another user by filling in the request body.
 */
@DisplayName("AgentProxyController - end-user identity stamping")
class AgentProxyControllerTest {

    private val sessionRouterService: SessionRouterService = mock()
    private val controller = AgentProxyController(sessionRouterService)
    private val httpRequest = MockHttpServletRequest()

    @AfterEach
    fun tearDown() {
        AuthContextHolder.clear()
    }

    private fun authenticated(userId: Long?) {
        AuthContextHolder.set(
            AuthContext(
                callerId = "tester",
                userId = userId,
                callerType = CallerType.EXTERNAL_API,
            ),
        )
    }

    @Test
    @DisplayName("chat - the authenticated user replaces the one named in the body")
    fun chatShouldOverrideBodyUserId() = runBlocking {
        authenticated(userId = 7L)
        whenever(sessionRouterService.proxyChatRequest(any()))
            .thenReturn(ResultVo.success(ChatResponse(sessionId = "s-1", content = "ok")))

        controller.proxyChat(
            ChatAgentRequest(sessionId = "s-1", message = "hi", userId = 999L),
            httpRequest,
        )

        val captor = argumentCaptor<ChatAgentRequest>()
        verify(sessionRouterService).proxyChatRequest(captor.capture())
        assertEquals(7L, captor.firstValue.userId)
    }

    @Test
    @DisplayName("chat stream - a caller without an end user keeps the userId it resolved itself")
    fun streamShouldKeepBodyUserIdWhenCallerHasNoEndUser() {
        authenticated(userId = null)
        whenever(sessionRouterService.proxyStreamRequest(any())).thenReturn(Flux.empty())

        controller.proxyChatStream(
            ChatAgentRequest(sessionId = "s-1", message = "hi", userId = 42L),
            httpRequest,
        )

        val captor = argumentCaptor<ChatAgentRequest>()
        verify(sessionRouterService).proxyStreamRequest(captor.capture())
        assertEquals(42L, captor.firstValue.userId)
    }

    @Test
    @DisplayName("command - the authenticated user is stamped on the forwarded request")
    fun commandShouldOverrideBodyUserId() = runBlocking {
        authenticated(userId = 7L)
        whenever(sessionRouterService.proxyCommandRequest(any()))
            .thenReturn(ResultVo.success(CommandResponse.success(sessionId = "s-1", message = "ok")))

        controller.proxyCommand(
            CommandAgentRequest(sessionId = "s-1", command = CommandType.INTERRUPT, userId = 999L),
            httpRequest,
        )

        val captor = argumentCaptor<CommandAgentRequest>()
        verify(sessionRouterService).proxyCommandRequest(captor.capture())
        assertEquals(7L, captor.firstValue.userId)
    }

    @Test
    @DisplayName("confirm - the authenticated user is stamped on the forwarded request")
    fun confirmShouldOverrideBodyUserId() {
        authenticated(userId = 7L)
        whenever(sessionRouterService.proxyConfirmStreamRequest(any())).thenReturn(Flux.empty())

        controller.proxyConfirm(
            ConfirmAgentRequest(sessionId = "s-1", isConfirmed = true, userId = 999L),
            httpRequest,
        )

        val captor = argumentCaptor<ConfirmAgentRequest>()
        verify(sessionRouterService).proxyConfirmStreamRequest(captor.capture())
        assertEquals(7L, captor.firstValue.userId)
    }
}
