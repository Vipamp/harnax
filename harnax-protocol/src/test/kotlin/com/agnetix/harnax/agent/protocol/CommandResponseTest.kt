package com.agnetix.harnax.agent.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CommandResponseTest {

    @Nested
    inner class SuccessFactory {
        @Test
        fun `success creates response with success true`() {
            val response = CommandResponse.success("session-1")

            assertTrue(response.success)
            assertEquals("session-1", response.sessionId)
            assertEquals("success", response.message)
            assertNull(response.result)
        }

        @Test
        fun `success with custom result`() {
            val result = mapOf("key" to "value")
            val response = CommandResponse.success("session-1", result = result)

            assertTrue(response.success)
            assertEquals(result, response.result)
            assertEquals("success", response.message)
        }

        @Test
        fun `success with custom message`() {
            val response = CommandResponse.success("session-1", message = "done")

            assertTrue(response.success)
            assertEquals("done", response.message)
        }

        @Test
        fun `success with result and message`() {
            val response = CommandResponse.success("session-1", result = 42, message = "completed")

            assertTrue(response.success)
            assertEquals(42, response.result)
            assertEquals("completed", response.message)
        }
    }

    @Nested
    inner class FailureFactory {
        @Test
        fun `failure creates response with success false`() {
            val response = CommandResponse.failure("session-1", "something went wrong")

            assertFalse(response.success)
            assertEquals("session-1", response.sessionId)
            assertEquals("something went wrong", response.message)
            assertNull(response.result)
        }

        @Test
        fun `failure with empty message`() {
            val response = CommandResponse.failure("session-1", "")

            assertFalse(response.success)
            assertEquals("", response.message)
        }
    }

    @Nested
    inner class DataClassBehavior {
        @Test
        fun `copy creates modified copy`() {
            val original = CommandResponse.success("session-1", result = "ok")
            val copy = original.copy(sessionId = "session-2")

            assertEquals("session-2", copy.sessionId)
            assertEquals(original.success, copy.success)
            assertEquals(original.result, copy.result)
            assertEquals(original.message, copy.message)
        }

        @Test
        fun `equals and hashCode work correctly`() {
            val r1 = CommandResponse("s1", true, "result", "msg")
            val r2 = CommandResponse("s1", true, "result", "msg")
            val r3 = CommandResponse("s2", true, "result", "msg")

            assertEquals(r1, r2)
            assertEquals(r1.hashCode(), r2.hashCode())
            assertNotEquals(r1, r3)
        }

        @Test
        fun `toString contains all fields`() {
            val response = CommandResponse.success("s1", result = "ok")
            val str = response.toString()

            assertTrue(str.contains("s1"))
            assertTrue(str.contains("true"))
            assertTrue(str.contains("ok"))
        }

        @Test
        fun `result can be any type`() {
            val listResult = CommandResponse("s1", true, result = listOf(1, 2, 3))
            val intResult = CommandResponse("s1", true, result = 42)
            val nullResult = CommandResponse("s1", true, result = null)

            assertEquals(listOf(1, 2, 3), listResult.result)
            assertEquals(42, intResult.result)
            assertNull(nullResult.result)
        }
    }
}
