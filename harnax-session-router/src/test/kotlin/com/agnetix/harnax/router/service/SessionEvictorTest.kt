package com.agnetix.harnax.router.service

import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.entity.AgentInstance
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.after
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import java.net.ConnectException
import java.time.Instant

/**
 * The eviction worker runs off the request thread, so these tests let Mockito wait for the worker
 * ([timeout] for a call that must happen, [after] for one that must not) instead of sleeping.
 */
class SessionEvictorTest {

    private lateinit var sessionMappingService: SessionMappingService
    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var agentServiceClient: AgentServiceClient
    private lateinit var evictor: SessionEvictor

    @BeforeEach
    fun setUp() {
        sessionMappingService = mock(SessionMappingService::class.java)
        instanceRegistry = mock(InstanceRegistry::class.java)
        agentServiceClient = mock(AgentServiceClient::class.java)
        evictor = SessionEvictor(sessionMappingService, instanceRegistry, agentServiceClient, enabled = true, maxPending = 20)
    }

    @AfterEach
    fun tearDown() {
        evictor.shutdown()
    }

    private fun instance(id: String): AgentInstance = AgentInstance().apply {
        instanceId = id
        host = "10.0.0.1"
        port = 8082
        status = "UP"
        active = 1
        lastHeartbeat = Instant.now()
    }

    private fun stubCommandAnswer(answer: ResultVo<CommandResponse>) {
        runBlocking {
            `when`(agentServiceClient.command(any(), any())).thenReturn(answer)
        }
    }

    /** Waits for the worker to have asked, or not asked, for anything at all. */
    private fun expectNoEviction() {
        runBlocking { verify(agentServiceClient, after(QUIT_WAIT).never()).command(any(), any()) }
    }

    @Test
    fun `a session that moved is stopped on the instance it left`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(instance("inst-1"))
        stubCommandAnswer(ResultVo.success(CommandResponse.success("session-1")))

        evictor.requestEviction("session-1", "inst-1")

        val url = argumentCaptor<String>()
        val request = argumentCaptor<CommandAgentRequest>()
        runBlocking { verify(agentServiceClient, timeout(SEEK_WAIT)).command(url.capture(), request.capture()) }
        assertEquals("http://10.0.0.1:8082", url.firstValue)
        assertEquals("session-1", request.firstValue.sessionId)
        // STOP_SANDBOX, not CLEAR: CLEAR deletes the history and the plans as well, and every instance
        // reads those from the same MySQL.
        assertEquals(CommandType.STOP_SANDBOX, request.firstValue.command)
    }

    @Test
    fun `a session that came back home is left running`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")

        evictor.requestEviction("session-1", "inst-1")

        expectNoEviction()
    }

    @Test
    fun `an unconfirmed move evicts nothing`() {
        // During a Redis outage the router places sessions from node-local knowledge. Evicting on the
        // strength of that would stop the sandbox of a session that never really moved.
        `when`(sessionMappingService.getInstanceId("session-1")).thenThrow(IllegalStateException("Redis is down"))

        evictor.requestEviction("session-1", "inst-1")

        expectNoEviction()
    }

    @Test
    fun `an instance the registry forgot is not evicted`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(null)

        evictor.requestEviction("session-1", "inst-1")

        // The worker did get as far as looking the instance up, so this is a decision and not a delay.
        verify(instanceRegistry, timeout(SEEK_WAIT)).getInstance("inst-1")
        expectNoEviction()
    }

    @Test
    fun `an agent that will not answer an eviction is not a failure`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(instance("inst-1"))
        runBlocking {
            `when`(agentServiceClient.command(any(), any())).thenAnswer {
                throw RuntimeException("Connection refused", ConnectException("Connection refused"))
            }
        }

        assertDoesNotThrow { evictor.requestEviction("session-1", "inst-1") }

        // One attempt only, and no retry: the worker does not chase an instance that refused it.
        runBlocking { verify(agentServiceClient, after(SEEK_WAIT).times(1)).command(any(), any()) }
    }

    @Test
    fun `eviction can be turned off`() {
        val disabled = SessionEvictor(sessionMappingService, instanceRegistry, agentServiceClient, enabled = false, maxPending = 20)

        disabled.requestEviction("session-1", "inst-1")
        // shutdown() waits for the worker, so nothing can still be in flight when it returns.
        disabled.shutdown()

        verifyNoInteractions(sessionMappingService, instanceRegistry, agentServiceClient)
    }

    @Test
    fun `a session with nowhere to come from is not evicted`() {
        evictor.requestEviction("session-1", null)

        expectNoEviction()
        verify(sessionMappingService, never()).getInstanceId("session-1")
    }

    companion object {
        private const val SEEK_WAIT = 5_000L
        private const val QUIT_WAIT = 500L
    }
}
