package com.agnetix.harnax.harness

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.agnetix.harnax.agent.CliSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.SandboxConfig
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.state.AgentStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import java.nio.file.Path
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * What a CLI package's declared `env` becomes inside the sandbox container: a package asks for
 * `${platform.adminUrl}` rather than a URL because only this deployment knows where admin is reachable
 * from a container. The resolution rules are the whole of what a package author can rely on, so they are
 * asserted here; the map is handed straight to `DockerFilesystemSpec.environment`.
 */
class HarnessAgentLauncherCliEnvTest {

    private fun launcher(
        adminUrl: String = "",
        internalToken: String = "",
    ): HarnessAgentLauncher = HarnessAgentLauncher(
        chatModelConfigAdaptor = mock(ChatModelConfigAdaptor::class.java),
        mcpConfigAdaptor = mock(McpConfigAdaptor::class.java),
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = mock(SkillAdaptor::class.java),
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
        harnessConfig = HarnessConfig(
            sandbox = SandboxConfig(platformAdminUrl = adminUrl, platformInternalToken = internalToken),
        ),
    )

    private fun cli(
        name: String = "harnax",
        runtimeEnv: Map<String, String> = emptyMap(),
        envBindings: Map<String, String> = emptyMap(),
    ) = CliSpec(cliId = 7L, name = name, runtimeEnv = runtimeEnv, envBindings = envBindings)

    /** [block] with every WARN the launcher emits while it runs, so a silent fallback cannot pass as a good one. */
    private fun reportingWarnings(block: () -> Map<String, String>): Pair<Map<String, String>, List<String>> {
        val logger = LoggerFactory.getLogger(HarnessAgentLauncher::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.WARN
        try {
            return block() to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    fun `a literal env value reaches the container unchanged`() {
        val env = launcher().cliEnvironment(listOf(cli(runtimeEnv = mapOf("HARNAX_PROFILE" to "prod"))))

        assertEquals(mapOf("HARNAX_PROFILE" to "prod"), env)
    }

    @Test
    fun `the platform slots are filled from this deployment's configuration`() {
        val env = launcher(adminUrl = "http://admin:8080", internalToken = "s3cret").cliEnvironment(
            listOf(
                cli(
                    runtimeEnv = mapOf(
                        "HARNAX_URL" to "\${platform.adminUrl}",
                        "HARNAX_TOKEN" to "\${platform.internalToken}",
                    ),
                ),
            ),
        )

        assertEquals("http://admin:8080", env["HARNAX_URL"])
        assertEquals("s3cret", env["HARNAX_TOKEN"])
    }

    @Test
    fun `an unpublished slot leaves its variable absent and says why`() {
        // Empty would be a value the CLI has to distinguish from a real one; absent makes the CLI
        // itself report the missing configuration instead of failing against an empty URL.
        val subject = launcher()
        val (env, warnings) = reportingWarnings {
            subject.cliEnvironment(listOf(cli(runtimeEnv = mapOf("HARNAX_URL" to "\${platform.adminUrl}"))))
        }

        assertFalse(env.containsKey("HARNAX_URL"), "unset slot leaked: $env")
        val warning = warnings.single { it.contains("HARNAX_URL") }
        assertTrue(warning.contains("platform.adminUrl"), "the warning does not name the slot: $warning")
    }

    @Test
    fun `an agent's own binding wins over the value the package declares`() {
        val env = launcher(adminUrl = "http://admin:8080").cliEnvironment(
            listOf(
                cli(
                    runtimeEnv = mapOf("HARNAX_URL" to "\${platform.adminUrl}", "ONLY_PACKAGE" to "p"),
                    envBindings = mapOf("HARNAX_URL" to "http://elsewhere"),
                ),
            ),
        )

        assertEquals("http://elsewhere", env["HARNAX_URL"])
        assertEquals("p", env["ONLY_PACKAGE"])
    }

    @Test
    fun `no selected CLI means no injected environment`() {
        val env = launcher(adminUrl = "http://admin:8080").cliEnvironment(emptyList())

        assertEquals(emptyMap<String, String>(), env)
    }
}
