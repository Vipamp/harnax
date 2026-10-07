package com.agnetix.harnax.harness

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.minio.ProcessLocalCoordinationStore
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Which store the assembly hands upstream for the consolidation gate, asserted against fakes rather than a
 * live MinIO: upstream builds `StoreBackedPeriodicGate` out of whatever the distributed store returns, so
 * this one choice is the whole difference between a throttle that works, one that claims nothing, and one
 * that claims everything.
 */
class HarnessAgentLauncherCoordinationTest {

    private fun launcher(workspaceRoot: Path) = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = mock(SkillAdaptor::class.java),
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = workspaceRoot,
        harnessConfig = HarnessConfig(),
    )

    /** [block] with every WARN the launcher emits while it runs. */
    private fun warnings(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(HarnessAgentLauncher::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val previous = logger.level
        logger.level = Level.WARN
        try {
            block()
            return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }
    }

    @Test
    fun `a store that really compares versions is handed upstream untouched`(@TempDir workspace: Path) {
        val store = HonestStore()

        val said = warnings { assertSame(store, launcher(workspace).coordinationStore(store)) }

        assertTrue(said.isEmpty(), "a store that answered the probe is not a problem to report: $said")
    }

    @Test
    fun `a store that cannot compare is answered for, with the reason it refused`(@TempDir workspace: Path) {
        val store = NoCasStore()
        var chosen: BaseStore? = null

        val said = warnings { chosen = launcher(workspace).coordinationStore(store) }

        assertInstanceOf(ProcessLocalCoordinationStore::class.java, chosen)
        assertEquals(1, said.size, "which gate the deployment got has to be said once, got $said")
        assertTrue(
            said.single().contains("never went through"),
            "the store's own refusal belongs in the warn an operator reads: ${said.single()}",
        )
    }

    @Test
    fun `a verdict the store gave is not asked again`(@TempDir workspace: Path) {
        // Assembly runs per session. The answer is a property of the object store, so a deployment on a
        // CAS-less store would pay the probe on every session start for a fact that cannot change there.
        val store = NoCasStore()
        val launcher = launcher(workspace)

        repeat(3) { launcher.coordinationStore(store) }

        assertEquals(1, store.probes, "the probe ran ${store.probes} times for three assemblies")
    }

    @Test
    fun `a verdict past its own life is asked again`(@TempDir workspace: Path) {
        // Why the cache expires at all: the warn that serves the gate locally tells the operator to repair the
        // store to get the shared one back, and that is only true if this class asks the store again sometime
        // after they do.
        val store = NoCasStore()
        val launcher = launcher(workspace)

        repeat(2) { launcher.casSupport(store, Duration.ofNanos(0)) }

        assertEquals(2, store.probes, "an expired verdict has to be looked up again, not kept: ${store.probes}")
    }

    @Test
    fun `a store that never answered is asked again next time`(@TempDir workspace: Path) {
        // The other half: MinIO restarting while the first session of the process comes in must not pin this
        // replica to per-replica coordination for the rest of its life.
        val store = UnreachableStore()
        val launcher = launcher(workspace)

        repeat(2) { assertFalse(launcher.casSupport(store).definitive, "a refused connection is not a verdict") }

        assertEquals(2, store.probes, "an answer about the connection has to be looked up again, not kept")
    }

    /** Counts probe visits and can genuinely compare versions. */
    private open class CountingStore : BaseStore {
        val versions = mutableMapOf<Pair<List<String>, String>, Long>()
        var probes = 0

        override fun get(namespace: List<String>, key: String): StoreItem? = versions[namespace to key]?.let { StoreItem(key, mapOf("lastClaimAt" to 0L), it) }

        override fun put(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
        ) {
            probes++
            write(namespace, key, value)
        }

        override fun search(namespace: List<String>, limit: Int, offset: Int): List<StoreItem> = versions.keys.filter { it.first == namespace }
            .map { (ns, key) -> StoreItem(key, emptyMap(), versions.getValue(ns to key)) }

        override fun delete(namespace: List<String>, key: String) {
            versions.remove(namespace to key)
        }

        protected fun write(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
        ) {
            versions[namespace to key] = (versions[namespace to key] ?: 0L) + 1L
        }
    }

    private class NoCasStore : CountingStore()

    /** Inherits nothing and answers nothing: what an endpoint that is not there looks like from here. */
    private class UnreachableStore : CountingStore() {
        override fun get(namespace: List<String>, key: String): StoreItem? = throw RuntimeException("connection refused")

        override fun put(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
        ) {
            probes++
            throw RuntimeException("connection refused")
        }
    }

    private class HonestStore : CountingStore() {
        override fun putIfVersion(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
            expectedVersion: Long,
        ): Boolean {
            if (versions[namespace to key] != expectedVersion) return false
            write(namespace, key, value)
            return true
        }
    }
}
