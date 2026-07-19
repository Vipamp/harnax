package com.agnetix.harnax.harness.permission

import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.permission.PermissionBehavior
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.tool.AgentTool
import io.agentscope.core.tool.ToolCallParam
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class DangerousInputCheckingToolTest {

    private lateinit var delegate: AgentTool
    private lateinit var tool: DangerousInputCheckingTool
    private val emptyContext = PermissionContextState.builder().build()

    @BeforeEach
    fun setUp() {
        delegate = mock(AgentTool::class.java)
        `when`(delegate.name).thenReturn("test_tool")
        `when`(delegate.description).thenReturn("A test tool")
        `when`(delegate.parameters).thenReturn(emptyMap())
        tool = DangerousInputCheckingTool(delegate)
    }

    @Nested
    inner class DangerousCommandDetection {

        @Test
        fun `detects rm -rf command in string input`() {
            val input = mapOf("command" to "rm -rf /tmp/data")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.ASK, decision.behavior)
            assertTrue(decision.message.contains("dangerous command"))
            assertTrue(decision.message.contains("rm -rf"))
        }

        @Test
        fun `detects sudo rm command case-insensitively`() {
            val input = mapOf("cmd" to "SUDO rm /etc/passwd")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.ASK, decision.behavior)
            assertTrue(decision.message.contains("dangerous command"))
        }

        @Test
        fun `detects chmod 777 command`() {
            val input = mapOf("command" to "chmod 777 /var/www")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.ASK, decision.behavior)
            assertTrue(decision.message.contains("chmod 777"))
        }

        @Test
        fun `detects kill -9 command`() {
            val input = mapOf("cmd" to "kill -9 1234")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.ASK, decision.behavior)
        }

        @Test
        fun `returns safety reason for bypass-immune behavior`() {
            val input = mapOf("command" to "rm -rf /")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.ASK, decision.behavior)
            assertTrue(decision.decisionReason.contains("safety"))
        }
    }

    @Nested
    inner class DangerousPathDetection {

        @Test
        fun `detects env file path`() {
            val input = mapOf("path" to "/home/user/.env")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.ASK, decision.behavior)
            assertTrue(decision.message.contains("dangerous path"))
        }

        @Test
        fun `detects bashrc file`() {
            val input = mapOf("file" to "/root/.bashrc")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.ASK, decision.behavior)
            assertTrue(decision.message.contains("dangerous path"))
        }

        @Test
        fun `detects ssh directory`() {
            val input = mapOf("path" to "/home/user/.ssh/id_rsa")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.ASK, decision.behavior)
            assertTrue(decision.message.contains("dangerous path"))
        }

        @Test
        fun `detects git config file`() {
            val input = mapOf("path" to "/project/.git/config")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.ASK, decision.behavior)
        }
    }

    @Nested
    inner class SafeInputs {

        @Test
        fun `returns PASSTHROUGH for safe string input`() {
            val input = mapOf("message" to "Hello, world!")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.PASSTHROUGH, decision.behavior)
        }

        @Test
        fun `returns PASSTHROUGH for safe file path`() {
            val input = mapOf("path" to "/tmp/output/result.txt")
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.PASSTHROUGH, decision.behavior)
        }

        @Test
        fun `returns PASSTHROUGH for empty input map`() {
            val decision = tool.checkPermissions(emptyMap(), emptyContext).block()!!
            assertEquals(PermissionBehavior.PASSTHROUGH, decision.behavior)
        }

        @Test
        fun `skips non-string values`() {
            val input = mapOf<String, Any>(
                "count" to 42,
                "enabled" to true,
                "ratio" to 3.14,
            )
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.PASSTHROUGH, decision.behavior)
        }

        @Test
        fun `skips short strings below minimum length`() {
            val input = mapOf("x" to "rm") // 2 chars, below MIN_SCAN_LENGTH=3
            val decision = tool.checkPermissions(input, emptyContext).block()!!
            assertEquals(PermissionBehavior.PASSTHROUGH, decision.behavior)
        }
    }

    @Nested
    inner class Delegation {

        @Test
        fun `callAsync delegates to the wrapped tool`() {
            val param = mock(ToolCallParam::class.java)
            val result = mock(ToolResultBlock::class.java)
            `when`(delegate.callAsync(param)).thenReturn(Mono.just(result))

            StepVerifier.create(tool.callAsync(param))
                .expectNext(result)
                .verifyComplete()

            verify(delegate).callAsync(param)
        }
    }

    @Nested
    inner class ToolMetadata {

        @Test
        fun `inherits name from delegate`() {
            assertEquals("test_tool", tool.name)
        }

        @Test
        fun `inherits description from delegate`() {
            assertEquals("A test tool", tool.description)
        }

        @Test
        fun `inherits readOnly from ToolBase delegate`() {
            val readOnlyDelegate = mock(AgentTool::class.java).also {
                `when`(it.name).thenReturn("read_only_tool")
                `when`(it.description).thenReturn("A read-only tool")
                `when`(it.parameters).thenReturn(emptyMap())
            }
            val readOnlyTool = DangerousInputCheckingTool(readOnlyDelegate)
            // AgentTool mock doesn't extend ToolBase, so isReadOnly defaults to false
            assertEquals(false, readOnlyTool.isReadOnly)
        }
    }
}
