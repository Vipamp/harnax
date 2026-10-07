package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.adaptor.SkillUsageAdaptor
import io.agentscope.core.skill.AgentSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What one session's skill reads turn into `VIEW` events: attributed ids only, at most one event per skill
 * per cooldown window, and never a failure that reaches the read that triggered it.
 *
 * The clock is injected because the whole contract under test is a time window, and a test of a window has
 * to be able to move time rather than sleep through it.
 */
class SkillViewRecorderTest {

    /** Records every batch it was handed, in order, with the user each batch named. */
    private class FakeAdaptor(private val onFailure: () -> Throwable? = { null }) : SkillUsageAdaptor {
        val batches = mutableListOf<Pair<String, List<Long>>>()
        val users = mutableListOf<Long?>()

        /** Separate from [batches]: the recorder under test only ever reports loads, so this stays empty. */
        val uses = mutableListOf<List<Long>>()

        override fun reportViews(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            batches += sessionId to skillIds
            users += userId
            onFailure()?.let { throw it }
        }

        override fun reportUses(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            uses += skillIds
        }
    }

    private fun skill(name: String) = AgentSkill.builder()
        .name(name)
        .description("description of $name")
        .skillContent("# $name")
        .build()

    private fun recorder(
        adaptor: SkillUsageAdaptor,
        startMillis: Long = 0L,
        cooldownMillis: Long = 60_000L,
        userId: Long? = 1L,
    ): Pair<SkillViewRecorder, () -> Unit> {
        var now = startMillis
        val recorder = SkillViewRecorder("web-1", userId, adaptor, cooldownMillis) { now }
        return recorder to { now += cooldownMillis }
    }

    @Test
    fun `an attributed skill is reported on the first read`() {
        val adaptor = FakeAdaptor()
        val (recorder, _) = recorder(adaptor)
        recorder.attribute("pdf-tools", 7L)

        recorder.onRead(listOf(skill("pdf-tools")))

        assertEquals(listOf("web-1" to listOf(7L)), adaptor.batches)
        // The recorder reports loads only. A USE batch here would double-count a skill the model never
        // worked through, and no other assertion in this class reads the uses list, so nothing else would notice.
        assertTrue(adaptor.uses.isEmpty())
    }

    @Test
    fun `a name no binding attributed is not reported`() {
        // A skill the recorder cannot trace back to a `skill` row has no id to report. Guessing one from
        // the name would file a count against somebody else's skill.
        val adaptor = FakeAdaptor()
        val (recorder, _) = recorder(adaptor)
        recorder.attribute("pdf-tools", 7L)

        recorder.onRead(listOf(skill("pdf-tools"), skill("written-by-someone-else")))

        assertEquals(listOf("web-1" to listOf(7L)), adaptor.batches)
    }

    @Test
    fun `reads inside one window count once`() {
        val adaptor = FakeAdaptor()
        val (recorder, _) = recorder(adaptor)
        recorder.attribute("pdf-tools", 7L)
        val delivered = listOf(skill("pdf-tools"))

        // The harness re-reads per model call, so one answer's iterations must not become N events.
        repeat(5) { recorder.onRead(delivered) }

        assertEquals(1, adaptor.batches.size)
    }

    @Test
    fun `a read after the window reports again`() {
        val adaptor = FakeAdaptor()
        val (recorder, advance) = recorder(adaptor)
        recorder.attribute("pdf-tools", 7L)
        recorder.onRead(listOf(skill("pdf-tools")))

        advance()
        recorder.onRead(listOf(skill("pdf-tools")))

        assertEquals(2, adaptor.batches.size)
    }

    @Test
    fun `one skill counted once per read whatever its bindings`() {
        // Two delivered entries can only differ by content, since the repository keeps one per name — but a
        // read is a plain collection, so the same id must not appear twice in one batch.
        val adaptor = FakeAdaptor()
        val (recorder, _) = recorder(adaptor)
        recorder.attribute("report", 3L)

        recorder.onRead(listOf(skill("report"), skill("report")))

        assertEquals(listOf("web-1" to listOf(3L)), adaptor.batches)
    }

    @Test
    fun `nothing is sent for an empty read`() {
        val adaptor = FakeAdaptor()
        val (recorder, _) = recorder(adaptor)

        recorder.onRead(emptyList())

        assertTrue(adaptor.batches.isEmpty())
    }

    @Test
    fun `an adaptor that throws does not reach the read`() {
        // The harness swallows an exception from a repository and skips that repository entirely: a throwing
        // recorder would take every delivered skill out of the prompt, not just the count.
        val adaptor = FakeAdaptor { IllegalStateException("admin is down") }
        val (recorder, _) = recorder(adaptor)
        recorder.attribute("pdf-tools", 7L)

        recorder.onRead(listOf(skill("pdf-tools")))

        assertEquals(1, adaptor.batches.size)
    }

    @Test
    fun `the last attribution for a name is the one reported`() {
        val adaptor = FakeAdaptor()
        val (recorder, _) = recorder(adaptor)
        // Same name bound twice: the builder delivers only the last binding, so attribution has to agree
        // with it or the count lands on the row the model never saw.
        recorder.attribute("report", 3L)
        recorder.attribute("report", 9L)

        recorder.onRead(listOf(skill("report")))

        assertEquals(listOf("web-1" to listOf(9L)), adaptor.batches)
    }

    @Test
    fun `the batch carries the end user this build was attributed to`() {
        val attributed = FakeAdaptor()
        val attributedRecorder = recorder(attributed, userId = 42L).first
        attributedRecorder.attribute("pdf-tools", 7L)

        attributedRecorder.onRead(listOf(skill("pdf-tools")))

        // A count nobody can trace to a person cannot answer what the visibility control plane asks: did
        // the allow-listed user actually get this skill into their context.
        assertEquals(listOf(42L), attributed.users)
    }

    @Test
    fun `a build with no user names nobody`() {
        // A channel conversation reaches the runtime with no harnax identity, and turning that into an id
        // would file every anonymous load against whoever happened to be first.
        val anonymous = FakeAdaptor()
        val anonymousRecorder = recorder(anonymous, userId = null).first
        anonymousRecorder.attribute("pdf-tools", 7L)

        anonymousRecorder.onRead(listOf(skill("pdf-tools")))

        assertEquals(listOf<Long?>(null), anonymous.users)
    }
}
