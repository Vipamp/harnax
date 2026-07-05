package com.agnetix.harnax.router.proxy

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.AgentServiceClient
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.slf4j.MDC
import reactor.test.StepVerifier
import java.net.ConnectException
import java.time.LocalDateTime

class SessionRouterServiceTest {

    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var sessionMappingService: SessionMappingService
    private lateinit var idempotencyService: IdempotencyService
    private lateinit var circuitBreaker: InstanceCircuitBreaker
    private lateinit var agentServiceClient: AgentServiceClient
    private lateinit var meterRegistry: MeterRegistry
    private lateinit var service: SessionRouterService

    private val heartbeatTimeoutMs = 30000L
    private val failoverMaxRetries = 2

    @BeforeEach
    fun setUp() {
        instanceRegistry = mock(InstanceRegistry::class.java)
        sessionMappingService = mock(SessionMappingService::class.java)
        idempotencyService = mock(IdempotencyService::class.java)
        circuitBreaker = LocalInstanceCircuitBreaker(failureThreshold = 3, openDurationMs = 30000)
        agentServiceClient = mock(AgentServiceClient::class.java)
        meterRegistry = SimpleMeterRegistry()
        service = SessionRouterService(
            instanceRegistry,
            sessionMappingService,
            idempotencyService,
            circuitBreaker,
            agentServiceClient,
            meterRegistry,
            heartbeatTimeoutMs,
            failoverMaxRetries,
        )
    }

    private fun stubAgentClientChat() {
        val dummyResponse = ResultVo.success(ChatResponse(sessionId = "test", content = "ok"))
        runBlocking {
            `when`(agentServiceClient.chat(any(), any())).thenReturn(dummyResponse)
        }
    }

    private fun healthyInstance(id: String = "inst-1"): AgentInstance = AgentInstance().apply {
        instanceId = id
        host = "10.0.0.1"
        port = 8082
        status = "UP"
        active = 1
        lastHeartbeat = LocalDateTime.now()
    }

    private fun staleInstance(id: String = "inst-1"): AgentInstance = AgentInstance().apply {
        instanceId = id
        host = "10.0.0.1"
        port = 8082
        status = "UP"
        active = 1
        lastHeartbeat = LocalDateTime.now().minusSeconds(60)
    }

    private fun drainingInstance(id: String = "inst-1"): AgentInstance = AgentInstance().apply {
        instanceId = id
        host = "10.0.0.1"
        port = 8082
        status = "DRAINING"
        active = 1
        lastHeartbeat = LocalDateTime.now()
    }

    // ==================== proxyChatRequest - idempotency ====================

    @Test
    fun `proxyChatRequest rejects duplicate request`() = runBlocking {
        `when`(idempotencyService.tryAcquire("req-1")).thenReturn(false)

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        val result = service.proxyChatRequest(request)

        assertFalse(result.isSuccess())
        assertTrue(result.message.contains("Duplicate"))
    }

