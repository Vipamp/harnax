package com.agnetix.harnax.router.controller

import com.agnetix.harnax.router.dto.ApiCallLogPage
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.entity.ApiCallLog
import com.agnetix.harnax.router.service.ApiCallLogService
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

class RouterMonitorControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var sessionMappingService: SessionMappingService
    private lateinit var apiCallLogService: ApiCallLogService

    @BeforeEach
    fun setUp() {
        instanceRegistry = mock(InstanceRegistry::class.java)
        sessionMappingService = mock(SessionMappingService::class.java)
        apiCallLogService = mock(ApiCallLogService::class.java)

        val controller = RouterMonitorController(instanceRegistry, sessionMappingService, apiCallLogService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
    }

    // ==================== listInstances ====================

    @Nested
    inner class ListInstances {
        @Test
        fun `returns sorted instances with session counts`() {
            val inst1 = AgentInstance().apply {
                instanceId = "inst-b"
                host = "10.0.0.2"
                port = 8080
                status = "UP"
                lastHeartbeat = LocalDateTime.of(2025, 1, 1, 12, 0, 0)
            }
            val inst2 = AgentInstance().apply {
                instanceId = "inst-a"
                host = "10.0.0.1"
                port = 8080
                status = "UP"
                lastHeartbeat = LocalDateTime.of(2025, 1, 1, 12, 0, 0)
            }
            `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(inst1, inst2))
            `when`(sessionMappingService.getSessionCountsByInstances(listOf("inst-b", "inst-a")))
                .thenReturn(mapOf("inst-b" to 3, "inst-a" to 5))

            mockMvc.perform(get("/api/router/monitor/instances"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray)
                // Sorted by instanceId: inst-a comes first
                .andExpect(jsonPath("$.data[0].instanceId").value("inst-a"))
                .andExpect(jsonPath("$.data[0].sessionCount").value(5))
                .andExpect(jsonPath("$.data[1].instanceId").value("inst-b"))
                .andExpect(jsonPath("$.data[1].sessionCount").value(3))
        }

        @Test
        fun `returns empty list when no instances`() {
            `when`(instanceRegistry.getAllActiveInstances()).thenReturn(emptyList())
            `when`(sessionMappingService.getSessionCountsByInstances(emptyList()))
                .thenReturn(emptyMap())

            mockMvc.perform(get("/api/router/monitor/instances"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").isArray)
                .andExpect(jsonPath("$.data").isEmpty)
        }

        @Test
        fun `handles session count service failure gracefully`() {
            val inst = AgentInstance().apply {
                instanceId = "inst-1"
                host = "10.0.0.1"
                port = 8080
                status = "UP"
                lastHeartbeat = LocalDateTime.now()
            }
            `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(inst))
            `when`(sessionMappingService.getSessionCountsByInstances(any()))
                .thenThrow(RuntimeException("Redis down"))

            mockMvc.perform(get("/api/router/monitor/instances"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data[0].sessionCount").value(0))
        }
    }

    // ==================== queryCallLogs ====================

    @Nested
    inner class QueryCallLogs {
        @Test
        fun `delegates query to service with correct parameters`() {
            val page = ApiCallLogPage(items = emptyList(), total = 0, limit = 100, offset = 0)
            `when`(apiCallLogService.query(any())).thenReturn(page)

            mockMvc.perform(get("/api/router/monitor/call-logs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isArray)
        }

        @Test
        fun `passes filter parameters correctly`() {
            val logEntry = ApiCallLog().apply {
                id = 1
                sessionId = "session-1"
                statusCode = 200
            }
            val page = ApiCallLogPage(items = listOf(logEntry), total = 1, limit = 50, offset = 10)
            `when`(apiCallLogService.query(any())).thenReturn(page)

            mockMvc.perform(
                get("/api/router/monitor/call-logs")
                    .param("sessionId", "session-1")
                    .param("statusCode", "200")
                    .param("limit", "50")
                    .param("offset", "10"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.limit").value(50))
                .andExpect(jsonPath("$.data.offset").value(10))
        }

        @Test
        fun `returns results with all optional filters`() {
            val page = ApiCallLogPage(items = emptyList(), total = 0, limit = 100, offset = 0)
            `when`(apiCallLogService.query(any())).thenReturn(page)

            mockMvc.perform(
                get("/api/router/monitor/call-logs")
                    .param("sessionId", "s1")
                    .param("instanceId", "inst-1")
                    .param("agentName", "agent")
                    .param("statusCode", "500")
                    .param("success", "0")
                    .param("minDurationMs", "1000"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isArray)
        }
    }

    // ==================== MonitorInstanceInfo ====================

    @Nested
    inner class MonitorInstanceInfoTests {
        @Test
        fun `fromAgentInstance creates correct info`() {
            val inst = AgentInstance().apply {
                instanceId = "inst-1"
                host = "10.0.0.1"
                port = 8080
                status = "UP"
                lastHeartbeat = LocalDateTime.of(2025, 1, 1, 12, 0, 0)
            }
            val nowMs = System.currentTimeMillis()
            val info = MonitorInstanceInfo.fromAgentInstance(inst, sessionCount = 5, nowMs = nowMs)

            assertEquals("inst-1", info.instanceId)
            assertEquals("10.0.0.1", info.host)
            assertEquals(8080, info.port)
            assertEquals("UP", info.status)
            assertEquals(5, info.sessionCount)
            assertTrue(info.lastHeartbeatAgeMs >= 0)
        }

        @Test
        fun `fromAgentInstance computes heartbeat age`() {
            val recentHeartbeat = LocalDateTime.now().minusSeconds(10)
            val inst = AgentInstance().apply {
                instanceId = "inst-1"
                host = "10.0.0.1"
                port = 8080
                status = "UP"
                lastHeartbeat = recentHeartbeat
            }
            val nowMs = System.currentTimeMillis()
            val info = MonitorInstanceInfo.fromAgentInstance(inst, sessionCount = 0, nowMs = nowMs)

            // Age should be approximately 10 seconds (10000ms), allow some tolerance
            assertTrue(info.lastHeartbeatAgeMs in 5000..15000)
        }
    }

    // ==================== parseLastHeartbeatMs ====================

    @Nested
    inner class ParseLastHeartbeatMs {
        @Test
        fun `handles LocalDateTime`() {
            val controller = RouterMonitorController(instanceRegistry, sessionMappingService, apiCallLogService)
            val method = RouterMonitorController::class.java.getDeclaredMethod("parseLastHeartbeatMs", Any::class.java)
            method.isAccessible = true

            val ldt = LocalDateTime.of(2025, 1, 1, 0, 0, 0)
            val result = method.invoke(controller, ldt) as Long
            val expected = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            assertEquals(expected, result)
            assertTrue(result > 0)
        }

        @Test
        fun `handles null`() {
            val controller = RouterMonitorController(instanceRegistry, sessionMappingService, apiCallLogService)
            val method = RouterMonitorController::class.java.getDeclaredMethod("parseLastHeartbeatMs", Any::class.java)
            method.isAccessible = true

            val result = method.invoke(controller, null) as Long
            assertEquals(0L, result)
        }

        @Test
        fun `handles Number`() {
            val controller = RouterMonitorController(instanceRegistry, sessionMappingService, apiCallLogService)
            val method = RouterMonitorController::class.java.getDeclaredMethod("parseLastHeartbeatMs", Any::class.java)
            method.isAccessible = true

            val result = method.invoke(controller, 12345L) as Long
            assertEquals(12345L, result)
        }

        @Test
        fun `handles unknown type`() {
            val controller = RouterMonitorController(instanceRegistry, sessionMappingService, apiCallLogService)
            val method = RouterMonitorController::class.java.getDeclaredMethod("parseLastHeartbeatMs", Any::class.java)
            method.isAccessible = true

            val result = method.invoke(controller, "not-a-date") as Long
            assertEquals(0L, result)
        }
    }
}
