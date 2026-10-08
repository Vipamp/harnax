package com.agnetix.harnax.harness.compaction

import io.agentscope.harness.agent.memory.compaction.CompactionConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The compaction tier harnax pins, and the one difference it deliberately takes from upstream.
 *
 * Every number here equals what agentscope 2.0.4 uses today; the point of the object is not the values, it
 * is that they stop being inherited. `auto()` and `command()` are checked field by field against that single
 * source, so neither path can gain a knob without this test saying so.
 */
class AutoCompactionTierTest {

    @Nested
    @DisplayName("the automatic tier")
    inner class AutoTier {

        @Test
        @DisplayName("pins every knob to the number upstream uses today")
        fun pinsEveryKnob() {
            val config = AutoCompactionTier.auto()

            assertEquals(50, config.triggerMessages)
            assertEquals(0, config.triggerTokens)
            assertEquals(20_000, config.reserved)
            assertEquals(20, config.keepMessages)
            assertEquals(-1, config.keepTokens)
            assertEquals(2_000, config.keepTokensMin)
            assertEquals(8_000, config.keepTokensMax)
            assertEquals(0.25, config.keepTokensRatio)
        }

        @Test
        @DisplayName("pins the prune tier that trims old tool results")
        fun pinsPrune() {
            val prune = requireNotNull(AutoCompactionTier.auto().pruneConfig)

            assertEquals(40_000, prune.protectTokens)
            assertEquals(20_000, prune.minimumTokens)
            assertEquals(2_000, prune.maxOutputChars)
            assertEquals(
                setOf("read_file", "memory_search", "memory_get", "session_search"),
                prune.excludedTools,
            )
        }

        @Test
        @DisplayName("flushes before compacting and writes no session copy")
        fun switches() {
            val config = AutoCompactionTier.auto()

            assertTrue(
                config.isFlushBeforeCompact,
                "memory reads the prefix that is about to be trimmed out of the daily ledger",
            )
            assertFalse(
                config.isOffloadBeforeCompact,
                "the offload copy is the transcript channel harnax disabled: no reader, no delete",
            )
        }

        @Test
        @DisplayName("leaves the summary prompt referenced and the model and arg truncation unset")
        fun leavesThree() {
            val config = AutoCompactionTier.auto()

            // Referenced, not copied: improving upstream's prompt should reach harnax through this line.
            assertEquals(CompactionConfig.DEFAULT_SUMMARY_PROMPT, config.summaryPrompt)
            assertNull(config.model, "the summary runs on the agent's own model")
            assertNull(
                config.truncateArgsConfig,
                "arg truncation is fixed at 500/1000 chars with no knob — the reason the transcript channel was rejected",
            )
        }
    }

    @Nested
    @DisplayName("the command tier")
    inner class CommandTier {

        @Test
        @DisplayName("differs from the automatic tier in exactly three fields")
        fun differsInThreeFields() {
            val auto = AutoCompactionTier.auto()
            val command = AutoCompactionTier.command(null)

            assertEquals(1, command.triggerMessages, "a user who asked for this must not be told the chat is too short")
            assertFalse(command.isFlushBeforeCompact)
            assertFalse(command.isOffloadBeforeCompact)
            // Everything else is the same tier, which is the whole point of one source.
            assertEquals(auto.triggerTokens, command.triggerTokens)
            assertEquals(auto.reserved, command.reserved)
            assertEquals(auto.keepMessages, command.keepMessages)
            assertEquals(auto.keepTokens, command.keepTokens)
            assertEquals(auto.keepTokensMin, command.keepTokensMin)
            assertEquals(auto.keepTokensMax, command.keepTokensMax)
            assertEquals(auto.keepTokensRatio, command.keepTokensRatio)
            assertEquals(auto.summaryPrompt, command.summaryPrompt)
            assertEquals(auto.pruneConfig.protectTokens, command.pruneConfig.protectTokens)
            assertEquals(auto.pruneConfig.minimumTokens, command.pruneConfig.minimumTokens)
            assertEquals(auto.pruneConfig.maxOutputChars, command.pruneConfig.maxOutputChars)
            assertEquals(auto.pruneConfig.excludedTools, command.pruneConfig.excludedTools)
            assertEquals(auto.truncateArgsConfig, command.truncateArgsConfig)
            assertEquals(auto.model, command.model)
        }

        @Test
        @DisplayName("keepTokens moves only the tail, and null leaves the dynamic tier in charge")
        fun keepTokensMovesOnlyTheTail() {
            assertEquals(1_500, AutoCompactionTier.command(1_500).keepTokens)
            assertEquals(-1, AutoCompactionTier.command(null).keepTokens)
            val withNumber = AutoCompactionTier.command(1_500)
            val without = AutoCompactionTier.command(null)
            assertEquals(without.triggerMessages, withNumber.triggerMessages)
            assertEquals(without.reserved, withNumber.reserved)
            assertEquals(without.keepMessages, withNumber.keepMessages)
        }
    }

    @Test
    @DisplayName("upstream drift: the pinned tier equals upstream's default in every field but offload")
    fun driftSentinel() {
        // INTENTIONALLY the canary. Every field equals upstream's default today, so the day agentscope moves
        // one of them this goes red on purpose — that is a decision for harnax to take, not a bug to fix.
        // Re-read the new default, decide whether to keep the pinned number, and say so in the commit.
        val pinned = AutoCompactionTier.auto()
        val upstream = CompactionConfig.builder().build()

        assertEquals(upstream.triggerMessages, pinned.triggerMessages)
        assertEquals(upstream.triggerTokens, pinned.triggerTokens)
        assertEquals(upstream.reserved, pinned.reserved)
        assertEquals(upstream.keepMessages, pinned.keepMessages)
        assertEquals(upstream.keepTokens, pinned.keepTokens)
        assertEquals(upstream.keepTokensMin, pinned.keepTokensMin)
        assertEquals(upstream.keepTokensMax, pinned.keepTokensMax)
        assertEquals(upstream.keepTokensRatio, pinned.keepTokensRatio)
        assertEquals(upstream.summaryPrompt, pinned.summaryPrompt)
        assertTrue(upstream.isFlushBeforeCompact && pinned.isFlushBeforeCompact)
        assertEquals(upstream.truncateArgsConfig, pinned.truncateArgsConfig)
        assertEquals(upstream.model, pinned.model)
        assertEquals(upstream.pruneConfig.protectTokens, pinned.pruneConfig.protectTokens)
        assertEquals(upstream.pruneConfig.minimumTokens, pinned.pruneConfig.minimumTokens)
        assertEquals(upstream.pruneConfig.maxOutputChars, pinned.pruneConfig.maxOutputChars)
        assertEquals(upstream.pruneConfig.excludedTools, pinned.pruneConfig.excludedTools)
        // The one intended difference.
        assertTrue(upstream.isOffloadBeforeCompact, "upstream still defaults the session copy on")
        assertFalse(pinned.isOffloadBeforeCompact)
    }
}
