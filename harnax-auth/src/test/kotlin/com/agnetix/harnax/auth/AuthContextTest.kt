package com.agnetix.harnax.auth

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AuthContextTest {

    @AfterEach
    fun tearDown() {
        AuthContextHolder.clear()
    }

    @Nested
    inner class IsInternal {
        @Test
        fun `returns true for INTERNAL_SERVICE caller`() {
            val ctx = AuthContext(callerId = "svc-a", callerType = CallerType.INTERNAL_SERVICE)
            assertTrue(ctx.isInternal())
        }

        @Test
        fun `returns false for EXTERNAL_API caller`() {
            val ctx = AuthContext(callerId = "ext-app", callerType = CallerType.EXTERNAL_API)
            assertFalse(ctx.isInternal())
        }

        @Test
        fun `default callerType is INTERNAL_SERVICE`() {
            val ctx = AuthContext(callerId = "svc-default")
            assertTrue(ctx.isInternal())
            assertEquals(CallerType.INTERNAL_SERVICE, ctx.callerType)
        }
    }

    @Nested
    inner class DefaultFields {
        @Test
        fun `default tenantId is null`() {
            val ctx = AuthContext(callerId = "svc")
            assertNull(ctx.tenantId)
        }

        @Test
        fun `default rateLimitPerMinute is null`() {
            val ctx = AuthContext(callerId = "svc")
            assertNull(ctx.rateLimitPerMinute)
        }

        @Test
        fun `default scopes is empty`() {
            val ctx = AuthContext(callerId = "svc")
            assertTrue(ctx.scopes.isEmpty())
        }
    }

    @Nested
    inner class AuthContextHolderTests {
        @Test
        fun `set and get returns same context`() {
            val ctx = AuthContext(callerId = "svc-1")
            AuthContextHolder.set(ctx)
            assertEquals(ctx, AuthContextHolder.get())
        }

        @Test
        fun `get returns null before set`() {
            assertNull(AuthContextHolder.get())
        }

        @Test
        fun `clear removes the context`() {
            AuthContextHolder.set(AuthContext(callerId = "svc-1"))
            AuthContextHolder.clear()
            assertNull(AuthContextHolder.get())
        }

        @Test
        fun `set overwrites previous context`() {
            AuthContextHolder.set(AuthContext(callerId = "svc-1"))
            AuthContextHolder.set(AuthContext(callerId = "svc-2"))
            assertEquals("svc-2", AuthContextHolder.get()?.callerId)
        }

        @Test
        fun `ThreadLocal isolation across threads`() {
            AuthContextHolder.set(AuthContext(callerId = "main-thread"))

            var otherThreadValue: AuthContext? = null
            val thread = Thread {
                otherThreadValue = AuthContextHolder.get()
            }
            thread.start()
            thread.join()

            assertEquals("main-thread", AuthContextHolder.get()?.callerId)
            assertNull(otherThreadValue)
        }
    }
}
