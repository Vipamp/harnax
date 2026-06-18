package com.agnetix.harnax.router.controller

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.proxy.SessionRouterService
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

class SessionRouterControllerIntegrationTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var sessionMappingService: SessionMappingService
    private lateinit var sessionRouterService: SessionRouterService

    @BeforeEach
    fun setUp() {
        instanceRegistry = mock(InstanceRegistry::class.java)
        sessionMappingService = mock(SessionMappingService::class.java)
        sessionRouterService = mock(SessionRouterService::class.java)

        val controller = SessionRouterController(
            instanceRegistry,
            sessionMappingService,
            sessionRouterService,
        )
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    }

    // ==================== Health endpoint ====================

    @Test
    fun `health endpoint returns UP with healthy instance count`() {
        val healthyInstance = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(healthyInstance))

        mockMvc.perform(get("/api/router/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.data.healthyInstances").value(1))
    }

    @Test
    fun `health endpoint returns zero healthy instances when none available`() {
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(emptyList())

        mockMvc.perform(get("/api/router/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.healthyInstances").value(0))
    }

    // ==================== Instance registration ====================

    @Test
    fun `register instance succeeds with valid parameters`() {
        mockMvc.perform(
            post("/api/router/instance/register")
                .param("instanceId", "inst-1")
                .param("host", "10.0.0.1")
                .param("port", "8082"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("registered"))
            .andExpect(jsonPath("$.data.instanceId").value("inst-1"))

        verify(instanceRegistry).registerInstance("inst-1", "10.0.0.1", 8082)
    }

    @Test
    fun `register instance rejects invalid host`() {
        mockMvc.perform(
            post("/api/router/instance/register")
                .param("instanceId", "inst-1")
                .param("host", "host with spaces")
                .param("port", "8082"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").isNotEmpty)

        verify(instanceRegistry, never()).registerInstance(anyString(), anyString(), anyInt())
    }

    @Test
    fun `register instance rejects loopback address`() {
        mockMvc.perform(
            post("/api/router/instance/register")
                .param("instanceId", "inst-1")
                .param("host", "127.0.0.1")
                .param("port", "8082"),
        )
            .andExpect(status().isOk)

        verify(instanceRegistry, never()).registerInstance(anyString(), anyString(), anyInt())
    }

    // ==================== Heartbeat ====================

    @Test
    fun `heartbeat succeeds for existing instance`() {
        mockMvc.perform(
            post("/api/router/instance/heartbeat")
                .param("instanceId", "inst-1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("ok"))

        verify(instanceRegistry).refreshHeartbeat("inst-1")
    }

    // ==================== Unregister ====================

    @Test
    fun `unregister instance succeeds`() {
        `when`(sessionMappingService.unbindInstanceSessions("inst-1")).thenReturn(3)

        mockMvc.perform(
            post("/api/router/instance/unregister")
                .param("instanceId", "inst-1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("unregistered"))

        verify(instanceRegistry).unregisterInstance("inst-1")
        verify(sessionMappingService).unbindInstanceSessions("inst-1")
    }

    // ==================== Drain ====================

    @Test
    fun `drain instance succeeds`() {
        mockMvc.perform(
            post("/api/router/instance/drain")
                .param("instanceId", "inst-1"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("draining"))

        verify(instanceRegistry).markAsDraining("inst-1")
    }

    // ==================== List instances ====================

    @Test
    fun `list instances returns all active instances`() {
        val inst1 = AgentInstance().apply {
            instanceId = "inst-1"
            host = "10.0.0.1"
            port = 8082
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(inst1))

        mockMvc.perform(get("/api/router/instance/list"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].instanceId").value("inst-1"))
            .andExpect(jsonPath("$.data[0].host").value("10.0.0.1"))
            .andExpect(jsonPath("$.data[0].port").value(8082))
    }

    // ==================== Proxy endpoints (suspend) ====================

    @Test
    fun `proxy clear session delegates to service`(): Unit = runBlocking {
        `when`(sessionRouterService.proxyClearSession("session-1"))
            .thenReturn(ResultVo.success("cleared"))

        sessionRouterService.proxyClearSession("session-1")

        verify(sessionRouterService).proxyClearSession("session-1")
    }

    @Test
    fun `proxy load history delegates to service`(): Unit = runBlocking {
        `when`(sessionRouterService.proxyLoadHistory("session-1"))
            .thenReturn(ResultVo.success(emptyList()))

        sessionRouterService.proxyLoadHistory("session-1")

        verify(sessionRouterService).proxyLoadHistory("session-1")
    }

    @Test
    fun `proxy load plans delegates to service`(): Unit = runBlocking {
        `when`(sessionRouterService.proxyLoadPlans("session-1"))
            .thenReturn(ResultVo.success(emptyList()))

        sessionRouterService.proxyLoadPlans("session-1")

        verify(sessionRouterService).proxyLoadPlans("session-1")
    }

    @Test
    fun `proxy load current plan delegates to service`(): Unit = runBlocking {
        `when`(sessionRouterService.proxyLoadCurrentPlan("session-1"))
            .thenReturn(ResultVo.success(null))

        sessionRouterService.proxyLoadCurrentPlan("session-1")

        verify(sessionRouterService).proxyLoadCurrentPlan("session-1")
    }
}
