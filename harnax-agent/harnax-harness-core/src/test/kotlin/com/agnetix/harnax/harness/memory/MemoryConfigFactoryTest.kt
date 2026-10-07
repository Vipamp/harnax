package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.harness.config.Memory
import io.agentscope.core.model.Model
import io.agentscope.harness.agent.memory.MemoryConfig
import io.agentscope.harness.agent.memory.MemoryFlushManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration

/**
 * What `harness.memory.*` becomes once the launcher hands it to the harness pipeline.
 *
 * The trigger row of the switch matrix lives here, so the two-stage choice (`always` until the store
 * could compare-and-swap, `throttled` after) is a configuration decision with a test behind it rather
 * than a value in a wrapper.
 */
class MemoryConfigFactoryTest {

    @Test
    fun `the throttled window comes from configuration`() {
        val config = MemoryConfigFactory.build(
            Memory(flushTrigger = "throttled", flushMinGap = Duration.ofMinutes(7)),
            null,
        )

        assertEquals(MemoryConfig.FlushMode.THROTTLED, config.flushTrigger().mode())
        assertEquals(Duration.ofMinutes(7), config.flushTrigger().minGap())
    }

    @Test
    fun `always ignores the window`() {
        val config = MemoryConfigFactory.build(Memory(flushTrigger = "always"), null)

        assertEquals(MemoryConfig.FlushMode.ALWAYS, config.flushTrigger().mode())
    }

    @Test
    fun `never keeps the per-call flush off`() {
        val config = MemoryConfigFactory.build(Memory(flushTrigger = "never"), null)

        assertEquals(MemoryConfig.FlushMode.NEVER, config.flushTrigger().mode())
    }

    @Test
    fun `a mistyped trigger is refused rather than guessed`() {
        // Both readings of a typo are expensive and invisible: `always` pays one extraction per turn,
        // `never` leaves the memory domain silently empty.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MemoryConfigFactory.build(Memory(flushTrigger = "throtlled"), null)
        }
        assertTrue(failure.message!!.contains("throtlled"), failure.message!!)
    }

    @Test
    fun `the extraction prompt keeps the harness default and adds the two prohibitions`() {
        val prompt = MemoryConfigFactory.build(Memory(), null).flushPrompt()

        assertTrue(prompt!!.startsWith(MemoryFlushManager.DEFAULT_FLUSH_PROMPT.trim()))
        assertTrue(prompt.contains("another user"), prompt)
        assertTrue(prompt.contains("another tenant"), prompt)
        assertTrue(prompt.contains("credential"), prompt)
    }

    @Test
    fun `the consolidation cadence is pinned`() {
        val config = MemoryConfigFactory.build(Memory(), null)

        assertEquals(Duration.ofMinutes(30), config.consolidationMinGap())
        assertEquals(4_000, config.consolidationMaxTokens())
        assertEquals(90, config.dailyFileRetentionDays())
        assertEquals(180, config.sessionRetentionDays())
    }

    @Test
    fun `the curation window comes from configuration`() {
        // One knob for both throttles of design 11.4: this value is what the harness consolidates a bucket on
        // and what the launcher hands the promotion middleware as its gate window, so slowing a deployment
        // down does not leave conversation layers draining at the old rate.
        val config = MemoryConfigFactory.build(Memory(consolidationMinGap = Duration.ofMinutes(11)), null)

        assertEquals(Duration.ofMinutes(11), config.consolidationMinGap())
    }

    @Test
    fun `a memory model is used only when one was configured`() {
        val stub = mock(Model::class.java)

        assertNull(MemoryConfigFactory.build(Memory(modelId = 0L), null).model())
        assertSame(stub, MemoryConfigFactory.build(Memory(modelId = 0L), stub).model())
    }
}
