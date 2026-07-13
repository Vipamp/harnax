package com.agnetix.harnax.tools.sdk

import com.agnetix.harnax.tools.sdk.adaptor.ToolCallInfo
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*

/**
 * ToolBox Unit Tests
 *
 * Uses a concrete TestableToolBox subclass to verify the abstract ToolBox behavior:
 * - init() stores context fields
 * - execute() wraps actions with logging (success / error)
 *
 * @author agnetix
 * @since 2026-07-10
 */
class ToolBoxTest {

    /** Concrete subclass for testing the abstract ToolBox */
    class TestableToolBox : ToolBox() {
        override fun name(): String = "test-tool"

        fun normalAction(): String = execute { "normal-result" }

        fun actionWithArgs(input: String): String = execute("input" to input) { "echo-$input" }

        fun failingAction(): String = execute { throw RuntimeException("boom") }

        fun getProtectedUserIdentifier(): UserIdentifier = userIdentifier()
    }

    private lateinit var toolBox: TestableToolBox
    private lateinit var mockAdaptor: ToolCallLogAdaptor
    private val sessionMeta = SessionMetaContext(agentId = 100L, sessionId = "sess-001")
    private val userId = UserIdentifier(userId = 42L)

    @BeforeEach
    fun setUp() {
        toolBox = TestableToolBox()
        mockAdaptor = mock()
        toolBox.init(mockAdaptor, sessionMeta, userId)
    }

    @Nested
    @DisplayName("Init Tests")
    inner class InitTests {

        @Test
        @DisplayName("init should store userIdentifier accessible via userIdentifier()")
        fun `init should store userIdentifier`() {
            assertEquals(userId, toolBox.getProtectedUserIdentifier())
        }
    }

    @Nested
    @DisplayName("Execute Success Tests")
    inner class ExecuteSuccessTests {

        @Test
        @DisplayName("execute should return action result on success")
        fun `execute should return action result`() {
            val result = toolBox.normalAction()
            assertEquals("normal-result", result)
        }

        @Test
        @DisplayName("execute should emit ToolCallInfo via adaptor on success")
        fun `execute should log success via adaptor`() {
            toolBox.normalAction()

            val captor = argumentCaptor<ToolCallInfo>()
            verify(mockAdaptor, times(1)).emit(captor.capture())

            val info = captor.firstValue
            assertEquals(100L, info.agentId)
            assertEquals("sess-001", info.sessionId)
            assertEquals("test-tool::normalAction", info.toolName)
            assertTrue(info.success)
            assertEquals("normal-result", info.result)
            assertTrue(info.duration >= 0)
        }

        @Test
        @DisplayName("execute with args should include args in ToolCallInfo")
        fun `execute with args should log args`() {
            toolBox.actionWithArgs("hello")

            val captor = argumentCaptor<ToolCallInfo>()
            verify(mockAdaptor, times(1)).emit(captor.capture())

            val info = captor.firstValue
            assertEquals("hello", info.args["input"])
            assertEquals("echo-hello", info.result)
        }
    }

    @Nested
    @DisplayName("Execute Error Tests")
    inner class ExecuteErrorTests {

        @Test
        @DisplayName("execute should rethrow exception from action")
        fun `execute should rethrow exception`() {
            val ex = assertThrows<RuntimeException> {
                toolBox.failingAction()
            }
            assertEquals("boom", ex.message)
        }

        @Test
        @DisplayName("execute should emit error ToolCallInfo before rethrowing")
        fun `execute should log error before rethrowing`() {
            assertThrows<RuntimeException> {
                toolBox.failingAction()
            }

            val captor = argumentCaptor<ToolCallInfo>()
            verify(mockAdaptor, times(1)).emit(captor.capture())

            val info = captor.firstValue
            assertFalse(info.success)
            assertTrue(info.result.contains("ERROR: boom"))
        }
    }

    @Nested
    @DisplayName("Adaptor Error Handling Tests")
    inner class AdaptorErrorTests {

        @Test
        @DisplayName("adaptor exception on success path should not propagate")
        fun `adaptor exception on success should be swallowed`() {
            whenever(mockAdaptor.emit(any())).thenThrow(RuntimeException("adaptor-fail"))

            // Should NOT throw — adaptor error is caught internally
            val result = toolBox.normalAction()
            assertEquals("normal-result", result)
        }

        @Test
        @DisplayName("adaptor exception on error path should not mask original exception")
        fun `adaptor exception on error should not mask original exception`() {
            whenever(mockAdaptor.emit(any())).thenThrow(RuntimeException("adaptor-fail"))

            val ex = assertThrows<RuntimeException> {
                toolBox.failingAction()
            }
            // Original exception should be rethrown, not the adaptor exception
            assertEquals("boom", ex.message)
        }
    }
}
