package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.entity.ApiCallLog
import com.agnetix.harnax.router.mapper.ApiCallLogMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import java.time.LocalDateTime

class ApiCallLogServiceTest {

    private lateinit var mapper: ApiCallLogMapper
    private lateinit var service: ApiCallLogService

    @BeforeEach
    fun setUp() {
        mapper = mock(ApiCallLogMapper::class.java)
        service = ApiCallLogService(mapper)
    }

    private fun dummyLog(id: Int = 1): ApiCallLog = ApiCallLog().apply {
        callerId = "caller-$id"
        callerType = "EXTERNAL_API"
        endpoint = "/api/router/agent/chat"
        method = "POST"
        statusCode = 200
        success = 1
        startTime = LocalDateTime.now()
        endTime = LocalDateTime.now()
        durationMs = 100
    }

    @Test
    fun `record single entry does not flush immediately`() {
        service.record(dummyLog())
        verify(mapper, never()).batchInsert(any())
    }

    @Test
    fun `record 50 entries triggers automatic flush`() {
        `when`(mapper.batchInsert(any())).thenReturn(50)
        for (i in 1..50) {
            service.record(dummyLog(i))
        }
        verify(mapper, times(1)).batchInsert(any())
    }

    @Test
    fun `record 49 entries does not trigger flush`() {
        for (i in 1..49) {
            service.record(dummyLog(i))
        }
        verify(mapper, never()).batchInsert(any())
    }

    @Test
    fun `scheduledFlush writes buffered entries`() {
        `when`(mapper.batchInsert(any())).thenReturn(5)
        for (i in 1..5) {
            service.record(dummyLog(i))
        }

        service.scheduledFlush()

        verify(mapper, times(1)).batchInsert(argThat { size == 5 })
    }

    @Test
    fun `scheduledFlush does nothing when buffer is empty`() {
        service.scheduledFlush()
        verify(mapper, never()).batchInsert(any())
    }

    @Test
    fun `scheduledFlush writes in batches of 50`() {
        `when`(mapper.batchInsert(any())).thenReturn(50)
        for (i in 1..75) {
            service.record(dummyLog(i))
        }
        // 50 auto-flushed at record #50
        verify(mapper, times(1)).batchInsert(argThat { size == 50 })

        // Remaining 25 still in buffer
        service.scheduledFlush()
        verify(mapper, times(1)).batchInsert(argThat { size == 25 })
    }

    @Test
    fun `onShutdown flushes remaining entries`() {
        `when`(mapper.batchInsert(any())).thenReturn(3)
        for (i in 1..3) {
            service.record(dummyLog(i))
        }

        service.onShutdown()

        verify(mapper, times(1)).batchInsert(argThat { size == 3 })
    }

    @Test
    fun `flush failure re-enqueues entries`() {
        `when`(mapper.batchInsert(any())).thenThrow(RuntimeException("DB connection lost"))

        for (i in 1..5) {
            service.record(dummyLog(i))
        }
        service.scheduledFlush()

        // Failed flush should re-enqueue, so a second flush should succeed
        reset(mapper)
        `when`(mapper.batchInsert(any())).thenReturn(5)
        service.scheduledFlush()

        verify(mapper, times(1)).batchInsert(argThat { size == 5 })
    }

    @Test
    fun `buildLogEntry sets all fields correctly`() {
        val start = LocalDateTime.of(2026, 1, 1, 10, 0, 0)
        val end = LocalDateTime.of(2026, 1, 1, 10, 0, 2, 500_000_000)

        val entry = service.buildLogEntry(
            callerId = "user-1",
            callerType = "EXTERNAL_API",
            tenantId = 5L,
            sessionId = "sess-abc",
            agentId = 10L,
            agentName = "test-agent",
            modelId = 3L,
            modelName = "gpt-4",
            endpoint = "/api/router/agent/chat",
            method = "POST",
            requestType = "CHAT",
            statusCode = 200,
            success = true,
            errorMessage = null,
            startTime = start,
            endTime = end,
            instanceId = "inst-1",
            requestId = "req-xyz",
        )

        assertEquals("user-1", entry.callerId)
        assertEquals("EXTERNAL_API", entry.callerType)
        assertEquals(5L, entry.tenantId)
        assertEquals("sess-abc", entry.sessionId)
        assertEquals(10L, entry.agentId)
        assertEquals("test-agent", entry.agentName)
        assertEquals(3L, entry.modelId)
        assertEquals("gpt-4", entry.modelName)
        assertEquals("/api/router/agent/chat", entry.endpoint)
        assertEquals("POST", entry.method)
        assertEquals("CHAT", entry.requestType)
        assertEquals(200, entry.statusCode)
        assertEquals(1, entry.success)
        assertNull(entry.errorMessage)
        assertEquals(start, entry.startTime)
        assertEquals(end, entry.endTime)
        assertEquals(2500, entry.durationMs)
        assertEquals("inst-1", entry.instanceId)
        assertEquals("req-xyz", entry.requestId)
    }

    @Test
    fun `buildLogEntry success false maps to 0`() {
        val now = LocalDateTime.now()
        val entry = service.buildLogEntry(
            callerId = "c", callerType = "INTERNAL_SERVICE", tenantId = null,
            sessionId = null, agentId = null, agentName = null,
            modelId = null, modelName = null,
            endpoint = "/test", method = "GET", requestType = null,
            statusCode = 500, success = false, errorMessage = "timeout",
            startTime = now, endTime = now, instanceId = null, requestId = null,
        )

        assertEquals(0, entry.success)
        assertEquals("timeout", entry.errorMessage)
        assertEquals(0, entry.durationMs)
    }

    @Test
    fun `multiple scheduledFlush cycles`() {
        `when`(mapper.batchInsert(any())).thenReturn(3)

        // Cycle 1
        for (i in 1..3) service.record(dummyLog(i))
        service.scheduledFlush()
        verify(mapper, times(1)).batchInsert(any())

        // Cycle 2
        for (i in 4..8) service.record(dummyLog(i))
        service.scheduledFlush()
        verify(mapper, times(2)).batchInsert(any())
    }

    @Test
    fun `record beyond MAX_BUFFER_SIZE drops new entries`() {
        // 让 mapper 抛异常，这样每 50 条都会重新入队，bufferSize 会持续上涨
        `when`(mapper.batchInsert(any())).thenThrow(RuntimeException("DB down"))

        // 连续 record 远超 10_000 条
        repeat(10_500) { service.record(dummyLog(it)) }

        // bufferSize 应被钳在 10_000 上限
        assertTrue(service.currentBufferSize() <= 10_000)
        // 有丢弃发生
        assertTrue(service.droppedCount() > 0)
    }

    @Test
    fun `flush failure does not overflow buffer on re-enqueue`() {
        `when`(mapper.batchInsert(any())).thenThrow(RuntimeException("DB down"))

        // 让 buffer 接近上限
        repeat(9_990) { service.record(dummyLog(it)) }

        // 这次 flush 会失败，需要把 50 条重新入队，导致超限
        service.scheduledFlush()

        // 仍然在上限内
        assertTrue(service.currentBufferSize() <= 10_000)
    }
}
