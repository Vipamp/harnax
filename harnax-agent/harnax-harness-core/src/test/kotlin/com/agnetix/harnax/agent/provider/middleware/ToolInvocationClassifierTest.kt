package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.entity.ToolInvocationLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * `kind` is decided by what assembly already knew, in one fixed order (design D4).
 *
 * The five cases below are the five shapes an incoming tool name can have; the priority cases are the
 * ones that would silently misfile a call if the order were read differently — an MCP server that ships a
 * tool named like a delivered one, and a shell command that runs a delivered CLI, are both real.
 */
class ToolInvocationClassifierTest {

    private val mcpIds = mapOf("github_search" to 11L)
    private val cliIds = mapOf("gh" to 21L, "aws" to 22L)
    private val builtins = setOf("send_email", "now")

    @Nested
    inner class Kind {
        @Test
        fun `a tool registered from an MCP server is mcp`() {
            val a = ToolInvocationClassifier.classify("github_search", emptyMap(), mcpIds, cliIds, builtins)
            assertEquals(ToolInvocationLog.KIND_MCP, a.kind)
            assertEquals(11L, a.mcpId)
            assertEquals("github_search", a.toolName)
        }

        @Test
        fun `the shell tool running a delivered CLI is cli keyed by the command name`() {
            val a = ToolInvocationClassifier.classify(
                "execute",
                mapOf("command" to "gh pr view 12"),
                mcpIds,
                cliIds,
                builtins,
            )
            assertEquals(ToolInvocationLog.KIND_CLI, a.kind)
            assertEquals(21L, a.cliId)
            assertEquals("gh", a.toolName, "the row must name the CLI, not the shell tool that ran it")
        }

        @Test
        fun `the shell tool without a delivered CLI is shell`() {
            val a = ToolInvocationClassifier.classify(
                "execute_shell_command",
                mapOf("command" to "ls -la"),
                mcpIds,
                cliIds,
                builtins,
            )
            assertEquals(ToolInvocationLog.KIND_SHELL, a.kind)
            assertEquals("execute_shell_command", a.toolName)
            assertNull(a.cliId)
        }

        @Test
        fun `a tool delivered with the agent spec is builtin`() {
            val a = ToolInvocationClassifier.classify("send_email", emptyMap(), mcpIds, cliIds, builtins)
            assertEquals(ToolInvocationLog.KIND_BUILTIN, a.kind)
        }

        @Test
        fun `anything else the harness registered is framework`() {
            val a = ToolInvocationClassifier.classify("read_file", emptyMap(), mcpIds, cliIds, builtins)
            assertEquals(ToolInvocationLog.KIND_FRAMEWORK, a.kind)
        }
    }

    @Nested
    inner class Priority {
        @Test
        fun `mcp wins over builtin when both names could match`() {
            val a = ToolInvocationClassifier.classify(
                "github_search",
                emptyMap(),
                mcpIds,
                cliIds,
                setOf("github_search"),
            )
            assertEquals(ToolInvocationLog.KIND_MCP, a.kind, "the registry is what the model actually sees")
        }

        @Test
        fun `a shell command that names an mcp tool is still shell not mcp`() {
            val a = ToolInvocationClassifier.classify(
                "execute",
                mapOf("command" to "github_search"),
                mcpIds,
                cliIds,
                builtins,
            )
            assertEquals(ToolInvocationLog.KIND_SHELL, a.kind)
        }

        @Test
        fun `a compound command records only the leftmost delivered cli`() {
            val a = ToolInvocationClassifier.classify(
                "execute",
                mapOf("command" to "gh pr view && aws s3 ls"),
                mcpIds,
                cliIds,
                builtins,
            )
            assertEquals(21L, a.cliId, "one invocation is one row (I1), so the second CLI is not duplicated")
        }

        @Test
        fun `the shell branch wins over builtin when the shell tool is itself delivered`() {
            val a = ToolInvocationClassifier.classify(
                "execute",
                mapOf("command" to "ls"),
                mcpIds,
                cliIds,
                builtins + "execute",
            )
            assertEquals(
                ToolInvocationLog.KIND_SHELL,
                a.kind,
                "Task 8 delivers execute as a builtin too; the shell must not degrade to builtin",
            )
        }

        @Test
        fun `a delivered cli is still cli when the shell tool is itself delivered`() {
            val a = ToolInvocationClassifier.classify(
                "execute",
                mapOf("command" to "gh pr view 12"),
                mcpIds,
                cliIds,
                builtins + "execute",
            )
            assertEquals(ToolInvocationLog.KIND_CLI, a.kind)
            assertEquals(21L, a.cliId)
        }
    }

    @Nested
    inner class CommandNames {
        @Test
        fun `an absolute path is matched by its file name`() {
            assertEquals(
                "aws",
                ToolInvocationClassifier.cliCommandName("/usr/local/bin/aws s3 ls", cliIds),
            )
        }

        @Test
        fun `an environment prefix does not become the command`() {
            assertEquals(
                "gh",
                ToolInvocationClassifier.cliCommandName("GH_PAGER=cat gh pr list", cliIds),
            )
        }

        @Test
        fun `a quoted command with a space is still one word`() {
            val ids = mapOf("my tool" to 31L)
            assertEquals("my tool", ToolInvocationClassifier.cliCommandName("\"my tool\" --version", ids))
        }

        @Test
        fun `pipes and semicolons open a new segment`() {
            assertEquals(
                "aws",
                ToolInvocationClassifier.cliCommandName("echo x | /bin/aws s3 ls", cliIds),
            )
            assertEquals("aws", ToolInvocationClassifier.cliCommandName("cd /tmp; aws s3 ls", cliIds))
        }

        @Test
        fun `nothing delivered matches an ordinary command`() {
            assertNull(ToolInvocationClassifier.cliCommandName("uname -a", cliIds))
            assertNull(ToolInvocationClassifier.cliCommandName(null, cliIds))
            assertNull(ToolInvocationClassifier.cliCommandName("gh --version", emptyMap()))
        }

        @Test
        fun `a delivered cli name used only as an argument does not attribute`() {
            assertNull(ToolInvocationClassifier.cliCommandName("echo gh", cliIds))
            assertNull(ToolInvocationClassifier.cliCommandName("echo x | grep aws", cliIds))
        }
    }

    /**
     * [ToolInvocationClassifier.commandHeads] is public because Task 8 builds a delivered CLI package's alias
     * set from that package's own `checkCommand`; these cases pin what such a caller may rely on.
     */
    @Nested
    inner class CommandHeads {
        @Test
        fun `every segment of a compound command contributes its first word`() {
            assertEquals(
                listOf("gh", "echo", "aws"),
                ToolInvocationClassifier.commandHeads("gh pr view && echo done; aws s3 ls"),
            )
        }

        @Test
        fun `an environment prefix is skipped so the real command is the head`() {
            assertEquals(listOf("gh"), ToolInvocationClassifier.commandHeads("GH_PAGER=cat gh pr list"))
        }

        @Test
        fun `an operator inside quotes does not open a segment`() {
            // the quoted `&&` is data and the unquoted one splits, which is what lets a check command carry a flag
            assertEquals(
                listOf("echo", "gh"),
                ToolInvocationClassifier.commandHeads("echo \"a && b\" && gh pr list"),
            )
        }

        @Test
        fun `only segment heads are candidates`() {
            // the reason `echo gh` runs no CLI: `gh` is an argument of echo, never a head
            assertEquals(listOf("echo"), ToolInvocationClassifier.commandHeads("echo gh"))
        }
    }
}
