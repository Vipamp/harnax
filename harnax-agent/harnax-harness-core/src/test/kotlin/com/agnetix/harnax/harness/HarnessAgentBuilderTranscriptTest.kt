package com.agnetix.harnax.harness

import io.agentscope.core.model.ChatModelBase
import io.agentscope.harness.agent.middleware.TranscriptMiddleware
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * Whether the built agent carries the transcript channel agentscope 2.0.4 installs by default.
 *
 * The channel is the second copy of a session's history: it appends the live context at the end of
 * every turn, truncated at fixed character counts with no configuration knob, and harnax has no
 * read-back path for it and no delete for it in `clearSession`. Turned off at assembly, so the
 * assertion belongs on the composed agent's middleware chain.
 */
class HarnessAgentBuilderTranscriptTest {

    private fun buildAgent(workspace: Path, transcriptOff: Boolean) = HarnessAgentBuilder()
        .name("tester")
        .description("tester")
        .maxIters(1)
        .systemPrompt("prompt")
        .model(mock(ChatModelBase::class.java))
        .workspace(workspace)
        .apply { if (transcriptOff) disableTranscript() }
        .build()
        .delegate
        .middlewares

    @Test
    fun `transcript channel is on unless the launcher turns it off`(@TempDir workspace: Path) {
        // The premise this test exists to keep honest: 2.0.4 installs the middleware by default, so
        // a passthrough that never reaches the builder would still leave the channel in place.
        assertTrue(
            buildAgent(workspace, transcriptOff = false).any { it is TranscriptMiddleware },
            "agentscope 2.0.4 is expected to install TranscriptMiddleware when nothing disables it",
        )
    }

    @Test
    fun `disableTranscript keeps the transcript channel off the assembled agent`(@TempDir workspace: Path) {
        assertFalse(
            buildAgent(workspace, transcriptOff = true).any { it is TranscriptMiddleware },
            "harnax reads history from the session_message archive, not from a truncated transcript copy",
        )
    }
}
