package com.agnetix.harnax.router.proxy

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.AgentServiceClient
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionAccessGuard
import com.agnetix.harnax.router.service.SessionEvictor
import com.agnetix.harnax.router.service.SessionMappingService
import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeoutException

class SessionRouterServiceTest {

    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var sessionMappingService: SessionMappingService
    private lateinit var idempotencyService: IdempotencyService
    private lateinit var circuitBreaker: InstanceCircuitBreaker
    private lateinit var agentServiceClient: AgentServiceClient
    private lateinit var sessionEvictor: SessionEvictor
    private lateinit var sessionAccessGuard: SessionAccessGuard
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
        sessionEvictor = mock(SessionEvictor::class.java)
        sessionAccessGuard = mock(SessionAccessGuard::class.java)
        meterRegistry = SimpleMeterRegistry()
        service = SessionRouterService(
            instanceRegistry,
            sessionMappingService,
            idempotencyService,
            circuitBreaker,
            agentServiceClient,
            sessionEvictor,
            sessionAccessGuard,
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

    private fun stubAgentClientChatFails(error: Throwable) {
        runBlocking {
            `when`(agentServiceClient.chat(any(), any())).thenThrow(error)
        }
    }

    private fun healthyInstance(id: String = "inst-1"): AgentInstance = AgentInstance().apply {
        instanceId = id
        host = "10.0.0.1"
        port = 8082
        status = "UP"
        active = 1
        lastHeartbeat = Instant.now()
    }

    private fun staleInstance(id: String = "inst-1"): AgentInstance = AgentInstance().apply {
        instanceId = id
        host = "10.0.0.1"
        port = 8082
        status = "UP"
        active = 1
        lastHeartbeat = Instant.now().minusSeconds(60)
    }

    private fun drainingInstance(id: String = "inst-1"): AgentInstance = AgentInstance().apply {
        instanceId = id
        host = "10.0.0.1"
        port = 8082
        status = "DRAINING"
        active = 1
        lastHeartbeat = Instant.now()
    }

    /**
     * Every placement routing asked for, in order, with the exclusion set as it looked when it was
     * handed over. Routing reuses one mutable set while it fails over, so a Mockito matcher or captor
     * would only ever show that set's final state — the snapshot has to be taken here.
     */
    private val placements = mutableListOf<Pair<String, Set<String>>>()

    /**
     * Every placement now names the instances it is fleeing — the bound one, the ones whose breaker is
     * tripped — so the single-argument overload is never what routing calls. Successive placements
     * return [targets] in order, repeating the last one.
     */
    private fun stubReroute(
        sessionId: String,
        vararg targets: String,
    ) {
        var calls = 0
        `when`(sessionMappingService.rerouteSession(eq(sessionId), any())).thenAnswer { invocation ->
            recordPlacement(sessionId, invocation)
            targets.getOrElse(calls++) { targets.last() }
        }
    }

    private fun stubRerouteFails(
        sessionId: String,
        error: Throwable,
    ) {
        `when`(sessionMappingService.rerouteSession(eq(sessionId), any())).thenAnswer { invocation ->
            recordPlacement(sessionId, invocation)
            throw error
        }
    }

    private fun recordPlacement(
        sessionId: String,
        invocation: InvocationOnMock,
    ) {
        val excluded: Set<String> = invocation
            .getArgument<Collection<String>>(1)
            .toCollection(mutableSetOf())
        placements += sessionId to excluded
    }

    /** Asserts the session was re-placed exactly once, fleeing exactly [excludedInstanceIds]. */
    private fun verifyRerouteAvoids(
        sessionId: String,
        vararg excludedInstanceIds: String,
    ) {
        assertEquals(
            listOf(sessionId to setOf(*excludedInstanceIds)),
            placements.filter { it.first == sessionId },
            "expected one placement fleeing exactly these instances",
        )
    }

    private fun verifyNeverRerouted(sessionId: String) {
        verify(sessionMappingService, never()).rerouteSession(eq(sessionId), any())
    }

    /** Opens an instance's circuit the way the breaker in [setUp] defines it. */
    private fun trip(breaker: InstanceCircuitBreaker, instanceId: String) {
        repeat(3) { breaker.recordFailure(instanceId) }
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
    fun `proxyChatRequest releases the lease once the request is over`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire("req-1")).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChat()

        val request = ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-1")

        assertTrue(service.proxyChatRequest(request).isSuccess())
        verify(idempotencyService).release("req-1")
    }

