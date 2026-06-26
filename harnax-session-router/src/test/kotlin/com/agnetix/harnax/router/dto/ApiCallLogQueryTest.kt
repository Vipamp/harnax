package com.agnetix.harnax.router.dto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ApiCallLogQueryTest {

    @Nested
    inner class DefaultValues {
        @Test
        fun `default limit is 100`() {
            val query = ApiCallLogQuery()
            assertEquals(100, query.limit)
        }

        @Test
        fun `default offset is 0`() {
            val query = ApiCallLogQuery()
            assertEquals(0, query.offset)
        }

        @Test
        fun `all filter fields default to null`() {
            val query = ApiCallLogQuery()
            assertNull(query.sessionId)
            assertNull(query.instanceId)
            assertNull(query.agentName)
            assertNull(query.statusCode)
            assertNull(query.success)
            assertNull(query.minDurationMs)
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `accepts limit at lower boundary`() {
            val query = ApiCallLogQuery(limit = 1)
            assertEquals(1, query.limit)
        }

        @Test
        fun `accepts limit at upper boundary`() {
            val query = ApiCallLogQuery(limit = 1000)
            assertEquals(1000, query.limit)
        }

        @Test
        fun `rejects limit below 1`() {
            assertThrows(IllegalArgumentException::class.java) {
                ApiCallLogQuery(limit = 0)
            }
        }

        @Test
        fun `rejects limit above 1000`() {
            assertThrows(IllegalArgumentException::class.java) {
                ApiCallLogQuery(limit = 1001)
            }
        }

        @Test
        fun `rejects negative offset`() {
            assertThrows(IllegalArgumentException::class.java) {
                ApiCallLogQuery(offset = -1)
            }
        }

        @Test
        fun `accepts offset of zero`() {
            val query = ApiCallLogQuery(offset = 0)
            assertEquals(0, query.offset)
        }

        @Test
        fun `accepts large offset`() {
            val query = ApiCallLogQuery(offset = 99999)
            assertEquals(99999, query.offset)
        }
    }

    @Nested
    inner class FilterFields {
        @Test
        fun `all filters can be set`() {
            val query = ApiCallLogQuery(
                sessionId = "session-1",
                instanceId = "inst-1",
                agentName = "test-agent",
                statusCode = 500,
                success = 0,
                minDurationMs = 1000L,
                limit = 50,
                offset = 10,
            )

            assertEquals("session-1", query.sessionId)
            assertEquals("inst-1", query.instanceId)
            assertEquals("test-agent", query.agentName)
            assertEquals(500, query.statusCode)
            assertEquals(0, query.success)
            assertEquals(1000L, query.minDurationMs)
            assertEquals(50, query.limit)
            assertEquals(10, query.offset)
        }
    }

    @Nested
    inner class DataClassBehavior {
        @Test
        fun `copy creates modified copy`() {
            val original = ApiCallLogQuery(sessionId = "s1", limit = 50)
            val copy = original.copy(sessionId = "s2")

            assertEquals("s2", copy.sessionId)
            assertEquals(50, copy.limit)
        }

        @Test
        fun `equals works correctly`() {
            val q1 = ApiCallLogQuery(sessionId = "s1", limit = 100)
            val q2 = ApiCallLogQuery(sessionId = "s1", limit = 100)
            val q3 = ApiCallLogQuery(sessionId = "s2", limit = 100)

            assertEquals(q1, q2)
            assertNotEquals(q1, q3)
        }
    }

    // ==================== ApiCallLogPage ====================

    @Nested
    inner class ApiCallLogPageTests {
        @Test
        fun `page holds correct data`() {
            val page = ApiCallLogPage(
                items = emptyList(),
                total = 42,
                limit = 10,
                offset = 20,
            )

            assertTrue(page.items.isEmpty())
            assertEquals(42, page.total)
            assertEquals(10, page.limit)
            assertEquals(20, page.offset)
        }
    }

    // ==================== RouterResponses DTOs ====================

    @Nested
    inner class RouterResponseDtos {
        @Test
        fun `InstanceOperationResponse holds status and instanceId`() {
            val resp = InstanceOperationResponse(status = "registered", instanceId = "inst-1")
            assertEquals("registered", resp.status)
            assertEquals("inst-1", resp.instanceId)
        }

        @Test
        fun `InstanceInfo holds all fields`() {
            val info = InstanceInfo(
                instanceId = "inst-1",
                host = "10.0.0.1",
                port = 8080,
                status = "UP",
                lastHeartbeat = "2025-01-01T12:00:00",
            )
            assertEquals("inst-1", info.instanceId)
            assertEquals("10.0.0.1", info.host)
            assertEquals(8080, info.port)
            assertEquals("UP", info.status)
            assertEquals("2025-01-01T12:00:00", info.lastHeartbeat)
        }

        @Test
        fun `RouterHealthResponse holds status and count`() {
            val resp = RouterHealthResponse(status = "UP", healthyInstances = 3)
            assertEquals("UP", resp.status)
            assertEquals(3, resp.healthyInstances)
        }
    }
}