    @Test
    fun `proxyChatRequest accepts first request with given requestId`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire("req-1")).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChat()

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        assertNotNull(result.data)
        verify(idempotencyService).tryAcquire("req-1")
    }

    @Test
    fun `proxyChatRequest generates UUID when requestId is blank`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChat()

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verify(idempotencyService).tryAcquire(argThat { isNotBlank() })
    }

    // ==================== proxyChatRequest - MDC cleanup ====================

    @Test
    fun `proxyChatRequest clears MDC after completion`() = runBlocking {
        `when`(idempotencyService.tryAcquire("req-1")).thenReturn(false)

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        service.proxyChatRequest(request)

        assertNull(MDC.get("sessionId"))
        assertNull(MDC.get("requestId"))
        assertNull(MDC.get("instanceId"))
    }

    @Test
    fun `proxyChatRequest clears MDC even on error`() = runBlocking {
        `when`(idempotencyService.tryAcquire("req-1")).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(null)
        `when`(sessionMappingService.rerouteSession("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(null)

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        try {
            service.proxyChatRequest(request)
        } catch (_: Exception) {
        }

        assertNull(MDC.get("sessionId"))
        assertNull(MDC.get("requestId"))
    }

    // ==================== resolveInstance logic via proxyChatRequest ====================

    @Test
    fun `proxyChatRequest reroutes when bound instance is unhealthy`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(staleInstance("inst-1"))
        `when`(sessionMappingService.rerouteSession("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        stubAgentClientChat()

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verify(sessionMappingService).rerouteSession("session-1")
    }

    @Test
    fun `proxyChatRequest uses existing healthy binding`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChat()

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verify(sessionMappingService, never()).rerouteSession("session-1")
    }

    @Test
    fun `proxyChatRequest reroutes when no binding exists`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn(null)
        `when`(sessionMappingService.rerouteSession("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChat()

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verify(sessionMappingService).rerouteSession("session-1")
    }

    // ==================== DRAINING instance handling ====================

    @Test
    fun `proxyChatRequest reroutes when bound instance is DRAINING`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(drainingInstance("inst-1"))
        `when`(sessionMappingService.rerouteSession("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        stubAgentClientChat()

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verify(sessionMappingService).rerouteSession("session-1")
    }

    // ==================== Circuit breaker integration ====================

    @Test
    fun `proxyChatRequest reroutes when circuit breaker is open`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        `when`(sessionMappingService.rerouteSession("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        stubAgentClientChat()

        circuitBreaker.recordFailure("inst-1")
        circuitBreaker.recordFailure("inst-1")
        circuitBreaker.recordFailure("inst-1")

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-cb",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verify(sessionMappingService).rerouteSession("session-1")
    }

    // ==================== proxyStreamRequest - MDC and basic setup ====================

    @Test
    fun `proxyStreamRequest sets up MDC and clears after`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-stream",
        )

        service.proxyStreamRequest(request)

        assertNull(MDC.get("sessionId"))
        assertNull(MDC.get("requestId"))
    }

    @Test
    fun `proxyStreamRequest reroutes unhealthy instance`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(staleInstance("inst-1"))
        `when`(sessionMappingService.rerouteSession("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
        )

        service.proxyStreamRequest(request)

        verify(sessionMappingService).rerouteSession("session-1")
    }

    @Test
    fun `proxyStreamRequest returns error flux when no instance available`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn(null)
        `when`(sessionMappingService.rerouteSession("session-1")).thenThrow(IllegalStateException("No healthy instances"))

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
        )

        val result = service.proxyStreamRequest(request)

        StepVerifier.create(result)
            .assertNext { event ->
                assertTrue(event is ErrorChatEvent, "First event should be ErrorChatEvent")
                assertTrue((event as ErrorChatEvent).message.contains("No healthy instances"))
            }
            .assertNext { event ->
                assertTrue(event is EndEventChatEvent, "Second event should be EndEventChatEvent")
            }
            .verifyComplete()
    }

    // ==================== proxyConfirmStreamRequest ====================

    @Test
    fun `proxyConfirmStreamRequest clears MDC after setup`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        val request = ConfirmAgentRequest(
            sessionId = "session-1",
            isConfirmed = true,
        )

        service.proxyConfirmStreamRequest(request)

        assertNull(MDC.get("sessionId"))
    }

    @Test
    fun `proxyConfirmStreamRequest returns error flux when no instance available`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn(null)
        `when`(sessionMappingService.rerouteSession("session-1")).thenThrow(IllegalStateException("No instances"))

        val request = ConfirmAgentRequest(
            sessionId = "session-1",
            isConfirmed = true,
        )

        val result = service.proxyConfirmStreamRequest(request)

        StepVerifier.create(result)
            .assertNext { event ->
                assertTrue(event is ErrorChatEvent, "First event should be ErrorChatEvent")
            }
            .assertNext { event ->
                assertTrue(event is EndEventChatEvent, "Second event should be EndEventChatEvent")
            }
            .verifyComplete()
    }

    // ==================== proxyCommandRequest ====================

    @Test
    fun `proxyCommandRequest clears MDC after completion`() = runBlocking {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        val request = CommandAgentRequest(
            sessionId = "session-1",
            command = CommandType.INTERRUPT,
        )

        try {
            service.proxyCommandRequest(request)
        } catch (_: Exception) {
        }

        assertNull(MDC.get("sessionId"))
        assertNull(MDC.get("instanceId"))
    }

    // ==================== proxyClearSession ====================

    @Test
    fun `proxyClearSession clears MDC after error`() = runBlocking {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        try {
            service.proxyClearSession("session-1")
        } catch (_: Exception) {
        }

        assertNull(MDC.get("sessionId"))
    }

    // ==================== proxyLoadHistory ====================

    @Test
    fun `proxyLoadHistory clears MDC after error`() = runBlocking {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        try {
            service.proxyLoadHistory("session-1")
        } catch (_: Exception) {
        }

        assertNull(MDC.get("sessionId"))
    }

    // ==================== proxyLoadPlans ====================

    @Test
    fun `proxyLoadPlans clears MDC after error`() = runBlocking {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        try {
            service.proxyLoadPlans("session-1")
        } catch (_: Exception) {
        }

        assertNull(MDC.get("sessionId"))
    }

    // ==================== failover retry logic ====================

    @Test
    fun `proxyChatRequest attempts failover when initial call fails`() = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        `when`(sessionMappingService.rerouteSession("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))

        val successResponse = ResultVo.success(ChatResponse(sessionId = "test", content = "ok"))
        `when`(agentServiceClient.chat(any(), any()))
            .thenThrow(RuntimeException("Connection refused"))
            .thenReturn(successResponse)

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-fail",
        )

        val result = service.proxyChatRequest(request)
        assertTrue(result.isSuccess())

        val failoverCount = meterRegistry.find("router.failover.count").counter()
        assertNotNull(failoverCount)
        assertTrue(failoverCount!!.count() > 0)
    }

    // ==================== Metrics ====================

    @Test
    fun `meterRegistry records metrics`() {
        val counter = meterRegistry.counter("router.proxy.requests", "endpoint", "chat", "status", "ok")
        counter.increment()
        assertEquals(1.0, counter.count())
    }

    // ==================== Failover exhaustion & circuit breaker skip ====================

    @Test
    fun `proxyChatRequest throws when all failover instances have open circuit breakers`() = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        // Initial call must throw to trigger failover
        `when`(agentServiceClient.chat(any(), any())).thenThrow(RuntimeException("Connection refused"))

        var rerouteCallCount = 0
        `when`(sessionMappingService.rerouteSession("session-1")).thenAnswer {
            rerouteCallCount++
            "inst-reroute-$rerouteCallCount"
        }
        `when`(instanceRegistry.getInstance("inst-reroute-1")).thenReturn(healthyInstance("inst-reroute-1"))
        `when`(instanceRegistry.getInstance("inst-reroute-2")).thenReturn(healthyInstance("inst-reroute-2"))

        circuitBreaker.recordFailure("inst-reroute-1")
        circuitBreaker.recordFailure("inst-reroute-1")
        circuitBreaker.recordFailure("inst-reroute-1")
        circuitBreaker.recordFailure("inst-reroute-2")
        circuitBreaker.recordFailure("inst-reroute-2")
        circuitBreaker.recordFailure("inst-reroute-2")

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-exhaust",
        )

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { service.proxyChatRequest(request) }
        }
        assertTrue(ex.message?.contains("exhausted") == true || ex.message?.contains("Failover") == true)
    }

    @Test
    fun `retryFailover skips instances with open circuit breaker and tries next`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        // Initial call must throw to trigger failover
        `when`(agentServiceClient.chat(any(), any())).thenThrow(RuntimeException("Connection refused"))

        var rerouteCount = 0
        `when`(sessionMappingService.rerouteSession("session-1")).thenAnswer {
            rerouteCount++
            "inst-failover-$rerouteCount"
        }
        for (i in 1..2) {
            `when`(instanceRegistry.getInstance("inst-failover-$i")).thenReturn(healthyInstance("inst-failover-$i"))
        }

        for (i in 1..2) {
            circuitBreaker.recordFailure("inst-failover-$i")
            circuitBreaker.recordFailure("inst-failover-$i")
            circuitBreaker.recordFailure("inst-failover-$i")
        }

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-cbskip",
        )

        try {
            service.proxyChatRequest(request)
        } catch (_: Exception) {
        }

        verify(sessionMappingService, times(2)).rerouteSession("session-1")
    }

    // ==================== Stream failover error paths ====================

    @Test
    fun `proxyStreamRequest triggers failover on connectivity error and handles reroute failure`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        `when`(sessionMappingService.rerouteSession("session-1"))
            .thenThrow(IllegalStateException("No healthy instances for failover"))

        `when`(agentServiceClient.chatStream(any(), any(), any())).thenReturn(
            reactor.core.publisher.Flux.error(ConnectException("Connection refused")),
        )

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-sf-throw",
        )

        val result = service.proxyStreamRequest(request)

        StepVerifier.create(result)
            .assertNext { event ->
                assertTrue(event is ErrorChatEvent, "First event should be ErrorChatEvent")
            }
            .assertNext { event ->
                assertTrue(event is EndEventChatEvent, "Second event should be EndEventChatEvent")
            }
            .verifyComplete()
    }

    @Test
    fun `proxyStreamRequest returns error when connectivity failover target has open circuit breaker`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        `when`(sessionMappingService.rerouteSession("session-1")).thenReturn("inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))

        circuitBreaker.recordFailure("inst-2")
        circuitBreaker.recordFailure("inst-2")
        circuitBreaker.recordFailure("inst-2")

        `when`(agentServiceClient.chatStream(any(), any(), any())).thenReturn(
            reactor.core.publisher.Flux.error(ConnectException("Connection refused")),
        )

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-sf-cb",
        )

        val result = service.proxyStreamRequest(request)

        StepVerifier.create(result)
            .assertNext { event ->
                assertTrue(event is ErrorChatEvent, "First event should be ErrorChatEvent")
            }
            .assertNext { event ->
                assertTrue(event is EndEventChatEvent, "Second event should be EndEventChatEvent")
            }
            .verifyComplete()
    }
}
