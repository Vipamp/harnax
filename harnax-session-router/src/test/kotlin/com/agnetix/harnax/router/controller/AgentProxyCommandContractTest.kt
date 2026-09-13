package com.agnetix.harnax.router.controller

import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.proxy.SessionRouterService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The scheduler does not call agent-service, and it holds no Java object of the router's: it POSTs to
 * `/api/router/agent/command` and reads a JSON body back. `CommandDelivery.from` then decides whether an
 * execution is over — a row at 4 (the user pressed stop) is only settled as "stopped" when the reply says
 * the interrupt found nothing live, and `data.success == false` with a `data` node present is the only
 * shape that says so.
 *
 * The contract is therefore the serialised envelope, not the service return value, and a service-level
 * test cannot see it: a `success` property that Jackson dropped, renamed or left out of `data` would keep
 * every one of those tests green while the scheduler read a real miss as "no verdict" and left the row at
 * 4 for the sweep to time out. These cases go through the HTTP layer.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("router 的 command 端点 - 交给 scheduler 的 JSON 契约")
class AgentProxyCommandContractTest {

    private val sessionRouterService: SessionRouterService = mock()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(AgentProxyController(sessionRouterService))
            .build()
    }

    /**
     * The body `RouterClient.sendCommand` puts on the wire: Jackson writes the `type` discriminator from
     * `AgentRequest` (it is a property of the class, `EXISTING_PROPERTY`), and a body without it is
     * rejected before the contract under test is ever reached.
     *
     * `proxyCommand` is a `suspend` handler, so Spring MVC starts an async dispatch and the first
     * `perform()` carries no body at all — the JSON the scheduler reads is written by the dispatch that
     * follows, which is what this returns.
     */
    private fun callCommand(sessionId: String, command: String): ResultActions {
        val started = mockMvc
            .perform(
                post("/api/router/agent/command")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"sessionId":"$sessionId","command":"$command","args":"","type":"COMMAND"}"""),
            )
            .andExpect(request().asyncStarted())
        return mockMvc.perform(asyncDispatch(started.andReturn()))
    }

    private fun answerWith(verdict: CommandResponse) {
        runBlocking { whenever(sessionRouterService.proxyCommandRequest(any())).thenReturn(ResultVo.success(verdict)) }
    }

    /**
     * The case the stop path branches on: agent-service answered normally and said nothing was running.
     * The envelope stays 200 — this is a failure of the *execution*, not of the call — and the verdict
     * arrives inside `data`, which is where the scheduler looks for it.
     */
    @Test
    @DisplayName("未命中 - 外层信封仍是 200，data.success 是真的 false")
    fun `a command that found no live execution is still a 200 whose data says false`() {
        answerWith(CommandResponse.failure(SESSION_ID, "No live execution for this session on this instance"))

        callCommand(SESSION_ID, "INTERRUPT")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").exists())
            .andExpect(jsonPath("$.data.success").value(false))
            .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
            .andExpect(jsonPath("$.data.message").value("No live execution for this session on this instance"))
    }

    /** The other arm, so the assertion above cannot be satisfied by a body that always says false. */
    @Test
    @DisplayName("命中 - data.success 为 true，scheduler 据此等执行自己收尾")
    fun `a delivered command reports success through the same path`() {
        answerWith(CommandResponse.success(SESSION_ID))

        callCommand(SESSION_ID, "INTERRUPT")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.success").value(true))
    }

    /**
     * The request half of the same contract: the scheduler names the command as an enum constant, and a
     * body that did not deserialise into [CommandType.INTERRUPT] would have the router interrupt nothing
     * while still answering that it had interrupted something.
     */
    @Test
    @DisplayName("请求侧 - 命令名按 CommandType 解出来，session 原样带上")
    fun `the command name on the wire reaches the service as the parsed enum`() {
        answerWith(CommandResponse.success(SESSION_ID))

        callCommand(SESSION_ID, "INTERRUPT")

        val captor = argumentCaptor<CommandAgentRequest>()
        runBlocking { verify(sessionRouterService).proxyCommandRequest(captor.capture()) }
        assertEquals(CommandType.INTERRUPT, captor.firstValue.command)
        assertEquals(SESSION_ID, captor.firstValue.sessionId)
    }

    companion object {
        /** Same shape the scheduler generates: `task-{taskId}-{uuid}`. */
        private const val SESSION_ID = "task-7-6f1d0a2e"
    }
}