    @Test
    fun `proxyChatRequest releases the lease when the call fails`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire("req-1")).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChatFails(RuntimeException("agent-service unavailable"))

        val request = ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-1")

        try {
            service.proxyChatRequest(request)
        } catch (_: Exception) {
            // The router rethrows; GlobalExceptionHandler renders it to the client.
        }
        // A client that retries after a failed call must not be told it is a duplicate.
        verify(idempotencyService).release("req-1")
    }

    @Test
    fun `proxyChatRequest does not dedupe a request without a client id`(): Unit = runBlocking {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChat()

        val request = ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "")

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        // The router generates an id for tracing, but a generated id identifies no client retry, so
        // spending two Redis round trips on it would be pure overhead.
        verifyNoInteractions(idempotencyService)
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
        stubReroute("session-1", "inst-2")
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
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        stubAgentClientChat()

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verifyRerouteAvoids("session-1", "inst-1")
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
        verifyNeverRerouted("session-1")
    }

    @Test
    fun `proxyChatRequest reroutes when no binding exists`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn(null)
        stubReroute("session-1", "inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChat()

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verifyRerouteAvoids("session-1")
    }

    // ==================== DRAINING instance handling ====================

    @Test
    fun `proxyChatRequest reroutes when bound instance is DRAINING`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(drainingInstance("inst-1"))
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        stubAgentClientChat()

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-1",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verifyRerouteAvoids("session-1", "inst-1")
    }

    // ==================== Circuit breaker integration ====================

    @Test
    fun `an open breaker leaves a live binding alone`(): Unit = runBlocking {
        // Re-homing on a breaker reading moved every session off the instance at once, and the
        // binding came straight back as soon as the window closed. Only the registry may invalidate
        // a binding.
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChat()
        trip(circuitBreaker, "inst-1")

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-cb-sticky",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verifyNeverRerouted("session-1")
    }

    @Test
    fun `a new placement avoids a tripped instance`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn(null)
        `when`(instanceRegistry.getHealthyInstances())
            .thenReturn(listOf(healthyInstance("inst-1"), healthyInstance("inst-2")))
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        stubAgentClientChat()
        trip(circuitBreaker, "inst-1")

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-cb-place",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        verifyRerouteAvoids("session-1", "inst-1")
    }

    @Test
    fun `a client-side 4xx is not held against the instance`(): Unit = runBlocking {
        // A 400 says the request was wrong, not that the node is sick. Tripping on it would let one
        // bad client take an instance out of the pool for everyone.
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        `when`(agentServiceClient.chat(any(), any())).thenThrow(responseError(400))

        val request = ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-4xx")

        assertThrows(WebClientResponseException::class.java) { runBlocking { service.proxyChatRequest(request) } }

        assertEquals(0, circuitBreaker.getFailureCount("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, circuitBreaker.getState("inst-1"))
        verifyNeverRerouted("session-1")
    }

    @Test
    fun `a 5xx counts once against the instance and fails the request over`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))

        val successResponse = ResultVo.success(ChatResponse(sessionId = "test", content = "ok"))
        `when`(agentServiceClient.chat(any(), any()))
            .thenThrow(responseError(503))
            .thenReturn(successResponse)

        val request = ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-5xx")

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        assertEquals(1, circuitBreaker.getFailureCount("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, circuitBreaker.getState("inst-2"))
    }

    private fun responseError(status: Int): WebClientResponseException = WebClientResponseException.create(
        status,
        "$status",
        HttpHeaders.EMPTY,
        ByteArray(0),
        StandardCharsets.UTF_8,
    )

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
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
        )

        service.proxyStreamRequest(request)

        verifyRerouteAvoids("session-1", "inst-1")
    }

    @Test
    fun `proxyStreamRequest returns error flux when no instance available`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn(null)
        stubRerouteFails("session-1", IllegalStateException("No healthy instances"))

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
        stubRerouteFails("session-1", IllegalStateException("No instances"))

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
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))

        val successResponse = ResultVo.success(ChatResponse(sessionId = "test", content = "ok"))
        `when`(agentServiceClient.chat(any(), any()))
            .thenThrow(RuntimeException("Connection refused", java.net.ConnectException("Connection refused")))
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
        verifyRerouteAvoids("session-1", "inst-1")
    }

    // ==================== Metrics ====================

    @Test
    fun `a chat that dies with an exception counts as an error`() = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        stubAgentClientChatFails(RuntimeException("Connection refused", ConnectException("Connection refused")))

        val request = ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-metrics")

        assertThrows(Exception::class.java) { runBlocking { service.proxyChatRequest(request) } }

        // An exception never reached these meters before: the dashboard showed a healthy fleet while
        // every request was dying.
        assertEquals(
            1.0,
            meterRegistry.get("router.proxy.requests").tag("endpoint", "chat").tag("status", "error").counter().count(),
        )
        assertEquals(1L, meterRegistry.get("router.proxy.duration").tag("endpoint", "chat").timer().count())
    }

    // ==================== Failover exhaustion & circuit breaker skip ====================

    @Test
    fun `proxyChatRequest throws when all failover instances have open circuit breakers`() = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        // Initial call must throw a retryable error to trigger failover
        `when`(agentServiceClient.chat(any(), any())).thenThrow(RuntimeException("Connection refused", java.net.ConnectException("Connection refused")))

        stubReroute("session-1", "inst-reroute-1", "inst-reroute-2")
        `when`(instanceRegistry.getInstance("inst-reroute-1")).thenReturn(healthyInstance("inst-reroute-1"))
        `when`(instanceRegistry.getInstance("inst-reroute-2")).thenReturn(healthyInstance("inst-reroute-2"))

        trip(circuitBreaker, "inst-reroute-1")
        trip(circuitBreaker, "inst-reroute-2")

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-exhaust",
        )

        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { service.proxyChatRequest(request) }
        }
        assertTrue(
            ex.message?.contains("exhausted") == true || ex.message?.contains("Circuit open") == true,
            "failover must report why it gave up, got: ${ex.message}",
        )
        assertEquals(2, placements.size, "each attempt must ask for a fresh placement")
    }

    @Test
    fun `retryFailover skips instances with open circuit breaker and tries next`(): Unit = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        `when`(instanceRegistry.getInstance("inst-open")).thenReturn(healthyInstance("inst-open"))
        `when`(instanceRegistry.getInstance("inst-good")).thenReturn(healthyInstance("inst-good"))

        // Initial call must throw a retryable error to trigger failover
        val successResponse = ResultVo.success(ChatResponse(sessionId = "test", content = "ok"))
        `when`(agentServiceClient.chat(any(), any()))
            .thenThrow(RuntimeException("Connection refused", ConnectException("Connection refused")))
            .thenReturn(successResponse)

        stubReroute("session-1", "inst-open", "inst-good")
        trip(circuitBreaker, "inst-open")

        val request = ChatAgentRequest(
            sessionId = "session-1",
            message = "hello",
            requestId = "req-cbskip",
        )

        val result = service.proxyChatRequest(request)

        assertTrue(result.isSuccess())
        assertEquals(
            listOf("session-1" to setOf("inst-1"), "session-1" to setOf("inst-1", "inst-open")),
            placements,
            "a candidate refused by its breaker must cost a placement, not a request — and the next " +
                "placement must flee it too",
        )
        // The refused candidate is never sent traffic: only the failed call and the good one are.
        verify(agentServiceClient, times(2)).chat(any(), any())
    }

    // ==================== Stream failover error paths ====================

    @Test
    fun `proxyStreamRequest triggers failover on connectivity error and handles reroute failure`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubRerouteFails("session-1", IllegalStateException("No healthy instances for failover"))

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
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))

        trip(circuitBreaker, "inst-2")

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

    // ==================== Streams are not replayed once they started ====================

    @Test
    fun `a stream that breaks after the first event is not answered again elsewhere`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        // Half an answer, then the agent dies: the client has already seen text.
        val partial: ChatEvent = StreamTextChatEvent(message = "Here is the plan", isLast = false, tokenUsage = null)
        val halfAnswered: Flux<ChatEvent> = Flux.just(partial)
            .concatWith(Flux.error(ConnectException("Connection reset")))
        `when`(agentServiceClient.chatStream(any(), any(), any())).thenReturn(halfAnswered)

        val request = ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-replay")

        StepVerifier.create(service.proxyStreamRequest(request))
            .assertNext { assertTrue(it is StreamTextChatEvent, "the partial answer must reach the client") }
            .assertNext { assertTrue(it is ErrorChatEvent, "a break must surface as an error, not silence") }
            .assertNext { assertTrue(it is EndEventChatEvent) }
            .verifyComplete()

        // Retrying would append a second answer under the first and run the agent's side effects twice.
        verifyNeverRerouted("session-1")
        verify(agentServiceClient, times(1)).chatStream(any(), any(), any())
    }

    @Test
    fun `a stream that goes silent is reported as failed and not moved elsewhere`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        val silent: Flux<ChatEvent> = Flux.error(TimeoutException("no event for 120s"))
        `when`(agentServiceClient.chatStream(any(), any(), any())).thenReturn(silent)

        val request = ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-idle")

        StepVerifier.create(service.proxyStreamRequest(request))
            .assertNext { assertTrue(it is ErrorChatEvent, "silence must reach the client as an error, not as the end of a conversation") }
            .assertNext { assertTrue(it is EndEventChatEvent) }
            .verifyComplete()

        // A stream that ran out of its own budget says nothing about the instance, so neither the
        // placement nor the circuit moves.
        verifyNeverRerouted("session-1")
        assertEquals(0, circuitBreaker.getFailureCount("inst-1"))
    }

    // ==================== Read-only endpoints do not re-place a session ====================

    @Test
    fun `reading history does not move the session when its instance looks stale`() {
        // The heartbeat says inst-1 may be gone. A chat would reroute; a history read must not, because
        // the history lives in inst-1's sandbox and no other instance can answer for it.
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(staleInstance("inst-1"))
        runBlocking {
            `when`(agentServiceClient.loadHistory(any(), eq("session-1"))).thenReturn(ResultVo.success(listOf("a message")))
        }

        val result = runBlocking { service.proxyLoadHistory("session-1") }

        assertTrue(result.isSuccess())
        assertEquals(1, result.data!!.size)
        verifyNeverRerouted("session-1")
    }

    @Test
    fun `a session that never routed simply has no history`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn(null)

        val result = runBlocking { service.proxyLoadHistory("session-1") }

        assertTrue(result.isSuccess())
        assertTrue(result.data!!.isEmpty())
        verifyNeverRerouted("session-1")
        verifyNoInteractions(agentServiceClient)
    }

    @Test
    fun `an upload with no instance to upload into is refused`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn(null)

        val result = runBlocking { service.proxyWorkspaceUpload("session-1", "/work", "notes.md", byteArrayOf(1, 2, 3)) }

        assertFalse(result.isSuccess())
        verifyNeverRerouted("session-1")
    }

    @Test
    fun `a failing history read is reported instead of being answered by another instance`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        runBlocking {
            `when`(agentServiceClient.loadHistory(any(), eq("session-1")))
                .thenThrow(RuntimeException("Connection refused", ConnectException("Connection refused")))
        }

        val thrown = assertThrows(RuntimeException::class.java) {
            runBlocking { service.proxyLoadHistory("session-1") }
        }
        assertTrue(thrown.cause is ConnectException, "the connectivity failure must reach the caller")

        verifyNeverRerouted("session-1")
        // The read still tells the breaker that this agent is not answering, so the next chat does
        // not land on it.
        assertEquals(1, circuitBreaker.getFailureCount("inst-1"))
    }

    // ==================== A session that moves is released where it was ====================

    @Test
    fun `a chat that reroutes away from a stale instance releases it there`() = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(staleInstance("inst-1"))
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        stubAgentClientChat()

        val result = service.proxyChatRequest(ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-evict"))

        assertTrue(result.isSuccess())
        verify(sessionEvictor).requestEviction("session-1", "inst-1")
    }

    @Test
    fun `a chat that stays on its instance releases nothing`() = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubAgentClientChat()

        val result = service.proxyChatRequest(ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-stay"))

        assertTrue(result.isSuccess())
        verifyNoInteractions(sessionEvictor)
    }

    @Test
    fun `failover releases the instance that stopped answering`() = runBlocking {
        `when`(idempotencyService.tryAcquire(anyString())).thenReturn(true)
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        val successResponse = ResultVo.success(ChatResponse(sessionId = "test", content = "ok"))
        `when`(agentServiceClient.chat(any(), any()))
            .thenThrow(RuntimeException("Connection refused", ConnectException("Connection refused")))
            .thenReturn(successResponse)

        service.proxyChatRequest(ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-fo"))

        verify(sessionEvictor).requestEviction("session-1", "inst-1")
    }

    @Test
    fun `a stream that fails over releases the instance that broke`() {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        stubReroute("session-1", "inst-2")
        `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
        val broken: Flux<ChatEvent> = Flux.error(RuntimeException("Connection refused", ConnectException("Connection refused")))
        val answered: Flux<ChatEvent> = Flux.just(EndEventChatEvent())
        `when`(agentServiceClient.chatStream(any(), any(), any())).thenReturn(broken).thenReturn(answered)

        val events = service.proxyStreamRequest(ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-sf"))
            .collectList()
            .block()!!

        assertEquals(1, events.size, "the retry answers the stream")
        verify(sessionEvictor).requestEviction("session-1", "inst-1")
    }

    @Test
    fun `a read-only proxy never releases the instance it asked`() = runBlocking {
        `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        `when`(agentServiceClient.loadHistory(any(), eq("session-1"))).thenReturn(ResultVo.success(emptyList()))
        `when`(agentServiceClient.loadPlans(any(), eq("session-1"))).thenReturn(ResultVo.success(emptyList()))
        `when`(agentServiceClient.workspaceListFiles(any(), eq("session-1"), any())).thenReturn(ResultVo.success(emptyList()))

        service.proxyLoadHistory("session-1")
        service.proxyLoadPlans("session-1")
        service.proxyWorkspaceListFiles("session-1", "/workspace")

        verifyNoInteractions(sessionEvictor)
    }

    // ==================== The call log learns where the call went ====================

    @Test
    fun `a routed chat leaves its placement on the request for the call log`() {
        val servletRequest = MockHttpServletRequest()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(servletRequest))
        try {
            `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
            `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
            stubAgentClientChat()

            runBlocking { service.proxyChatRequest(ChatAgentRequest(sessionId = "session-1", message = "hello")) }

            assertEquals("inst-1", servletRequest.getAttribute(SessionRouterService.ROUTED_INSTANCE_ATTR))
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    @Test
    fun `a stream records the instance that answered it rather than the one that broke`() {
        val servletRequest = MockHttpServletRequest()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(servletRequest))
        try {
            `when`(sessionMappingService.getInstanceId("session-1")).thenReturn("inst-1")
            `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
            `when`(instanceRegistry.getInstance("inst-2")).thenReturn(healthyInstance("inst-2"))
            stubReroute("session-1", "inst-2")
            val broken: Flux<ChatEvent> = Flux.error(RuntimeException("Connection refused", ConnectException("Connection refused")))
            val answered: Flux<ChatEvent> = Flux.just(EndEventChatEvent())
            `when`(agentServiceClient.chatStream(any(), any(), any())).thenReturn(broken).thenReturn(answered)

            service.proxyStreamRequest(ChatAgentRequest(sessionId = "session-1", message = "hello", requestId = "req-attr"))
                .collectList()
                .block()

            // MDC says nothing for a stream, so this attribute is the only trace of where it went.
            assertEquals("inst-2", servletRequest.getAttribute(SessionRouterService.ROUTED_INSTANCE_ATTR))
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    // ==================== Tenant access ====================

    /**
     * The guard runs before routing on every entry point, so a session the caller may not touch
     * reaches neither an agent — which would answer as a peer service — nor the registry.
     */
    @Test
    fun `a refused session reaches neither an agent nor the registry`() {
        doThrow(SecurityException("Session belongs to another tenant"))
            .`when`(sessionAccessGuard)
            .requireAccessible("session-1")

        assertThrows(SecurityException::class.java) {
            runBlocking {
                service.proxyChatRequest(
                    ChatAgentRequest(sessionId = "session-1", message = "hi", requestId = ""),
                )
            }
        }
        assertThrows(SecurityException::class.java) {
            runBlocking { service.proxyLoadHistory("session-1") }
        }
        // Refused before routing, so no stream is ever opened: the caller gets an ordinary error
        // response rather than an error event inside a stream it asked for.
        assertThrows(SecurityException::class.java) {
            service.proxyStreamRequest(ChatAgentRequest(sessionId = "session-1", message = "hi", requestId = ""))
        }

        verifyNoInteractions(agentServiceClient)
        verifyNoInteractions(instanceRegistry)
    }

    @Test
    fun `a malformed session list never reaches an agent`(): Unit = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.proxyWorkspaceStatus("session-1,../../etc/passwd") }
        }
        verify(agentServiceClient, never()).workspaceStatus(any(), any())
    }

    @Test
    fun `workspace status is refused unless every listed session is the caller's`(): Unit = runBlocking {
        doAnswer { invocation ->
            if (invocation.getArgument<String>(0) == "other") {
                throw SecurityException("Session belongs to another tenant")
            }
            null
        }.`when`(sessionAccessGuard).requireAccessible(any())
        `when`(sessionMappingService.getInstanceId("mine")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))

        assertThrows(SecurityException::class.java) {
            runBlocking { service.proxyWorkspaceStatus("mine,other") }
        }
        verify(agentServiceClient, never()).workspaceStatus(any(), any())
    }

    @Test
    fun `workspace status forwards the sessions it checked`(): Unit = runBlocking {
        `when`(sessionMappingService.getInstanceId("a")).thenReturn("inst-1")
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(healthyInstance("inst-1"))
        `when`(agentServiceClient.workspaceStatus(any(), any())).thenReturn(
            ResultVo.success(mapOf<String, Map<String, Any>>("a" to mapOf("active" to true))),
        )

        service.proxyWorkspaceStatus("a, b")

        verify(sessionAccessGuard, atLeastOnce()).requireAccessible("a")
        verify(sessionAccessGuard, atLeastOnce()).requireAccessible("b")
        verify(agentServiceClient).workspaceStatus("http://10.0.0.1:8082", "a,b")
    }
}
